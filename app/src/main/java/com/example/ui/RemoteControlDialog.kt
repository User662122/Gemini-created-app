package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.remote.RemoteControl

/** Settings for the localhost control server used by scripts in Termux. */
@Composable
fun RemoteControlDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val status = RemoteControl.status

    var enabled by remember { mutableStateOf(RemoteControl.isEnabled(context)) }
    var token by remember { mutableStateOf(RemoteControl.token(context)) }
    var savedPort by remember { mutableStateOf(RemoteControl.port(context)) }
    var portText by remember { mutableStateOf(savedPort.toString()) }
    var portError by remember { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The server works either way; the permission only makes its notification visible. */ }

    fun copy(label: String, text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        RemoteControl.setEnabled(context, value)
        if (value && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val setupCommand = "export BROWSER_PORT=$savedPort BROWSER_TOKEN=$token"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remote control") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Lets scripts on this phone, such as Python in Termux, control the browser and " +
                        "the pages open in it through http://127.0.0.1:$savedPort.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow localhost control", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { setEnabled(it) })
                }

                val statusText = when {
                    !enabled -> "Off"
                    status.running -> "Listening on 127.0.0.1:${status.port}"
                    status.error != null -> "Error: ${status.error}"
                    else -> "Starting..."
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled && status.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = {
                            portText = it.filter(Char::isDigit).take(5)
                            portError = null
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Port") },
                        singleLine = true,
                        isError = portError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    TextButton(
                        enabled = portText != savedPort.toString(),
                        onClick = {
                            val port = portText.toIntOrNull()
                            if (port != null && RemoteControl.setPort(context, port)) {
                                savedPort = port
                            } else {
                                portError = "Use 1024-65535"
                            }
                        },
                    ) { Text("Apply") }
                }
                portError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Text("Access token", style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { copy("Token", token) }) { Text("Copy token") }
                    TextButton(onClick = { token = RemoteControl.regenerateToken(context) }) { Text("New token") }
                }

                Text("Termux setup", style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(setupCommand, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { copy("Command", setupCommand) }) { Text("Copy command") }

                Text(
                    "Anyone with the token can read and control every page, including sites you are " +
                        "signed in to. Only share it with scripts you trust; tap New token to revoke it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
