package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.selection.SelectionContainer

@Composable
fun NetworkInspectorDialog(
    entries: List<NetworkRequestLog>,
    captureReady: Boolean,
    recording: Boolean,
    captureError: String?,
    exportStatus: String?,
    onRecordingChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmStart by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var selectedEntryId by remember { mutableStateOf<String?>(null) }
    val selectedEntry = entries.firstOrNull { it.id == selectedEntryId }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Network inspector", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = if (recording) "Recording locally — sensitive values included" else "Capture is off",
                            color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }

                Text(
                    text = "Records this app's GeckoView HTTP(S) requests and WebSocket handshake metadata: URLs, headers (including cookies when Gecko exposes them), and available bodies. Nothing is sent to a logging service. Logs stay in app memory until cleared or the app closes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
                Text(
                    text = "Limits: at most 1,200 requests, 1 MiB per upload body, 2 MiB per response body, and a 16 MiB body-capture budget. Once the quota is reached, bodies are marked truncated; clear the log to reset it. Gecko does not expose every upload stream, browser-internal request, or WebSocket frame; this is not a packet/TLS capture.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (recording) onRecordingChange(false) else confirmStart = true
                        },
                        enabled = recording || captureReady,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (recording) "Stop capture" else "Start capture")
                    }
                    OutlinedButton(
                        onClick = onExport,
                        enabled = entries.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Download HAR")
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = entries.isNotEmpty(),
                    ) { Text("Clear log") }
                    Text(
                        text = "${entries.size} requests",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 12.dp, end = 8.dp),
                    )
                }

                when {
                    !captureReady && captureError == null -> Text(
                        "Preparing the local Gecko network bridge…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    captureError != null -> Text(
                        captureError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                exportStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                if (entries.isEmpty()) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text(
                            "No captured requests yet",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Start capture, then browse or reload a page. Only traffic from this app's browser tabs is in scope.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(items = entries, key = { it.id }) { entry ->
                            NetworkRequestRow(entry = entry, onClick = { selectedEntryId = entry.id })
                        }
                    }
                }
            }
        }
    }

    if (confirmStart) {
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text("Start sensitive capture?") },
            text = {
                Text(
                    "This can record cookies, authorization headers, account identifiers, form values, and response content. The log stays on this device in app memory, but a downloaded HAR is unredacted and can expose accounts or private data. Continue only for traffic you are authorized to inspect."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = false
                    onRecordingChange(true)
                }) { Text("Start capture") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear captured traffic?") },
            text = { Text("This permanently removes the current in-memory log and captured body data.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

    selectedEntry?.let { entry ->
        NetworkRequestDetailsDialog(entry = entry, onDismiss = { selectedEntryId = null })
    }
}

@Composable
private fun NetworkRequestRow(entry: NetworkRequestLog, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.method, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    entry.statusCode?.toString() ?: if (entry.isComplete) "—" else "Pending",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (entry.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    entry.resourceType,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                entry.url,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            Row(modifier = Modifier.padding(top = 3.dp)) {
                val tab = entry.tabId?.let { "Tab $it" } ?: "Browser/background"
                val responseSize = entry.responseBodyBytesCaptured
                Text(
                    "$tab • ${formatByteCount(responseSize)} response body${if (entry.responseBodyTruncated) " (truncated)" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.durationMs?.let {
                    Spacer(Modifier.width(6.dp))
                    Text("• ${it} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            entry.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun NetworkRequestDetailsDialog(entry: NetworkRequestLog, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("${entry.method} • ${entry.statusCode ?: "Pending"}")
                Text(entry.url, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Normal)
            }
        },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    DetailLine("Resource", entry.resourceType)
                    entry.statusLine?.let { DetailLine("Status line", it) }
                    DetailLine("Tab / frame", "${entry.tabId ?: "—"} / ${entry.frameId ?: "—"}")
                    DetailLine("Started", runCatching {
                        java.time.Instant.ofEpochMilli(entry.startedAtEpochMs).toString()
                    }.getOrDefault(entry.startedAtEpochMs.toString()))
                    DetailLine("Duration", entry.durationMs?.let { "$it ms" } ?: if (entry.isComplete) "—" else "In progress")
                    entry.initiator?.let { DetailLine("Initiator", it) }
                    entry.redirectUrl?.let { DetailLine("Redirect target", it) }
                    entry.error?.let { DetailLine("Network error", it, isError = true) }

                    DetailSection("Request headers", entry.requestHeaders)
                    DetailSection("Response headers", entry.responseHeaders)

                    Text("Request body", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        "${entry.requestBodyBytesCaptured} bytes${if (entry.requestBodyTruncated) " • truncated" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    entry.requestBodyNote?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        NetworkBodyPreview.format(
                            entry.requestBody,
                            entry.requestBodyMimeType ?: entry.requestHeaders.firstHeaderValue("content-type"),
                            forceText = entry.requestBodyFormat == "form-data-fields",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    Text("Response body", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        "${entry.responseBodyBytesCaptured} bytes${if (entry.responseBodyTruncated) " • truncated" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    entry.responseBodyNote?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        NetworkBodyPreview.format(entry.responseBody, entry.responseBodyMimeType),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DetailSection(title: String, headers: List<NetworkHeader>) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 3.dp))
    if (headers.isEmpty()) {
        Text("No headers exposed by Gecko.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        headers.forEach { header ->
            Text(
                "${header.name}: ${if (header.isBase64) "[base64] ${header.value}" else header.value}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, isError: Boolean = false) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        value,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 5.dp),
    )
}

private fun List<NetworkHeader>.firstHeaderValue(name: String): String? =
    firstOrNull { it.name.equals(name, ignoreCase = true) && !it.isBase64 }?.value

private fun formatByteCount(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    else -> "${bytes / (1024 * 1024)} MiB"
}
