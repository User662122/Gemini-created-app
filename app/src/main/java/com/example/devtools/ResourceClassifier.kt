package com.example.devtools

/**
 * Works out the resource type ("Documents", "XHR/Fetch", "JavaScript", …) that the filter bar and the
 * list use.
 *
 * **Honesty rule:** WebView has no API that reports a resource type. WebView 1.12+ exposes an
 * experimental `WebResourceRequest.isWebRequest()` flag on some builds, but it is not part of the
 * supported surface, so this inspector never relies on it. Instead the category is decided from
 * evidence in this order, and [Classification.source] always records which evidence was used:
 *
 * 1. `isForMainFrame` from `WebResourceRequest` → Documents.
 * 2. The `Sec-Fetch-Dest` request header → the exact type the page declared for this fetch
 *    (<https://fetch.spec.whatwg.org/#sec-fetch-dest-header>). This is the strongest signal and it is
 *    widely present on modern sites.
 * 3. The `Accept` / `X-Requested-With` headers → JavaScript, CSS, or Documents.
 * 4. The initiator reported by the injected page hooks (`fetch`, `XMLHttpRequest`, `sendBeacon`).
 * 5. The file extension in the URL → a *guess*, shown in the UI as "inferred".
 * 6. Otherwise `Other`, with the reason recorded as "not available".
 *
 * When a response arrives, [refineWithResponse] may upgrade an extension guess to a
 * `Content-Type`-based classification, which is also marked as inferred.
 */
object ResourceClassifier {

    /** `Sec-Fetch-Dest` → category. Values come from the Fetch standard. */
    private val DEST_CATEGORIES: Map<String, ResourceCategory> = mapOf(
        "document" to ResourceCategory.DOCUMENT,
        "iframe" to ResourceCategory.DOCUMENT,
        "frame" to ResourceCategory.DOCUMENT,
        "embed" to ResourceCategory.DOCUMENT,
        "object" to ResourceCategory.DOCUMENT,
        "style" to ResourceCategory.CSS,
        "script" to ResourceCategory.JAVASCRIPT,
        "image" to ResourceCategory.IMAGE,
        "audio" to ResourceCategory.OTHER,
        "video" to ResourceCategory.OTHER,
        "track" to ResourceCategory.OTHER,
        "font" to ResourceCategory.OTHER,
        "manifest" to ResourceCategory.OTHER,
        "worker" to ResourceCategory.OTHER,
        "sharedworker" to ResourceCategory.OTHER,
        "serviceworker" to ResourceCategory.OTHER,
        "report" to ResourceCategory.OTHER,
        "xslt" to ResourceCategory.OTHER,
        "empty" to ResourceCategory.XHR_FETCH,
    )

    private val EXTENSION_CATEGORIES: Map<String, ResourceCategory> = mapOf(
        "js" to ResourceCategory.JAVASCRIPT,
        "mjs" to ResourceCategory.JAVASCRIPT,
        "cjs" to ResourceCategory.JAVASCRIPT,
        "css" to ResourceCategory.CSS,
        "png" to ResourceCategory.IMAGE,
        "jpg" to ResourceCategory.IMAGE,
        "jpeg" to ResourceCategory.IMAGE,
        "gif" to ResourceCategory.IMAGE,
        "webp" to ResourceCategory.IMAGE,
        "avif" to ResourceCategory.IMAGE,
        "svg" to ResourceCategory.IMAGE,
        "ico" to ResourceCategory.IMAGE,
        "bmp" to ResourceCategory.IMAGE,
        "html" to ResourceCategory.DOCUMENT,
        "htm" to ResourceCategory.DOCUMENT,
        "xhtml" to ResourceCategory.DOCUMENT,
        "shtml" to ResourceCategory.DOCUMENT,
    )

    private val MIME_CATEGORIES: Map<String, ResourceCategory> = mapOf(
        "text/css" to ResourceCategory.CSS,
        "text/javascript" to ResourceCategory.JAVASCRIPT,
        "application/javascript" to ResourceCategory.JAVASCRIPT,
        "application/x-javascript" to ResourceCategory.JAVASCRIPT,
        "text/ecmascript" to ResourceCategory.JAVASCRIPT,
        "application/ecmascript" to ResourceCategory.JAVASCRIPT,
        "text/html" to ResourceCategory.DOCUMENT,
        "application/xhtml+xml" to ResourceCategory.DOCUMENT,
        "image/png" to ResourceCategory.IMAGE,
        "image/jpeg" to ResourceCategory.IMAGE,
        "image/gif" to ResourceCategory.IMAGE,
        "image/webp" to ResourceCategory.IMAGE,
        "image/avif" to ResourceCategory.IMAGE,
        "image/svg+xml" to ResourceCategory.IMAGE,
        "image/x-icon" to ResourceCategory.IMAGE,
        "image/vnd.microsoft.icon" to ResourceCategory.IMAGE,
        "image/bmp" to ResourceCategory.IMAGE,
    )

    /** Result of a classification, including the evidence behind it. */
    data class Classification(
        val category: ResourceCategory,
        val source: EvidenceSource,
        val detail: String?,
    )

    /**
     * Classifies a request.
     *
     * @param header lookup for request headers (already lower-cased names are expected, but the
     *        helper handles both).
     * @param isMainFrame `WebResourceRequest.isForMainFrame`, or null when unknown.
     * @param initiator initiator reported by the page hooks, or null.
     */
    fun classify(
        url: String,
        method: String,
        header: (String) -> String?,
        isMainFrame: Boolean?,
        initiator: Initiator?,
    ): Classification {
        if (isMainFrame == true) {
            return Classification(
                ResourceCategory.DOCUMENT,
                EvidenceSource.WEBVIEW_CALLBACK,
                "main frame document",
            )
        }

        val dest = header("sec-fetch-dest")?.trim()?.lowercase()
        if (!dest.isNullOrEmpty()) {
            val mapped = DEST_CATEGORIES[dest]
            if (mapped != null) {
                val category = if (mapped == ResourceCategory.XHR_FETCH && initiator == Initiator.SEND_BEACON) {
                    ResourceCategory.XHR_FETCH
                } else {
                    mapped
                }
                return Classification(
                    category,
                    EvidenceSource.REQUEST_HEADER,
                    "Sec-Fetch-Dest: $dest",
                )
            }
            return Classification(
                ResourceCategory.OTHER,
                EvidenceSource.REQUEST_HEADER,
                "Sec-Fetch-Dest: $dest (no inspector bucket for this type)",
            )
        }

        val accept = header("accept")?.lowercase()
        if (accept != null) {
            if (accept.contains("text/css")) {
                return Classification(
                    ResourceCategory.CSS,
                    EvidenceSource.REQUEST_HEADER,
                    "Accept: text/css",
                )
            }
            if (accept.contains("javascript") || accept.contains("ecmascript")) {
                return Classification(
                    ResourceCategory.JAVASCRIPT,
                    EvidenceSource.REQUEST_HEADER,
                    "Accept contains a JavaScript media type",
                )
            }
            if (accept.contains("image/")) {
                return Classification(
                    ResourceCategory.IMAGE,
                    EvidenceSource.REQUEST_HEADER,
                    "Accept contains an image media type",
                )
            }
        }

        if (header("x-requested-with")?.equals("XMLHttpRequest", ignoreCase = true) == true) {
            return Classification(
                ResourceCategory.XHR_FETCH,
                EvidenceSource.REQUEST_HEADER,
                "X-Requested-With: XMLHttpRequest (legacy AJAX marker)",
            )
        }

        if (initiator == Initiator.FETCH || initiator == Initiator.XMLHTTP_REQUEST) {
            return Classification(
                ResourceCategory.XHR_FETCH,
                EvidenceSource.PAGE_JAVASCRIPT,
                "started by " + initiator.label,
            )
        }

        if (method.equals("POST", ignoreCase = true) ||
            method.equals("PUT", ignoreCase = true) ||
            method.equals("PATCH", ignoreCase = true) ||
            method.equals("DELETE", ignoreCase = true)
        ) {
            return Classification(
                ResourceCategory.XHR_FETCH,
                EvidenceSource.DERIVED,
                "non-GET request (form or script submission); WebView does not report the type",
            )
        }

        val extension = UrlParts.fileExtension(url)
        val fromExtension = extension?.let { EXTENSION_CATEGORIES[it] }
        if (fromExtension != null) {
            return Classification(
                fromExtension,
                EvidenceSource.DERIVED,
                "inferred from the .$extension file extension",
            )
        }

        if (accept != null && accept.contains("text/html") && method.equals("GET", ignoreCase = true)) {
            return Classification(
                ResourceCategory.DOCUMENT,
                EvidenceSource.DERIVED,
                "Accept: text/html on a GET",
            )
        }

        return Classification(
            ResourceCategory.OTHER,
            EvidenceSource.UNAVAILABLE,
            "no resource type was reported for this request",
        )
    }

    /**
     * Tries to upgrade a guessed category once the response `Content-Type` is known.
     *
     * Only guesses are upgraded: a category backed by `Sec-Fetch-Dest` or by the main-frame flag is
     * never overwritten, because those are direct statements from the page rather than inference.
     */
    fun refineWithResponse(
        current: Classification,
        contentType: String?,
    ): Classification {
        if (current.source == EvidenceSource.REQUEST_HEADER ||
            current.source == EvidenceSource.WEBVIEW_CALLBACK
        ) {
            return current
        }
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return current
        if (mime.isEmpty()) return current
        val mapped = MIME_CATEGORIES[mime] ?: return current
        return Classification(
            mapped,
            EvidenceSource.DERIVED,
            "inferred from the response Content-Type ($mime)",
        )
    }
}
