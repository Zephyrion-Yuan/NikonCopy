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
import com.garag.nikoncopy.mtp.OpenedPtpDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(applicationContext)
        ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service is bound; START_NOT_STICKY is intentional — if the system kills us
        // mid-copy we don't try to silently restart with stale intent extras.
        return START_NOT_STICKY
    }

    /**
     * Begin a copy. Picks the best available source path:
     *   - MSC profile → SAF tree (user-picked SD/U盘 root)
     *   - PTP profile + direct PTP available (com.android.mtp disabled) → raw USB PTP (fast)
     *   - PTP profile + SAF source URI saved (system MTP provider tree) → SAF (slower, non-root)
     *   - none of the above → friendly error pointing the user at the Settings picker
     * Idempotent: no-op if a copy is already running.
     */
    fun start(destinationTree: Uri, mode: CopyMode) {
        if (copyJob?.isActive == true) return
        val startedAt = System.currentTimeMillis()

        startForegroundCompat(buildNotification(0f, "正在扫描…", null))

        copyJob = scope.launch {
            // Resolve active profile up front so we know whether to take the
            // PTP path or the SAF (MSC / system-MTP-provider) path.
            val activeNow = settings.activeProfileSnapshot()
            if (activeNow?.kind == com.garag.nikoncopy.data.DeviceKind.MSC) {
                runSafCopy(activeNow, destinationTree, mode, startedAt, label = "外接存储")
                return@launch
            }

            val opened: OpenedPtpDevice? = try {
                withContext(Dispatchers.IO) {
                    NikonDirect.openDevice(applicationContext)
                }
            } catch (t: Throwable) {
                android.util.Log.w("CopyService", "direct open failed: ${t.message}")
                null
            }

            // Resolve which profile this run targets (for cache + manifest scoping).
            // Prefer the profile whose deviceKey matches the connected camera; fall
            // back to the user's currently-active profile if none matches.
            val profile = run {
                val byDevice = if (opened != null) {
                    settings.cameraProfilesSnapshot().firstOrNull { it.matches(opened.deviceKey) }
                } else null
                byDevice ?: settings.activeProfileSnapshot()
            }
            val profileId = profile?.id ?: ManifestStore.LEGACY_GLOBAL_ID
            val manifest = ManifestStore(applicationContext, profileId)
            val engine = CopyEngine(applicationContext, manifest)
            if (profile != null) settings.recordCopyStarted(profile.id, startedAt)

            try {
                if (opened == null) {
                    // Direct PTP unavailable — try the SAF fallback if the user
                    // already authorized a system-MTP tree URI for this camera.
                    val safUri = profile?.sourceTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    if (profile != null && safUri != null && safUri != Uri.EMPTY) {
                        android.util.Log.i("CopyService", "PTP direct unavailable; falling back to SAF source")
                        // We've already startedAt-recorded above; pass it through to keep the cache consistent.
                        runSafCopy(profile, destinationTree, mode, startedAt, label = "相机 (SAF)", srcOverride = safUri, recordStarted = false)
                        return@launch
                    }
                    val message = "未能直连相机，且未授权相机文件夹访问。\n" +
                        "请到「设置」→ 展开当前设备 → 点「选择源目录」，在系统弹出的文件选择器中选中相机后再试。\n" +
                        "（若设备已 root 并应用了「直连加速」，则不必选源目录。）"
                    android.util.Log.w("CopyService", "PTP copy aborted: no direct + no SAF fallback URI")
                    _state.value = CopyState.Failed(message)
                    updateNotification(0f, "失败", message)
                    return@launch
                }
                val destination = profile?.destinationUri
                    ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    ?: destinationTree
                if (destination == Uri.EMPTY) {
                    val message = "未设置保存目录：请在设置中为该相机配置保存目录，或设置默认保存目录。"
                    _state.value = CopyState.Failed(message)
                    updateNotification(0f, "失败", message)
                    return@launch
                }
                val filter = profile?.let {
                    CopyFilter(
                        directories = it.selectedDirectories,
                        extensions = it.selectedExtensions,
                    )
                } ?: CopyFilter()
                android.util.Log.i(
                    "CopyService",
                    "using DIRECT PTP path device=${opened.deviceKey} profile=${profile?.id ?: "(none)"} " +
                        "dirs=${filter.directories.size} exts=${filter.extensions.size}",
                )
                updateNotification(0f, "正在扫描 (直连)…", null)
                val flow = engine.copyFlowDirect(opened.client, destination, mode, filter)
                flow.collect { s ->
                    _state.value = s
                    when (s) {
                        is CopyState.Scanning -> updateNotification(0f, "扫描中：发现 ${s.foundFiles} 个文件", null)
                        is CopyState.Running -> {
                            val pct = s.progress
                            val sub = when (s.phase) {
                                CopyState.Phase.COPYING -> {
                                    val rate = humanRate(s.bytesPerSecond)
                                    "${s.currentIndex}/${s.totalFiles} · $rate"
                                }
                                CopyState.Phase.FINALIZING -> {
                                    "${s.currentIndex}/${s.totalFiles} · 整理元数据，待处理 ${s.pendingPostProcess}"
                                }
                                CopyState.Phase.FIXING_DATES -> {
                                    "${s.currentIndex}/${s.totalFiles}"
                                }
                            }
                            val title = when (s.phase) {
                                CopyState.Phase.COPYING -> "正在拷贝：${s.currentName}"
                                CopyState.Phase.FINALIZING -> "正在整理：${s.currentName}"
                                CopyState.Phase.FIXING_DATES -> "修复日期：${s.currentName}"
                            }
                            updateNotification(pct, title, sub)
                        }
                        is CopyState.Done -> {
                            if (profile != null) {
                                settings.recordCopyCompleted(profile.id, System.currentTimeMillis(), s.filesCopied.toLong())
                            }
                            val sub = if (s.filesSkipped > 0) "跳过 ${s.filesSkipped} 个同名文件" else null
                            updateNotification(1f, "完成 · 拷贝 ${s.filesCopied} 个文件", sub)
                        }
                        is CopyState.Failed -> {
                            updateNotification(0f, "失败", s.message)
                        }
                        CopyState.Idle -> Unit
                    }
                }
            } catch (ce: CancellationException) {
                // User cancelled — if any files already made it into the batch
                // manifest, treat this as a partial copy completion so the UI
                // prompts the user to run fix-dates on them.
                withContext(NonCancellable) {
                    val batchSize = manifest.loadLastBatch().size
                    if (batchSize > 0 && profile != null) {
                        settings.recordCopyCompleted(profile.id, System.currentTimeMillis(), batchSize.toLong())
                    }
                }
                throw ce
            } finally {
                runCatching {
                    withContext(Dispatchers.IO) {
                        opened?.client?.close()
                    }
                }
                stopForegroundCompat()
            }
        }
    }

    /**
     * SAF-source copy. Handles both MSC (SD/U盘 SAF tree) and the PTP non-root
     * fallback (system com.android.mtp DocumentsProvider tree). The active
     * profile owns its own destination URI and SAF source URI; the filter is
     * the user's selected source subdirs + selected file formats.
     *
     * @param srcOverride if non-null, use this tree URI instead of profile.sourceTreeUri
     *                    (used by the PTP→SAF fallback so the caller can keep its already-resolved URI).
     * @param recordStarted if false, skips recordCopyStarted (the outer flow already did it).
     * @param label shown in the notification — "外接存储" or "相机 (SAF)" etc.
     */
    private suspend fun runSafCopy(
        profile: com.garag.nikoncopy.data.DeviceProfile,
        destinationTree: Uri,
        mode: CopyMode,
        startedAt: Long,
        label: String,
        srcOverride: Uri? = null,
        recordStarted: Boolean = true,
    ) {
        val sourceTree = srcOverride
            ?: profile.sourceTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (sourceTree == null || sourceTree == Uri.EMPTY) {
            val msg = "未选择源目录：请在设置中展开此设备 → 点「选择源目录」。"
            _state.value = CopyState.Failed(msg)
            updateNotification(0f, "失败", msg)
            stopForegroundCompat()
            return
        }
        val destination = profile.destinationUri
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: destinationTree
        if (destination == Uri.EMPTY) {
            val msg = "未设置保存目录：请在设置中为该设备配置保存目录。"
            _state.value = CopyState.Failed(msg)
            updateNotification(0f, "失败", msg)
            stopForegroundCompat()
            return
        }

        val manifest = ManifestStore(applicationContext, profile.id)
        val engine = CopyEngine(applicationContext, manifest)
        if (recordStarted) settings.recordCopyStarted(profile.id, startedAt)

        val filter = CopyFilter(
            directories = profile.selectedDirectories,
            extensions = profile.selectedExtensions,
        )
        android.util.Log.i(
            "CopyService",
            "using SAF path ($label) profile=${profile.id} src=$sourceTree " +
                "dirs=${filter.directories.size} exts=${filter.extensions.size}",
        )
        updateNotification(0f, "正在扫描 ($label)…", null)

        try {
            engine.copyFlowSaf(sourceTree, destination, mode, filter).collect { s ->
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
                        settings.recordCopyCompleted(profile.id, System.currentTimeMillis(), s.filesCopied.toLong())
                        val sub = if (s.filesSkipped > 0) "跳过 ${s.filesSkipped} 个同名文件" else null
                        updateNotification(1f, "完成 · 拷贝 ${s.filesCopied} 个文件", sub)
                    }
                    is CopyState.Failed -> updateNotification(0f, "失败", s.message)
                    CopyState.Idle -> Unit
                }
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                val batchSize = manifest.loadLastBatch().size
                if (batchSize > 0) {
                    settings.recordCopyCompleted(profile.id, System.currentTimeMillis(), batchSize.toLong())
                }
            }
            throw ce
        } finally {
            stopForegroundCompat()
        }
    }

    /**
     * Fix dates on files already in the destination directory.
     * High-parallelism EXIF → mtime + DATE_TAKEN.
     */
    fun startFixDates(destinationTree: Uri) {
        if (copyJob?.isActive == true) return

        startForegroundCompat(buildNotification(0f, "正在扫描…", null))

        copyJob = scope.launch {
            val profile = settings.activeProfileSnapshot()
            val profileId = profile?.id ?: ManifestStore.LEGACY_GLOBAL_ID
            val manifest = ManifestStore(applicationContext, profileId)
            val engine = CopyEngine(applicationContext, manifest)
            try {
                engine.fixDatesFlow(destinationTree).collect { s ->
                    _state.value = s
                    when (s) {
                        is CopyState.Scanning -> updateNotification(0f, "扫描中：发现 ${s.foundFiles} 个文件", null)
                        is CopyState.Running -> {
                            val pct = s.progress
                            updateNotification(pct, "修复日期：${s.currentName}", "${s.currentIndex}/${s.totalFiles}")
                        }
                        is CopyState.Done -> {
                            if (profile != null) {
                                settings.recordFixCompleted(profile.id, System.currentTimeMillis())
                            }
                            updateNotification(1f, "日期修复完成 · ${s.filesCopied} 个文件", "${s.elapsedMillis / 1000.0} 秒")
                        }
                        is CopyState.Failed -> updateNotification(0f, "失败", s.message)
                        CopyState.Idle -> Unit
                    }
                }
            } finally {
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
