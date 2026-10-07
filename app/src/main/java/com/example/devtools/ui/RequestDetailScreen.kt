package com.example.devtools.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.devtools.BodyRecord
import com.example.devtools.EntryState
import com.example.devtools.EvidenceSource
import com.example.devtools.HeaderCompleteness
import com.example.devtools.InspectorExplanations
import com.example.devtools.InspectorFormatting
import com.example.devtools.InspectorReport
import com.example.devtools.InspectorValue
import com.example.devtools.NetworkEntry
import com.example.devtools.ui.theme.InspectorAmber

/**
 * Detail screen for one request/response pair.
 *
 * Opened by tapping a row in the network list. Everything that could not be observed is spelled out
 * with the technical reason, and masked fields offer a per-field reveal when raw capture is on.
 */
@Composable
fun RequestDetailScreen(
    entry: NetworkEntry,
    /** True while "Reveal sensitive values" is on, so the screen can explain what it is showing. */
    revealSensitiveValues: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("inspector_detail_screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("inspector_detail_back")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Request #${entry.id}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InspectorBadge(
                            text = if (entry.method == "UNKNOWN") "(method not reported)" else entry.method,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        InspectorBadge(
                            text = InspectorFormatting.statusLabel(entry),
                            color = statusColor(entry.statusCode, entry.state),
                        )
                        InspectorBadge(text = entry.state.name, color = statusColor(entry.statusCode, entry.state))
                    }
                }
                IconButton(
                    onClick = { copyToClipboard(context, InspectorReport.entryReport(entry, revealSensitiveValues)) },
                    modifier = Modifier.testTag("inspector_detail_copy"),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy report",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = { shareText(context, InspectorReport.entryReport(entry, revealSensitiveValues)) },
                    modifier = Modifier.testTag("inspector_detail_share"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share report",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (entry.notes.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = InspectorAmber.copy(alpha = 0.12f),
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Caveats for this entry",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            entry.notes.forEach { note ->
                                Text(
                                    text = "• $note",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }

                InspectorSection("Request")
                InspectorValueRow(
                    label = "URL",
                    value = InspectorValue.known(entry.request.url, EvidenceSource.WEBVIEW_CALLBACK),
                    mono = true,
                )
                InspectorValueRow(
                    label = "Method",
                    value = if (entry.method == "UNKNOWN") {
                        InspectorValue.unknown("No source reported a method for this message.")
                    } else {
                        InspectorValue.known(entry.method, entry.request.startedAtSource)
                    },
                )
                InspectorValueRow(
                    label = "Start time",
                    value = InspectorValue.known(
                        InspectorFormatting.time(entry.request.startedAtMillis),
                        entry.request.startedAtSource,
                        if (entry.request.startedAtSource == EvidenceSource.PAGE_JAVASCRIPT) {
                            "Timestamp comes from the page's own clock (Date.now()), not from the app."
                        } else {
                            null
                        },
                    ),
                )
                InspectorValueRow(
                    label = "Main frame",
                    value = flagValue(entry.request.isForMainFrame, "WebView did not report whether this was a main-frame request."),
                )
                InspectorValueRow(
                    label = "Redirect hop",
                    value = flagValue(entry.request.isRedirect, "WebView did not report whether this request came from a redirect."),
                )
                InspectorValueRow(
                    label = "Initiated by",
                    value = InspectorValue.known(entry.request.initiator.label, entry.request.categorySource),
                )
                InspectorValueRow(
                    label = "Resource type",
                    value = InspectorValue.known(
                        entry.request.category.label,
                        entry.request.categorySource,
                        entry.request.categoryDetail,
                    ),
                )

                InspectorSection(
                    title = "Request headers",
                    subtitle = entry.request.headersCompleteness.explanation,
                )
                InspectorHeaderList(
                    fields = entry.request.headers,
                    emptyText = if (entry.request.headersCompleteness == HeaderCompleteness.UNKNOWN) {
                        "No headers were reported for this request. " + InspectorExplanations.REQUEST_HEADERS_SUBSET
                    } else {
                        "WebView reported an empty header set for this request."
                    },
                )
                if (revealSensitiveValues && entry.request.headers.any { it.isRedacted }) {
                    MaskedWhileOffNote()
                }

                InspectorSection("Request body")
                BodySection(
                    body = entry.request.body,
                    unavailableReason = InspectorExplanations.REQUEST_BODY_UNAVAILABLE,
                    revealSensitiveValues = revealSensitiveValues,
                )

                InspectorSection("Response")
                val response = entry.response
                if (response == null) {
                    Text(
                        text = if (entry.state == EntryState.PENDING) {
                            "No response callback has arrived yet."
                        } else {
                            InspectorExplanations.RESPONSE_NEVER_OBSERVED
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                } else {
                    InspectorValueRow(label = "Status", value = response.statusCode)
                    InspectorValueRow(label = "Reason phrase", value = response.reasonPhrase)
                    InspectorValueRow(label = "Content-Type", value = response.contentType)
                    val receivedAt = response.receivedAtMillis.value
                    InspectorValueRow(
                        label = "Received",
                        value = if (receivedAt != null) {
                            InspectorValue.known(
                                InspectorFormatting.time(receivedAt),
                                response.receivedAtMillis.source,
                                response.receivedAtMillis.note,
                            )
                        } else {
                            response.receivedAtMillis
                        },
                    )
                    InspectorValueRow(label = "Final URL", value = response.finalUrl, mono = true)
                    InspectorValueRow(
                        label = "Duration",
                        value = if (response.durationMillis != null) {
                            InspectorValue.known(
                                InspectorFormatting.duration(response.durationMillis) ?: "—",
                                EvidenceSource.DERIVED,
                                "Measured between the observed request and the observed response.",
                            )
                        } else {
                            InspectorValue.unknown(
                                "Not measured: WebView does not report durations, and the two " +
                                    "timestamps came from different clocks."
                            )
                        },
                    )
                    response.errorDescription?.let { failure ->
                        InspectorValueRow(label = "Failure", value = failure)
                    }

                    InspectorSection(
                        title = "Response headers",
                        subtitle = if (response.headers.isEmpty()) {
                            InspectorExplanations.RESPONSE_HEADERS_EMPTY_FOR_HTTP_ERROR
                        } else {
                            HeaderCompleteness.SUBSET_REPORTED_BY_WEBVIEW.explanation
                        },
                    )
                    InspectorHeaderList(
                        fields = response.headers,
                        emptyText = "No response headers were reported for this message.",
                    )
                    if (revealSensitiveValues && response.headers.any { it.isRedacted }) {
                        MaskedWhileOffNote()
                    }

                    InspectorSection("Response body preview")
                    BodySection(
                        body = response.body,
                        unavailableReason = InspectorExplanations.RESPONSE_BODY_UNAVAILABLE,
                        revealSensitiveValues = revealSensitiveValues,
                    )
                }

                InspectorSection("What this row cannot show")
                Text(
                    text = listOf(
                        InspectorExplanations.SERVICE_WORKER_UNAVAILABLE,
                        InspectorExplanations.WEBSOCKET_UNAVAILABLE,
                        InspectorExplanations.CACHE_AND_PREFLIGHT,
                        InspectorExplanations.REDIRECT_INVISIBLE,
                    ).joinToString("\n\n"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun BodySection(
    body: BodyRecord?,
    unavailableReason: String,
    revealSensitiveValues: Boolean,
) {
    if (body == null) {
        Text(
            text = unavailableReason,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        return
    }
    val value = body.preview
    val preview = value.value
    if (preview == null) {
        Text(
            text = value.note ?: unavailableReason,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        return
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            InspectorBadge(text = body.kind.name.lowercase(), color = MaterialTheme.colorScheme.primary)
            InspectorBadge(text = value.source.label, color = MaterialTheme.colorScheme.primary)
            if (body.truncated) InspectorBadge(text = "truncated", color = InspectorAmber)
            if (!revealSensitiveValues) InspectorBadge(text = "scrubbed", color = InspectorAmber)
        }
        Text(
            text = preview,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        if (!revealSensitiveValues) {
            Text(
                text = "Secrets that matched the redaction policy were replaced before this text was " +
                    "stored, so the file the page actually sent is not recoverable from this row. Turn " +
                    "on \"Reveal sensitive values\" and reload the page to capture bodies verbatim.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Shown when a row contains masked values although "Reveal sensitive values" is on *now*: the masking
 * was decided when the row was captured, and re-reading it would need the request to happen again.
 */
@Composable
private fun MaskedWhileOffNote() {
    Text(
        text = InspectorExplanations.FULL_CAPTURE_OFF_NOTE,
        fontSize = 11.sp,
        color = InspectorAmber,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private fun flagValue(value: Boolean?, unknownReason: String): InspectorValue<Boolean> =
    if (value == null) InspectorValue.unknown(unknownReason) else InspectorValue.known(value, EvidenceSource.WEBVIEW_CALLBACK)

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Network Inspector", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "Network Inspector report")
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share report")) }
}
