package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.DownloadEntry
import com.example.data.model.DownloadStatus
import java.util.Locale

/**
 * The browser's download list.
 *
 * The WebView engine had no downloads at all — no `DownloadListener`, no list — so this is a real
 * feature rather than a port. An entry that lives in shared storage offers "Open"; one that had to
 * be written somewhere only this app can reach says where it went instead of offering an action that
 * could not work.
 */
@Composable
fun DownloadsDialog(
    downloads: List<DownloadEntry>,
    onOpen: (DownloadEntry) -> Unit,
    onRemove: (DownloadEntry) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Downloads") },
        text = {
            if (downloads.isEmpty()) {
                Text(
                    "No downloads yet. Files a site sends as a download — a PDF, an archive, an image " +
                        "sent as an attachment — are saved to Downloads by the browser engine."
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(downloads, key = { it.id }) { entry ->
                        DownloadRow(
                            entry = entry,
                            onOpen = { onOpen(entry) },
                            onRemove = { onRemove(entry) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (downloads.isNotEmpty()) {
                TextButton(onClick = onClearAll) { Text("Clear list") }
            }
        }
    )
}

@Composable
private fun DownloadRow(
    entry: DownloadEntry,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = entry.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = describe(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.isRunning) {
                val progress = entry.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.End) {
            if (entry.contentUri != null) {
                IconButton(onClick = onOpen) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = "Open ${entry.fileName}")
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove from list")
            }
        }
    }
}

private fun describe(entry: DownloadEntry): String {
    val size = formatBytes(entry.bytesWritten)
    return when (entry.status) {
        DownloadStatus.RUNNING -> "Downloading… $size"
        DownloadStatus.COMPLETE -> "Saved to ${entry.location} · $size"
        DownloadStatus.FAILED -> "Failed: ${entry.error ?: "the file could not be written"}"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}
