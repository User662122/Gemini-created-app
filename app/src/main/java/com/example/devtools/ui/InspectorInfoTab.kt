package com.example.devtools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.devtools.InspectorSetting
import com.example.devtools.InspectorUiState
import com.example.devtools.ui.theme.InspectorRed

/**
 * Settings and, more importantly, the honest capability summary: which parts of a request/response
 * this tool can and cannot see, with the reason. It is the same list that ships in
 * `docs/NETWORK_INSPECTOR.md`.
 */
@Composable
fun InspectorInfoTab(
    state: InspectorUiState,
    onSettingChange: (InspectorSetting, Boolean) -> Unit,
    onClearAll: () -> Unit,
    onEndSession: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        InspectorSection(
            title = "Capture settings",
            subtitle = "All switches apply only to this debug build; release builds contain no capture code.",
        )
        InspectorSetting.values().forEach { setting ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = setting.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = setting.description,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (setting.sessionOnly) {
                        Text(
                            text = "Session only: never written to disk.",
                            fontSize = 10.sp,
                            color = InspectorRed,
                        )
                    }
                }
                Switch(
                    checked = state.settings[setting] == true,
                    onCheckedChange = { onSettingChange(setting, it) },
                    modifier = Modifier.testTag("inspector_setting_${setting.key}"),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }

        InspectorSection("Buffers and limits")
        InspectorLimitBanner(
            droppedEntries = state.droppedEntries,
            droppedConsole = state.droppedConsole,
            droppedByRateLimit = state.droppedByRateLimit,
            droppedByPageScript = state.droppedByPageScript,
        )
        Text(
            text = "The inspector keeps a bounded ring buffer (a few hundred rows), collapses repeated " +
                "console lines, rate-limits messages and never writes to disk. Dropped data is counted " +
                "and shown here instead of being hidden, and capture happens on the same threads " +
                "WebView already uses, so enabling it does not add work to page rendering.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = onClearAll, modifier = Modifier.testTag("inspector_clear_all")) {
                Text("Clear all buffers", fontSize = 12.sp)
            }
            TextButton(onClick = onEndSession) {
                Text("End session (drop raw capture)", fontSize = 12.sp)
            }
        }

        InspectorSection(
            title = "What WebView can and cannot provide",
            subtitle = "Cross-checked against the android.webkit API surface; nothing in this list is a guess.",
        )
        CapabilitySummary()

        InspectorSection("Explicitly out of scope")
        Text(
            text = "This inspector observes traffic. It does not modify requests or responses, does not " +
                "inject or forge headers, does not read or write cookies itself, does not retry or " +
                "throttle anything, and implements no CAPTCHA, anti-bot, fingerprint-spoofing or " +
                "rate-limit workarounds. Service Worker fetches and WebSocket frames are not shown " +
                "because WebView exposes no callback for them to an app, not because they are filtered " +
                "out.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}
