package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.automation.AutomationService

/** Turns the localhost automation API on or off and shows the address and token. */
@Composable
fun AutomationDialog(service: AutomationService, onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(service.isEnabled) }
    var token by remember { mutableStateOf(service.token) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Local control API", modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            enabled = on
                            service.setEnabled(on)
                        },
                    )
                }

                Text(
                    text = if (service.isRunning) {
                        "Listening on http://127.0.0.1:${service.port}"
                    } else {
                        "Not running"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                service.lastError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(
                    "Access token (sent as the X-Automation-Token header):",
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Text(
                        text = token,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(onClick = {
                    service.regenerateToken()
                    token = service.token
                }) {
                    Text("Generate new token")
                }

                Text(
                    "From a PC over USB, run: adb forward tcp:${service.port} tcp:${service.port}\n" +
                        "Then use the Python client in tools/gecko_automation. " +
                        "Apps on this phone (for example Termux) can use localhost directly.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}
