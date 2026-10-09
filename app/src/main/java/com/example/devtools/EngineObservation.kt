package com.example.devtools

/**
 * Raw observations reported by an embedded browser engine (Gecko) through its own observation
 * channel — Gecko's `webRequest` and `cookies` WebExtension APIs.
 *
 * These are deliberately *pre-masking*: the engine reports what it saw exactly as it saw it, and
 * [LiveNetworkObserver] applies the same [Redaction] policy the WebView path applies, at capture
 * time, before anything is stored. Masking in one place only is what keeps "Reveal sensitive
 * values" meaningful for both engines.
 */

/** One HTTP exchange as Gecko's `webRequest` saw it, assembled by the bridge extension. */
data class EngineRequestRecord(
    /** Gecko's request id; unique per request, stable across its redirect hops' events. */
    val requestId: String,
    val url: String,
    val method: String,
    /**
     * The app's tab id, resolved on the engine side from Gecko's internal tab id and the
     * initiating document's URL. `"unknown"` when no open tab matches (a request Gecko made on its
     * own, for example a favicon or a prefetch).
     */
    val tabId: String,
    /** The URL of the document that initiated this request, as Gecko reported it. */
    val documentUrl: String,
    val resourceType: String,
    val isMainFrame: Boolean,
    /** Set on every hop after the first of a redirect chain. */
    val redirectedFrom: String?,
    /** Where this request redirected to (the 3xx response's Location), if it did. */
    val redirectedTo: String?,
    val requestHeaders: Map<String, String>,
    val statusCode: Int?,
    val responseHeaders: Map<String, String>,
    /** Milliseconds between the request starting and the response completing, from Gecko's clock. */
    val durationMillis: Long?,
    /** Network-level failure text (Gecko's `onErrorOccurred` `error`), when the request failed. */
    val error: String?,
    /** Response body preview, present only for text-ish resources when preview capture is on. */
    val bodyPreview: String?,
    val bodyTruncated: Boolean,
)

/** One cookie as Gecko's `cookies` API reports it. */
data class EngineCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val session: Boolean,
    /** Epoch seconds, or null for a session cookie. */
    val expirationDate: Long?,
    val sameSite: String?,
    val hostOnly: Boolean,
)
