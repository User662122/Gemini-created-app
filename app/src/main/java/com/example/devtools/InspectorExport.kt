package com.example.devtools

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The two shapes the inspector can be exported in. */
enum class ExportFormat(val mimeType: String, val extension: String) {
    /** Human-readable report: every entry in full, then console and cookies. */
    TEXT("text/plain", "txt"),

    /** Machine-readable dump of the same data, for offline analysis. */
    JSON("application/json", "json"),
}

/**
 * Builds the exportable form of everything the inspector holds.
 *
 * Pure formatting — no Android types, no I/O — so it can be unit tested, and so the file-writing side
 * ([InspectorDownload]) stays a thin adapter. Both formats carry the same information: capture mode,
 * every setting, the buffer and drop counters, the capability summary, every network entry in full,
 * the console, and the cookies.
 *
 * Whether values appear in the clear is decided by the *capture mode that stored them*, not by this
 * formatter: when full capture was off, the stored values are already masked and there is nothing left
 * to hide. When it was on, the file contains real tokens and cookie values, which is why the file
 * starts with an explicit warning.
 */
object InspectorExport {

    /** File name for an export, e.g. `network-inspector-20261007-123005.json`. */
    fun fileName(format: ExportFormat, nowMillis: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis))
        return "network-inspector-$stamp.${format.extension}"
    }

    /** Renders the whole session in the requested format. */
    fun render(
        format: ExportFormat,
        exportedAtMillis: Long,
        settings: Map<InspectorSetting, Boolean>,
        snapshot: InspectorSnapshot,
        droppedConsoleRows: Int,
        cookies: List<CookieRecord>,
        droppedByRateLimit: Long,
        droppedByPageScript: Long,
    ): String {
        val reveal = settings[InspectorSetting.FULL_CAPTURE] == true
        return when (format) {
            ExportFormat.TEXT -> text(
                exportedAtMillis = exportedAtMillis,
                settings = settings,
                snapshot = snapshot,
                droppedConsoleRows = droppedConsoleRows,
                cookies = cookies,
                droppedByRateLimit = droppedByRateLimit,
                droppedByPageScript = droppedByPageScript,
            )

            ExportFormat.JSON -> json(
                exportedAtMillis = exportedAtMillis,
                settings = settings,
                snapshot = snapshot,
                droppedConsoleRows = droppedConsoleRows,
                cookies = cookies,
                droppedByRateLimit = droppedByRateLimit,
                droppedByPageScript = droppedByPageScript,
                reveal = reveal,
            ).toString(2)
        }
    }

    /** True when the rendered text will contain unmasked secrets. */
    fun containsUnmaskedValues(settings: Map<InspectorSetting, Boolean>): Boolean =
        settings[InspectorSetting.FULL_CAPTURE] == true

    // ------------------------------------------------------------------------------------ text

    private fun text(
        exportedAtMillis: Long,
        settings: Map<InspectorSetting, Boolean>,
        snapshot: InspectorSnapshot,
        droppedConsoleRows: Int,
        cookies: List<CookieRecord>,
        droppedByRateLimit: Long,
        droppedByPageScript: Long,
    ): String = buildString {
        val reveal = settings[InspectorSetting.FULL_CAPTURE] == true

        appendLine("Network Inspector export")
        appendLine("Generated:      ${timestamp(exportedAtMillis)}")
        appendLine("Capture mode:   " + if (reveal) "FULL CAPTURE — values are unmasked" else "masked (full capture was off)")
        appendLine("Entries:        ${snapshot.entries.size} (evicted: ${snapshot.droppedEntryCount})")
        appendLine("Console lines:  ${snapshot.console.size} (evicted: $droppedConsoleRows)")
        appendLine("Cookies:        ${cookies.size}")
        if (droppedByRateLimit > 0L || droppedByPageScript > 0L) {
            appendLine(
                "Dropped by limits: $droppedByRateLimit message(s) over the rate limit, " +
                    "$droppedByPageScript record(s) by the page script"
            )
        }
        appendLine()
        if (reveal) {
            appendLine("!! " + InspectorExplanations.FULL_CAPTURE_WARNING)
            appendLine()
        }
        appendLine("SETTINGS")
        InspectorSetting.values().forEach { setting ->
            appendLine("  ${setting.title}: ${settings[setting] == true}")
        }
        appendLine()
        appendLine("WHAT WEBVIEW LETS THIS APP OBSERVE")
        InspectorExplanations.CAPABILITY_SUMMARY.forEach { (label, available) ->
            appendLine("  [${if (available) "yes" else "no "}] $label")
        }

        appendLine()
        appendLine("=".repeat(72))
        appendLine("NETWORK ENTRIES (newest first)")
        appendLine("=".repeat(72))
        if (snapshot.entries.isEmpty()) {
            appendLine("(nothing captured)")
        } else {
            snapshot.entries.forEach { entry ->
                appendLine(InspectorReport.entryReport(entry, reveal))
                appendLine("-".repeat(72))
            }
        }

        appendLine()
        appendLine("=".repeat(72))
        appendLine("CONSOLE (newest first)")
        appendLine("=".repeat(72))
        if (snapshot.console.isEmpty()) {
            appendLine("(nothing captured)")
        } else {
            snapshot.console.forEach { line ->
                appendLine("[${line.level.label}] ${timestamp(line.timestampMillis)} ${line.message}")
                if (line.repeatCount > 1) appendLine("    (repeated ${line.repeatCount} times)")
                line.source?.let { appendLine("    at $it${line.lineNumber?.let { n -> ":$n" } ?: ""}") }
                line.stackTrace?.let { appendLine(indent(it)) }
                if (line.masked) appendLine("    (text was scrubbed at capture time)")
                line.evidence.let { if (it == EvidenceSource.PAGE_JAVASCRIPT) appendLine("    reported by the page hook") }
            }
        }

        appendLine()
        appendLine("=".repeat(72))
        appendLine("COOKIES")
        appendLine("=".repeat(72))
        if (cookies.isEmpty()) {
            appendLine("(none visible)")
        } else {
            cookies.forEach { cookie ->
                appendLine("${cookie.name} = ${cookie.value.value ?: "(not stored)"}")
                appendLine("    domain:   ${cookie.domain.value ?: "(not available)"}")
                appendLine("    path:     ${cookie.path.value ?: "(not available)"}")
                appendLine("    secure:   ${cookie.secure.value ?: "(not available)"}")
                appendLine("    httponly: ${cookie.httpOnly.value ?: "(not available)"}")
                appendLine("    expires:  ${cookie.expiration.value ?: "(not available)"}")
                appendLine("    seen at:  ${cookie.observedForUrl}")
                appendLine("    sources:  ${cookie.sources.joinToString { it.label }}")
                cookie.notes.forEach { appendLine("    note: $it") }
            }
        }
        appendLine()
        appendLine("End of export.")
    }

    private fun indent(text: String): String = text.lines().joinToString("\n") { "    $it" }

    // ------------------------------------------------------------------------------------ json

    private fun json(
        exportedAtMillis: Long,
        settings: Map<InspectorSetting, Boolean>,
        snapshot: InspectorSnapshot,
        droppedConsoleRows: Int,
        cookies: List<CookieRecord>,
        droppedByRateLimit: Long,
        droppedByPageScript: Long,
        reveal: Boolean,
    ): JSONObject = JSONObject().apply {
        put("exportedAtMillis", exportedAtMillis)
        put("captureMode", if (reveal) "full" else "masked")
        put("unmaskedValuesIncluded", reveal)
        put("captureWarning", if (reveal) InspectorExplanations.FULL_CAPTURE_WARNING else JSONObject.NULL)
        put("revision", snapshot.revision)

        put("settings", JSONObject().apply {
            InspectorSetting.values().forEach { setting ->
                put(setting.key, settings[setting] == true)
            }
        })

        put("dropped", JSONObject().apply {
            put("entries", snapshot.droppedEntryCount)
            put("consoleRows", droppedConsoleRows)
            put("rateLimitedMessages", droppedByRateLimit)
            put("pageScriptRecords", droppedByPageScript)
        })

        put("capabilities", JSONArray().apply {
            InspectorExplanations.CAPABILITY_SUMMARY.forEach { (label, available) ->
                put(JSONObject().apply {
                    put("capability", label)
                    put("available", available)
                })
            }
        })

        put("entries", JSONArray().apply { snapshot.entries.forEach { put(entryJson(it)) } })
        put("console", JSONArray().apply { snapshot.console.forEach { put(consoleJson(it)) } })
        put("cookies", JSONArray().apply { cookies.forEach { put(cookieJson(it)) } })
    }

    private fun entryJson(entry: NetworkEntry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("tabId", entry.tabId)
        put("state", entry.state.name)
        // App clock only: this is when the inspector stored the row, never a page-supplied time.
        put("recordedAtAppMillis", entry.recordedAtAppMillis)
        put("pageObserved", entry.pageObserved)
        put("notes", JSONArray(entry.notes))
        put("request", requestJson(entry.request))
        entry.response?.let { put("response", responseJson(it)) }
    }

    private fun requestJson(request: RequestRecord): JSONObject = JSONObject().apply {
        put("url", request.url)
        put("method", request.method)
        put("startedAtMillis", request.startedAtMillis)
        put("startedAtSource", request.startedAtSource.name)
        put("isForMainFrame", request.isForMainFrame ?: JSONObject.NULL)
        put("isRedirect", request.isRedirect ?: JSONObject.NULL)
        put("hasGesture", request.hasGesture ?: JSONObject.NULL)
        put("initiator", request.initiator.name)
        put("category", request.category.name)
        put("categorySource", request.categorySource.name)
        put("categoryDetail", request.categoryDetail ?: JSONObject.NULL)
        put("headerCompleteness", request.headersCompleteness.name)
        put("headers", JSONArray().apply { request.headers.forEach { put(fieldJson(it)) } })
        request.body?.let { put("body", bodyJson(it)) }
    }

    private fun responseJson(response: ResponseRecord): JSONObject = JSONObject().apply {
        put("statusCode", valueJson(response.statusCode))
        put("reasonPhrase", valueJson(response.reasonPhrase))
        put("contentType", valueJson(response.contentType))
        put("receivedAtMillis", valueJson(response.receivedAtMillis))
        put("finalUrl", valueJson(response.finalUrl))
        put("durationMillis", response.durationMillis ?: JSONObject.NULL)
        response.errorDescription?.let { put("failure", valueJson(it)) }
        put("headers", JSONArray().apply { response.headers.forEach { put(fieldJson(it)) } })
        response.body?.let { put("body", bodyJson(it)) }
    }

    private fun bodyJson(body: BodyRecord): JSONObject = JSONObject().apply {
        put("kind", body.kind.name)
        put("reportedLength", body.reportedLength ?: JSONObject.NULL)
        put("truncated", body.truncated)
        put("preview", valueJson(body.preview))
    }

    private fun consoleJson(line: ConsoleEntry): JSONObject = JSONObject().apply {
        put("id", line.id)
        put("level", line.level.name)
        put("message", line.message)
        put("timestampMillis", line.timestampMillis)
        put("repeatCount", line.repeatCount)
        put("masked", line.masked)
        put("evidence", line.evidence.name)
        put("source", line.source ?: JSONObject.NULL)
        put("lineNumber", line.lineNumber ?: JSONObject.NULL)
        put("stackTrace", line.stackTrace ?: JSONObject.NULL)
    }

    private fun cookieJson(cookie: CookieRecord): JSONObject = JSONObject().apply {
        put("id", cookie.id)
        put("name", cookie.name)
        put("value", valueJson(cookie.value))
        put("domain", valueJson(cookie.domain))
        put("path", valueJson(cookie.path))
        put("secure", valueJson(cookie.secure))
        put("httpOnly", valueJson(cookie.httpOnly))
        put("expiration", valueJson(cookie.expiration))
        put("observedForUrl", cookie.observedForUrl)
        put("sources", JSONArray().apply { cookie.sources.forEach { put(it.name) } })
        put("notes", JSONArray(cookie.notes))
    }

    private fun fieldJson(field: HttpField): JSONObject = JSONObject().apply {
        put("name", field.name)
        put("value", field.display)
        put("redaction", field.redaction.name)
        field.note?.let { put("note", it) }
    }

    /** Every value keeps its provenance, so an offline reader can tell fact from inference. */
    private fun valueJson(value: InspectorValue<*>): JSONObject = JSONObject().apply {
        put("value", value.value ?: JSONObject.NULL)
        put("source", value.source.name)
        put("inferred", value.isInferred)
        value.note?.let { put("note", it) }
    }

    private fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))
}
