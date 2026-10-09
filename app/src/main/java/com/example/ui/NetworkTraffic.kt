package com.example.ui

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Instant

/** A header value may be text or a base64-encoded byte sequence supplied by Gecko. */
data class NetworkHeader(
    val name: String,
    val value: String,
    val isBase64: Boolean = false,
)

/** A request/response observed by the built-in GeckoView webRequest extension. */
data class NetworkRequestLog(
    val id: String,
    val requestId: String,
    val url: String,
    val method: String,
    val resourceType: String,
    val tabId: Int?,
    val frameId: Int?,
    val startedAtEpochMs: Long,
    val initiator: String? = null,
    val requestHeaders: List<NetworkHeader> = emptyList(),
    val responseHeaders: List<NetworkHeader> = emptyList(),
    val requestBody: ByteArray? = null,
    val responseBody: ByteArray? = null,
    val requestBodyMimeType: String? = null,
    val requestBodyFormat: String? = null,
    val responseBodyMimeType: String? = null,
    val requestBodyBytesCaptured: Int = 0,
    val responseBodyBytesCaptured: Int = 0,
    val requestBodyAvailable: Boolean = false,
    val requestBodyTruncated: Boolean = false,
    val responseBodyTruncated: Boolean = false,
    val requestBodyNote: String? = null,
    val responseBodyNote: String? = null,
    val statusCode: Int? = null,
    val statusLine: String? = null,
    val durationMs: Long? = null,
    val fromCache: Boolean? = null,
    val redirectUrl: String? = null,
    val error: String? = null,
    val isComplete: Boolean = false,
)

/** HAR 1.2 export helpers. The raw headers and body content are deliberately not redacted. */
internal object NetworkHarExporter {

    fun write(entries: List<NetworkRequestLog>, output: OutputStream) {
        val har = JSONObject()
            .put("log", JSONObject()
                .put("version", "1.2")
                .put("creator", JSONObject()
                    .put("name", "Gecko Browser Network Inspector")
                    .put("version", "1.1"))
                .put("entries", JSONArray().also { array ->
                    entries.sortedBy { it.startedAtEpochMs }.forEach { array.put(toHarEntry(it)) }
                }))
        output.write(har.toString(2).toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun toHarEntry(entry: NetworkRequestLog): JSONObject {
        val request = JSONObject()
            .put("method", entry.method)
            .put("url", entry.url)
            .put("httpVersion", "")
            .put("cookies", requestCookies(entry.requestHeaders))
            .put("headers", headersArray(entry.requestHeaders))
            .put("queryString", queryArray(entry.url))
            .put("headersSize", -1)
            .put("bodySize", entry.requestBody?.size ?: if (entry.requestBodyAvailable) entry.requestBodyBytesCaptured else -1)

        val requestBytes = entry.requestBody
        if (requestBytes != null) {
            val mimeType = entry.requestBodyMimeType
                ?: headerValue(entry.requestHeaders, "content-type")
                ?: "application/octet-stream"
            request.put(
                "postData",
                bodyObject(requestBytes, mimeType, forceText = entry.requestBodyFormat == "form-data-fields"),
            )
        }

        val response = JSONObject()
            .put("status", entry.statusCode ?: 0)
            .put("statusText", harStatusText(entry.statusLine))
            .put("httpVersion", "")
            .put("cookies", responseCookies(entry.responseHeaders))
            .put("headers", headersArray(entry.responseHeaders))
            .put("content", JSONObject()
                .put("size", entry.responseBody?.size ?: entry.responseBodyBytesCaptured)
                .put("mimeType", entry.responseBodyMimeType
                    ?: headerValue(entry.responseHeaders, "content-type")
                    ?: "application/octet-stream"))
            .put("redirectURL", entry.redirectUrl ?: headerValue(entry.responseHeaders, "location").orEmpty())
            .put("headersSize", -1)
            .put("bodySize", entry.responseBody?.size ?: entry.responseBodyBytesCaptured)

        entry.responseBody?.let { bytes ->
            val mimeType = entry.responseBodyMimeType
                ?: headerValue(entry.responseHeaders, "content-type")
                ?: "application/octet-stream"
            response.getJSONObject("content").putAll(bodyObject(bytes, mimeType))
        }

        val duration = entry.durationMs?.coerceAtLeast(0) ?: -1L
        val harEntry = JSONObject()
            .put("startedDateTime", runCatching { Instant.ofEpochMilli(entry.startedAtEpochMs).toString() }
                .getOrElse { "1970-01-01T00:00:00Z" })
            .put("time", duration)
            .put("request", request)
            .put("response", response)
            .put("cache", JSONObject())
            .put("timings", JSONObject()
                .put("send", -1)
                .put("wait", duration)
                .put("receive", 0))
            .put("_requestId", entry.requestId)
            .put("_resourceType", entry.resourceType)
            .put("_captureComplete", entry.isComplete)
            .put("_requestBodyTruncated", entry.requestBodyTruncated)
            .put("_responseBodyTruncated", entry.responseBodyTruncated)
            .put("_requestBodyBytesCaptured", entry.requestBodyBytesCaptured)
            .put("_responseBodyBytesCaptured", entry.responseBodyBytesCaptured)

        entry.tabId?.let { harEntry.put("_tabId", it) }
        entry.frameId?.let { harEntry.put("_frameId", it) }
        entry.initiator?.let { harEntry.put("_initiator", it) }
        entry.fromCache?.let { harEntry.put("_fromCache", it) }
        entry.error?.let { harEntry.put("_error", it) }
        entry.requestBodyNote?.let { harEntry.put("_requestBodyNote", it) }
        entry.responseBodyNote?.let { harEntry.put("_responseBodyNote", it) }
        entry.requestBodyFormat?.let { harEntry.put("_requestBodyFormat", it) }
        return harEntry
    }

    private fun harStatusText(statusLine: String?): String {
        if (statusLine.isNullOrBlank()) return ""
        val parts = statusLine.trim().split(Regex("\\s+"), limit = 3)
        return if (parts.size >= 3 && parts[1].toIntOrNull() != null) {
            parts[2]
        } else {
            statusLine.substringAfter(' ', statusLine).trim()
        }
    }

    private fun headersArray(headers: List<NetworkHeader>): JSONArray = JSONArray().also { array ->
        headers.forEach { header ->
            val item = JSONObject()
                .put("name", header.name)
                .put("value", header.value)
            if (header.isBase64) item.put("_valueEncoding", "base64")
            array.put(item)
        }
    }

    private fun requestCookies(headers: List<NetworkHeader>): JSONArray = JSONArray().also { cookies ->
        headers.filter { it.name.equals("cookie", ignoreCase = true) && !it.isBase64 }
            .forEach { header ->
                header.value.split(';').forEach { part ->
                    val separator = part.indexOf('=')
                    if (separator > 0) {
                        cookies.put(JSONObject()
                            .put("name", part.substring(0, separator).trim())
                            .put("value", part.substring(separator + 1).trim()))
                    }
                }
            }
    }

    private fun responseCookies(headers: List<NetworkHeader>): JSONArray = JSONArray().also { cookies ->
        headers.filter { it.name.equals("set-cookie", ignoreCase = true) && !it.isBase64 }
            .forEach { header ->
                val parts = header.value.split(';')
                val pair = parts.firstOrNull().orEmpty()
                val separator = pair.indexOf('=')
                if (separator > 0) {
                    val cookie = JSONObject()
                        .put("name", pair.substring(0, separator).trim())
                        .put("value", pair.substring(separator + 1).trim())
                    parts.drop(1).forEach { attribute ->
                        val attributeSeparator = attribute.indexOf('=')
                        val name = if (attributeSeparator < 0) attribute.trim() else attribute.substring(0, attributeSeparator).trim()
                        val value = if (attributeSeparator < 0) "" else attribute.substring(attributeSeparator + 1).trim()
                        when (name.lowercase()) {
                            "path" -> cookie.put("path", value)
                            "domain" -> cookie.put("domain", value)
                            "expires" -> cookie.put("expires", value)
                            "samesite" -> cookie.put("sameSite", value)
                            "secure" -> cookie.put("secure", true)
                            "httponly" -> cookie.put("httpOnly", true)
                        }
                    }
                    cookies.put(cookie)
                }
            }
    }

    private fun queryArray(url: String): JSONArray = JSONArray().also { query ->
        val rawQuery = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        if (rawQuery.isBlank()) return@also
        rawQuery.split('&').forEach { part ->
            if (part.isNotEmpty()) {
                val separator = part.indexOf('=')
                val rawName = if (separator < 0) part else part.substring(0, separator)
                val rawValue = if (separator < 0) "" else part.substring(separator + 1)
                query.put(JSONObject()
                    .put("name", decodeQueryComponent(rawName))
                    .put("value", decodeQueryComponent(rawValue)))
            }
        }
    }

    private fun decodeQueryComponent(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun bodyObject(bytes: ByteArray, mimeType: String, forceText: Boolean = false): JSONObject {
        val body = JSONObject().put("mimeType", mimeType)
        if (forceText || isTextMimeType(mimeType)) {
            val charsetName = Regex("(?i)charset\\s*=\\s*['\"]?([^;\\s'\"]+)")
                .find(mimeType)?.groupValues?.getOrNull(1)
            val charset = charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() }
                ?: StandardCharsets.UTF_8
            body.put("text", String(bytes, charset))
        } else {
            body.put("text", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("encoding", "base64")
        }
        return body
    }

    private fun headerValue(headers: List<NetworkHeader>, name: String): String? =
        headers.firstOrNull { it.name.equals(name, ignoreCase = true) && !it.isBase64 }?.value

    fun isTextMimeType(mimeType: String): Boolean {
        val type = mimeType.substringBefore(';').trim().lowercase()
        return type.startsWith("text/") ||
            type.contains("json") ||
            type.contains("xml") ||
            type.contains("javascript") ||
            type.contains("ecmascript") ||
            type.contains("x-www-form-urlencoded") ||
            type == "image/svg+xml"
    }
}

internal object NetworkBodyPreview {
    private const val MAX_PREVIEW_CHARACTERS = 12_000

    fun format(bytes: ByteArray?, mimeType: String?, forceText: Boolean = false): String {
        if (bytes == null) return "No body bytes were captured."
        if (bytes.isEmpty()) return "(empty body)"
        val mime = mimeType.orEmpty()
        val text = if (forceText || NetworkHarExporter.isTextMimeType(mime)) {
            String(bytes, StandardCharsets.UTF_8)
        } else {
            "Base64 encoding:\n" + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        return if (text.length > MAX_PREVIEW_CHARACTERS) {
            text.take(MAX_PREVIEW_CHARACTERS) + "\n… preview clipped; export the HAR for captured bytes."
        } else {
            text
        }
    }
}

private fun JSONObject.putAll(other: JSONObject): JSONObject {
    val keys = other.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        put(key, other.get(key))
    }
    return this
}
