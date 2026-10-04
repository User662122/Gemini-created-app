package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BrowserAutomation

@Composable
fun AutomationsDialog(
    automations: List<BrowserAutomation>,
    currentUrl: String?,
    isIncognito: Boolean,
    isJavaScriptEnabled: Boolean,
    isRecording: Boolean,
    recordingStepCount: Int,
    runningAutomationName: String?,
    statusMessage: String?,
    onStartRecording: () -> Unit,
    onSaveRecording: (String) -> Unit,
    onCancelRecording: () -> Unit,
    onRunAutomation: (BrowserAutomation) -> Unit,
    onDeleteAutomation: (BrowserAutomation) -> Unit,
    onStopPlayback: () -> Unit,
    onDismiss: () -> Unit
) {
    var automationName by rememberSaveable(isRecording) { mutableStateOf("") }
    val canRecord = !isIncognito && isJavaScriptEnabled &&
        (currentUrl?.startsWith("http://") == true || currentUrl?.startsWith("https://") == true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automations") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isRecording) {
                    Text(
                        text = "Recording · $recordingStepCount step${if (recordingStepCount == 1) "" else "s"}",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Actions are saved on this device. Password fields and file uploads are not recorded; other text you enter may be stored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = automationName,
                        onValueChange = { automationName = it.take(50) },
                        label = { Text("Automation name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (recordingStepCount == 0) {
                        Text(
                            text = "Interact with the website before returning here to save the steps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSaveRecording(automationName) },
                            enabled = recordingStepCount > 0
                        ) {
                            Text("Save recording")
                        }
                        TextButton(onClick = onCancelRecording) {
                            Text("Discard")
                        }
                    }
                } else {
                    Text(
                        text = "Record clicks and text-field entries on a website, then replay them later.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onStartRecording,
                        enabled = canRecord && runningAutomationName == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start recording")
                    }
                    if (!canRecord) {
                        Text(
                            text = when {
                            isIncognito -> "Recording is disabled in incognito tabs."
                            !isJavaScriptEnabled -> "Enable JavaScript in Settings to record website actions."
                            else -> "Open a website first to start recording."
                        },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!runningAutomationName.isNullOrBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Running $runningAutomationName",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = onStopPlayback) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Stop")
                            }
                        }
                    }

                    HorizontalDivider()
                    Text("Saved automations", style = MaterialTheme.typography.titleSmall)
                    if (automations.isEmpty()) {
                        Text(
                            text = "No automations saved yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(automations, key = { it.id }) { automation ->
                                AutomationRow(
                                    automation = automation,
                                    enabled = runningAutomationName == null && isJavaScriptEnabled,
                                    onRun = { onRunAutomation(automation) },
                                    onDelete = { onDeleteAutomation(automation) }
                                )
                            }
                        }
                    }
                }

                if (!statusMessage.isNullOrBlank()) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun AutomationRow(
    automation: BrowserAutomation,
    enabled: Boolean,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = automation.name,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${automation.steps.size} steps · ${Uri.parse(automation.startUrl).host ?: automation.startUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRun, enabled = enabled) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Run ${automation.name}")
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${automation.name}")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}
