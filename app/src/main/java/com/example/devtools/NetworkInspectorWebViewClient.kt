package com.example.devtools

import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
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
                            description = "The WebView renderer process was killed",
                            errorCode = null,
                            isForMainFrame = false,
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

    private fun isNetworkUrl(url: String): Boolean {
        val scheme = UrlParts.scheme(url)
        return scheme == "http" || scheme == "https"
    }
}
