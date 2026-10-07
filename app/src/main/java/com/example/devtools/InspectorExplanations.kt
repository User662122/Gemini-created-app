package com.example.devtools

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
        "Masked by the inspector's redaction policy at capture time. Enable raw capture in Network " +
            "Inspector settings to reveal individual fields for the current session."

    const val RAW_CAPTURE_WARNING =
        "Raw capture stores unmasked header values, request bodies and console text for the current " +
            "session, so they can be revealed on screen. It never writes them to disk and never sends " +
            "them anywhere; it is cleared when the inspector is switched off or the app is closed."

    /**
     * The short, user-facing capability summary shown at the top of the inspector and in the report.
     */
    val CAPABILITY_SUMMARY: List<Pair<String, Boolean>> = listOf(
        "Request URL and method, with timestamps" to true,
        "Request headers, as the subset WebView exposes" to true,
        "Request bodies passed to fetch/XHR/sendBeacon by the page" to true,
        "Request-body capture is off by default (privacy)" to true,
        "HTTP status for 4xx/5xx and for responses the app intercepts" to true,
        "Response headers and Content-Type for responses the app intercepts" to true,
        "Cookie scope (domain/path/flags/expiry) for cookies the app can see" to true,
        "Cookie scope read back from Set-Cookie for the page's own fetch/XHR" to true,
        "console.log/warn/error and uncaught JavaScript errors" to true,
        "Top-level status line for 2xx/3xx responses the app does not intercept" to false,
        "Response bodies of resources the app does not fetch itself" to false,
        "Complete header sets (the platform decides what to expose)" to false,
        "WebSocket frames" to false,
        "Service Worker fetches (without androidx.webkit)" to false,
        "Redirect chains for subresources" to false,
        "Requests served from cache with no callback" to false,
        "Anything at all in a release build" to false,
    )
}
