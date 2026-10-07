package com.example.devtools

/**
 * Text form of a single captured entry, used by the detail screen's Copy/Share actions and by the
 * session export.
 *
 * Two rules make the output trustworthy:
 *
 *  * a field that is missing prints the *reason* it is missing, never a guess;
 *  * when full capture was off at capture time the values are already masked in the buffer, and the
 *    report says so instead of pretending the entry is complete.
 *
 * Session-level exports live in [InspectorExport], which reuses [entryReport].
 */
object InspectorReport {

    fun entryReport(entry: NetworkEntry, revealSensitiveValues: Boolean): String = buildString {
        appendLine("Network Inspector entry #${entry.id}")
        appendLine("Captured by a debug build of this app. Fields marked \"not available\" are not exposed by WebView.")
        appendLine()
        appendLine("REQUEST")
        appendLine("  URL:      ${entry.url}")
        appendLine("  Method:   ${if (entry.method == UNKNOWN_METHOD) "(not reported)" else entry.method}")
        appendLine("  Started:  ${entry.request.startedAtMillis} (${entry.request.startedAtSource.label})")
        appendLine("  Main frame: ${entry.request.isForMainFrame?.toString() ?: "(not reported)"}")
        appendLine("  Redirect:   ${entry.request.isRedirect?.toString() ?: "(not reported)"}")
        appendLine("  Initiated by: ${entry.request.initiator.label}")
        appendLine("  Resource type: ${entry.request.category.label} (${entry.request.categorySource.label})")
        entry.request.categoryDetail?.let { appendLine("  Type evidence: $it") }
        appendLine("  Headers (${entry.request.headersCompleteness.label}):")
        appendHeaders(this, entry.request.headers, revealSensitiveValues)
        appendBody(this, "  Request body", entry.request.body, revealSensitiveValues, InspectorExplanations.REQUEST_BODY_UNAVAILABLE)
        appendLine()
        appendLine("RESPONSE")
        val response = entry.response
        if (response == null) {
            appendLine("  (no response observed)")
        } else {
            appendLine("  Status:        ${response.statusCode.value ?: "(not observed)"} (${response.statusCode.source.label})")
            response.statusCode.note?.let { appendLine("    reason: $it") }
            appendLine("  Reason phrase: ${response.reasonPhrase.value ?: "(not reported)"}")
            appendLine("  Content-Type:  ${response.contentType.value ?: "(not reported)"}")
            appendLine("  Received at:   ${response.receivedAtMillis.value ?: "(not observed)"} (${response.receivedAtMillis.source.label})")
            appendLine("  Final URL:     ${response.finalUrl.value ?: "(not reported)"}")
            response.finalUrl.note?.let { appendLine("    reason: $it") }
            response.durationMillis?.let { appendLine("  Duration:      ${it} ms") }
            response.errorDescription?.value?.let { appendLine("  Failure:       $it") }
            appendLine("  Headers:")
            appendHeaders(this, response.headers, revealSensitiveValues)
            appendBody(this, "  Response body", response.body, revealSensitiveValues, InspectorExplanations.RESPONSE_BODY_UNAVAILABLE)
        }
        appendLine()
        appendLine("STATE: ${entry.state}")
        if (entry.notes.isNotEmpty()) {
            appendLine()
            appendLine("CAVEATS")
            entry.notes.forEach { appendLine("  - $it") }
        }
    }

    /** Short one-line summary, used for sharing a selection. */
    fun shortEntry(entry: NetworkEntry): String =
        "${entry.method} ${entry.response?.statusCode?.value ?: "—"} ${entry.url}"

    private fun appendHeaders(
        target: StringBuilder,
        headers: List<HttpField>,
        revealSensitiveValues: Boolean,
    ) {
        if (headers.isEmpty()) {
            target.appendLine("    (none reported)")
            return
        }
        headers.forEach { field ->
            // `display` is the real value whenever full capture is on; when it was off the value was
            // replaced before it was ever stored, so there is nothing else to print here.
            target.appendLine("    ${field.name}: ${field.display}")
            if (field.isRedacted && revealSensitiveValues) {
                target.appendLine("      (stored masked: full capture was off when this was captured)")
            }
        }
    }

    private fun appendBody(
        target: StringBuilder,
        label: String,
        body: BodyRecord?,
        revealSensitiveValues: Boolean,
        unavailableReason: String,
    ) {
        if (body == null) {
            target.appendLine("$label: $unavailableReason")
            return
        }
        val preview = body.preview.value
        if (preview == null) {
            target.appendLine("$label: ${body.preview.note ?: unavailableReason}")
            return
        }
        target.appendLine(
            "$label (${body.kind}, reported size ${body.reportedLength ?: "unknown"}" +
                if (body.truncated) ", truncated)" else ")"
        )
        target.appendLine(preview)
        if (!revealSensitiveValues) {
            target.appendLine("    note: ${InspectorExplanations.MASKED_BY_POLICY}")
        }
    }
}
