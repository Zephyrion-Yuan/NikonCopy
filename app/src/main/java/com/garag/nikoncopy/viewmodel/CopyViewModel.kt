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
            val currentMatches = current != null && detected.any { current.matches(it.deviceKey) }
            if (currentMatches) return@launch
            val matchingProfile = settings.cameraProfilesSnapshot()
                .firstOrNull { p -> detected.any { p.matches(it.deviceKey) } }
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
     * Re-probe a profile's saved SAF source tree for media. Works for MSC
     * (SD/U盘 root chosen via SAF) and for PTP profiles that have a
     * `com.android.mtp` tree URI saved (non-root fallback path). Returns a
     * friendly status message; never throws.
     */
    suspend fun rescanSafSource(profileId: String): String = withContext(Dispatchers.IO) {
        android.util.Log.i("CopyViewModel", "rescanSafSource profileId=$profileId")
        val profile = settings.cameraProfilesSnapshot().firstOrNull { it.id == profileId }
            ?: return@withContext "找不到该设备配置"
        val sourceUri = profile.sourceTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: run {
                android.util.Log.w("CopyViewModel", "rescanSafSource: ${profile.id} has no sourceTreeUri")
                return@withContext "尚未选择源目录"
            }
        android.util.Log.i("CopyViewModel", "rescanSafSource: starting probe on $sourceUri")
        val result = try {
            MediaProbe.probeSaf(ctx.contentResolver, sourceUri)
        } catch (t: Throwable) {
            return@withContext "扫描失败：${t.message ?: t.javaClass.simpleName}"
        }
        if (result.directories.isEmpty()) {
            return@withContext "在 ${result.totalDirsScanned} 个目录中未找到任何媒体文件" +
                if (result.bailedOnLimit) "（已达扫描上限）" else ""
        }
        android.util.Log.i("CopyViewModel", "rescanSafSource: probe done — ${result.directories.size} dirs, ${result.allExtensions.size} ext kinds")
        val detectedDirs = result.allDirectories
        val detectedExts = result.allExtensions
        val detectedDirTimes = result.directories.associate { it.path to it.lastModifiedAt }
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
                detectedDirectoryTimes = detectedDirTimes,
                selectedDirectories = selectedDirs,
                selectedExtensions = selectedExts,
                updatedAt = System.currentTimeMillis(),
            )
        }
        val mode = if (result.usedConventionProbe) "约定扫描" else "全量 BFS"
        val limitNote = if (result.bailedOnLimit) "（已达扫描上限）" else ""
        "$mode 完成：${detectedDirs.size} 个目录，${detectedExts.size} 种文件格式，" +
            "耗时 ${result.elapsedMs / 1000.0} 秒$limitNote"
    }

    /** Back-compat alias used elsewhere. */
    suspend fun rescanMscProfile(profileId: String): String = rescanSafSource(profileId)

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

    /**
     * Scan a PTP camera profile. Tries direct PTP first (fast); if direct PTP
     * isn't available on this device (no root + com.android.mtp still active)
     * AND the profile already has a SAF source URI (from the system MTP
     * provider, picked once by the user), falls back to a SAF probe — same
     * code path MSC uses. Returns a friendly status string; never throws.
     */
    suspend fun scanConnectedCameraProfile(profileId: String? = null): String = withContext(Dispatchers.IO) {
        // Try direct PTP first.
        val opened = try {
            NikonDirect.openDevice(ctx)
        } catch (t: Throwable) {
            android.util.Log.w("CopyViewModel", "direct PTP open threw: ${t.message}")
            null
        }
        if (opened == null) {
            // Direct PTP unavailable. Fall back to SAF source if the profile has one.
            val target = profileId?.let { id ->
                settings.cameraProfilesSnapshot().firstOrNull { it.id == id }
            } ?: settings.activeProfileSnapshot()
            val safUri = target?.sourceTreeUri
            return@withContext if (safUri != null) {
                android.util.Log.i("CopyViewModel", "scanConnectedCameraProfile: falling back to SAF probe")
                rescanSafSource(target.id)
            } else {
                "未能直连相机。请在下方点「选择源目录」，授权相机的文件夹访问（系统会弹出文件选择器，选中相机即可）。\n" +
                    "提示：若设备已 root 且应用了「直连加速」，则不必选源目录，可直接扫描。"
            }
        }

        try {
            val files = try {
                opened.client.listFiles()
            } catch (t: Throwable) {
                return@withContext "扫描失败（${opened.deviceName}）：${t.message ?: t.javaClass.simpleName}。" +
                    "可重试一次，或先在「直连加速」中关闭/重启 com.android.mtp。"
            }
            val detectedDirs = files.map { it.directoryPath }.toSortedSet()
            val detectedExts = files.map { it.extension }.filter { it.isNotBlank() }.toSortedSet()
            // Per-dir max mtime — drives the settings UI's newest-first sort.
            val detectedDirTimes: Map<String, Long> = files
                .groupBy { it.directoryPath }
                .mapValues { (_, list) -> list.maxOf { it.dateModifiedMillis } }
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
            // CRITICAL: rescan must preserve user-set fields on the existing
            // profile (copyDirectoryStructure, cacheStartedAt/CompletedAt,
            // sourceTreeUri, lastFixCompletedAt, …). Earlier versions
            // re-constructed `CameraProfile(...)` from scratch which silently
            // reset every unlisted field — re-scanning a structure-mode profile
            // would lose the irreversible flag, and rescanning after a copy
            // would lose the "已拷贝" record so the lock would unlock.
            val profile = (existing ?: CameraProfile(
                id = opened.deviceKey,
                deviceKey = opened.deviceKey,
                deviceName = opened.deviceName,
            )).copy(
                deviceKey = opened.deviceKey,
                deviceName = opened.deviceName,
                destinationUri = existing?.destinationUri ?: destinationUri.value,
                selectedDirectories = selectedDirs,
                selectedExtensions = selectedExts,
                detectedDirectories = detectedDirs,
                detectedExtensions = detectedExts,
                detectedDirectoryTimes = detectedDirTimes,
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

    /**
     * Turn on the directory-structure-preserving copy mode for [profileId].
     * Irreversible — refuses to flip the flag back off once set, and refuses
     * to set it once any copy has run for this profile (preventing the
     * half-flat-half-nested mess we don't have de-dup semantics for).
     */
    fun enableCopyDirectoryStructure(profileId: String) {
        viewModelScope.launch {
            settings.updateCameraProfile(profileId) { profile ->
                if (profile.structureLocked) profile
                else profile.copy(
                    copyDirectoryStructure = true,
                    updatedAt = System.currentTimeMillis(),
                )
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
