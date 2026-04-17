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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garag.nikoncopy.viewmodel.CopyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: CopyViewModel,
    onBack: () -> Unit,
) {
    val destination by vm.destinationUri.collectAsStateWithLifecycle()
    val source by vm.sourceUri.collectAsStateWithLifecycle()
    val cache by vm.cacheRecord.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickDestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some providers won't allow persistable permission; we still try to use it for this session.
        }
        vm.setDestination(uri)
    }

    val pickSourceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        vm.setSource(uri)
    }

    var showClearDialog by remember { mutableStateOf(false) }

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
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            SettingRow(
                title = "保存目录",
                subtitle = destination?.let { friendlyTreePath(it) } ?: "未设置",
                actionIcon = Icons.Outlined.FolderOpen,
                actionLabel = if (destination == null) "选择" else "更改",
                onAction = { pickDestLauncher.launch(null) },
            )

            Spacer(Modifier.height(16.dp))

            SettingRowWithSecondary(
                title = "默认相机目录",
                subtitle = source?.let { friendlyTreePath(it) }
                    ?: "未设置 — 每次按按钮会弹出选择器",
                primaryIcon = Icons.Outlined.Camera,
                primaryLabel = if (source == null) "选择" else "更改",
                onPrimary = { pickSourceLauncher.launch(null) },
                secondaryIcon = Icons.Outlined.Clear,
                secondaryLabel = "清除",
                onSecondary = { vm.clearSource() }.takeIf { source != null },
            )

            Spacer(Modifier.height(16.dp))

            SettingRow(
                title = "已导入缓存记录",
                subtitle = if (cache.lastCopyStartedAt > 0)
                    "上次开始：${formatTime(cache.lastCopyStartedAt)}"
                else "无",
                actionIcon = Icons.Outlined.DeleteSweep,
                actionLabel = "清空",
                destructive = true,
                onAction = { showClearDialog = true },
            )

            Spacer(Modifier.height(24.dp))
            HelpCard()
        }
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

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    actionLabel: String,
    destructive: Boolean = false,
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
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
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
private fun SettingRowWithSecondary(
    title: String,
    subtitle: String,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    secondaryLabel: String,
    onSecondary: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onPrimary,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(primaryIcon, contentDescription = null, modifier = Modifier.height(18.dp).width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(primaryLabel)
                }
                if (onSecondary != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onSecondary) {
                        Icon(secondaryIcon, contentDescription = null, modifier = Modifier.height(18.dp).width(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(secondaryLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "使用提示",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                """
                · 「保存目录」必填。建议选择手机内部存储下你创建的相册文件夹。
                · 「默认相机目录」选填。设了之后，点功能按钮会先用它；若相机没插或目录失效，
                  自动回退到弹出选择器。
                · 「拷贝全部」：跳过目标里已存在同名的文件。
                · 「增量拷贝」：跳过(已存在 ∪ 已成功导入过)，因此首次运行=拷贝全部；上次中
                  断的文件未进入「已成功」清单，会被补传；你在手机端手动删除的，因为在清单里，
                  不会重新导入。
                · 拷贝完成后会把目标文件的修改时间改成相机记录的拍摄时间，相册按时间正确排序。
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
