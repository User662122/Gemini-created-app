package com.example.devtools

/**
 * The single port the WebView talks to.
 *
 * Everything the WebView side needs to do is expressed as one of these methods. Two implementations
 * exist:
 *
 *  * [LiveNetworkObserver] — the real capture pipeline (debug builds).
 *  * [NullNetworkObserver] — a no-op used in release builds and when the inspector is off, so the
 *    `WebViewClient` keeps a single code path with no `if (debug)` branches in the hot path and no
 *    chance of a release build holding inspector state.
 *
 * All methods are called on whatever thread WebView uses: the UI thread for `onPageStarted`-style
 * callbacks and a background thread for `shouldInterceptRequest`/`onReceivedHttpError`. Implementations
 * must therefore be thread-safe and must not touch the view hierarchy.
 */
interface NetworkObserver {

    /** `true` when capture is switched on; lets callers skip building observation objects. */
    val isActive: Boolean

    fun onRequestStarted(observation: RequestObservation)

    fun onResponseReceived(observation: ResponseObservation)

    /** Network-level failure (`WebResourceError`) or a failed renderer. */
    fun onRequestFailed(observation: FailureObservation)

    /** An HTTP status of 400+ for any resource, reported by `onReceivedHttpError`. */
    fun onHttpError(observation: HttpErrorObservation)

    /**
     * A TLS/SSL error that made WebView cancel the load (`onReceivedSslError`).
     *
     * Without this channel the failure is invisible: WebView's default behaviour is to cancel the
     * navigation, so no response is ever delivered, the page never runs, and the console stays empty.
     */
    fun onSslError(observation: SslErrorObservation)

    /**
     * The main-frame document still had not finished [elapsedMillis] after it started.
     *
     * Reported by the browser's load watchdog. It never cancels the load — WebView has no built-in
     * navigation timeout, so a stalled server otherwise leaves the spinner running forever with
     * nothing recorded anywhere.
     */
    fun onDocumentLoadTimeout(tabId: String, url: String?, elapsedMillis: Long)

    /** HTTP authentication challenge (`onReceivedHttpAuthRequest`). */
    fun onAuthenticationRequest(observation: AuthObservation)

    /** Batched JSON from the injected page hooks. */
    fun onPageRecords(tabId: String, json: String)

    /** A `console.*` message or uncaught JavaScript error reported by WebView. */
    fun onConsoleMessage(
        tabId: String,
        level: ConsoleLevel,
        message: String,
        source: String?,
        lineNumber: Int?,
        stackTrace: String?,
    )

    /** A request/response pair produced by the app's own HTTP client (not by WebView). */
    fun onAppHttpExchange(request: RequestObservation, response: ResponseObservation)

    /** A new document navigation started in this tab. */
    fun onDocumentStarted(tabId: String)

    /** The document of this tab finished loading (or showed an error page). */
    fun onDocumentFinished(tabId: String, url: String?, title: String?)

    /** The cookie store changed for the given URL (`CookieManager` callback). */
    fun onCookieStoreChanged(url: String?)

    /** A WebView was destroyed; its in-flight correlations are dropped. */
    fun onWebViewDestroyed(tabId: String)
}

/** No-op observer: release builds and the "inspector off" case. */
object NullNetworkObserver : NetworkObserver {
    override val isActive: Boolean = false
    override fun onRequestStarted(observation: RequestObservation) = Unit
    override fun onResponseReceived(observation: ResponseObservation) = Unit
    override fun onRequestFailed(observation: FailureObservation) = Unit
    override fun onHttpError(observation: HttpErrorObservation) = Unit
    override fun onSslError(observation: SslErrorObservation) = Unit
    override fun onDocumentLoadTimeout(tabId: String, url: String?, elapsedMillis: Long) = Unit
    override fun onAuthenticationRequest(observation: AuthObservation) = Unit
    override fun onPageRecords(tabId: String, json: String) = Unit
    override fun onConsoleMessage(
        tabId: String,
        level: ConsoleLevel,
        message: String,
        source: String?,
        lineNumber: Int?,
        stackTrace: String?,
    ) = Unit

    override fun onAppHttpExchange(request: RequestObservation, response: ResponseObservation) = Unit
    override fun onDocumentStarted(tabId: String) = Unit
    override fun onDocumentFinished(tabId: String, url: String?, title: String?) = Unit
    override fun onCookieStoreChanged(url: String?) = Unit
    override fun onWebViewDestroyed(tabId: String) = Unit
}

/**
 * A request as really observed by the app.
 *
 * Exactly one of [observedVia]/[timeSource] flavours is used per instance, which is how the UI knows
 * whether a timestamp came from the app clock or from the page's `Date.now()`.
 */
data class RequestObservation(
    val tabId: String,
    val url: String,
    val method: String,
    val observedAtMillis: Long,
    val timeSource: EvidenceSource,
    val isForMainFrame: Boolean?,
    val isRedirect: Boolean?,
    val hasGesture: Boolean?,
    /** Request headers as reported by the observer; may be a subset ([HeadersReport.isSubset]). */
    val headers: Map<String, String>,
    val headersReport: HeadersReport,
    val initiator: Initiator?,
    val observedVia: EvidenceSource,
    /** Body preview, only ever from the page-JS hooks. */
    val body: BodyRecord? = null,
)

/** A response as really observed, with its own provenance. */
data class ResponseObservation(
    val tabId: String,
    val url: String,
    val method: String,
    val statusCode: Int?,
    val reasonPhrase: String?,
    val statusSource: EvidenceSource,
    val headers: Map<String, String>,
    val headersReport: HeadersReport,
    val contentType: String?,
    val observedAtMillis: Long,
    val timeSource: EvidenceSource,
    val durationMillis: Long?,
    val isForMainFrame: Boolean?,
    val observedVia: EvidenceSource,
    /** Final URL reported by the source (page-JS redirect follow, or the app HTTP client). */
    val finalUrl: String? = null,
    val body: BodyRecord? = null,
    val failureText: String? = null,
)

/** How complete a captured header set is, and who reported it. */
enum class HeadersReport(val completeness: HeaderCompleteness) {
    /** From `WebResourceRequest.getRequestHeaders()` or `WebResourceResponse.getResponseHeaders()`. */
    WEBVIEW_SUBSET(HeaderCompleteness.SUBSET_REPORTED_BY_WEBVIEW),

    /** From the page's own fetch/XHR call, before the browser adds its own headers. */
    PAGE_JAVASCRIPT(HeaderCompleteness.FROM_PAGE_JAVASCRIPT),

    /** From an HTTP client the app controls, so the set is complete as sent by that client. */
    APP_HTTP_CLIENT(HeaderCompleteness.FROM_APP_HTTP_CLIENT),

    /** Nothing reported any headers for this message. */
    UNKNOWN(HeaderCompleteness.UNKNOWN),
}

/** A network-level failure for one request. */
data class FailureObservation(
    val tabId: String,
    val url: String,
    /** Human-readable description from `WebResourceError.getDescription()`. */
    val description: String,
    val errorCode: Int?,
    val isForMainFrame: Boolean,
    val method: String,
    val observedAtMillis: Long,
    /** True when the whole renderer died rather than a single request failing. */
    val isRendererProcessDeath: Boolean = false,
)

/** An HTTP error status observed for one resource. */
data class HttpErrorObservation(
    val tabId: String,
    val url: String,
    val method: String,
    val statusCode: Int,
    val reasonPhrase: String?,
    val headers: Map<String, String>,
    val isForMainFrame: Boolean,
    val observedAtMillis: Long,
)

/** An HTTP authentication challenge. */
data class AuthObservation(
    val tabId: String,
    val url: String,
    val host: String,
    val realm: String?,
    val observedAtMillis: Long,
)

/**
 * A TLS/SSL error that cancelled a load.
 *
 * `android.net.http.SslError` is deliberately not referenced here so this file stays free of Android
 * types and can be exercised by plain JVM tests; the WebView adapter flattens it into [description]
 * and [primaryError] before dispatching.
 */
data class SslErrorObservation(
    val tabId: String,
    val url: String,
    /** Human-readable cause, e.g. "The certificate has expired". */
    val description: String,
    /** `SslError.getPrimaryError()`, kept so the raw code stays checkable. */
    val primaryError: Int?,
    /** Null when WebView did not say which frame the handshake belonged to. */
    val isForMainFrame: Boolean?,
    val observedAtMillis: Long,
)
