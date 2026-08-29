package com.example.pwalibrary.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.pwalibrary.R
import com.example.pwalibrary.data.AppEntity
import com.example.pwalibrary.data.FolderGrant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppDetailDialog(
    app: AppEntity,
    onDismiss: () -> Unit,
    folders: List<FolderGrant>,
    onRevokeFolder: (FolderGrant) -> Unit,
    onAddToHome: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppIcon(app.iconPath, app.name, size = 56.dp) },
        title = { Text(app.name) },
        text = {
            // The info rows plus three actions overflow the dialog on short screens.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (app.description.isNotBlank()) {
                    Text(app.description, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                InfoRow("バージョン", app.version)
                InfoRow("サイズ", formatSize(app.sizeBytes))
                InfoRow("追加日", formatDate(app.createdAt))
                InfoRow("更新日", formatDate(app.updatedAt))
                InfoRow("起動ファイル", app.startUrl)
                if (app.shortcutId != null) InfoRow("ホーム画面", "追加済み")

                if (folders.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "アクセスを許可したフォルダ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    folders.forEach { folder ->
                        ActionRow(
                            Icons.Filled.FolderOff,
                            folder.displayName + " の許可を取り消す",
                            { onRevokeFolder(folder) },
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()

                ActionRow(Icons.Filled.Home, stringResource(R.string.add_to_home), onAddToHome)
                ActionRow(Icons.Filled.Share, stringResource(R.string.share_zip), onShare)
                ActionRow(Icons.Filled.SaveAlt, stringResource(R.string.export_zip), onExport)
                ActionRow(Icons.Filled.Refresh, "新しい zip で更新", onUpdate)
                ActionRow(
                    Icons.Filled.Delete,
                    stringResource(R.string.delete),
                    onDelete,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint)
            Spacer(Modifier.width(12.dp))
            Text(label, color = tint)
        }
    }
}

@Composable
fun DeleteConfirmDialog(app: AppEntity, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${app.name} を削除しますか？") },
        text = {
            Text(
                "アプリのファイルと、アプリが保存したデータもすべて消えます。" +
                    if (app.shortcutId != null) {
                        "\n\nホーム画面のアイコンは Android の制約により自動では消せないため、手動で削除してください。"
                    } else {
                        ""
                    }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
