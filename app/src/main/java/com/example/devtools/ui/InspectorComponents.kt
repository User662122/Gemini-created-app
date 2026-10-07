package com.example.devtools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.devtools.BodyRecord
import com.example.devtools.EntryState
import com.example.devtools.EvidenceSource
import com.example.devtools.InspectorExplanations
import com.example.devtools.InspectorValue
import com.example.devtools.RedactionKind
import com.example.devtools.HttpField
import com.example.devtools.asText
import com.example.devtools.ui.theme.InspectorGreen
import com.example.devtools.ui.theme.InspectorIndigo
import com.example.devtools.ui.theme.InspectorRed
import com.example.devtools.ui.theme.InspectorAmber
import com.example.devtools.ui.theme.InspectorGrey

/**
 * Shared building blocks for the inspector screens.
 *
 * The visual language is deliberate: anything the inspector did not observe is rendered as an
 * explicit "not available" sentence in a muted style, so a missing field can never be confused with an
 * empty one.
 */

/** Small rounded label, used for methods, status codes, resource categories and source tags. */
@Composable
fun InspectorBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Text(
        text = text,
        color = if (filled) Color.White else color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Colour for an HTTP status code, or for a non-HTTP state. */
fun statusColor(code: Int?, state: EntryState): Color = when {
    code != null && code >= 500 -> InspectorRed
    code != null && code >= 400 -> InspectorAmber
    code != null && code >= 300 -> InspectorIndigo
    code != null && code >= 200 -> InspectorGreen
    state == EntryState.FAILED -> InspectorRed
    state == EntryState.PENDING -> InspectorIndigo
    else -> InspectorGrey
}

/** Colour for a console level. */
fun consoleColor(level: com.example.devtools.ConsoleLevel): Color = when (level) {
    com.example.devtools.ConsoleLevel.ERROR -> InspectorRed
    com.example.devtools.ConsoleLevel.WARN -> InspectorAmber
    com.example.devtools.ConsoleLevel.LOG -> InspectorGrey
    com.example.devtools.ConsoleLevel.VERBOSE -> InspectorGrey
}

/** Section heading inside the detail screen. */
@Composable
fun InspectorSection(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(
            text = title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * One labelled value. When the value is unknown, [InspectorValue.note] is shown instead of an empty
 * cell, together with the source badge ("inferred" / "not available").
 */
@Composable
fun InspectorValueRow(
    label: String,
    value: InspectorValue<*>,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(132.dp)
            )
            Text(
                text = value.asText(),
                fontSize = 13.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                color = if (value.isKnown) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = if (value.isKnown) Modifier.weight(1f, fill = false) else Modifier.weight(1f),
            )
            if (value.isKnown) {
                Spacer(modifier = Modifier.width(6.dp))
                SourceBadge(value.source)
            }
            trailing?.let {
                Spacer(modifier = Modifier.width(4.dp))
                it()
            }
        }
        if (value.note != null) {
            Text(
                text = value.note,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 132.dp, top = 2.dp),
            )
        }
    }
}

/** Compact badge naming where a value came from, or why it is missing. */
@Composable
fun SourceBadge(source: EvidenceSource) {
    val color = when (source) {
        EvidenceSource.UNAVAILABLE -> InspectorRed
        EvidenceSource.DERIVED -> InspectorAmber
        EvidenceSource.WEBVIEW_CALLBACK -> InspectorIndigo
        EvidenceSource.PAGE_JAVASCRIPT -> InspectorGreen
        else -> InspectorGrey
    }
    val label = when (source) {
        EvidenceSource.UNAVAILABLE -> "not available"
        EvidenceSource.DERIVED -> "inferred"
        else -> source.label
    }
    InspectorBadge(text = label, color = color)
}

/**
 * One header row. Masked values show the mask plus a "reveal" affordance when raw capture is on.
 */
@Composable
fun InspectorHeaderRow(
    field: HttpField,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
) {
    val displayValue = if (revealed && field.canReveal) {
        field.raw ?: field.display
    } else {
        field.display
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = field.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            when {
                revealed && field.canReveal -> InspectorBadge("revealed", InspectorRed)
                field.redaction == RedactionKind.REVEALED -> InspectorBadge("unmasked", InspectorRed)
                field.redaction == RedactionKind.MASKED -> InspectorBadge("masked", InspectorAmber)
                field.redaction == RedactionKind.TRUNCATED -> InspectorBadge("truncated", InspectorAmber)
                field.redaction == RedactionKind.NEVER_STORED -> InspectorBadge("not stored", InspectorRed)
            }
            // With full capture on there is nothing left to reveal: the value above already *is* the
            // stored value. The toggle is only offered for rows whose value is still hidden.
            if (field.canReveal && !revealed && field.redaction == RedactionKind.MASKED) {
                Text(
                    text = "reveal",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onToggleReveal() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (revealed && field.canReveal) {
                Text(
                    text = "hide",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onToggleReveal() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = displayValue,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 1.dp),
        )
        field.note?.let { note ->
            Text(
                text = note,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/** Header list with duplicate names removed and a plain-language completeness note. */
@Composable
fun InspectorHeaderList(fields: List<HttpField>, emptyText: String) {
    if (fields.isEmpty()) {
        Text(
            text = emptyText,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        return
    }
    Column {
        fields.forEach { field ->
            var revealed by rememberRevealed(field.name)
            InspectorHeaderRow(
                field = field,
                revealed = revealed,
                onToggleReveal = { revealed = !revealed },
            )
        }
    }
}

/** Remembers the reveal state of one header while the detail screen is open. */
@Composable
private fun rememberRevealed(key: String): MutableState<Boolean> = remember(key) { mutableStateOf(false) }

/**
 * Permanent, unmissable reminder that the inspector is currently storing secrets in the clear.
 *
 * It is deliberately not dismissible: the state it describes is the one that ends up in exported
 * files, and a developer coming back to the screen after a while should never have to guess which mode
 * the buffer was captured in.
 */
@Composable
fun FullCaptureBanner(
    revealSensitiveValues: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!revealSensitiveValues) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(InspectorRed.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("inspector_full_capture_banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InspectorBadge(text = "UNMASKED", color = InspectorRed)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Reveal sensitive values is on: headers, URLs, cookies and bodies are stored " +
                "exactly as observed. Debug builds only.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Short size label for a request/response body. */
fun bodySizeLabel(body: BodyRecord?): String? {
    val length = body?.reportedLength ?: return null
    return if (length < 1024) "$length chars (as reported)" else "%.1f KB (as reported)".format(length / 1024.0)
}

/** The "what this tool can and cannot see" panel, shared by the settings tab and the report. */
@Composable
fun CapabilitySummary() {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "What WebView lets this app observe",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        InspectorExplanations.CAPABILITY_SUMMARY.forEach { (line, supported) ->
            Row(
                modifier = Modifier.padding(vertical = 1.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = if (supported) "✓" else "✕",
                    fontSize = 12.sp,
                    color = if (supported) InspectorGreen else InspectorGrey,
                    modifier = Modifier.width(18.dp),
                )
                Text(
                    text = line,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Empty state with a reason, never just "no data". */
@Composable
fun InspectorEmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Global "something was dropped" banner; the inspector never hides its own limits. */
@Composable
fun InspectorLimitBanner(
    droppedEntries: Int,
    droppedConsole: Int,
    droppedByRateLimit: Long,
    droppedByPageScript: Long,
) {
    val parts = ArrayList<String>(4)
    if (droppedEntries > 0) parts += "$droppedEntries request(s) scrolled out of the ring buffer"
    if (droppedConsole > 0) parts += "$droppedConsole console line(s) dropped"
    if (droppedByRateLimit > 0) parts += "$droppedByRateLimit message(s) dropped by the rate limiter"
    if (droppedByPageScript > 0) parts += "$droppedByPageScript record(s) dropped by the page-side queue"
    if (parts.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(InspectorAmber.copy(alpha = 0.14f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Limits in effect: " + parts.joinToString("; ") + ". The inspector drops data instead of " +
                "slowing the browser down, and it tells you when this happens.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


