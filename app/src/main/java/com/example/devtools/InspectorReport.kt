package com.example.devtools

/**
 * Turns inspector data into plain text for "Copy" and "Share".
 *
 * Two rules:
 *
 *  * the text contains only what the inspector really observed — masked values stay masked unless raw
 *    capture is on, which is why [entryReport] takes that flag;
 *  * every "not available" line carries its reason, so a shared report cannot be misread as evidence
 *    that a field was empty.
 */
object InspectorReport {

    /** Report for a single request/response pair. */
    fun entryReport(entry: NetworkEntry, rawCapture: Boolean): String = buildString {
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
        appendHeaders(this, entry.request.headers, rawCapture)
        appendBody(this, "  Request body", entry.request.body, rawCapture, InspectorExplanations.REQUEST_BODY_UNAVAILABLE)
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
            appendHeaders(this, response.headers, rawCapture)
            appendBody(this, "  Response body", response.body, rawCapture, InspectorExplanations.RESPONSE_BODY_UNAVAILABLE)
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

    /** Session report: everything the buffers hold, plus the inspector's own limits. */
    fun sessionReport(snapshot: InspectorSnapshot, console: List<ConsoleEntry>, rawCapture: Boolean): String =
        buildString {
            appendLine("Network Inspector session report")
            appendLine("Entries: ${snapshot.entries.size} (dropped: ${snapshot.droppedEntryCount})")
            appendLine("Console lines: ${console.size}")
            appendLine()
            snapshot.entries.asReversed().forEach { entry ->
                appendLine(entryReport(entry, rawCapture))
                appendLine("-".repeat(60))
            }
            if (console.isNotEmpty()) {
                appendLine("CONSOLE")
                console.asReversed().forEach { line ->
                    appendLine("[${line.level.label}] ${line.message}")
                    line.source?.let { appendLine("    at $it${line.lineNumber?.let { n -> ":$n" } ?: ""}") }
                }
            }
        }

    private fun appendHeaders(target: StringBuilder, headers: List<HttpField>, rawCapture: Boolean) {
        if (headers.isEmpty()) {
            target.appendLine("    (none reported)")
            return
        }
        headers.forEach { field ->
            val value = if (rawCapture && field.canReveal) field.raw else field.display
            target.appendLine("    ${field.name}: $value")
        }
    }

    private fun appendBody(
        target: StringBuilder,
        label: String,
        body: BodyRecord?,
        rawCapture: Boolean,
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
        target.appendLine("$label (${body.kind}, reported size ${body.reportedLength ?: "unknown"}" +
            if (body.truncated) ", truncated)" else ")")
        target.appendLine(preview)
        if (!rawCapture) {
            target.appendLine("    note: ${InspectorExplanations.MASKED_BY_POLICY}")
        }
    }
}
