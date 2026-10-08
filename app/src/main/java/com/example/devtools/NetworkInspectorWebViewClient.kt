package com.example.devtools

import android.net.http.SslError
import android.os.Build
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * A [WebViewClient] that reports what WebView tells it to the inspector and then behaves exactly like
 * the platform default.
 *
 * It is *delegating on purpose*: every override calls `super` first, and the app's real client extends
 * this class, so the inspector can be switched off without changing a single line of browser logic.
 * When the inspector is disabled, `inspector.enabled` is false and each callback costs one boolean
 * check.
 *
 * Scope notes (see `docs/NETWORK_INSPECTOR.md`):
 *
 *  * Only `http`/`https` URLs are recorded — `data:`, `blob:` and `file:` loads are not network
 *    traffic and are skipped instead of being mislabelled.
 *  * A response that the app consumes in `shouldInterceptRequest` (by returning a non-null
 *    [WebResourceResponse]) is not logged as a request, because the app then owns that response and
 *    has the real object at hand. Subclasses that consume responses should log them themselves.
 *  * Every observation is built inside a `try`/`catch` so a hostile or unusual page can never turn a
 *    logging failure into a crash or a broken navigation.
 */
open class NetworkInspectorWebViewClient(
    private val tabId: String,
    private val inspector: InspectorRuntime,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        // Returning whatever the platform produced keeps caching and COOP/COEP behaviour untouched.
        val response = super.shouldInterceptRequest(view, request)
        if (request == null || response != null || !inspector.enabled) return response
        try {
            val url = request.url?.toString() ?: return response
            if (!isNetworkUrl(url)) return response
            inspector.dispatch(
                InspectorMessage.RequestStarted(
                    RequestObservation(
                        tabId = tabId,
                        url = url,
                        method = request.method ?: "GET",
                        observedAtMillis = System.currentTimeMillis(),
                        timeSource = EvidenceSource.APP_CLOCK,
                        isForMainFrame = request.isForMainFrame,
                        isRedirect = request.isRedirect,
                        hasGesture = request.hasGesture(),
                        headers = request.requestHeaders ?: emptyMap(),
                        headersReport = HeadersReport.WEBVIEW_SUBSET,
                        initiator = if (request.isForMainFrame) {
                            Initiator.DOCUMENT_NAVIGATION
                        } else {
                            Initiator.RESOURCE_LOAD
                        },
                        observedVia = EvidenceSource.WEBVIEW_CALLBACK,
                    )
                )
            )
        } catch (_: Throwable) {
            // Logging must never break a load.
        }
        return response
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request == null || errorResponse == null || !inspector.enabled) return
        try {
            val url = request.url?.toString() ?: return
            if (!isNetworkUrl(url)) return
            inspector.dispatch(
                InspectorMessage.HttpError(
                    HttpErrorObservation(
                        tabId = tabId,
                        url = url,
                        method = request.method ?: "GET",
                        statusCode = errorResponse.statusCode,
                        reasonPhrase = errorResponse.reasonPhrase,
                        headers = errorResponse.responseHeaders ?: emptyMap(),
                        isForMainFrame = request.isForMainFrame,
                        observedAtMillis = System.currentTimeMillis(),
                    )
                )
            )
        } catch (_: Throwable) {
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request == null || error == null || !inspector.enabled) return
        try {
            val url = request.url?.toString() ?: return
            if (!isNetworkUrl(url)) return
            inspector.dispatch(
                InspectorMessage.RequestFailed(
                    FailureObservation(
                        tabId = tabId,
                        url = url,
                        description = error.description?.toString() ?: "Unknown network error",
                        errorCode = error.errorCode,
                        isForMainFrame = request.isForMainFrame,
                        method = request.method ?: "GET",
                        observedAtMillis = System.currentTimeMillis(),
                    )
                )
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Records a refused TLS certificate, then keeps WebView's own decision.
     *
     * `super` calls `handler.cancel()`. That is deliberate and must not change: this class observes,
     * it never calls `handler.proceed()`, because accepting a certificate WebView rejected would
     * expose the session to a man in the middle. Before this override existed the callback was not
     * handled at all, so a refused handshake cancelled the load *silently* — no page, no console
     * output, no network row, nothing to explain the blank screen. Now the reason is recorded.
     */
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        try {
            if (inspector.enabled && error != null) {
                inspector.dispatch(
                    InspectorMessage.SslError(
                        SslErrorObservation(
                            tabId = tabId,
                            // SslError carries no URL the app may rely on, so the view's current one
                            // is the honest stand-in; blank when the renderer no longer reports it.
                            url = view?.url ?: "",
                            description = describeSslError(error.primaryError),
                            primaryError = error.primaryError,
                            // WebView does not report which frame the handshake belonged to.
                            isForMainFrame = null,
                            observedAtMillis = System.currentTimeMillis(),
                        )
                    )
                )
            }
        } catch (_: Throwable) {
        }
        // Cancels the load, exactly as WebView would have done unobserved.
        super.onReceivedSslError(view, handler, error)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler?,
        host: String?,
        realm: String?,
    ) {
        try {
            if (inspector.enabled) {
                val url = view?.url ?: host?.let { "https://$it" } ?: ""
                inspector.dispatch(
                    InspectorMessage.AuthChallenge(
                        AuthObservation(
                            tabId = tabId,
                            url = url,
                            host = host ?: "",
                            realm = realm,
                            observedAtMillis = System.currentTimeMillis(),
                        )
                    )
                )
            }
        } catch (_: Throwable) {
        }
        super.onReceivedHttpAuthRequest(view, handler, host, realm)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (!inspector.enabled) return
        try {
            inspector.dispatch(InspectorMessage.DocumentStarted(tabId))
        } catch (_: Throwable) {
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (!inspector.enabled) return
        try {
            inspector.dispatch(InspectorMessage.DocumentFinished(tabId, url, view?.title))
        } catch (_: Throwable) {
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        try {
            if (inspector.enabled) {
                inspector.dispatch(
                    InspectorMessage.RequestFailed(
                        FailureObservation(
                            tabId = tabId,
                            url = view?.url ?: "",
                            description = "The WebView renderer process was killed" +
                                describeRendererExit(detail),
                            errorCode = null,
                            // The renderer hosts this tab's document, so the main frame is affected
                            // by definition — this is never a single sub-resource failing.
                            isForMainFrame = true,
                            method = UNKNOWN_METHOD,
                            observedAtMillis = System.currentTimeMillis(),
                            isRendererProcessDeath = true,
                        )
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return super.onRenderProcessGone(view, detail)
    }

    /**
     * Tells a renderer that *crashed* from one the *system killed to reclaim memory*, which is the
     * difference between "the page has a bug" and "this device ran out of RAM on a heavy page".
     *
     * [RenderProcessGoneDetail] only carries this on API 26+; on older devices the reason is simply
     * not reported, and the text says so instead of guessing.
     */
    private fun describeRendererExit(detail: RenderProcessGoneDetail?): String {
        if (detail == null) return " (the platform reported no detail)"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return " (detail unavailable before API 26)"
        return if (detail.didCrash()) {
            " — it crashed, so the page hit a fault of its own"
        } else {
            " — the system killed it to reclaim memory, which is what a heavy page does on a " +
                "low-RAM device (renderer priority at exit: ${detail.rendererPriorityAtExit()})"
        }
    }

    /** Plain-English cause for an `android.net.http.SslError` primary error code. */
    private fun describeSslError(primaryError: Int): String = when (primaryError) {
        SslError.SSL_NOT_YET_VALID -> "The server certificate is not valid yet"
        SslError.SSL_EXPIRED -> "The server certificate has expired"
        SslError.SSL_IDMISMATCH ->
            "The certificate's hostname does not match the site being loaded"
        SslError.SSL_UNTRUSTED ->
            "The certificate's authority is not trusted by this device"
        SslError.SSL_DATE_INVALID ->
            "The certificate's date is invalid — often a wrong device clock"
        else -> "A TLS/SSL error occurred"
    }

    private fun isNetworkUrl(url: String): Boolean {
        val scheme = UrlParts.scheme(url)
        return scheme == "http" || scheme == "https"
    }
}
