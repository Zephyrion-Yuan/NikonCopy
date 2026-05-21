package com.garag.nikoncopy.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sd
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.ui.draw.blur
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garag.nikoncopy.copy.RootPatcher
import com.garag.nikoncopy.data.CameraProfile
import com.garag.nikoncopy.data.DetectedDevice
import com.garag.nikoncopy.data.DeviceKind
import com.garag.nikoncopy.data.extensionLabel
import com.garag.nikoncopy.viewmodel.CopyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: CopyViewModel,
    onBack: () -> Unit,
) {
    val destination by vm.destinationUri.collectAsStateWithLifecycle()
    val profiles by vm.cameraProfiles.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    val detectedDevices by vm.detectedDevices.collectAsStateWithLifecycle()
    val cache by vm.cacheRecord.collectAsStateWithLifecycle()
    val orphanCount by vm.orphanCount.collectAsStateWithLifecycle()
    val patchStatus by vm.patchStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(destination) { vm.scanOrphans() }
    LaunchedEffect(Unit) {
        vm.refreshPatchStatus()
        vm.refreshDetectedDevices()
    }
    // Poll for device attach/detach while Settings is open so the chip row and
    // each card's "已连接 / 未连接" badge stay in sync when the user yanks the
    // U盘 or camera mid-session. Cheap: scan is just a StorageManager + USB
    // enumeration with no I/O.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            vm.refreshDetectedDevices()
        }
    }

    // If user taps a detected MSC chip we still need a SAF tree URI before we
    // can scan or copy — Android won't grant access to a USB volume without an
    // OpenDocumentTree consent. Stash the detected device while the picker is
    // up so we can use its deviceKey/name once a tree is granted.
    var pendingDetectedMsc by remember { mutableStateOf<DetectedDevice.Msc?>(null) }

    // SAF tree picker for adding an MSC profile (SD card / USB stick).
    val pickMscTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val claimed = pendingDetectedMsc
        pendingDetectedMsc = null
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {}
        // Prefer the deviceKey/name from the auto-detected volume when we have
        // one (it survives unmount/remount via StorageManager UUID). Otherwise
        // fall back to a synthesised key from the tree URI.
        val msc = claimed ?: run {
            val name = uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
                ?: "外接存储"
            DetectedDevice.Msc(
                deviceKey = "msc:${uri.authority}:${uri.lastPathSegment}",
                deviceName = name,
                volumeUuid = null,
                volumePath = null,
            )
        }
        vm.addProfileForDetected(msc, mscSourceTree = uri)
    }

    var pendingProfileDestination by remember { mutableStateOf<String?>(null) }
    // Profile id whose MSC source we're (re)picking. Used to repair MSC profiles
    // that were saved without a tree URI before the picker step was wired in.
    var pendingMscSourceProfileId by remember { mutableStateOf<String?>(null) }

    val pickMscSourceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val profileId = pendingMscSourceProfileId
        pendingMscSourceProfileId = null
        if (uri == null || profileId == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {}
        vm.setMscSource(profileId, uri)
    }

    val pickDestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val profileId = pendingProfileDestination
        pendingProfileDestination = null
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some providers won't allow persistable permission; we still try to use it for this session.
        }
        if (profileId != null) vm.setProfileDestination(profileId, uri) else vm.setDestination(uri)
    }

    var showClearDialog by remember { mutableStateOf(false) }
    var patchResult by remember { mutableStateOf<PatchDialogContent?>(null) }
    var infoDialog by remember { mutableStateOf<InfoContent?>(null) }
    val showInfo: (String, String) -> Unit = { t, b -> infoDialog = InfoContent(t, b) }

    val anyDialogOpen = infoDialog != null || showClearDialog || patchResult != null
    val blurModifier = if (anyDialogOpen) Modifier.blur(16.dp) else Modifier

    Box(modifier = Modifier.fillMaxSize().then(blurModifier)) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            DevicePickerRow(
                profiles = profiles,
                activeProfileId = activeProfile?.id,
                detected = detectedDevices,
                onSelectProfile = { vm.setActiveProfile(it) },
                onAddDetected = { detected ->
                    // PTP devices are added directly (USB perm flow lives in NikonDirect).
                    // MSC devices require a SAF tree URI — bounce through the picker.
                    if (detected is DetectedDevice.Msc) {
                        pendingDetectedMsc = detected
                        pickMscTreeLauncher.launch(null)
                    } else {
                        vm.addProfileForDetected(detected)
                    }
                },
                onAddMsc = {
                    pendingDetectedMsc = null
                    pickMscTreeLauncher.launch(null)
                },
            )
            Spacer(Modifier.height(16.dp))

            SettingRow(
                title = "默认保存目录",
                subtitle = destination?.let { friendlyTreePath(it) } ?: "未设置",
                actionIcon = Icons.Outlined.FolderOpen,
                actionLabel = if (destination == null) "选择" else "更改",
                info = InfoContent(
                    title = "默认保存目录",
                    body = "默认保存目录。每个设备配置可以单独覆盖。",
                ),
                onShowInfo = showInfo,
                onAction = {
                    pendingProfileDestination = null
                    pickDestLauncher.launch(null)
                },
            )

            // Per-profile transient state (rescan in flight, last status text).
            val rescanning = remember { mutableStateOf<Set<String>>(emptySet()) }
            val scanMsgs = remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            // Per-card expansion override. Default: only the active profile
            // is expanded; everything else collapsed to its header.
            val expandOverride = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
            val detectedKeys = detectedDevices.map { it.deviceKey }.toSet()
            for (profile in profiles) {
                Spacer(Modifier.height(12.dp))
                val isMsc = profile.kind == DeviceKind.MSC
                val ptpConnected = !isMsc && profile.deviceKey in detectedKeys
                // "Connected" means physically present right now. Having a saved
                // sourceTreeUri only means we have permission to scan when it
                // does come back — it isn't a connection signal.
                val mscConnected = isMsc && profile.deviceKey in detectedKeys
                val connected = ptpConnected || mscConnected
                // Rescan is meaningful whenever any source path is available:
                // - direct PTP (camera connected + 直连加速 ready), OR
                // - SAF tree URI saved (MSC or PTP-via-system-MTP-provider).
                val canRescan = profile.sourceTreeUri != null || ptpConnected
                val isRescanning = profile.id in rescanning.value
                val defaultExpanded = profile.id == activeProfile?.id
                val expanded = expandOverride.value[profile.id] ?: defaultExpanded
                CameraProfileCard(
                    profile = profile,
                    fallbackDestination = destination,
                    connected = connected,
                    expanded = expanded,
                    onToggleExpanded = {
                        expandOverride.value = expandOverride.value + (profile.id to !expanded)
                        // Expanding a card also makes it the active one — only
                        // one device is active at a time.
                        if (!expanded && profile.id != activeProfile?.id) {
                            vm.setActiveProfile(profile.id)
                        }
                    },
                    onPickDestination = {
                        pendingProfileDestination = profile.id
                        pickDestLauncher.launch(null)
                    },
                    // Both MSC and PTP profiles can carry a SAF source URI now:
                    // for MSC it's the SD/U盘 root; for PTP it's the system
                    // com.android.mtp provider tree (non-root fallback).
                    onPickMscSource = {
                        pendingMscSourceProfileId = profile.id
                        pickMscSourceLauncher.launch(null)
                    },
                    onToggleDirectory = { dir, selected ->
                        vm.setProfileDirectory(profile.id, dir, selected)
                    },
                    onToggleExtension = { ext, selected ->
                        vm.setProfileExtension(profile.id, ext, selected)
                    },
                    onDelete = { vm.deleteProfile(profile.id) },
                    onRescan = if (canRescan) {
                        {
                            scope.launch {
                                rescanning.value = rescanning.value + profile.id
                                try {
                                    // For PTP: scanConnectedCameraProfile tries direct PTP
                                    // first and auto-falls back to SAF probe when direct is
                                    // unavailable. For MSC: rescanSafSource directly.
                                    val msg = if (isMsc) vm.rescanSafSource(profile.id)
                                    else vm.scanConnectedCameraProfile(profile.id)
                                    scanMsgs.value = scanMsgs.value + (profile.id to msg)
                                } finally {
                                    rescanning.value = rescanning.value - profile.id
                                }
                            }
                        }
                    } else null,
                    rescanInProgress = isRescanning,
                    rescanMessage = scanMsgs.value[profile.id],
                    onShowInfo = showInfo,
                )
            }

            Spacer(Modifier.height(16.dp))

            SettingRow(
                title = "已导入缓存记录",
                subtitle = if (cache.lastCopyStartedAt > 0)
                    "上次开始：${formatTime(cache.lastCopyStartedAt)}"
                else "无",
                actionIcon = Icons.Outlined.DeleteSweep,
                actionLabel = "清空",
                destructive = true,
                info = InfoContent(
                    title = "已导入缓存记录",
                    body = "保留两类信息：上次拷贝完成的时间点，以及「已成功导入」的文件记录。\n\n —— 在手机端手动删除的文件因为名字仍在记录里，下次增量拷贝不会再拷贝。\n\n清空记录后，下一次「增量拷贝」等同于「拷贝全部」。"
                ),
                onShowInfo = showInfo,
                onAction = { showClearDialog = true },
            )

            Spacer(Modifier.height(16.dp))

            var applyingPatch by remember { mutableStateOf(false) }
            SettingRow(
                title = "直连加速设置（可选）",
                subtitle = when {
                    applyingPatch -> "正在应用…"
                    patchStatus.fullyApplied -> "已就绪 · 直连模式可用"
                    else -> "未启用 · 兼容模式（无需 root）也可用"
                },
                actionIcon = Icons.Outlined.Bolt,
                actionLabel = if (patchStatus.fullyApplied) "检查状态" else "应用",
                destructive = false,
                info = InfoContent(
                    title = "直连加速设置",
                    body = "可选的高级选项，启用后能让 USB 直连拷贝速度从 ~14 MB/s 提到 160+ MB/s。\n\n· 不启用：照样可以拷贝（兼容模式，走系统 MTP 路径）。\n· 启用条件：设备已 root（Magisk / KernelSU / APatch）。应用会自动写入两项系统设置；无 root 也可手动执行给出的 adb 命令。\n\n普通用户可以完全忽略这一项。"
                ),
                onShowInfo = showInfo,
                onAction = {
                    if (patchStatus.fullyApplied) {
                        vm.refreshPatchStatus()
                    } else {
                        scope.launch {
                            applyingPatch = true
                            try {
                                val result = vm.applyRootPatches()
                                patchResult = when (result) {
                                    RootPatcher.Result.APPLIED -> PatchDialogContent(
                                        title = "应用成功",
                                        message = "两项系统设置已通过 root 写入，重启后依然有效。插上 Nikon 即可体验 160+ MB/s 直连。",
                                        showAdb = false,
                                    )
                                    RootPatcher.Result.ALREADY_APPLIED -> PatchDialogContent(
                                        title = "已是最佳状态",
                                        message = "设置已就绪，无需改动。",
                                        showAdb = false,
                                    )
                                    RootPatcher.Result.PARTIAL -> PatchDialogContent(
                                        title = "部分应用",
                                        message = "只改成功了一项，另一项需要在更高权限下写入。建议用下方 adb 命令手动补齐。",
                                        showAdb = true,
                                    )
                                    RootPatcher.Result.NO_ROOT -> PatchDialogContent(
                                        title = "未检测到 root",
                                        message = "当前设备没有可用的 su 二进制（未装 Magisk/KernelSU/APatch）。请通过 adb 手动执行下面两条命令：",
                                        showAdb = true,
                                    )
                                }
                            } finally {
                                applyingPatch = false
                            }
                        }
                    }
                },
            )

            Spacer(Modifier.height(16.dp))

            var cleaning by remember { mutableStateOf(false) }
            SettingRow(
                title = "清理无主",
                subtitle = when {
                    cleaning -> "正在清理…"
                    orphanCount < 0 -> "检测中…"
                    orphanCount == 0 -> "目录整洁，无需清理"
                    else -> "发现 $orphanCount 个孤儿"
                },
                actionIcon = Icons.Outlined.CleaningServices,
                actionLabel = if (orphanCount > 0) "清理 $orphanCount" else "重新检测",
                destructive = orphanCount > 0,
                info = InfoContent(
                    title = "清理废止文件",
                    body = "扫描保存目录里的「废止文件」：\n· 拷贝中断时留下的临时文件\n· 实际未拷贝成功的文件记录 \n\n清理只删废止文件，不会删改已成功导入的文件。建议时常清理。",
                ),
                onShowInfo = showInfo,
                onAction = {
                    if (orphanCount > 0) {
                        scope.launch {
                            cleaning = true
                            try { vm.cleanOrphans() } finally { cleaning = false }
                        }
                    } else {
                        vm.scanOrphans()
                    }
                },
            )

        }
    }
    } // end blur Box

    infoDialog?.let { content ->
        InfoDialog(content.title, content.body, onDismiss = { infoDialog = null })
    }

    patchResult?.let { content ->
        AlertDialog(
            onDismissRequest = { patchResult = null },
            title = { Text(content.title) },
            text = {
                Column {
                    Text(content.message, style = MaterialTheme.typography.bodyMedium)
                    if (content.showAdb) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                RootPatcher.ADB_INSTRUCTIONS,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { patchResult = null }) { Text("确定") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空缓存记录？") },
            text = { Text("将删除上次拷贝的时间戳与「已成功」清单。下一次「增量拷贝」会等同于拷贝全部。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearCache()
                    showClearDialog = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

private data class PatchDialogContent(
    val title: String,
    val message: String,
    val showAdb: Boolean,
)

internal data class InfoContent(val title: String, val body: String)

/**
 * Horizontal row of device chips. Saved profiles appear first (highlighted when
 * active); detected-but-unsaved devices appear next as "+ Add" affordances; a
 * permanent trailing "+ SD/U盘" chip launches the SAF tree picker so the user
 * can register a removable storage volume.
 */
@Composable
private fun DevicePickerRow(
    profiles: List<CameraProfile>,
    activeProfileId: String?,
    detected: List<DetectedDevice>,
    onSelectProfile: (String) -> Unit,
    onAddDetected: (DetectedDevice) -> Unit,
    onAddMsc: () -> Unit,
) {
    val savedKeys = profiles.map { it.deviceKey }.toSet()
    val unsavedDetected = detected.filter { it.deviceKey !in savedKeys }
    val detectedKeys = detected.map { it.deviceKey }.toSet()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                "设备",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (profiles.isEmpty() && unsavedDetected.isEmpty()) {
                    Text(
                        "未检测到任何设备。插入相机或 SD 卡，或下方按 + 选目录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                profiles.forEach { p ->
                    DeviceChip(
                        label = p.deviceName,
                        kind = p.kind,
                        connected = p.deviceKey in detectedKeys,
                        active = p.id == activeProfileId,
                        onClick = { onSelectProfile(p.id) },
                    )
                }
                unsavedDetected.forEach { d ->
                    DeviceChip(
                        label = "${d.deviceName} +",
                        kind = d.kind,
                        connected = true,
                        active = false,
                        onClick = { onAddDetected(d) },
                    )
                }
                AddMscChip(onClick = onAddMsc)
            }
        }
    }
}

@Composable
private fun DeviceChip(
    label: String,
    kind: DeviceKind,
    connected: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    val container = when {
        active -> MaterialTheme.colorScheme.primary
        connected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val content = when {
        active -> MaterialTheme.colorScheme.onPrimary
        connected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = container,
        modifier = Modifier.height(40.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (kind == DeviceKind.PTP) Icons.Outlined.Camera else Icons.Outlined.Sd,
                contentDescription = null,
                tint = content,
                modifier = Modifier.height(18.dp).width(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AddMscChip(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.height(40.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.height(18.dp).width(18.dp))
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.Usb, contentDescription = null, modifier = Modifier.height(18.dp).width(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("SD / U 盘", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    actionLabel: String,
    destructive: Boolean = false,
    info: InfoContent? = null,
    onShowInfo: ((String, String) -> Unit)? = null,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (info != null && onShowInfo != null) {
                        InfoIcon { onShowInfo(info.title, info.body) }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (destructive) {
                OutlinedButton(
                    onClick = onAction,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.height(18.dp).width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(actionLabel)
                }
            } else {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.height(18.dp).width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun CameraProfileCard(
    profile: CameraProfile,
    fallbackDestination: String?,
    connected: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onPickDestination: () -> Unit,
    onPickMscSource: (() -> Unit)? = null,
    onToggleDirectory: (String, Boolean) -> Unit,
    onToggleExtension: (String, Boolean) -> Unit,
    onDelete: () -> Unit,
    onRescan: (() -> Unit)? = null,
    rescanInProgress: Boolean = false,
    rescanMessage: String? = null,
    onShowInfo: ((String, String) -> Unit)? = null,
) {
    // Collapsed cards visually recede so the active/connected one stands out.
    val cardColor =
        if (expanded) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    // No Surface.onClick — that would attach a whole-card ripple whose bounds
    // are captured at click time, leaving the expanding area uncovered. Instead
    // we put a bounded clickable on the header only and let animateContentSize
    // grow/shrink the body smoothly.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = cardColor,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .animateContentSize(),
        ) {
            // ---- Header (always shown, only this row is clickable) ----
            // No ripple — pure semantic click. The chevron flip + body
            // animateContentSize is enough visual feedback that the tap
            // registered.
            val headerInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = headerInteraction,
                        indication = null,
                        onClick = onToggleExpanded,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile.deviceName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (onShowInfo != null) {
                            InfoIcon {
                                onShowInfo(
                                    "${profile.deviceName} · 设备配置",
                                    if (profile.kind == DeviceKind.MSC)
                                        "外接存储设备。识别码：${profile.deviceKey}\n更新时间：${formatTime(profile.updatedAt)}\n\n存储设备按 RAW/JPG/MP4 等已知格式做探测。可点「重新扫描」更换源目录。"
                                    else
                                        "PTP 相机。识别码：${profile.deviceKey}\n更新时间：${formatTime(profile.updatedAt)}\n\n两种读取方式：\n· 直连模式（快，160+ MB/s）：需要设备 root + 应用了「直连加速」，应用会自动尝试\n· 兼容模式（一般，14 MB/s 左右）：无需 root，但需要你点「选择源目录」授权一次，在弹出的文件选择器中选中相机\n\n两种方式可以同时配置 —— 直连可用时优先用直连，不可用时自动回退到兼容模式。",
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(if (profile.kind == DeviceKind.MSC) "外接存储" else "PTP 相机")
                            append(" · ")
                            append(if (connected) "已连接" else "未连接")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Chevron tells the user "tap to expand/collapse" — and confirms
                // the entire card surface is the toggle target.
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.height(24.dp).width(24.dp),
                )
            }

            // ---- Body (expanded only) ----
            if (expanded) {
                if (onRescan != null || onPickMscSource != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onPickMscSource != null && profile.sourceTreeUri == null) {
                            // MSC profile without a source tree — primary repair action.
                            Button(onClick = onPickMscSource) { Text("选择源目录") }
                        } else {
                            if (onRescan != null) {
                                OutlinedButton(onClick = onRescan, enabled = !rescanInProgress) {
                                    Text(if (rescanInProgress) "扫描中…" else "重新扫描")
                                }
                            }
                            if (onPickMscSource != null) {
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = onPickMscSource) { Text("更换源目录") }
                            }
                        }
                    }
                }
                if (rescanMessage != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        rescanMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "保存目录",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            profile.destinationUri?.let { friendlyTreePath(it) }
                                ?: fallbackDestination?.let { "使用默认：${friendlyTreePath(it)}" }
                                ?: "未设置",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = onPickDestination) { Text("选择") }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "选定文件格式",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                val extensions = profile.detectedExtensions.sorted()
                if (extensions.isEmpty()) {
                    Text("未检测到可识别的文件格式", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    extensions.forEach { ext ->
                        val selected = ext in profile.selectedExtensions
                        ProfileCheckRow(
                            label = extensionLabel(ext),
                            checked = selected,
                            enabled = selected || profile.selectedExtensions.size > 1,
                            onCheckedChange = { onToggleExtension(ext, it) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "源子目录",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                val dirs = profile.detectedDirectories.sorted()
                if (dirs.isEmpty()) {
                    Text("未检测到源子目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    dirs.forEach { dir ->
                        val selected = dir in profile.selectedDirectories
                        ProfileCheckRow(
                            label = directoryLabel(dir),
                            checked = selected,
                            enabled = selected || profile.selectedDirectories.size > 1,
                            onCheckedChange = { onToggleDirectory(dir, it) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDelete) {
                    Text("删除配置", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ProfileCheckRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun directoryLabel(path: String): String {
    return if (path == "/") "根目录" else path.removePrefix("/")
}

/**
 * Tiny circled-(i) affordance that hides verbose static documentation behind a
 * single tap. Used to keep card titles uncluttered while still letting curious
 * users read what each action does.
 */
@Composable
internal fun InfoIcon(
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.padding(start = 4.dp).height(24.dp).width(24.dp),
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = "说明",
            modifier = Modifier.height(18.dp).width(18.dp),
            tint = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.55f),
        )
    }
}

/**
 * Modal-style info dialog used by the (i) affordance. Dismisses on outside tap
 * (AlertDialog default), back press, or "好的" button.
 */
@Composable
internal fun InfoDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("好的") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
