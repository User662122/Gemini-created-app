package com.example.ui.engine.gecko

import android.net.Uri
import android.util.Log
import com.example.devtools.CapturePolicy
import com.example.devtools.EngineCookie
import com.example.devtools.EngineRequestRecord
import com.example.devtools.InspectorMessage
import com.example.devtools.InspectorRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension

/**
 * The Network Inspector's capture channel under the Gecko engine.
 *
 * GeckoView exposes no request interception to embedders — no `shouldInterceptRequest`, no
 * `evaluateJavascript` — so the WebView client's capture path has nothing to hold on to. What Gecko
 * does offer is its WebExtension APIs, and the bundled bridge extension's background script
 * (`assets/browserbridge/background.js`) uses two of them to observe everything:
 *
 *  * `webRequest` — every request on the wire: full header sets, the status of every response,
 *    redirect hops, timing, network-level errors, and (for text-like resources, while enabled) a
 *    streamed response-body preview.
 *  * `cookies` — the engine's whole cookie store with every attribute, which `CookieManager` never
 *    exposed to this app.
 *
 * The extension pushes records over native messaging on its own channel
 * ([GeckoRuntimeManager.BRIDGE_NET_NATIVE_APP], separate from the per-tab content-script ports so
 * the two never mix). Everything arrives **raw** and is masked by the inspector at capture time,
 * exactly as the WebView path is, so "Reveal sensitive values" behaves the same under both engines.
 *
 * The extension cannot be pushed to, so it *pulls* the capture settings from this delegate: while
 * the inspector is off it attaches no listeners at all, and in a release build (where the inspector
 * is [com.example.devtools.NullInspectorRuntime]) it never attaches them.
 *
 * What this cannot do, honestly: request bodies (Gecko's `webRequest` reports requests, not their
 * bodies, and the page hooks that capture bodies under WebView cannot run in a Gecko content
 * script's isolated world), `console.log` (same reason — uncaught errors still arrive, via the
 * content script), WebSocket frames, service-worker fetches, and traffic from private tabs (the
 * extension is not allowed in private browsing). `docs/NETWORK_INSPECTOR.md` says the same.
 */
class GeckoNetworkCapture(
    private val inspector: InspectorRuntime,
    /** The app's tab ids mapped to their current URLs, read on demand to attribute requests. */
    private val tabUrls: () -> Map<String, String>,
) {

    /** Registers the extension-level message delegate. Idempotent; replaces any previous one. */
    fun attach() {
        GeckoRuntimeManager.setExtensionMessageDelegate(Delegate())
        GeckoCaptureDiagnostics.delegateRegistered = true
        Log.i(TAG, "capture delegate registered")
    }

    private fun settingsReply(): JSONObject {
        val policy: CapturePolicy = runCatching { inspector.capturePolicy() }
            .getOrDefault(CapturePolicy.DISABLED)
        GeckoCaptureDiagnostics.recordSettingsPull(policy.enabled)
        Log.i(TAG, "settings pull answered: enabled=${policy.enabled}")
        return JSONObject()
            .put("enabled", policy.enabled)
            .put("responsePreviews", policy.enabled && policy.captureResponseBodies)
    }

    private fun handleRecord(json: JSONObject?) {
        if (json == null) return
        runCatching {
            val record = EngineRequestRecord(
                requestId = json.optString("requestId"),
                url = json.optString("url"),
                method = json.optString("method", "GET"),
                tabId = resolveTabId(
                    documentUrl = json.optString("documentUrl"),
                    requestUrl = json.optString("url"),
                    isMainFrame = json.optBoolean("isMainFrame"),
                ),
                documentUrl = json.optString("documentUrl"),
                resourceType = json.optString("resourceType", "other"),
                isMainFrame = json.optBoolean("isMainFrame"),
                redirectedFrom = json.optString("redirectedFrom").takeIf { it.isNotBlank() },
                redirectedTo = json.optString("redirectedTo").takeIf { it.isNotBlank() },
                requestHeaders = headersToMap(json.optJSONObject("requestHeaders")),
                statusCode = optIntOrNull(json, "statusCode"),
                responseHeaders = headersToMap(json.optJSONObject("responseHeaders")),
                durationMillis = durationOf(json),
                error = json.optString("error").takeIf { it.isNotBlank() },
                bodyPreview = json.optString("bodyPreview").takeIf { it.isNotBlank() },
                bodyTruncated = json.optBoolean("bodyTruncated"),
            )
            GeckoCaptureDiagnostics.recordNetworkRecord(record.url)
            Log.i(TAG, "record: ${record.method} ${record.url.take(120)} -> ${record.statusCode ?: record.error ?: "?"}")
            inspector.dispatch(InspectorMessage.EngineRequest(record))
        }
    }

    private fun handleCookies(array: JSONArray?, removed: Boolean) {
        if (array == null) return
        runCatching {
            val cookies = ArrayList<EngineCookie>(array.length())
            for (index in 0 until array.length()) {
                val cookie = array.optJSONObject(index) ?: continue
                cookies += EngineCookie(
                    name = cookie.optString("name"),
                    value = cookie.optString("value"),
                    domain = cookie.optString("domain"),
                    path = cookie.optString("path", "/"),
                    secure = cookie.optBoolean("secure"),
                    httpOnly = cookie.optBoolean("httpOnly"),
                    session = cookie.optBoolean("session"),
                    expirationDate = cookie.optDouble("expirationDate")
                        .takeIf { !it.isNaN() }?.toLong(),
                    sameSite = cookie.optString("sameSite").takeIf { it.isNotBlank() },
                    hostOnly = cookie.optBoolean("hostOnly"),
                )
            }
            if (removed) {
                GeckoCaptureDiagnostics.recordCookieChange(true)
            } else if (array.length() > 1) {
                GeckoCaptureDiagnostics.recordCookieDump(array.length())
            } else {
                GeckoCaptureDiagnostics.recordCookieChange(false)
            }
            Log.i(TAG, "cookies: ${cookies.size} (removed=$removed)")
            inspector.dispatch(InspectorMessage.EngineCookies(cookies, removed))
        }
    }

    private fun handleStatus(json: JSONObject?) {
        if (json == null) return
        runCatching {
            val rows = ArrayList<Pair<String, String>>(12)
            fun put(label: String, value: Any?) {
                if (value != null) rows += label to value.toString()
            }
            put("manifest version running", json.optString("manifestVersion").takeIf { it.isNotBlank() })
            put("webRequest API present", json.optBoolean("webRequestApi"))
            put("cookies API present", json.optBoolean("cookiesApi"))
            val permissions = json.optJSONObject("permissions")
            if (permissions != null) {
                val keys = permissions.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = permissions.opt(key)
                    put("permission $key", if (value is Boolean) value.toString() else value?.toString())
                }
            }
            put("capture enabled (extension's view)", json.optBoolean("captureEnabled"))
            put("response previews enabled", json.optBoolean("responsePreviewsEnabled"))
            put("passive listeners attached", json.optBoolean("passiveListenersAttached"))
            put("body-preview listener attached", json.optBoolean("bodyListenerAttached"))
            put("pending requests in extension", json.optInt("pendingRequests"))
            put("listener error", json.optString("listenerError").takeIf { it.isNotBlank() })
            GeckoCaptureDiagnostics.recordStatus(rows)
            Log.i(TAG, "extension status: ${rows.joinToString(", ") { "${it.first}=${it.second}" }}")
        }
    }

    /**
     * Gecko's `webRequest` knows its own internal tab ids, which the app cannot see; what it also
     * reports is the URL of the document that initiated each request, and that is what this maps:
     * exact URL first, then origin, for both the document and the request URL. A navigation
     * initiated by a page happens in the tab showing that page, so `documentUrl` attributes
     * main-frame requests too. Anything unmatched (Gecko's own favicon or prefetch requests, say)
     * is recorded against `"unknown"` rather than dropped.
     */
    private fun resolveTabId(documentUrl: String, requestUrl: String, isMainFrame: Boolean): String {
        val tabs = tabUrls()
        if (tabs.isEmpty()) return UNKNOWN_TAB_ID
        if (documentUrl.isNotBlank()) {
            tabs.forEach { (id, url) -> if (url == documentUrl) return id }
        }
        if (isMainFrame) {
            tabs.forEach { (id, url) -> if (url == requestUrl) return id }
        }
        originOf(documentUrl)?.let { origin ->
            tabs.forEach { (id, url) -> if (originOf(url) == origin) return id }
        }
        originOf(requestUrl)?.let { origin ->
            tabs.forEach { (id, url) -> if (originOf(url) == origin) return id }
        }
        return UNKNOWN_TAB_ID
    }

    private fun originOf(url: String): String? {
        if (url.isBlank()) return null
        return runCatching {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            "$scheme://$host:${uri.port}"
        }.getOrNull()
    }

    /** The extension sends each header set as a JSON object of name → value. */
    private fun headersToMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val headers = LinkedHashMap<String, String>(obj.length().coerceAtMost(64))
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            headers[name] = obj.optString(name)
        }
        return headers
    }

    private fun durationOf(json: JSONObject): Long? {
        val start = json.optDouble("startTime")
        val end = json.optDouble("endTime")
        if (start.isNaN() || end.isNaN() || start <= 0 || end < start) return null
        return (end - start).toLong()
    }

    /** `optInt` reads a JSON null as 0, which would invent a status; this reads it as absent. */
    private fun optIntOrNull(json: JSONObject, key: String): Int? =
        if (json.isNull(key)) null else json.optInt(key)

    private inner class Delegate : WebExtension.MessageDelegate {

        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            val json = message as? JSONObject ?: return null
            return when (json.optString("type")) {
                // The extension pulls: native code cannot push to an extension, so the settings
                // travel this way and the extension re-reads them on an interval.
                TYPE_GET_SETTINGS -> GeckoResult.fromValue<Any>(settingsReply())
                TYPE_NETWORK_RECORD -> {
                    handleRecord(json.optJSONObject("record"))
                    null
                }
                TYPE_COOKIES_DUMP -> {
                    handleCookies(json.optJSONArray("cookies"), removed = false)
                    null
                }
                TYPE_COOKIE_CHANGED -> {
                    handleCookies(cookieArray(json.optJSONObject("cookie")), removed = false)
                    null
                }
                TYPE_COOKIE_REMOVED -> {
                    handleCookies(cookieArray(json.optJSONObject("cookie")), removed = true)
                    null
                }
                TYPE_CAPTURE_STATUS -> {
                    handleStatus(json)
                    null
                }
                else -> null
            }
        }

        private fun cookieArray(cookie: JSONObject?): JSONArray? =
            cookie?.let { JSONArray().put(it) }
    }

    companion object {
        const val UNKNOWN_TAB_ID = "unknown"

        private const val TAG = "GeckoCapture"

        private const val TYPE_GET_SETTINGS = "get-capture-settings"
        private const val TYPE_NETWORK_RECORD = "network-record"
        private const val TYPE_COOKIES_DUMP = "cookies-dump"
        private const val TYPE_COOKIE_CHANGED = "cookie-changed"
        private const val TYPE_COOKIE_REMOVED = "cookie-removed"
        private const val TYPE_CAPTURE_STATUS = "capture-status"

        /** Diagnostics for the inspector's export (the CAPTURE SOURCE section). */
        fun diagnostics(): List<Pair<String, String>> = GeckoCaptureDiagnostics.snapshot()
    }
}
