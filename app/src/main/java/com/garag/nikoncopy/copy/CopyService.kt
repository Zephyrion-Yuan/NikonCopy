package com.garag.nikoncopy.copy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.garag.nikoncopy.MainActivity
import com.garag.nikoncopy.R
import com.garag.nikoncopy.data.ManifestStore
import com.garag.nikoncopy.data.SettingsRepository
import com.garag.nikoncopy.mtp.NikonDirect
import com.garag.nikoncopy.mtp.PtpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Long-running foreground service that owns the copy task. Surviving the activity lifecycle
 * lets the user lock the screen / switch apps while a multi-GB copy is in flight.
 *
 * The activity binds to read [state]. The activity also calls [start] / [cancel].
 */
class CopyService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): CopyService = this@CopyService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var copyJob: Job? = null

    private val _state = MutableStateFlow<CopyState>(CopyState.Idle)
    val state: StateFlow<CopyState> = _state.asStateFlow()

    private lateinit var settings: SettingsRepository
    private lateinit var manifest: ManifestStore

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(applicationContext)
        manifest = ManifestStore(applicationContext)
        ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service is bound; START_NOT_STICKY is intentional — if the system kills us
        // mid-copy we don't try to silently restart with stale intent extras.
        return START_NOT_STICKY
    }

    /**
     * Begin a copy. Idempotent: a no-op if a copy is already running.
     *
     * Tries direct USB-PTP path first (force-claims interface 0, talks raw PTP
     * for max speed). If a Nikon isn't found, USB permission is denied, or PTP
     * setup throws, falls back to the SAF/MtpDocumentsProvider path using the
     * caller-supplied [sourceTree].
     */
    fun start(sourceTree: Uri, destinationTree: Uri, mode: CopyMode) {
        if (copyJob?.isActive == true) return
        val startedAt = System.currentTimeMillis()

        startForegroundCompat(buildNotification(0f, "正在扫描…", null))

        copyJob = scope.launch {
            settings.recordCopyStarted(startedAt)
            val engine = CopyEngine(applicationContext, manifest)

            val ptp: PtpClient? = try {
                withContext(Dispatchers.IO) {
                    NikonDirect.open(applicationContext)
                }
            } catch (t: Throwable) {
                android.util.Log.w("CopyService", "direct open failed: ${t.message}")
                null
            }

            try {
                val flow = if (ptp != null) {
                    android.util.Log.i("CopyService", "using DIRECT PTP path")
                    updateNotification(0f, "正在扫描 (直连)…", null)
                    engine.copyFlowDirect(ptp, destinationTree, mode)
                } else {
                    android.util.Log.i("CopyService", "using SAF fallback path")
                    engine.copyFlow(sourceTree, destinationTree, mode)
                }
                flow.collect { s ->
                    _state.value = s
                    when (s) {
                        is CopyState.Scanning -> updateNotification(0f, "扫描中：发现 ${s.foundFiles} 个文件", null)
                        is CopyState.Running -> {
                            val pct = s.progress
                            val rate = humanRate(s.bytesPerSecond)
                            val sub = "${s.currentIndex}/${s.totalFiles} · $rate"
                            updateNotification(pct, "正在拷贝：${s.currentName}", sub)
                        }
                        is CopyState.Done -> {
                            settings.recordCopyCompleted(System.currentTimeMillis(), s.filesCopied.toLong())
                            val sub = if (s.filesSkipped > 0) "跳过 ${s.filesSkipped} 个同名文件" else null
                            updateNotification(1f, "完成 · 拷贝 ${s.filesCopied} 个文件", sub)
                        }
                        is CopyState.Failed -> {
                            updateNotification(0f, "失败", s.message)
                        }
                        CopyState.Idle -> Unit
                    }
                }
            } finally {
                runCatching {
                    withContext(Dispatchers.IO) {
                        ptp?.close()
                    }
                }
                stopForegroundCompat()
            }
        }
    }

    /**
     * Cancel an in-flight copy. The next call to [start] will be accepted again.
     */
    fun cancel() {
        copyJob?.cancel()
        copyJob = null
        _state.value = CopyState.Idle
        stopForegroundCompat()
    }

    /**
     * After the user has acknowledged a terminal state, reset the visible state to Idle.
     * (The recorded timestamp in DataStore is not affected.)
     */
    fun acknowledge() {
        if (_state.value is CopyState.Done || _state.value is CopyState.Failed) {
            _state.value = CopyState.Idle
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- notification plumbing ----

    private fun buildNotification(progress: Float, title: String, sub: String?): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (sub != null) builder.setContentText(sub)
        builder.setProgress(1000, (progress * 1000).toInt().coerceIn(0, 1000), progress <= 0f)
        return builder.build()
    }

    private fun updateNotification(progress: Float, title: String, sub: String?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(progress, title, sub))
    }

    @Suppress("DEPRECATION")
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        const val CHANNEL_ID = "nikoncopy_progress"
        const val NOTIFICATION_ID = 1001

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "拷贝进度",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Nikon → 手机 拷贝进度通知"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }

        fun humanRate(bps: Long): String {
            if (bps <= 0) return "—"
            val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
            var v = bps.toDouble()
            var i = 0
            while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
            return "%.1f %s".format(v, units[i])
        }

        fun humanSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = listOf("B", "KB", "MB", "GB", "TB")
            var v = bytes.toDouble()
            var i = 0
            while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
            return "%.1f %s".format(v, units[i])
        }
    }
}
