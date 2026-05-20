package com.garag.nikoncopy.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.garag.nikoncopy.copy.CopyMode
import com.garag.nikoncopy.copy.CopyService
import com.garag.nikoncopy.copy.CopyState
import com.garag.nikoncopy.copy.OrphanCleaner
import com.garag.nikoncopy.copy.RootPatcher
import com.garag.nikoncopy.data.CacheRecord
import com.garag.nikoncopy.data.CameraProfile
import com.garag.nikoncopy.data.DetectedDevice
import com.garag.nikoncopy.data.DeviceDetector
import com.garag.nikoncopy.data.DeviceKind
import com.garag.nikoncopy.data.DeviceProfile
import com.garag.nikoncopy.data.ManifestStore
import com.garag.nikoncopy.data.MediaProbe
import com.garag.nikoncopy.data.SettingsRepository
import com.garag.nikoncopy.mtp.NikonDirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CopyViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    val settings = SettingsRepository(ctx)

    val destinationUri: StateFlow<String?> = settings.destinationUri.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    val cacheRecord: StateFlow<CacheRecord> = settings.cacheRecord.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CacheRecord(0, 0, 0, 0),
    )

    val cameraProfiles: StateFlow<List<CameraProfile>> = settings.cameraProfiles.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    val activeProfile: StateFlow<DeviceProfile?> = settings.activeProfile.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    /** Devices physically connected right now (PTP cameras + removable storage). */
    private val _detectedDevices = MutableStateFlow<List<DetectedDevice>>(emptyList())
    val detectedDevices: StateFlow<List<DetectedDevice>> = _detectedDevices.asStateFlow()

    fun refreshDetectedDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            _detectedDevices.value = DeviceDetector.scan(ctx)
            // Auto-select: if a saved profile matches a detected device and no
            // active profile is matched yet, switch to it. Avoids the user
            // editing settings for one camera while another is plugged in.
            val detected = _detectedDevices.value
            if (detected.isEmpty()) return@launch
            val current = settings.activeProfileSnapshot()
            val currentMatches = detected.any { it.deviceKey == current?.deviceKey }
            if (currentMatches) return@launch
            val matchingProfile = settings.cameraProfilesSnapshot()
                .firstOrNull { p -> detected.any { it.deviceKey == p.deviceKey } }
            if (matchingProfile != null && matchingProfile.id != current?.id) {
                settings.setActiveProfile(matchingProfile.id)
            }
        }
    }

    fun setActiveProfile(profileId: String) {
        viewModelScope.launch { settings.setActiveProfile(profileId) }
    }

    /**
     * Create a new profile for a detected (but not-yet-saved) device. For PTP we
     * only need the deviceKey + name; for MSC we additionally need the user to
     * grant a SAF tree URI (passed in [mscSourceTree]). For MSC profiles, the
     * source tree is automatically probed for known media via [MediaProbe] —
     * no horizontal full-tree scan is performed (would be ruinously slow on
     * a 200k-file SSD).
     */
    fun addProfileForDetected(detected: DetectedDevice, mscSourceTree: Uri? = null) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val profile = DeviceProfile(
                id = detected.deviceKey.replace("[^A-Za-z0-9._-]".toRegex(), "_"),
                deviceKey = detected.deviceKey,
                deviceName = detected.deviceName,
                kind = detected.kind,
                sourceTreeUri = mscSourceTree?.toString(),
                updatedAt = now,
            )
            settings.upsertDeviceProfile(profile)
            settings.setActiveProfile(profile.id)
            // Auto-enumerate so the new profile lands with detectedDirectories /
            // detectedExtensions populated. Both helpers swallow errors and return
            // a friendly message; we don't surface it here (user can re-trigger
            // from the per-profile "重新扫描" button).
            when (detected.kind) {
                DeviceKind.MSC -> if (mscSourceTree != null) rescanMscProfile(profile.id)
                DeviceKind.PTP -> scanConnectedCameraProfile()
            }
        }
    }

    /**
     * Re-probe an MSC profile's source tree for media. Returns a friendly status
     * message that the UI can surface; never throws.
     */
    suspend fun rescanMscProfile(profileId: String): String = withContext(Dispatchers.IO) {
        android.util.Log.i("CopyViewModel", "rescanMscProfile profileId=$profileId")
        val profile = settings.cameraProfilesSnapshot().firstOrNull { it.id == profileId }
            ?: return@withContext "找不到该设备配置"
        if (profile.kind != DeviceKind.MSC) return@withContext "只有外接存储设备才能扫描"
        val sourceUri = profile.sourceTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: run {
                android.util.Log.w("CopyViewModel", "rescanMscProfile: ${profile.id} has no sourceTreeUri")
                return@withContext "尚未选择源目录"
            }
        android.util.Log.i("CopyViewModel", "rescanMscProfile: starting probe on $sourceUri")
        val result = try {
            MediaProbe.probeSaf(ctx.contentResolver, sourceUri)
        } catch (t: Throwable) {
            return@withContext "扫描失败：${t.message ?: t.javaClass.simpleName}"
        }
        if (result.directories.isEmpty()) {
            return@withContext "在 ${result.totalDirsScanned} 个目录中未找到任何媒体文件" +
                if (result.bailedOnLimit) "（已达扫描上限）" else ""
        }
        val detectedDirs = result.allDirectories
        val detectedExts = result.allExtensions
        // Default selections: keep prior selections that still exist; otherwise
        // start with everything detected (typical "import all" behaviour).
        val selectedDirs = profile.selectedDirectories
            .filter { it in detectedDirs }
            .toSet()
            .takeIf { it.isNotEmpty() } ?: detectedDirs
        val selectedExts = profile.selectedExtensions
            .filter { it in detectedExts }
            .toSet()
            .takeIf { it.isNotEmpty() } ?: detectedExts
        settings.updateDeviceProfile(profileId) {
            it.copy(
                detectedDirectories = detectedDirs,
                detectedExtensions = detectedExts,
                selectedDirectories = selectedDirs,
                selectedExtensions = selectedExts,
                updatedAt = System.currentTimeMillis(),
            )
        }
        val mode = if (result.usedConventionProbe) "约定扫描" else "全量 BFS"
        val limitNote = if (result.bailedOnLimit) "（已达扫描上限）" else ""
        "$mode 完成：${result.totalMediaFiles} 个媒体文件，${detectedDirs.size} 个目录，" +
            "${detectedExts.size} 种格式，耗时 ${result.elapsedMs / 1000.0} 秒$limitNote"
    }

    init {
        viewModelScope.launch {
            settings.migrateLegacyGlobalsIfNeeded()
            refreshDetectedDevices()
        }
    }

    private val rootPatcher = RootPatcher(ctx)
    private val _patchStatus = MutableStateFlow(rootPatcher.currentStatus())
    val patchStatus: StateFlow<RootPatcher.Status> = _patchStatus.asStateFlow()

    fun refreshPatchStatus() {
        _patchStatus.value = rootPatcher.currentStatus()
    }

    suspend fun applyRootPatches(): RootPatcher.Result {
        val result = rootPatcher.apply()
        _patchStatus.value = rootPatcher.currentStatus()
        return result
    }

    private val orphanCleaner = OrphanCleaner(ctx)
    private val _orphanCount = MutableStateFlow(-1) // -1 = unknown / not scanned yet
    val orphanCount: StateFlow<Int> = _orphanCount.asStateFlow()

    fun scanOrphans() {
        val destStr = destinationUri.value ?: run {
            _orphanCount.value = 0
            return
        }
        val dest = Uri.parse(destStr)
        viewModelScope.launch {
            val entries = orphanCleaner.scan(dest)
            _orphanCount.value = entries.size
        }
    }

    /** Returns number of deleted files. */
    suspend fun cleanOrphans(): Int {
        val destStr = destinationUri.value ?: return 0
        val dest = Uri.parse(destStr)
        val removed = orphanCleaner.cleanAll(dest)
        // refresh count
        val entries = orphanCleaner.scan(dest)
        _orphanCount.value = entries.size
        return removed
    }

    private var service: CopyService? = null
    private val _state = MutableStateFlow<CopyState>(CopyState.Idle)
    val state: StateFlow<CopyState> = _state.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? CopyService.LocalBinder)?.service() ?: return
            service = s
            viewModelScope.launch {
                s.state.collect { _state.value = it }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    init {
        bindService()
    }

    private fun bindService() {
        val intent = Intent(ctx, CopyService::class.java)
        ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun setDestination(uri: Uri) {
        viewModelScope.launch { settings.setDestinationUri(uri.toString()) }
    }

    suspend fun scanConnectedCameraProfile(): String = withContext(Dispatchers.IO) {
        // Open can fail in many ways (no device, USB perm denied, PTP handshake bad).
        // Wrap the whole open-and-enumerate path so any thrown IOException becomes
        // a friendly message instead of an uncaught exception crashing the UI.
        val opened = try {
            NikonDirect.openDevice(ctx)
        } catch (t: Throwable) {
            return@withContext "无法打开 PTP 相机：${t.message ?: t.javaClass.simpleName}"
        } ?: return@withContext "没有打开 PTP 相机。请确认相机已连接、已授权 USB，并且直连加速设置已就绪。"

        try {
            val files = try {
                opened.client.listFiles()
            } catch (t: Throwable) {
                return@withContext "扫描失败（${opened.deviceName}）：${t.message ?: t.javaClass.simpleName}。" +
                    "可重试一次，或先在「直连加速」中关闭/重启 com.android.mtp。"
            }
            val detectedDirs = files.map { it.directoryPath }.toSortedSet()
            val detectedExts = files.map { it.extension }.filter { it.isNotBlank() }.toSortedSet()
            val existing = settings.cameraProfilesSnapshot()
                .firstOrNull { it.matches(opened.deviceKey) }
            val selectedDirs = existing?.selectedDirectories
                ?.filter { it in detectedDirs }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?: detectedDirs
            val selectedExts = existing?.selectedExtensions
                ?.filter { it in detectedExts }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?: detectedExts
            val profile = CameraProfile(
                id = existing?.id ?: opened.deviceKey,
                deviceKey = opened.deviceKey,
                deviceName = opened.deviceName,
                destinationUri = existing?.destinationUri ?: destinationUri.value,
                selectedDirectories = selectedDirs,
                selectedExtensions = selectedExts,
                detectedDirectories = detectedDirs,
                detectedExtensions = detectedExts,
                updatedAt = System.currentTimeMillis(),
            )
            settings.upsertCameraProfile(profile)
            "已保存 ${profile.deviceName}：${detectedDirs.size} 个源子目录，${detectedExts.size} 种文件格式，${files.size} 个可拷贝文件。"
        } finally {
            runCatching { opened.client.close() }
        }
    }

    fun setProfileDestination(profileId: String, uri: Uri) {
        viewModelScope.launch {
            settings.updateCameraProfile(profileId) { it.copy(destinationUri = uri.toString()) }
        }
    }

    /**
     * Update the SAF tree URI used as the scan/copy source for an MSC profile.
     * Triggers a probe of the new tree so detected dirs/extensions refresh.
     * Used to repair profiles created before the SAF-picker step was wired up.
     */
    fun setMscSource(profileId: String, uri: Uri) {
        viewModelScope.launch {
            settings.updateCameraProfile(profileId) {
                it.copy(sourceTreeUri = uri.toString(), updatedAt = System.currentTimeMillis())
            }
            rescanMscProfile(profileId)
        }
    }

    fun setProfileExtension(profileId: String, extension: String, selected: Boolean) {
        viewModelScope.launch {
            settings.updateCameraProfile(profileId) { profile ->
                val ext = extension.lowercase()
                val next = if (selected) {
                    profile.selectedExtensions + ext
                } else {
                    profile.selectedExtensions - ext
                }
                profile.copy(selectedExtensions = next)
            }
        }
    }

    fun setProfileDirectory(profileId: String, directory: String, selected: Boolean) {
        viewModelScope.launch {
            settings.updateCameraProfile(profileId) { profile ->
                val next = if (selected) {
                    profile.selectedDirectories + directory
                } else {
                    profile.selectedDirectories - directory
                }
                profile.copy(selectedDirectories = next)
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch { settings.deleteCameraProfile(profileId) }
    }

    /** Clear cache record AND the success manifest for the active profile. */
    fun clearCache() {
        viewModelScope.launch {
            val active = settings.activeProfileSnapshot()
            settings.clearCache(active?.id)
            // Clear the per-profile manifest file. If no active profile, clear
            // the legacy global one (covers freshly-installed users).
            val mid = active?.id ?: ManifestStore.LEGACY_GLOBAL_ID
            ManifestStore(ctx, mid).clear()
        }
    }

    fun startCopyAll() = startCopy(CopyMode.ALL)
    fun startCopyIncremental() = startCopy(CopyMode.INCREMENTAL)

    /** Fix dates on already-copied files in the destination directory. */
    fun startFixDates() {
        val dest = destinationUri.value?.let(Uri::parse) ?: Uri.EMPTY
        val intent = Intent(ctx, CopyService::class.java)
        ctx.startForegroundService(intent)
        if (service == null) bindService()
        val s = service
        if (s != null) {
            s.startFixDates(dest)
        } else {
            viewModelScope.launch {
                var i = 0
                while (service == null && i < 40) {
                    kotlinx.coroutines.delay(50)
                    i++
                }
                service?.startFixDates(dest)
            }
        }
    }

    private fun startCopy(mode: CopyMode) {
        val dest = destinationUri.value?.let(Uri::parse) ?: Uri.EMPTY
        val intent = Intent(ctx, CopyService::class.java)
        ctx.startForegroundService(intent)
        if (service == null) bindService()
        val s = service
        if (s != null) {
            s.start(dest, mode)
        } else {
            viewModelScope.launch {
                var i = 0
                while (service == null && i < 40) {
                    kotlinx.coroutines.delay(50)
                    i++
                }
                service?.start(dest, mode)
            }
        }
    }

    fun cancelCopy() {
        service?.cancel()
    }

    fun acknowledgeResult() {
        service?.acknowledge()
    }

    override fun onCleared() {
        try { ctx.unbindService(connection) } catch (_: Throwable) {}
        super.onCleared()
    }
}
