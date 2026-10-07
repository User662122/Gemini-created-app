package com.example.devtools

import org.json.JSONObject

/**
 * One event parsed out of a batch sent by the inspector's injected page hooks.
 *
 * The wire format is documented in `docs/NETWORK_INSPECTOR.md`; keys are short because every byte
 * crosses the JavaScript bridge.
 */
data class PageJsRecord(
    val kind: Kind,
    /** Page-generated request identifier, unique per document (used to pair request and response). */
    val requestId: Long,
    val url: String,
    val method: String,
    /** `Date.now()` of the page when the call was made — a *different clock* from the app's. */
    val pageTimeMillis: Long?,
    val requestHeaders: Map<String, String>,
    val bodyPreview: String?,
    val bodyKind: BodyKind,
    val bodyLength: Int?,
    val bodyTruncated: Boolean,
    val initiator: Initiator?,
    val statusCode: Int?,
    val statusText: String?,
    val responseHeaders: Map<String, String>,
    val contentType: String?,
    val responseTimeMillis: Long?,
    val durationMillis: Long?,
    val finalUrl: String?,
    /** Response body preview, only present when the user enabled response-body capture. */
    val responseBodyPreview: String?,
    val responseBodyTruncated: Boolean,
    val errorText: String?,
    val stackTrace: String?,
) {
    enum class Kind { REQUEST, RESPONSE, FAILURE, CONSOLE_ERROR }
}

/**
 * A batch of records pushed by the injected hooks, together with the identifier of the document that
 * produced them (`docId` is generated when the script is installed). The document id keeps request
 * identifiers unique across navigations, so a late response from the previous page can never be
 * paired with a request from the new one.
 */
data class PageJsBatch(
    val documentId: String?,
    val records: List<PageJsRecord>,
    /** Records the page-side script dropped because its own send queue was full. */
    val droppedByPage: Int,
) {
    companion object {
        val EMPTY = PageJsBatch(documentId = null, records = emptyList(), droppedByPage = 0)
    }
}

/**
 * Parses the JSON batches produced by [InspectorScripts].
 *
 * The page can send anything through the bridge (it is the page's own JavaScript), so this parser is
 * defensive: it never throws, it validates that URLs are http(s), it caps the number of records it
 * will accept per batch, and it rejects oversized strings. A malformed message is simply dropped —
 * the inspector never fabricates a request to explain a bad payload.
 */
object PageJsRecordParser {

    /** Records accepted from a single batch. */
    const val MAX_RECORDS_PER_BATCH = 200

    /** Longest URL accepted from the page. */
    private const val MAX_URL = InspectorLimits.MAX_URL_CHARS

    /** Longest single header value accepted from the page. */
    private const val MAX_HEADER_VALUE = InspectorLimits.MAX_HEADER_VALUE_CHARS

    fun parseBatch(json: String): PageJsBatch {
        if (json.length > InspectorLimits.MAX_BRIDGE_BATCH_CHARS) return PageJsBatch.EMPTY
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return PageJsBatch.EMPTY
        val array = root.optJSONArray("r") ?: return PageJsBatch.EMPTY

        val records = ArrayList<PageJsRecord>(array.length().coerceAtMost(MAX_RECORDS_PER_BATCH))
        val limit = array.length().coerceAtMost(MAX_RECORDS_PER_BATCH)
        for (index in 0 until limit) {
            val item = array.optJSONObject(index) ?: continue
            val record = parseRecord(item) ?: continue
            records.add(record)
        }
        return PageJsBatch(
            documentId = root.optStringOrNull("doc", 64),
            records = records,
            droppedByPage = root.optIntOrNull("d") ?: 0,
        )
    }

    private fun parseRecord(item: JSONObject): PageJsRecord? {
        val kind = when (item.optString("k")) {
            "req" -> PageJsRecord.Kind.REQUEST
            "res" -> PageJsRecord.Kind.RESPONSE
            "err" -> PageJsRecord.Kind.FAILURE
            "cerr" -> PageJsRecord.Kind.CONSOLE_ERROR
            else -> return null
        }

        val url = item.optString("url").take(MAX_URL)
        if (kind != PageJsRecord.Kind.CONSOLE_ERROR) {
            val scheme = UrlParts.scheme(url)
            if (scheme != "http" && scheme != "https") return null
        }

        val requestId = item.optLong("id", -1L)
        if (kind != PageJsRecord.Kind.CONSOLE_ERROR && requestId < 0L) return null

        return PageJsRecord(
            kind = kind,
            requestId = requestId,
            url = url,
            method = item.optString("m").ifBlank { "GET" }.uppercase(),
            pageTimeMillis = item.optLongOrNull("t"),
            requestHeaders = readHeaders(item.optJSONObject("h")),
            bodyPreview = item.optStringOrNull("b", InspectorLimits.MAX_BODY_PREVIEW_CHARS),
            bodyKind = bodyKind(item.optString("bk")),
            bodyLength = item.optIntOrNull("bl"),
            bodyTruncated = item.optInt("bt", 0) == 1,
            initiator = initiator(item.optString("i")),
            statusCode = item.optIntOrNull("st"),
            statusText = item.optStringOrNull("sx", 120),
            responseHeaders = readHeaders(item.optJSONObject("rh")),
            contentType = item.optStringOrNull("ct", 200),
            responseTimeMillis = item.optLongOrNull("rt"),
            durationMillis = item.optLongOrNull("du"),
            finalUrl = item.optStringOrNull("fu", MAX_URL),
            responseBodyPreview = item.optStringOrNull("rb", InspectorLimits.MAX_BODY_PREVIEW_CHARS),
            responseBodyTruncated = item.optInt("rbt", 0) == 1,
            errorText = item.optStringOrNull("em", 500),
            stackTrace = item.optStringOrNull("stack", InspectorLimits.MAX_STACK_TRACE_CHARS),
        )
    }

    private fun readHeaders(json: JSONObject?): Map<String, String> {
        if (json == null || json.length() == 0) return emptyMap()
        val headers = LinkedHashMap<String, String>(json.length())
        val names = json.keys()
        while (names.hasNext()) {
            val name = names.next()
            val value = json.opt(name)
            if (value == null || value == JSONObject.NULL) continue
            headers[name] = value.toString().take(MAX_HEADER_VALUE)
            if (headers.size >= InspectorLimits.MAX_HEADERS_PER_MESSAGE) break
        }
        return headers
    }

    private fun bodyKind(value: String): BodyKind = when (value) {
        "text" -> BodyKind.TEXT
        "json" -> BodyKind.JSON
        "form" -> BodyKind.FORM_URLENCODED
        "multipart" -> BodyKind.MULTIPART_FORM_DATA
        "binary" -> BodyKind.BINARY
        "stream" -> BodyKind.STREAM
        else -> BodyKind.UNKNOWN
    }

    private fun initiator(value: String): Initiator? = when (value) {
        "fetch" -> Initiator.FETCH
        "xhr" -> Initiator.XMLHTTP_REQUEST
        "beacon" -> Initiator.SEND_BEACON
        "document" -> Initiator.DOCUMENT_NAVIGATION
        "resource" -> Initiator.RESOURCE_LOAD
        else -> null
    }
}

/** `optLong` that distinguishes "absent" from 0. */
private fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val value = optLong(name, Long.MIN_VALUE)
    return if (value == Long.MIN_VALUE) null else value
}

/** `optInt` that distinguishes "absent" from 0. */
private fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    val value = optInt(name, Int.MIN_VALUE)
    return if (value == Int.MIN_VALUE) null else value
}

/** `optString` that returns null for absent/empty values and enforces a length cap. */
private fun JSONObject.optStringOrNull(name: String, maxChars: Int): String? {
    if (!has(name) || isNull(name)) return null
    val value = optString(name)
    if (value.isEmpty()) return null
    return Redaction.truncate(value, maxChars)
}
