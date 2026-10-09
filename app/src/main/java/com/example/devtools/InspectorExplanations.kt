package com.example.devtools

import com.example.ui.engine.BrowserEngineKind

/**
 * Every "this field is not available, and here is why" string the inspector can show.
 *
 * They live in one file on purpose: they are the honest half of this feature. Whenever a value is
 * missing, the UI shows the matching sentence instead of an empty cell, so nobody can mistake "the
 * platform does not expose this" for "the app failed to capture it".
 *
 * The same sentences are reproduced in `docs/NETWORK_INSPECTOR.md`; keep both in sync.
 */
object InspectorExplanations {

    const val REQUEST_BODY_UNAVAILABLE =
        "Not available: WebView exposes no API that returns the body of a request. A request body is " +
            "shown only when the page's own fetch()/XMLHttpRequest call is observed by the injected " +
            "hooks, because then the page itself supplies the body it passed in."

    const val REQUEST_BODY_NOT_CAPTURED =
        "Not captured: request-body capture is switched off in Network Inspector settings."

    const val RESPONSE_BODY_UNAVAILABLE =
        "Not available: response bodies are not copied by default. Enable \"Capture response body " +
            "previews\" to read them from the page's own fetch()/XMLHttpRequest responses (needs page " +
            "JavaScript); WebView itself never hands the app a response body it did not request."

    const val ENGINE_REQUEST_BODY_UNAVAILABLE =
        "Not available under the embedded Gecko engine: its webRequest observation reports every " +
            "request on the wire but not the body, and the page hooks that capture bodies in the " +
            "WebView engine cannot run there (a Gecko content script lives in an isolated world). " +
            "The request's headers, timing and destination are still recorded in full."

    const val ENGINE_BODY_NOT_CAPTURED =
        "Not captured: the engine streams response bodies only while \"Capture response body " +
            "previews\" is on, and only for text-like resources (documents, scripts, stylesheets, " +
            "XHR) — binary resources are never previewed as text."

    const val REASON_PHRASE_UNAVAILABLE =
        "Not available: WebView reports the numeric status, but not the HTTP reason phrase, for " +
            "responses the app did not fetch itself."

    const val FINAL_URL_SUBRESOURCE_UNAVAILABLE =
        "Not available for this message: WebView reports a final URL for main-frame navigations " +
            "(onPageFinished), and the injected hooks report it for the page's own fetch()/XHR calls. " +
            "For subresources (images, CSS, scripts, ads) no final URL is exposed, so redirects are " +
            "invisible here."

    const val REDIRECT_INVISIBLE =
        "Redirects are not reported by WebViewClient for subresources; only the page's own fetch()/" +
            "XHR hook (which follows redirects itself) can show where a request ended up."

    const val STATUS_FROM_HTTP_ERROR_CHANNEL =
        "Reported by WebViewClient.onReceivedHttpError, which fires for 4xx/5xx responses on any " +
            "resource. It carries no response object, so headers and the body of that response are " +
            "not available."

    const val STATUS_FROM_ERROR_PAGE_TITLE =
        "Inferred from the WebView error page title. When the app consumes a response in " +
            "shouldInterceptRequest, WebView has no response object left to report, so the only " +
            "signal is the title of the error page (for example \"HTTP ERROR 404\")."

    const val NETWORK_LEVEL_FAILURE =
        "The load failed before a usable HTTP response arrived (DNS, TLS, connection or timeout). " +
            "WebResourceError gives a code and description; it never gives a status code."

    const val RENDERER_PROCESS_GONE =
        "The WebView renderer process was killed. Requests that were still in flight can never be " +
            "completed, so their responses cannot be observed."

    /**
     * Renderer deaths, refused TLS handshakes and stalled documents are the three failures that used
     * to leave the inspector completely empty: the page that would normally log the error is dead,
     * never loaded, or still hanging, so nothing reaches `onConsoleMessage` and no response callback
     * ever arrives. Each of them is now recorded here by the app itself, which is the only witness
     * left. These strings say so explicitly, so a reader never mistakes "the app captured nothing"
     * for "nothing happened".
     */
    const val APP_OBSERVED_FAILURE =
        "Recorded by the app, not by the page: WebView reported this failure through a callback, and " +
            "the page itself produced no console output and no response for it. When the Console tab " +
            "is otherwise empty, a row like this is the reason."

    const val SSL_ERROR_CANCELLED =
        "WebView refused the TLS certificate and the load was cancelled, so no response was ever " +
            "delivered to the app. The app does not override this decision and never calls " +
            "SslErrorHandler.proceed(): accepting a bad certificate would expose the session. The " +
            "usual causes are an expired or mismatched certificate, a captive portal or proxy " +
            "re-signing HTTPS, or a device clock that is wrong."

    /** Note attached to rows that were still waiting when the load watchdog fired. */
    fun documentLoadTimeout(elapsedMillis: Long): String =
        "The document had still not finished loading ${elapsedMillis / 1000} s after it started, and " +
            "WebView had reported no page-finished, no error and no HTTP status for it. It may still " +
            "be loading: the app does not cancel it. This is what an overloaded or very slow server " +
            "looks like from here."

    /**
     * Explains a refusal by the site's own access-control layer, with the server's own reference.
     *
     * The point of the reference is that it is quotable: it names the rule and edge node that refused
     * the request, which nothing on the client side can see. The point of the rest of the sentence is
     * to stop this being read as a network fault or a bug in the page — it is neither, and no amount
     * of reloading from this client changes the server's answer.
     */
    fun cdnAccessDenied(statusCode: Int, referenceId: String?): String = buildString {
        append("HTTP ")
        append(statusCode)
        append(": the site's CDN/access-control layer refused this client. ")
        if (referenceId != null) {
            append("Reference ")
            append(referenceId)
            append(" — quote this to the site's support; it identifies the exact rule and edge node " +
                "that rejected the request. ")
        }
        append("This is the server refusing the client, not a network fault and not an error in the " +
            "page. Rules like this are commonly armed or tightened while the site is at peak load, " +
            "and they key on client properties, which is why another browser on the same device can " +
            "be accepted at the same moment.")
    }

    const val REQUEST_HEADERS_SUBSET =
        "These headers are the subset WebView chooses to expose. WebView's own documentation make no " +
            "completeness promise; headers added lower in the network stack (cookies, User-Agent " +
            "details, HTTP/2 pseudo headers) are frequently absent."

    const val RESPONSE_HEADERS_EMPTY_FOR_HTTP_ERROR =
        "WebView reported no response headers for this message, most often because the response was " +
            "consumed by the app in shouldInterceptRequest or because the status came from " +
            "onReceivedHttpError, which carries no header map."

    const val HTTP_AUTH_SCHEME_UNAVAILABLE =
        "Not available: WebViewClient.onReceivedHttpAuthRequest reports the host and realm, but the " +
            "authentication scheme (Basic/Digest/NTLM) is not exposed to the host app."

    const val COOKIE_FIELD_UNAVAILABLE =
        "Not available: Android exposes only the Cookie header string through " +
            "CookieManager.getCookie(url). Domain, Path, Secure, HttpOnly and expiry are not readable " +
            "for cookies the app did not set (they are stripped from the value returned by the " +
            "browser process). Rows filled in from a Set-Cookie header show their real attributes."

    const val COOKIE_MASKED =
        "Cookie values are masked by the inspector policy and are never stored. CookieManager remains " +
            "the source of truth if the app itself needs the value."

    const val RESOURCE_TYPE_INFERRED =
        "WebView reports no resource type. This bucket was derived from request/response evidence; " +
            "open the detail sheet to see exactly which evidence was used."

    const val SERVICE_WORKER_UNAVAILABLE =
        "Not observed: fetch events handled inside a Service Worker never reach " +
            "WebViewClient.shouldInterceptRequest. Covering them needs " +
            "ServiceWorkerControllerCompat from androidx.webkit (see the integration guide)."

    const val WEBSOCKET_UNAVAILABLE =
        "Not observed: WebSocket frames (ws://, wss://) are not part of the HTTP resource pipeline, so " +
            "no WebView callback reports them. The inspector never fabricates WebSocket rows."

    const val CACHE_AND_PREFLIGHT =
        "Requests served entirely from the WebView cache, and HTTP/2 or HTTP/3 preflight traffic, may " +
            "never appear because no callback is fired for them."

    const val PAGE_JS_OBSERVED =
        "Captured by the inspector's injected hooks inside the page (window.fetch / XMLHttpRequest / " +
            "sendBeacon). That is genuine data, but it describes what the page asked for — the browser " +
            "may add headers, change the body encoding, or retry afterwards."

    const val APP_HTTP_CLIENT_OBSERVED =
        "Captured by an HTTP client this app owns, not by the WebView. Header sets from this source are " +
            "complete as sent by that client."

    const val ORPHAN_RESPONSE =
        "Only the response was observed; the matching request was not. WebView fires response callbacks " +
            "on a background thread with no request object, so the start time and request headers for " +
            "this row could not be observed, and the start time shown is inferred from the response."

    const val AMBIGUOUS_CORRELATION =
        "Several identical requests were in flight at the same time. WebView gives the host app no " +
            "request identifier, so this response was paired with the most recent unmatched request of " +
            "the same URL and method."

    const val RESPONSE_NEVER_OBSERVED =
        "No response callback ever arrived for this request, so its outcome is unknown. WebView " +
            "reports a successful subresource response only when the app intercepts it in " +
            "shouldInterceptRequest, and reports 4xx/5xx only through onReceivedHttpError; a request " +
            "served from the WebView cache, or finished entirely inside the browser process, can " +
            "complete with no callback at all. This row is kept so the request is not silently lost, " +
            "and it is not counted as a failure."

    const val MASKED_BY_POLICY =
        "Masked by the inspector's redaction policy at capture time, so the original value was never " +
            "stored. Turn on \"Reveal sensitive values\" and capture the request again to see it."

    const val FULL_CAPTURE_WARNING =
        "Reveal sensitive values is ON. Authorization and Cookie headers, tokens in URLs, cookie " +
            "values, bodies and console text are stored exactly as observed, and any file you export " +
            "contains them in the clear. This is a debug-build-only tool: switch the switch off to go " +
            "back to masking at capture time."

    const val FULL_CAPTURE_OFF_NOTE =
        "This row was stored while \"Reveal sensitive values\" was off, so the original value is not " +
            "in the buffer. Turn the switch on and reload the page to capture it in the clear."

    /**
     * The short, user-facing capability summary shown at the top of the inspector and in the report.
     */
    val CAPABILITY_SUMMARY_WEBVIEW: List<Pair<String, Boolean>> = listOf(
        "Request URL and method, with timestamps" to true,
        "Request headers, as the subset WebView exposes" to true,
        "Request bodies passed to fetch/XHR/sendBeacon by the page" to true,
        "Sensitive values unmasked while \"Reveal sensitive values\" is on" to true,
        "HTTP status for 4xx/5xx and for responses the app intercepts" to true,
        "Response headers and Content-Type for responses the app intercepts" to true,
        "Cookie scope (domain/path/flags/expiry) for cookies the app can see" to true,
        "Cookie scope read back from Set-Cookie for the page's own fetch/XHR" to true,
        "console.log/warn/error and uncaught JavaScript errors" to true,
        "Renderer death, refused TLS certificates and stalled loads, reported by the app itself" to true,
        "Top-level status line for 2xx/3xx responses the app does not intercept" to false,
        "Response bodies of resources the app does not fetch itself" to false,
        "Complete header sets (the platform decides what to expose)" to false,
        "WebSocket frames" to false,
        "Service Worker fetches (without androidx.webkit)" to false,
        "Redirect chains for subresources" to false,
        "Requests served from cache with no callback" to false,
        "Anything at all in a release build" to false,
    )

    /**
     * What the Gecko engine lets this app observe. Gecko exposes no request interception to
     * embedders, but its extensions see the wire: the bridge extension's `webRequest` and `cookies`
     * APIs feed the inspector. Compared to WebView this is *more* in some rows (complete header sets,
     * every status, redirect hops, full cookie attributes) and less in others (no request bodies, no
     * console.log — a Gecko content script lives in an isolated world and cannot hook the page).
     */
    val CAPABILITY_SUMMARY_GECKO: List<Pair<String, Boolean>> = listOf(
        "Request URL and method, with timestamps" to true,
        "Complete request and response header sets, as they went on the wire" to true,
        "Request bodies passed to fetch/XHR/sendBeacon by the page" to false,
        "Sensitive values unmasked while \"Reveal sensitive values\" is on" to true,
        "HTTP status for every response, including 2xx/3xx" to true,
        "Response headers and Content-Type for every response" to true,
        "Response body previews for text-like resources (documents, scripts, stylesheets, XHR)" to true,
        "Redirect chains, each hop recorded with its status" to true,
        "Cookie scope (domain/path/flags/expiry) for every cookie in the engine's store" to true,
        "Cookie values, masked or revealed per the capture mode" to true,
        "Uncaught JavaScript errors, reported by the page bridge" to true,
        "console.log/warn/error from the page" to false,
        "Renderer death, refused TLS certificates and stalled loads, reported by the app itself" to true,
        "WebSocket frames" to false,
        "Service Worker fetches" to false,
        "Traffic from private (incognito) tabs" to false,
        "Anything at all in a release build" to false,
    )

    /** The capability rows for the engine currently selected, for the export header. */
    fun capabilitySummary(engine: BrowserEngineKind): List<Pair<String, Boolean>> =
        when (engine) {
            BrowserEngineKind.GECKO -> CAPABILITY_SUMMARY_GECKO
            BrowserEngineKind.WEBVIEW -> CAPABILITY_SUMMARY_WEBVIEW
        }

    /** The export section title naming the engine the rows describe. */
    fun capabilitySectionTitle(engine: BrowserEngineKind): String =
        when (engine) {
            BrowserEngineKind.GECKO -> "WHAT THE GECKO ENGINE LETS THIS APP OBSERVE"
            BrowserEngineKind.WEBVIEW -> "WHAT WEBVIEW LETS THIS APP OBSERVE"
        }
}
