package com.example.devtools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.devtools.ConsoleEntry
import com.example.devtools.ConsoleLevel
import com.example.devtools.EvidenceSource
import com.example.devtools.InspectorFormatting
import com.example.devtools.InspectorUiState
import com.example.devtools.ui.theme.InspectorGrey

/**
 * JavaScript console.
 *
 * Sources: `WebChromeClient.onConsoleMessage` for `console.log/warn/error/debug` and for JavaScript
 * errors WebView reports itself, plus the injected `window.onerror` /
 * `unhandledrejection` hooks for stack traces. The inspector only *reports* console output — it never
 * suppresses it, so messages still reach logcat exactly as before (`onConsoleMessage` returns false).
 *
 * Message text is scrubbed before it is stored, in both capture modes, because page code routinely
 * logs tokens and session ids.
 */
@Composable
fun ConsoleInspectorTab(
    state: InspectorUiState,
    onClearConsole: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var levels by remember { mutableStateOf(setOf(ConsoleLevel.LOG, ConsoleLevel.WARN, ConsoleLevel.ERROR)) }

    val filtered = if (query.isBlank()) {
        state.console.filter { it.level in levels }
    } else {
        state.console.filter { it.level in levels && it.message.contains(query, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("console_search_field"),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            placeholder = { Text("Search console output…", fontSize = 12.sp) },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConsoleLevel.values().forEach { level ->
                FilterChip(
                    selected = level in levels,
                    onClick = {
                        levels = if (level in levels) {
                            if (levels.size == 1) levels else levels - level
                        } else {
                            levels + level
                        }
                    },
                    label = { Text(level.label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(),
                    modifier = Modifier.testTag("console_level_${level.name.lowercase()}"),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${filtered.size} of ${state.consoleTotal} line(s)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearConsole, modifier = Modifier.testTag("console_clear")) {
                Text("Clear", fontSize = 12.sp)
            }
        }

        if (filtered.isEmpty()) {
            InspectorEmptyState(
                title = "No console output",
                message = "console.log/warn/error and uncaught JavaScript errors from this tab appear " +
                    "here. console.debug/info are only recorded when \"verbose\" is enabled in the " +
                    "Info tab.",
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = filtered, key = { it.id }) { line ->
                ConsoleRow(line)
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ConsoleRow(line: ConsoleEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(8.dp)
            .testTag("console_row_${line.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InspectorBadge(text = line.level.label, color = consoleColor(line.level))
            Text(
                text = InspectorFormatting.time(line.timestampMillis),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (line.repeatCount > 1) {
                InspectorBadge(text = "×${line.repeatCount}", color = InspectorGrey)
            }
            if (line.masked) {
                InspectorBadge(text = "scrubbed", color = MaterialTheme.colorScheme.primary)
            }
            if (line.evidence == EvidenceSource.PAGE_JAVASCRIPT) {
                InspectorBadge(text = "page hook", color = InspectorGrey)
            }
        }
        Text(
            text = line.message,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
        val location = line.source?.let { source ->
            val lineSuffix = line.lineNumber?.let { number -> ":$number" } ?: ""
            "at $source$lineSuffix"
        }
        if (location != null) {
            Text(
                text = location,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal,
            )
        }
        line.stackTrace?.let { stack ->
            Text(
                text = stack,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
