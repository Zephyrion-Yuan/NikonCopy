package com.garag.nikoncopy.ui

import android.Manifest
import android.content.Intent
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garag.nikoncopy.copy.CopyMode
import com.garag.nikoncopy.copy.CopyService
import com.garag.nikoncopy.copy.CopyState
import com.garag.nikoncopy.viewmodel.CopyViewModel
import kotlinx.coroutines.launch
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
    val savedSource by vm.sourceUri.collectAsStateWithLifecycle()
    val cache by vm.cacheRecord.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Which mode initiated the picker (if it was launched). Used so the picker callback
    // knows whether to start an ALL or INCREMENTAL copy after the user chooses a tree.
    var pendingMode by remember { mutableStateOf<CopyMode?>(null) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notifications are nice-to-have. */ }

    val pickSourceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val mode = pendingMode ?: return@rememberLauncherForActivityResult
        pendingMode = null
        if (uri == null) return@rememberLauncherForActivityResult

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Best-effort persistable read permission so we can probe + reuse next time.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        vm.setSource(uri)

        when (mode) {
            CopyMode.ALL -> vm.startCopyAll(uri)
            CopyMode.INCREMENTAL -> vm.startCopyIncremental(uri)
        }
    }

    /** Reuse the saved source if it's still accessible; otherwise show the picker. */
    val launchOrReuse: (CopyMode) -> Unit = { mode ->
        scope.launch {
            val saved = savedSource?.let { Uri.parse(it) }
            if (saved != null && vm.probeSource(saved)) {
                when (mode) {
                    CopyMode.ALL -> vm.startCopyAll(saved)
                    CopyMode.INCREMENTAL -> vm.startCopyIncremental(saved)
                }
            } else {
                pendingMode = mode
                val hint = "content://com.android.mtp/document/root".toUri()
                try {
                    pickSourceLauncher.launch(hint)
                } catch (_: Throwable) {
                    pickSourceLauncher.launch(null)
                }
            }
        }
        Unit
    }

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
            DestinationStrip(destination, onOpenSettings)
            Spacer(Modifier.height(20.dp))

            val running = state is CopyState.Running || state is CopyState.Scanning
            val canStart = destination != null && !running

            BigButton(
                title = "拷贝全部",
                subtitle = "递归拷贝相机所有 NEF / JPG / MP4，跳过同名",
                icon = Icons.Outlined.CloudDownload,
                primary = true,
                enabled = canStart,
                onClick = { launchOrReuse(CopyMode.ALL) },
            )
            Spacer(Modifier.height(16.dp))
            BigButton(
                title = "增量拷贝",
                subtitle = if (cache.lastCopyStartedAt > 0)
                    "上次 ${formatTime(cache.lastCopyStartedAt)} · 跳过已成功"
                else "首次运行：等同于拷贝全部",
                icon = Icons.Outlined.Refresh,
                primary = false,
                enabled = canStart,
                onClick = { launchOrReuse(CopyMode.INCREMENTAL) },
            )

            Spacer(Modifier.height(28.dp))
            ProgressArea(
                state = state,
                onCancel = { vm.cancelCopy() },
                onAcknowledge = { vm.acknowledgeResult() },
            )

            Spacer(Modifier.height(20.dp))
            CacheStatusCard(
                lastStarted = cache.lastCopyStartedAt,
                lastCompleted = cache.lastCopyCompletedAt,
                lastFiles = cache.lastCopyFiles,
            )
        }
    }
}

@Composable
private fun DestinationStrip(destination: String?, onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "保存到",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = destination?.let { friendlyTreePath(it) } ?: "未设置（请到设置中选择目录）",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(onClick = onOpenSettings) { Text("修改") }
            }
        }
    }
}

@Composable
private fun BigButton(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val container = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, contentDescription = null, modifier = Modifier.width(28.dp).height(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
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

@Composable
private fun ProgressArea(
    state: CopyState,
    onCancel: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    when (state) {
        CopyState.Idle -> {
            Box(
                modifier = Modifier.fillMaxWidth().height(72.dp).background(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(12.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "空闲。点击上方按钮开始。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
            ProgressCard(
                title = "${state.currentIndex} / ${state.totalFiles}  ·  ${state.currentName}",
                subtitle = "${CopyService.humanSize(state.bytesCopied)} / ${CopyService.humanSize(state.totalBytes)}",
                progress = state.progress.coerceIn(0f, 1f),
                rate = CopyService.humanRate(state.bytesPerSecond),
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
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
private fun CacheStatusCard(lastStarted: Long, lastCompleted: Long, lastFiles: Long) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "上次拷贝",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (lastStarted > 0) "开始：${formatTime(lastStarted)}" else "尚未拷贝过",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (lastCompleted > 0) {
                Text(
                    "完成：${formatTime(lastCompleted)} · $lastFiles 个文件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun String.toUri(): Uri = Uri.parse(this)
