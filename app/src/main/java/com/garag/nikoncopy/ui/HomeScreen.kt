package com.garag.nikoncopy.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.blur
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garag.nikoncopy.copy.CopyMode
import com.garag.nikoncopy.copy.CopyService
import com.garag.nikoncopy.copy.CopyState
import com.garag.nikoncopy.viewmodel.CopyViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: CopyViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val destination by vm.destinationUri.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    val detectedDevices by vm.detectedDevices.collectAsStateWithLifecycle()
    val profiles by vm.cameraProfiles.collectAsStateWithLifecycle()
    val cache by vm.cacheRecord.collectAsStateWithLifecycle()

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notifications are nice-to-have. */ }

    val launchPtpCopy: (CopyMode) -> Unit = { mode ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        when (mode) {
            CopyMode.ALL -> vm.startCopyAll()
            CopyMode.INCREMENTAL -> vm.startCopyIncremental()
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshDetectedDevices() }
    // Poll so the ActiveDeviceChip's connected/disconnected badge tracks
    // physical state without requiring the user to re-enter the screen.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            vm.refreshDetectedDevices()
        }
    }

    var infoDialog by remember { mutableStateOf<InfoContent?>(null) }
    val showInfo: (String, String) -> Unit = { t, b -> infoDialog = InfoContent(t, b) }
    val blurModifier = if (infoDialog != null) Modifier.blur(16.dp) else Modifier

    Box(modifier = Modifier.fillMaxSize().then(blurModifier)) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("NikonCopy", fontWeight = FontWeight.Medium) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            ActiveDeviceChip(
                profileName = activeProfile?.deviceName,
                connected = activeProfile?.deviceKey?.let { key ->
                    detectedDevices.any { it.deviceKey == key }
                } ?: false,
                onClick = onOpenSettings,
                onShowInfo = showInfo,
            )
            Spacer(Modifier.height(12.dp))

            // Live progress sits up here (fixed height, translucent bg) so the
            // user can watch the bar while pressing the main buttons below.
            ProgressArea(
                state = state,
                onCancel = { vm.cancelCopy() },
                onAcknowledge = { vm.acknowledgeResult() },
            )

            Spacer(Modifier.height(24.dp))

            val running = state is CopyState.Running || state is CopyState.Scanning
            val hasUnfixed = cache.hasUnfixedBatch
            val hasOutput = destination != null || profiles.any { it.destinationUri != null }
            val canCopy = hasOutput && !running && !hasUnfixed
            val canFix = hasOutput && !running && hasUnfixed

            BigButton(
                title = "拷贝全部",
                subtitle = null,
                icon = Icons.Outlined.CloudDownload,
                primary = true,
                enabled = canCopy,
                info = InfoContent(
                    title = "拷贝全部",
                    body = "扫描设备中所有选定文件格式的文件，拷贝到保存目录。\n\n注：不会重复拷贝同名文件",
                ),
                onShowInfo = showInfo,
                onClick = { launchPtpCopy(CopyMode.ALL) },
            )
            Spacer(Modifier.height(16.dp))
            BigButton(
                title = "增量拷贝",
                subtitle = if (cache.lastCopyStartedAt > 0)
                    "上次 ${formatTime(cache.lastCopyStartedAt)}"
                else null,
                icon = Icons.Outlined.Refresh,
                primary = false,
                enabled = canCopy,
                info = InfoContent(
                    title = "增量拷贝",
                    body = "只拷贝新增文件，跳过已成功导入过的历史文件。\n\n注：历史成功导入的记录可在「设置 → 已导入缓存记录」里清空。",
                ),
                onShowInfo = showInfo,
                onClick = { launchPtpCopy(CopyMode.INCREMENTAL) },
            )
            Spacer(Modifier.height(16.dp))
            BigButton(
                title = "修复日期",
                subtitle = if (hasUnfixed) "当前批次待修复" else null,
                icon = Icons.Outlined.DateRange,
                primary = hasUnfixed,
                enabled = canFix,
                info = InfoContent(
                    title = "修复日期",
                    body = "根据EXIF等元数据修复文件日期，使得相册中文件按实际时间顺序排列。",
                ),
                onShowInfo = showInfo,
                onClick = { vm.startFixDates() },
            )

            Spacer(Modifier.height(20.dp))
            // Cache record (last copy) at the bottom — less critical info,
            // surfaced for occasional reference.
            CacheStatusCard(
                lastStarted = cache.lastCopyStartedAt,
                lastCompleted = cache.lastCopyCompletedAt,
                lastFiles = cache.lastCopyFiles,
                onShowInfo = showInfo,
            )
        }
    }
    } // end blur Box

    infoDialog?.let { content ->
        InfoDialog(content.title, content.body, onDismiss = { infoDialog = null })
    }
}

@Composable
private fun BigButton(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    enabled: Boolean,
    info: InfoContent? = null,
    onShowInfo: ((String, String) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val container = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
    val hasInfo = info != null && onShowInfo != null
    Box(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = content,
                disabledContainerColor = container.copy(alpha = 0.4f),
                disabledContentColor = content.copy(alpha = 0.6f),
            ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // Reserve room on the right so the title/subtitle text never
                    // slides under the (i) overlay button.
                    .padding(end = if (hasInfo) 56.dp else 0.dp),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.width(28.dp).height(28.dp))
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = content.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
        // Overlay (i) — sized to match the function icon (28dp) and centered
        // vertically on the button so it can't be confused for the primary
        // action. IconButton (48dp touch target) consumes the gesture so the
        // surrounding Button does not fire.
        if (hasInfo) {
            IconButton(
                onClick = { onShowInfo!!(info!!.title, info.body) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .height(48.dp)
                    .width(48.dp),
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = "说明",
                    tint = content.copy(alpha = 0.85f),
                    modifier = Modifier.height(28.dp).width(28.dp),
                )
            }
        }
    }
}

private val PROGRESS_AREA_HEIGHT = 140.dp

@Composable
private fun ProgressArea(
    state: CopyState,
    onCancel: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    // Fixed-height shell so the layout never jumps when copy state changes.
    // Inner ProgressCard / Idle surface fills this shell.
    Box(modifier = Modifier.fillMaxWidth().height(PROGRESS_AREA_HEIGHT)) {
    when (state) {
        CopyState.Idle -> {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "空闲。点击上方按钮开始。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        is CopyState.Scanning -> {
            ProgressCard(
                title = "扫描中",
                subtitle = "已发现 ${state.foundFiles} 个文件…",
                progress = null,
                rate = null,
                actionLabel = "取消",
                onAction = onCancel,
            )
        }
        is CopyState.Running -> {
            val title = when (state.phase) {
                CopyState.Phase.COPYING -> "${state.currentIndex} / ${state.totalFiles}  ·  ${state.currentName}"
                CopyState.Phase.FINALIZING -> "整理元数据 · ${state.currentName}"
                CopyState.Phase.FIXING_DATES -> "修复日期 ${state.currentIndex}/${state.totalFiles} · ${state.currentName}"
            }
            val subtitle = when (state.phase) {
                CopyState.Phase.COPYING -> "${CopyService.humanSize(state.bytesCopied)} / ${CopyService.humanSize(state.totalBytes)}"
                CopyState.Phase.FINALIZING -> "${CopyService.humanSize(state.bytesCopied)} / ${CopyService.humanSize(state.totalBytes)} · 待处理 ${state.pendingPostProcess} 个文件"
                CopyState.Phase.FIXING_DATES -> "待处理 ${state.totalFiles - state.currentIndex} 个文件"
            }
            val rate = when (state.phase) {
                CopyState.Phase.COPYING -> CopyService.humanRate(state.bytesPerSecond)
                CopyState.Phase.FINALIZING -> "整理中"
                CopyState.Phase.FIXING_DATES -> null
            }
            ProgressCard(
                title = title,
                subtitle = subtitle,
                progress = state.progress.coerceIn(0f, 1f),
                rate = rate,
                actionLabel = "取消",
                onAction = onCancel,
            )
        }
        is CopyState.Done -> {
            val skipPart = if (state.filesSkipped > 0) " · 跳过 ${state.filesSkipped}" else ""
            ProgressCard(
                title = "完成",
                subtitle = "拷贝 ${state.filesCopied}$skipPart · ${CopyService.humanSize(state.totalBytes)} · ${state.elapsedMillis / 1000.0} 秒",
                progress = 1f,
                rate = null,
                actionLabel = "确定",
                onAction = onAcknowledge,
            )
        }
        is CopyState.Failed -> {
            ProgressCard(
                title = "失败",
                subtitle = state.message,
                progress = null,
                rate = null,
                actionLabel = "确定",
                onAction = onAcknowledge,
            )
        }
    }
    } // end fixed-height Box
}

@Composable
private fun ProgressCard(
    title: String,
    subtitle: String,
    progress: Float?,
    rate: String?,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (rate != null) {
                    Text(rate, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun CacheStatusCard(
    lastStarted: Long,
    lastCompleted: Long,
    lastFiles: Long,
    onShowInfo: ((String, String) -> Unit)? = null,
) {
    // Fixed height — sized for the "started + completed" two-line case so the
    // card never resizes when a copy completes and the second line appears.
    Surface(
        modifier = Modifier.fillMaxWidth().height(96.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "上次拷贝",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onShowInfo != null) {
                    InfoIcon {
                        onShowInfo(
                            "上次拷贝",
                            "记录最近一次拷贝的开始/完成时间与文件数。仅作回顾，不影响下次拷贝逻辑（增量拷贝看的是「已成功导入」清单，不是这里的时间戳）。",
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                if (lastStarted > 0) "开始：${formatTime(lastStarted)}" else "尚未拷贝过",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (lastCompleted > 0) {
                Text(
                    "完成：${formatTime(lastCompleted)} · $lastFiles 个文件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

internal fun friendlyTreePath(treeUri: String): String {
    // content://<authority>/tree/primary%3ANikon  -> primary:Nikon
    val decoded = Uri.decode(treeUri)
    val tree = decoded.substringAfterLast("/tree/", missingDelimiterValue = decoded)
    return tree
}

@Composable
private fun ActiveDeviceChip(
    profileName: String?,
    connected: Boolean,
    onClick: () -> Unit,
    onShowInfo: ((String, String) -> Unit)? = null,
) {
    val container = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = container,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Usb,
                contentDescription = null,
                tint = content,
                modifier = Modifier.height(20.dp).width(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "当前设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.75f),
                    )
                    if (onShowInfo != null) {
                        InfoIcon {
                            onShowInfo(
                                "当前设备",
                                "每个设备可配置源目录、保存目录、选定文件格式",
                            )
                        }
                    }
                }
                Text(
                    profileName ?: "未选择 — 点此进入设置",
                    style = MaterialTheme.typography.bodyLarge,
                    color = content,
                )
            }
            Text(
                if (connected) "已连接" else if (profileName == null) "" else "未连接",
                style = MaterialTheme.typography.bodySmall,
                color = content,
            )
        }
    }
}
