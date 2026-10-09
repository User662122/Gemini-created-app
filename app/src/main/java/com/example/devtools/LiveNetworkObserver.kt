package com.example.devtools

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Method shown when no source reported one (for example for an authentication challenge). */
const val UNKNOWN_METHOD = "UNKNOWN"

/**
 * The real capture pipeline.
 *
 * Threading model (this is what keeps browsing fast):
 *
 *  * WebView callbacks arrive on the UI thread (`onPageStarted`, `onReceivedTitle`, the JS bridge) or
 *    on a WebView background thread (`shouldInterceptRequest`, `onReceivedHttpError`,
 *    `onReceivedError`). Work happens **on the calling thread** and never touches the view hierarchy,
 *    so the UI thread never waits for a network thread and vice versa.
 *  * All state that can be touched concurrently lives in the thread-safe [NetworkLogStore] or in
 *    `ConcurrentHashMap`s. There is no global lock: each pending-correlation queue has its own monitor
 *    and is held for a handful of instructions.
 *  * Nothing here does disk I/O, JSON encoding of large payloads, or bitmap/string formatting for the
 *    UI. Values are turned into display strings when capture happens (they are needed for the ring
 *    buffer anyway) and the UI only reads them.
 *  * Two independent token buckets cap the message rate, so a page that hammers `fetch` or
 *    `console.log` cannot push the app into a busy loop: excess messages are dropped and counted (and
 *    the count is shown in the UI) instead of being queued.
 *  * A per-callback `CapturePolicy` read is the only settings access, so a disabled observer costs one
 *    boolean check per callback and allocations are limited to what the callback handed us.
 */
class LiveNetworkObserver(
    /** Read once per callback; must be cheap (an `AtomicReference` read in practice). */
    private val capturePolicy: () -> CapturePolicy,
) : NetworkObserver {

    /** Bounded, thread-safe store shared with the UI. */
    val store = NetworkLogStore()

    /** Invoked (on the calling thread) whenever `CookieManager` reports a change. */
    @Volatile
    var onCookieStoreChanged: (() -> Unit)? = null

    private val lastExpirySweepMillis = AtomicLong(0L)
    private val pendingByKey = ConcurrentHashMap<String, ArrayDeque<PendingRequest>>()
    private val pendingByUrl = ConcurrentHashMap<String, ArrayDeque<PendingRequest>>()
    private val pendingByTab = ConcurrentHashMap<String, ArrayDeque<PendingRequest>>()
    private val requestIdBuckets = ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>>()

    private val messageBudget = SecondBudget(InspectorLimits.MAX_MESSAGES_PER_SECOND)
    private val consoleBudget = SecondBudget(InspectorLimits.MAX_CONSOLE_MESSAGES_PER_SECOND)
    private val bridgeBudget = SecondBudget(InspectorLimits.MAX_BRIDGE_BATCHES_PER_SECOND)

    private val droppedByRate = AtomicLong(0L)
    private val droppedByPage = AtomicLong(0L)
    private val consoleMessagesSinceCheck = AtomicLong(0L)
    private val cookieEventsSinceCheck = AtomicLong(0L)

    override val isActive: Boolean
        get() = capturePolicy().enabled

    // ---------------------------------------------------------------- counters used by the UI ticker

    /** Console rows captured since the last call; resets the counter. */
    fun consumeConsoleCount(): Long = consoleMessagesSinceCheck.getAndSet(0L)

    /** Cookie-store changes since the last call; resets the counter. */
    fun consumeCookieEventCount(): Long = cookieEventsSinceCheck.getAndSet(0L)

    /** Messages dropped because a rate limit was hit. */
    fun droppedByRateLimit(): Long = droppedByRate.get()

    /** Records the page's own script dropped (its send queue was full). */
    fun droppedByPageScript(): Long = droppedByPage.get()

    /**
     * Marks requests that never received any response callback as [EntryState.UNOBSERVED].
     *
     * Called from the UI ticker (main thread) but throttled here, so the scan of the ring buffer runs
     * at most once every [EXPIRY_SWEEP_INTERVAL_MS] no matter how often the caller asks.
     */
    fun expireStalePendingEntries(nowAppMillis: Long): Int {
        if (!capturePolicy().enabled) return 0
        val previous = lastExpirySweepMillis.get()
        if (nowAppMillis - previous < EXPIRY_SWEEP_INTERVAL_MS) return 0
        if (!lastExpirySweepMillis.compareAndSet(previous, nowAppMillis)) return 0
        return store.expirePendingEntries(
            cutoffAppMillis = nowAppMillis - PENDING_EXPIRY_MS,
            note = InspectorExplanations.RESPONSE_NEVER_OBSERVED,
        )
    }

    // ------------------------------------------------------------------------ NetworkObserver events

    override fun onRequestStarted(observation: RequestObservation) {
        val policy = capturePolicy()
        if (!policy.enabled) return
        val now = System.currentTimeMillis()
        if (!messageBudget.allow(now)) {
            droppedByRate.incrementAndGet()
            return
        }

        val entryId = store.allocateEntryId()
        store.addEntry(buildEntry(entryId, observation, policy, notes = emptyList()))
        addPending(
            PendingRequest(
                entryId = entryId,
                tabId = observation.tabId,
                correlationKey = keyFor(observation.url, observation.method, policy),
                urlKey = displayUrl(observation.url, policy),
                recordedAtAppMillis = now,
                appClock = observation.timeSource == EvidenceSource.APP_CLOCK,
                nativeObserved = observation.observedVia == EvidenceSource.WEBVIEW_CALLBACK,
            )
        )
    }

    override fun onResponseReceived(observation: ResponseObservation) {
        val policy = capturePolicy()
        if (!policy.enabled) return
        val now = System.currentTimeMillis()

        val match = takePending(
            key = keyFor(observation.url, observation.method, policy),
            urlKey = displayUrl(observation.url, policy),
            preferNative = observation.observedVia == EvidenceSource.WEBVIEW_CALLBACK,
            nowAppMillis = now,
        )

        if (match != null) {
            val duration = if (match.pending.appClock) now - match.pending.recordedAtAppMillis else observation.durationMillis
            val updated = store.updateEntry(match.pending.entryId) { entry ->
                applyResponse(entry, observation, policy, duration, match.ambiguous)
            }
            if (updated != null) return
            // The entry was already evicted from the ring buffer; fall through and store the response
            // on its own rather than losing it.
        }

        val entryId = store.allocateEntryId()
        val orphan = buildEntry(entryId, orphanRequestObservation(observation), policy, notes = listOf(InspectorExplanations.ORPHAN_RESPONSE))
        store.addEntry(
            applyResponse(
                orphan,
                observation,
                policy,
                observation.durationMillis,
                ambiguous = false,
            )
        )
    }

    override fun onRequestFailed(observation: FailureObservation) {
        val policy = capturePolicy()
        if (!policy.enabled) return

        if (observation.isRendererProcessDeath) {
            // Annotate whatever was still in flight, then *always* leave a record of the event
            // itself. This is the failure that used to be completely invisible: when the renderer
            // dies the page is gone, so nothing reaches onConsoleMessage and no response callback
            // can ever arrive. If no request happened to be pending, the old code stored nothing at
            // all and the session looked as though it had never failed.
            val affected = store.updateEntriesForTab(observation.tabId) { entry ->
                if (entry.state == EntryState.PENDING) {
                    entry.copy(
                        state = EntryState.FAILED,
                        notes = entry.notes + InspectorExplanations.RENDERER_PROCESS_GONE,
                    )
                } else {
                    entry
                }
            }
            // Every correlation for this tab is dead with the renderer, whether or not a row was
            // still pending, so none of them may be paired with a later response.
            clearPendingForTab(observation.tabId)
            val recorded = recordAppDiagnosis(
                policy = policy,
                tabId = observation.tabId,
                url = observation.url,
                method = observation.method,
                isForMainFrame = true,
                description = observation.description +
                    if (affected > 0) " ($affected request(s) were still in flight)" else "",
                note = InspectorExplanations.RENDERER_PROCESS_GONE,
                observedAtMillis = observation.observedAtMillis,
            )
            if (recorded) store.recordIncident(IncidentKind.RENDERER_KILLED)
            return
        }

        // A main-frame failure is the one a reader actually cares about ("why is my page blank?"),
        // and it is precisely the case where the page produced no console output of its own, so the
        // app has to say it. Sub-resource failures stay in the network list only: a page can lose
        // dozens of images or beacons without the user noticing, and logging each would bury the
        // console (and burn the console rate budget) for no benefit.
        if (observation.isForMainFrame) {
            val recorded = addDiagnosisConsoleLine(
                policy = policy,
                tabId = observation.tabId,
                level = ConsoleLevel.ERROR,
                message = "The page failed to load: ${observation.description}" +
                    (observation.errorCode?.let { " (WebResourceError errorCode=$it)" } ?: "") +
                    " — ${observation.url}",
            )
            if (isNewIncident(recorded)) store.recordIncident(IncidentKind.MAIN_FRAME_LOAD_FAILURE)
        }

        val now = System.currentTimeMillis()
        val key = keyFor(observation.url, observation.method, policy)
        val urlKey = displayUrl(observation.url, policy)
        val match = takePending(key, urlKey, preferNative = true, nowAppMillis = now)

        if (match != null) {
            val updated = store.updateEntry(match.pending.entryId) { entry ->
                entry.copy(
                    response = withFailure(entry.response, observation),
                    state = EntryState.FAILED,
                    notes = appendNote(entry.notes, ambiguityNote(match.ambiguous)),
                )
            }
            if (updated != null) return
        }

        val entryId = store.allocateEntryId()
        val request = RequestObservation(
            tabId = observation.tabId,
            url = observation.url,
            method = observation.method,
            observedAtMillis = observation.observedAtMillis,
            timeSource = EvidenceSource.APP_CLOCK,
            isForMainFrame = observation.isForMainFrame,
            isRedirect = null,
            hasGesture = null,
            headers = emptyMap(),
            headersReport = HeadersReport.UNKNOWN,
            initiator = if (observation.isForMainFrame) Initiator.DOCUMENT_NAVIGATION else Initiator.RESOURCE_LOAD,
            observedVia = EvidenceSource.WEBVIEW_CALLBACK,
        )
        store.addEntry(
            buildEntry(entryId, request, policy, notes = listOf(InspectorExplanations.ORPHAN_RESPONSE))
                .copy(response = withFailure(null, observation), state = EntryState.FAILED)
        )
    }

    override fun onHttpError(observation: HttpErrorObservation) {
        val policy = capturePolicy()
        if (!policy.enabled) return

        // A 4xx/5xx on the main document means the server rejected the navigation itself, so the
        // page's scripts never ran and its console never produced a line. This is the row that
        // explains a "the site just refused me and showed me nothing" session. When the same failure
        // also arrives through the error-page title, addConsole collapses the repeat.
        if (observation.isForMainFrame) {
            val recorded = addDiagnosisConsoleLine(
                policy = policy,
                tabId = observation.tabId,
                level = ConsoleLevel.ERROR,
                message = "HTTP ${observation.statusCode} on the main document ${observation.url}" +
                    (observation.reasonPhrase?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                    ". The server rejected the navigation, so the page's own JavaScript and console " +
                    "output may never have started.",
            )
            if (isNewIncident(recorded)) store.recordIncident(IncidentKind.MAIN_FRAME_HTTP_ERROR)
        }

        // Name the refusal when the server's own headers say an access-control layer issued it. A
        // bare "403" in the list is where understanding goes to die: it reads as a fault, when it is
        // a decision — and the reference the layer stamps on the response is the one thing that lets
        // the site's owner confirm which rule fired.
        if (observation.isForMainFrame &&
            AccessControlRejection.isRejection(observation.statusCode, observation.headers)
        ) {
            val recorded = addDiagnosisConsoleLine(
                policy = policy,
                tabId = observation.tabId,
                level = ConsoleLevel.ERROR,
                message = InspectorExplanations.cdnAccessDenied(
                    statusCode = observation.statusCode,
                    referenceId = AccessControlRejection.referenceId(observation.headers),
                ),
            )
            if (isNewIncident(recorded)) store.recordIncident(IncidentKind.CDN_ACCESS_DENIED)
        }

        val key = keyFor(observation.url, observation.method, policy)

        // Fill in an existing row whenever possible: the same failure can reach us twice (once as a
        // delivered 4xx/5xx and once through the error-page title for the main frame).
        val existing = store.findEntry(observation.tabId, key, skipPageObserved = false, maxScan = 60)
        if (existing != null) {
            val updated = store.updateEntry(existing.id) { entry ->
                val knownStatus = entry.response?.statusCode?.isKnown == true
                if (knownStatus) {
                    entry
                } else {
                    entry.copy(
                        response = mergeHttpError(entry.response, observation, policy),
                        notes = appendNote(entry.notes, InspectorExplanations.STATUS_FROM_HTTP_ERROR_CHANNEL),
                    )
                }
            }
            if (updated != null) return
        }

        val entryId = store.allocateEntryId()
        val request = RequestObservation(
            tabId = observation.tabId,
            url = observation.url,
            method = observation.method,
            observedAtMillis = observation.observedAtMillis,
            timeSource = EvidenceSource.APP_CLOCK,
            isForMainFrame = observation.isForMainFrame,
            isRedirect = null,
            hasGesture = null,
            headers = emptyMap(),
            headersReport = HeadersReport.UNKNOWN,
            initiator = if (observation.isForMainFrame) Initiator.DOCUMENT_NAVIGATION else Initiator.RESOURCE_LOAD,
            observedVia = EvidenceSource.WEBVIEW_CALLBACK,
        )
        store.addEntry(
            buildEntry(
                entryId,
                request,
                policy,
                notes = listOf(InspectorExplanations.ORPHAN_RESPONSE),
            ).copy(response = mergeHttpError(null, observation, policy))
        )
    }

    override fun onSslError(observation: SslErrorObservation) {
        val policy = capturePolicy()
        if (!policy.enabled) return
        val recorded = recordAppDiagnosis(
            policy = policy,
            tabId = observation.tabId,
            url = observation.url,
            method = UNKNOWN_METHOD,
            // WebView does not say which frame the handshake belonged to, so this stays unknown
            // rather than being guessed at "main frame".
            isForMainFrame = observation.isForMainFrame,
            description = buildString {
                append("TLS/SSL error: ")
                append(observation.description)
                observation.primaryError?.let { append(" (SslError primaryError=$it)") }
                append(". WebView cancelled the load, so no response was ever delivered.")
            },
            note = InspectorExplanations.SSL_ERROR_CANCELLED,
            observedAtMillis = observation.observedAtMillis,
        )
        if (recorded) store.recordIncident(IncidentKind.TLS_REFUSED)
    }

    override fun onDocumentLoadTimeout(tabId: String, url: String?, elapsedMillis: Long) {
        val policy = capturePolicy()
        if (!policy.enabled) return
        val note = InspectorExplanations.documentLoadTimeout(elapsedMillis)
        // Invent no response: the document may still be loading and may still succeed, and marking
        // its rows FAILED would be a lie. Annotate what is waiting, and say it once in the console,
        // which is the first place a reader looks when a page appears to have hung.
        store.updateEntriesForTab(tabId) { entry ->
            if (entry.state == EntryState.PENDING) {
                entry.copy(notes = appendNote(entry.notes, note))
            } else {
                entry
            }
        }
        val recorded = addDiagnosisConsoleLine(
            policy = policy,
            tabId = tabId,
            level = ConsoleLevel.WARN,
            message = "Load watchdog: ${url ?: "the document"} had not finished loading " +
                "${elapsedMillis / 1000} s after it started. WebView reported no page-finished, no " +
                "error and no HTTP status for it. The server may be overloaded or the renderer " +
                "stalled; the load has not been cancelled and may still complete.",
        )
        if (isNewIncident(recorded)) store.recordIncident(IncidentKind.LOAD_TIMEOUT)
    }

    override fun onAuthenticationRequest(observation: AuthObservation) {
        val policy = capturePolicy()
        if (!policy.enabled) return
        val entryId = store.allocateEntryId()
        val request = RequestObservation(
            tabId = observation.tabId,
            url = observation.url,
            method = UNKNOWN_METHOD,
            observedAtMillis = observation.observedAtMillis,
            timeSource = EvidenceSource.APP_CLOCK,
            isForMainFrame = null,
            isRedirect = null,
            hasGesture = null,
            headers = emptyMap(),
            headersReport = HeadersReport.UNKNOWN,
            initiator = Initiator.RESOURCE_LOAD,
            observedVia = EvidenceSource.WEBVIEW_CALLBACK,
        )
        val note = buildString {
            append("An HTTP authentication challenge was received for host \"")
            append(observation.host)
            append('"')
            if (!observation.realm.isNullOrBlank()) {
                append(" (realm \"")
                append(observation.realm)
                append("\")")
            }
            append(". ")
            append(InspectorExplanations.HTTP_AUTH_SCHEME_UNAVAILABLE)
        }
        store.addEntry(
            buildEntry(entryId, request, policy, notes = listOf(note)).copy(
                response = ResponseRecord(
                    statusCode = InspectorValue.unknown(
                        "WebView reports the challenge but not the status code that triggered it " +
                            "(typically 401 for an origin, 407 for a proxy)."
                    ),
                    reasonPhrase = InspectorValue.unknown(InspectorExplanations.REASON_PHRASE_UNAVAILABLE),
                    headers = emptyList(),
                    contentType = InspectorValue.unknown("No response body was delivered for this challenge."),
                    receivedAtMillis = InspectorValue.known(observation.observedAtMillis, EvidenceSource.APP_CLOCK),
                    finalUrl = InspectorValue.unknown(InspectorExplanations.FINAL_URL_SUBRESOURCE_UNAVAILABLE),
                    durationMillis = null,
                ),
                state = EntryState.FAILED,
            )
        )
    }

    override fun onConsoleMessage(
        tabId: String,
        level: ConsoleLevel,
        message: String,
        source: String?,
        lineNumber: Int?,
        stackTrace: String?,
    ) {
        val policy = capturePolicy()
        if (!policy.enabled || !policy.captureConsole) return
        if (level == ConsoleLevel.VERBOSE && !policy.captureConsoleVerbose) return

        val now = System.currentTimeMillis()
        if (!consoleBudget.allow(now)) {
            droppedByRate.incrementAndGet()
            return
        }

        val scrubbed = Redaction.scrubText(
            message,
            InspectorLimits.MAX_CONSOLE_MESSAGE_CHARS,
            policy.revealSensitiveValues,
        )
        val scrubbedStack = stackTrace?.let {
            Redaction.scrubText(it, InspectorLimits.MAX_STACK_TRACE_CHARS, policy.revealSensitiveValues)
        }
        store.addConsole(
            ConsoleEntry(
                id = store.allocateConsoleId(),
                tabId = tabId,
                level = level,
                message = scrubbed.text,
                masked = scrubbed.masked,
                source = source?.take(InspectorLimits.MAX_URL_CHARS),
                lineNumber = lineNumber,
                timestampMillis = now,
                stackTrace = scrubbedStack?.text,
                evidence = EvidenceSource.WEBVIEW_CALLBACK,
            )
        )
        consoleMessagesSinceCheck.incrementAndGet()
    }

    override fun onPageRecords(tabId: String, json: String) {
        val policy = capturePolicy()
        if (!policy.enabled) return
        if (json.length > InspectorLimits.MAX_BRIDGE_BATCH_CHARS) {
            droppedByRate.incrementAndGet()
            return
        }
        val now = System.currentTimeMillis()
        if (!bridgeBudget.allow(now)) {
            droppedByRate.incrementAndGet()
            return
        }

        val batch = PageJsRecordParser.parseBatch(json)
        if (batch.droppedByPage > 0) droppedByPage.addAndGet(batch.droppedByPage.toLong())
        if (batch.records.isEmpty()) return

        val bucketKey = documentBucketKey(tabId, batch.documentId)
        val bucket = requestIdBucket(bucketKey)
        for (record in batch.records) {
            try {
                handlePageRecord(tabId, bucket, record, policy)
            } catch (_: RuntimeException) {
                // A malformed page payload must never take down the WebView thread.
                droppedByRate.incrementAndGet()
            }
        }
    }

    override fun onAppHttpExchange(request: RequestObservation, response: ResponseObservation) {
        val policy = capturePolicy()
        if (!policy.enabled) return
        val entryId = store.allocateEntryId()
        store.addEntry(
            applyResponse(
                entry = buildEntry(
                    entryId,
                    request,
                    policy,
                    notes = listOf(InspectorExplanations.APP_HTTP_CLIENT_OBSERVED),
                ),
                observation = response,
                policy = policy,
                durationMillis = response.durationMillis,
                ambiguous = false,
            )
        )
    }

    override fun onDocumentStarted(tabId: String) {
        val policy = capturePolicy()
        if (!policy.enabled) return
        // A new document invalidates every in-flight correlation of this tab: the requests that were
        // pending belong to a document the renderer has already thrown away.
        clearPendingForTab(tabId)
    }

    override fun onDocumentFinished(tabId: String, url: String?, title: String?) {
        val policy = capturePolicy()
        if (!policy.enabled) return

        val titleStatus = httpStatusFromErrorPageTitle(title)
        store.updateEntriesForTab(tabId) { entry ->
            if (entry.request.isForMainFrame != true) return@updateEntriesForTab entry
            var updated = entry

            if (titleStatus != null && entry.response?.statusCode?.isKnown != true) {
                val response = (entry.response ?: emptyResponse(entry.request.startedAtMillis)).copy(
                    statusCode = InspectorValue.derived(
                        titleStatus,
                        "Read from the WebView error page title (\"$title\").",
                    ),
                )
                updated = updated.copy(
                    response = response,
                    state = EntryState.FAILED,
                    notes = appendNote(updated.notes, InspectorExplanations.STATUS_FROM_ERROR_PAGE_TITLE),
                )
            }

            if (!url.isNullOrBlank() && updated.response?.finalUrl?.isKnown != true) {
                val response = (updated.response ?: emptyResponse(updated.request.startedAtMillis)).copy(
                    finalUrl = InspectorValue.known(url, EvidenceSource.WEBVIEW_CALLBACK),
                )
                updated = updated.copy(response = response)
            }

            updated
        }
    }

    override fun onCookieStoreChanged(url: String?) {
        val policy = capturePolicy()
        if (!policy.enabled) return
        cookieEventsSinceCheck.incrementAndGet()
        onCookieStoreChanged?.invoke()
    }

    override fun onWebViewDestroyed(tabId: String) {
        clearPendingForTab(tabId)
        val iterator = requestIdBuckets.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key.startsWith("$tabId/")) requestIdBuckets.remove(key)
        }
    }

    // --------------------------------------------------------------------- engine (Gecko) input

    /**
     * One HTTP exchange as the embedded engine's `webRequest` saw it.
     *
     * This is the engine's replacement for `shouldInterceptRequest`: Gecko exposes no request
     * interception to embedders, but its extensions see every request on the wire — full header
     * sets, status for every response (not just 4xx/5xx), redirect hops, timing, and (for text-ish
     * resources, when enabled) a streamed body preview. The record arrives complete, so it is fed
     * through the same two observer methods the WebView path uses and lands in the same rows, with
     * the same masking, classification and correlation.
     */
    override fun onEngineRequest(record: EngineRequestRecord) {
        val policy = capturePolicy()
        if (!policy.enabled) return
        if (!isHttpUrl(record.url)) return

        val request = RequestObservation(
            tabId = record.tabId,
            url = record.url,
            method = record.method.ifBlank { UNKNOWN_METHOD },
            observedAtMillis = System.currentTimeMillis(),
            timeSource = EvidenceSource.APP_CLOCK,
            isForMainFrame = record.isMainFrame,
            isRedirect = record.redirectedFrom != null,
            hasGesture = null,
            headers = record.requestHeaders,
            headersReport = HeadersReport.ENGINE_WEB_REQUEST,
            initiator = if (record.isMainFrame) Initiator.DOCUMENT_NAVIGATION else Initiator.RESOURCE_LOAD,
            observedVia = EvidenceSource.ENGINE_WEB_REQUEST,
            // Gecko's webRequest does not expose request bodies, and the page hooks cannot run in
            // Gecko (the content script lives in an isolated world), so this says so rather than
            // showing an empty body.
            body = BodyRecord(
                preview = InspectorValue.unknown(InspectorExplanations.ENGINE_REQUEST_BODY_UNAVAILABLE),
                kind = BodyKind.UNKNOWN,
            ),
        )
        onRequestStarted(request)

        val notes = ArrayList<String>(3)
        if (record.redirectedFrom != null) {
            notes += "Redirect hop: the engine reports this request was redirected to from " +
                "${record.redirectedFrom}."
        }
        if (record.redirectedTo != null && record.statusCode != null && record.statusCode in 300..399) {
            notes += "Redirected to ${record.redirectedTo} (HTTP ${record.statusCode})."
        }
        if (notes.isNotEmpty()) {
            val key = keyFor(record.url, request.method, policy)
            val pending = store.findEntry(record.tabId, key, skipPageObserved = false, maxScan = 5)
            if (pending != null) {
                store.updateEntry(pending.id) { entry ->
                    entry.copy(notes = entry.notes + notes)
                }
            }
        }

        val failure = record.error
        if (failure != null) {
            onRequestFailed(
                FailureObservation(
                    tabId = record.tabId,
                    url = record.url,
                    description = "The engine reported a network-level failure: $failure",
                    errorCode = null,
                    isForMainFrame = record.isMainFrame,
                    method = request.method,
                    observedAtMillis = System.currentTimeMillis(),
                )
            )
            return
        }

        val contentType = record.responseHeaders.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
        onResponseReceived(
            ResponseObservation(
                tabId = record.tabId,
                url = record.url,
                method = request.method,
                statusCode = record.statusCode,
                reasonPhrase = null,
                statusSource = EvidenceSource.ENGINE_WEB_REQUEST,
                headers = record.responseHeaders,
                headersReport = HeadersReport.ENGINE_WEB_REQUEST,
                contentType = contentType,
                observedAtMillis = System.currentTimeMillis(),
                timeSource = EvidenceSource.APP_CLOCK,
                durationMillis = record.durationMillis,
                isForMainFrame = record.isMainFrame,
                observedVia = EvidenceSource.ENGINE_WEB_REQUEST,
                body = engineBodyRecord(record, policy),
            )
        )
    }

    /**
     * The engine's cookie store (Gecko's `cookies` API), which — unlike WebView's `CookieManager` —
     * reports every attribute: domain, path, Secure, HttpOnly and expiry.
     *
     * The engine pushes the full store when its background script starts and one record per
     * `cookies.onChanged` event afterwards; [removed] distinguishes the two shapes. Values are
     * masked here, at capture time, exactly like every other source.
     */
    override fun onEngineCookies(cookies: List<EngineCookie>, removed: Boolean) {
        val policy = capturePolicy()
        if (!policy.enabled) return
        for (cookie in cookies) {
            val id = "engine:${cookie.domain}:${cookie.path}:${cookie.name}"
            if (removed) {
                store.removeCookieObservation(id)
                continue
            }
            val url = "https://" + cookie.domain.trimStart('.') + "/"
            val expiryLabel = cookie.expirationDate?.let { epochSeconds ->
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
                    .format(Date(epochSeconds * 1000L))
            } ?: "session cookie"
            store.addCookieObservation(
                CookieRecord(
                    id = id,
                    name = cookie.name,
                    value = InspectorValue.known(
                        Redaction.cookieValue(cookie.value, policy.revealSensitiveValues),
                        EvidenceSource.ENGINE_COOKIE_STORE,
                        if (policy.revealSensitiveValues) {
                            "Full capture is on: the cookie value is stored exactly as the engine " +
                                "reports it."
                        } else {
                            InspectorExplanations.COOKIE_MASKED
                        },
                    ),
                    domain = InspectorValue.known(
                        cookie.domain,
                        EvidenceSource.ENGINE_COOKIE_STORE,
                        if (cookie.hostOnly) {
                            "Host-only cookie: Gecko reports no leading dot, so it is scoped to exactly " +
                                "this host."
                        } else {
                            "Domain attribute reported by the engine's cookie store."
                        },
                    ),
                    path = InspectorValue.known(
                        cookie.path,
                        EvidenceSource.ENGINE_COOKIE_STORE,
                        "Path attribute reported by the engine's cookie store.",
                    ),
                    secure = InspectorValue.known(
                        cookie.secure,
                        EvidenceSource.ENGINE_COOKIE_STORE,
                        "Secure flag reported by the engine's cookie store.",
                    ),
                    httpOnly = InspectorValue.known(
                        cookie.httpOnly,
                        EvidenceSource.ENGINE_COOKIE_STORE,
                        "HttpOnly flag reported by the engine's cookie store.",
                    ),
                    expiration = InspectorValue.known(
                        expiryLabel,
                        EvidenceSource.ENGINE_COOKIE_STORE,
                        if (cookie.session) {
                            "Session cookie: the engine reports no expiry date."
                        } else {
                            "Expiry reported by the engine's cookie store, rendered as declared."
                        },
                    ),
                    observedForUrl = Redaction.displayUrl(
                        url,
                        maskSensitiveParams = !policy.revealSensitiveValues,
                    ),
                    sources = setOf(EvidenceSource.ENGINE_COOKIE_STORE),
                    notes = listOfNotNull(
                        cookie.sameSite?.let { "SameSite=$it was reported by the engine's cookie store." },
                    ),
                )
            )
        }
        cookieEventsSinceCheck.addAndGet(cookies.size.toLong())
        onCookieStoreChanged?.invoke()
    }

    private fun engineBodyRecord(record: EngineRequestRecord, policy: CapturePolicy): BodyRecord? {
        val preview = record.bodyPreview ?: return null
        if (!policy.captureResponseBodies) {
            return BodyRecord(
                preview = InspectorValue.unknown(InspectorExplanations.ENGINE_BODY_NOT_CAPTURED),
                kind = BodyKind.UNKNOWN,
            )
        }
        val scrubbed = Redaction.scrubText(
            preview,
            InspectorLimits.MAX_ENGINE_BODY_PREVIEW_CHARS,
            policy.revealSensitiveValues,
        )
        return BodyRecord(
            preview = InspectorValue.known(scrubbed.text, EvidenceSource.ENGINE_WEB_REQUEST),
            kind = BodyKind.TEXT,
            truncated = record.bodyTruncated || scrubbed.text.length < preview.length,
        )
    }

    private fun isHttpUrl(url: String): Boolean {
        val scheme = UrlParts.scheme(url)
        return scheme == "http" || scheme == "https"
    }

    // ------------------------------------------------------------------------------ entry building

    /**
     * Stores a failure the **app** witnessed rather than the page: a dead renderer, a refused TLS
     * handshake, a stalled document.
     *
     * These are exactly the events that used to leave the inspector empty, because the page that
     * would normally report them is dead, never loaded, or still hanging — so no console line and no
     * response callback can ever arrive. Each one becomes a FAILED network row plus a console line,
     * so an exported session always states what happened instead of looking as though nothing did.
     *
     * @return true when this was a distinct failure, false when it was the same failure arriving
     *   through a second channel and collapsing into a row already recorded.
     */
    private fun recordAppDiagnosis(
        policy: CapturePolicy,
        tabId: String,
        url: String,
        method: String,
        isForMainFrame: Boolean?,
        description: String,
        note: String?,
        observedAtMillis: Long,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (!messageBudget.allow(now)) {
            droppedByRate.incrementAndGet()
            // The row was dropped by the rate limit, but the failure still happened, so the console
            // line and the session counter still get it.
            return isNewIncident(
                addDiagnosisConsoleLine(policy, tabId, ConsoleLevel.ERROR, description)
            )
        }
        // A dead renderer often cannot report its URL any more. Fall back to the newest main-frame
        // document the inspector saw for this tab rather than storing a row with a blank URL.
        val resolvedUrl = url.ifBlank { store.latestDocumentUrl(tabId) ?: "(URL not reported)" }
        val entryId = store.allocateEntryId()
        val request = RequestObservation(
            tabId = tabId,
            url = resolvedUrl,
            method = method,
            observedAtMillis = observedAtMillis,
            timeSource = EvidenceSource.APP_CLOCK,
            isForMainFrame = isForMainFrame,
            isRedirect = null,
            hasGesture = null,
            headers = emptyMap(),
            headersReport = HeadersReport.UNKNOWN,
            initiator = if (isForMainFrame == true) {
                Initiator.DOCUMENT_NAVIGATION
            } else {
                Initiator.RESOURCE_LOAD
            },
            observedVia = EvidenceSource.WEBVIEW_CALLBACK,
        )
        store.addEntry(
            buildEntry(
                entryId,
                request,
                policy,
                notes = listOfNotNull(note, InspectorExplanations.APP_OBSERVED_FAILURE),
            ).copy(
                response = withFailure(
                    null,
                    FailureObservation(
                        tabId = tabId,
                        url = resolvedUrl,
                        description = description,
                        errorCode = null,
                        isForMainFrame = isForMainFrame == true,
                        method = method,
                        observedAtMillis = observedAtMillis,
                    ),
                ),
                state = EntryState.FAILED,
            )
        )
        return isNewIncident(addDiagnosisConsoleLine(policy, tabId, ConsoleLevel.ERROR, description))
    }

    /**
     * Adds one console line the *app* generated, tagged [EvidenceSource.DERIVED] so it can never be
     * mistaken for output the page itself produced.
     *
     * Gated on the same console switch and rate budget as real console messages: with "JavaScript
     * console" switched off the user asked for no console rows, and the network entry still carries
     * the failure. Identical consecutive lines collapse in [NetworkLogStore.addConsole], so one
     * failure reported through two channels does not become two rows.
     */
    private fun addDiagnosisConsoleLine(
        policy: CapturePolicy,
        tabId: String,
        level: ConsoleLevel,
        message: String,
    ): ConsoleEntry? {
        if (!policy.captureConsole) return null
        val now = System.currentTimeMillis()
        if (!consoleBudget.allow(now)) {
            droppedByRate.incrementAndGet()
            return null
        }
        val scrubbed = Redaction.scrubText(
            message,
            InspectorLimits.MAX_CONSOLE_MESSAGE_CHARS,
            policy.revealSensitiveValues,
        )
        val stored = store.addConsole(
            ConsoleEntry(
                id = store.allocateConsoleId(),
                tabId = tabId,
                level = level,
                message = scrubbed.text,
                masked = scrubbed.masked,
                source = null,
                lineNumber = null,
                timestampMillis = now,
                stackTrace = null,
                evidence = EvidenceSource.DERIVED,
            )
        )
        consoleMessagesSinceCheck.incrementAndGet()
        return stored
    }

    /**
     * True when [stored] is a new event rather than a repeat collapsed into an earlier row.
     *
     * One failure can reach the app through two channels — a 4xx arrives both as a delivered response
     * and as the error-page title — and [NetworkLogStore.addConsole] merges those into one row with a
     * repeat count. Counting the incident only for unmerged rows keeps the session total a count of
     * *failures* rather than of callbacks. A null result means the console switch was off or the
     * console budget was spent; the failure still happened, so it still counts.
     */
    private fun isNewIncident(stored: ConsoleEntry?): Boolean =
        stored == null || stored.repeatCount == 1

    private fun buildEntry(
        id: Long,
        observation: RequestObservation,
        policy: CapturePolicy,
        notes: List<String>,
    ): NetworkEntry {
        val loweredHeaders = loweredHeaders(observation.headers)
        val classification = ResourceClassifier.classify(
            url = observation.url,
            method = observation.method,
            header = { name -> loweredHeaders[name] },
            isMainFrame = observation.isForMainFrame,
            initiator = observation.initiator,
        )

        return NetworkEntry(
            id = id,
            tabId = observation.tabId,
            recordedAtAppMillis = System.currentTimeMillis(),
            request = RequestRecord(
                url = displayUrl(observation.url, policy),
                method = observation.method.ifBlank { UNKNOWN_METHOD },
                startedAtMillis = observation.observedAtMillis,
                startedAtSource = observation.timeSource,
                isForMainFrame = observation.isForMainFrame,
                isRedirect = observation.isRedirect,
                hasGesture = observation.hasGesture,
                headers = headerFields(observation.headers, policy),
                headersCompleteness = observation.headersReport.completeness,
                body = observation.body,
                category = classification.category,
                categorySource = classification.source,
                categoryDetail = classification.detail,
                initiator = observation.initiator
                    ?: if (observation.isForMainFrame == true) Initiator.DOCUMENT_NAVIGATION else Initiator.RESOURCE_LOAD,
            ),
            response = null,
            state = EntryState.PENDING,
            pageObserved = observation.observedVia == EvidenceSource.PAGE_JAVASCRIPT,
            notes = notes,
        )
    }

    private fun applyResponse(
        entry: NetworkEntry,
        observation: ResponseObservation,
        policy: CapturePolicy,
        durationMillis: Long?,
        ambiguous: Boolean,
    ): NetworkEntry {
        val incoming = buildResponse(observation, policy, durationMillis)
        val response = mergeResponse(entry.response, incoming)
        val refined = ResourceClassifier.refineWithResponse(
            ResourceClassifier.Classification(
                entry.request.category,
                entry.request.categorySource,
                entry.request.categoryDetail,
            ),
            observation.contentType,
        )
        return entry.copy(
            request = entry.request.copy(
                category = refined.category,
                categorySource = refined.source,
                categoryDetail = refined.detail,
            ),
            response = response,
            state = if (observation.failureText != null) EntryState.FAILED else EntryState.RECEIVED,
            notes = appendNote(entry.notes, ambiguityNote(ambiguous)),
        )
    }

    /**
     * A `Set-Cookie` header is the only complete cookie scope the app can ever see, so it is kept
     * separately (attributes only, value masked) for the Cookie Inspector.
     */
    private fun rememberSetCookieHeader(
        url: String,
        headers: Map<String, String>,
        policy: CapturePolicy,
    ) {
        val setCookie = headers.entries
            .firstOrNull { it.key.equals("set-cookie", ignoreCase = true) }
            ?.value ?: return
        val parsed = SetCookieParser.parse(setCookie) ?: return
        val host = UrlParts.host(url) ?: return

        val domain = parsed.domain ?: host
        val path = parsed.path ?: UrlParts.defaultCookiePath(url)
        val hostOnly = parsed.domain == null
        val notes = ArrayList<String>(2)
        if (parsed.possiblyMerged) {
            notes += "WebView merges multiple Set-Cookie headers into one comma separated value, which " +
                "cannot be split reliably; only the first cookie of this header is shown."
        }
        if (parsed.sameSite != null) {
            notes += "SameSite=$parsed.sameSite was declared by the server."
        }

        store.addCookieObservation(
            CookieRecord(
                id = "set-cookie:$domain:${parsed.name}",
                name = parsed.name,
                value = InspectorValue.known(
                    Redaction.cookieValue(parsed.value, policy.revealSensitiveValues),
                    EvidenceSource.SET_COOKIE_HEADER,
                    if (policy.revealSensitiveValues) {
                        "Full capture is on: the cookie value is stored exactly as the server sent it."
                    } else {
                        InspectorExplanations.COOKIE_MASKED
                    },
                ),
                domain = InspectorValue.known(
                    domain,
                    EvidenceSource.SET_COOKIE_HEADER,
                    if (hostOnly) {
                        "Host-only cookie (no Domain attribute was sent), so it is scoped to $host."
                    } else {
                        "Domain attribute sent by the server."
                    },
                ),
                path = InspectorValue.known(
                    path,
                    EvidenceSource.SET_COOKIE_HEADER,
                    if (parsed.path == null) {
                        "No Path attribute was sent; the RFC 6265 default path for the request URL is shown."
                    } else {
                        "Path attribute sent by the server."
                    },
                ),
                secure = InspectorValue.known(
                    parsed.secure,
                    EvidenceSource.SET_COOKIE_HEADER,
                    "A Set-Cookie header lists every attribute, so an absent Secure flag means false.",
                ),
                httpOnly = InspectorValue.known(
                    parsed.httpOnly,
                    EvidenceSource.SET_COOKIE_HEADER,
                    "A Set-Cookie header lists every attribute, so an absent HttpOnly flag means false.",
                ),
                expiration = InspectorValue.known(
                    parsed.expiryLabel,
                    EvidenceSource.SET_COOKIE_HEADER,
                    "Value exactly as declared by the server; the inspector does not reinterpret dates.",
                ),
                observedForUrl = Redaction.displayUrl(url, maskSensitiveParams = !policy.revealSensitiveValues),
                sources = setOf(EvidenceSource.SET_COOKIE_HEADER),
                notes = notes,
            )
        )
    }

    private fun buildResponse(
        observation: ResponseObservation,
        policy: CapturePolicy,
        durationMillis: Long?,
    ): ResponseRecord {
        rememberSetCookieHeader(observation.url, observation.headers, policy)
        val status = observation.statusCode
        return ResponseRecord(
            statusCode = if (status != null) {
                InspectorValue.known(status, observation.statusSource)
            } else {
                InspectorValue.unknown(
                    if (observation.failureText != null) {
                        InspectorExplanations.NETWORK_LEVEL_FAILURE
                    } else {
                        "Not available: no status code was reported for this message."
                    }
                )
            },
            reasonPhrase = observation.reasonPhrase?.let {
                InspectorValue.known(it, observation.statusSource)
            } ?: InspectorValue.unknown(InspectorExplanations.REASON_PHRASE_UNAVAILABLE),
            headers = headerFields(observation.headers, policy),
            contentType = observation.contentType?.let {
                InspectorValue.known(it, observation.observedVia)
            } ?: InspectorValue.unknown("No Content-Type was reported for this response."),
            receivedAtMillis = InspectorValue.known(observation.observedAtMillis, observation.timeSource),
            finalUrl = observation.finalUrl?.let { url ->
                InspectorValue.known(displayUrl(url, policy), observation.observedVia)
            } ?: InspectorValue.unknown(
                if (observation.isForMainFrame == true) {
                    "Not reported for this message: the final URL for a main-frame navigation arrives " +
                        "with onPageFinished."
                } else {
                    InspectorExplanations.FINAL_URL_SUBRESOURCE_UNAVAILABLE
                }
            ),
            durationMillis = durationMillis,
            errorDescription = observation.failureText?.let {
                InspectorValue.known(it, observation.observedVia)
            },
            body = observation.body,
        )
    }

    private fun mergeHttpError(
        existing: ResponseRecord?,
        observation: HttpErrorObservation,
        policy: CapturePolicy,
    ): ResponseRecord {
        val headers = headerFields(observation.headers, policy)
        val receivedAt = InspectorValue.known(observation.observedAtMillis, EvidenceSource.APP_CLOCK)
        if (existing == null) {
            return ResponseRecord(
                statusCode = InspectorValue.known(observation.statusCode, EvidenceSource.WEBVIEW_CALLBACK),
                reasonPhrase = observation.reasonPhrase?.let {
                    InspectorValue.known(it, EvidenceSource.WEBVIEW_CALLBACK)
                } ?: InspectorValue.unknown(InspectorExplanations.REASON_PHRASE_UNAVAILABLE),
                headers = headers,
                contentType = headers.firstOrNull { it.name.equals("content-type", ignoreCase = true) }
                    ?.let { InspectorValue.known(it.display, EvidenceSource.WEBVIEW_CALLBACK) }
                    ?: InspectorValue.unknown("No Content-Type was reported for this error response."),
                receivedAtMillis = receivedAt,
                finalUrl = InspectorValue.unknown(InspectorExplanations.FINAL_URL_SUBRESOURCE_UNAVAILABLE),
                durationMillis = null,
            )
        }
        return existing.copy(
            statusCode = if (existing.statusCode.isKnown) {
                existing.statusCode
            } else {
                InspectorValue.known(observation.statusCode, EvidenceSource.WEBVIEW_CALLBACK)
            },
            reasonPhrase = if (existing.reasonPhrase.isKnown) {
                existing.reasonPhrase
            } else {
                observation.reasonPhrase?.let { InspectorValue.known(it, EvidenceSource.WEBVIEW_CALLBACK) }
                    ?: existing.reasonPhrase
            },
            headers = if (existing.headers.isEmpty()) headers else existing.headers,
            receivedAtMillis = if (existing.receivedAtMillis.isKnown) existing.receivedAtMillis else receivedAt,
        )
    }

    private fun withFailure(existing: ResponseRecord?, observation: FailureObservation): ResponseRecord {
        val base = existing ?: emptyResponse(observation.observedAtMillis)
        return base.copy(
            statusCode = base.statusCode,
            errorDescription = InspectorValue.known(
                buildString {
                    append(observation.description)
                    observation.errorCode?.let {
                        append(" (WebResourceError errorCode=")
                        append(it)
                        append(')')
                    }
                },
                EvidenceSource.WEBVIEW_CALLBACK,
            ),
            receivedAtMillis = if (base.receivedAtMillis.isKnown) {
                base.receivedAtMillis
            } else {
                InspectorValue.known(observation.observedAtMillis, EvidenceSource.APP_CLOCK)
            },
        )
    }

    private fun emptyResponse(atMillis: Long): ResponseRecord = ResponseRecord(
        statusCode = InspectorValue.unknown("No HTTP status was observed for this request."),
        reasonPhrase = InspectorValue.unknown(InspectorExplanations.REASON_PHRASE_UNAVAILABLE),
        headers = emptyList(),
        contentType = InspectorValue.unknown("No response was delivered."),
        receivedAtMillis = InspectorValue.known(atMillis, EvidenceSource.APP_CLOCK),
        finalUrl = InspectorValue.unknown(InspectorExplanations.FINAL_URL_SUBRESOURCE_UNAVAILABLE),
        durationMillis = null,
    )

    private fun orphanRequestObservation(observation: ResponseObservation): RequestObservation {
        val inferredStart = if (observation.durationMillis != null) {
            observation.observedAtMillis - observation.durationMillis
        } else {
            observation.observedAtMillis
        }
        return RequestObservation(
            tabId = observation.tabId,
            url = observation.url,
            method = observation.method,
            observedAtMillis = inferredStart,
            timeSource = EvidenceSource.DERIVED,
            isForMainFrame = observation.isForMainFrame,
            isRedirect = null,
            hasGesture = null,
            headers = emptyMap(),
            headersReport = HeadersReport.UNKNOWN,
            // A response carries no initiator of its own, so it is derived from the main-frame flag.
            initiator = if (observation.isForMainFrame == true) {
                Initiator.DOCUMENT_NAVIGATION
            } else {
                Initiator.RESOURCE_LOAD
            },
            observedVia = observation.observedVia,
        )
    }

    /** Combines a response the app already knows with the one that just arrived, keeping the best of both. */
    private fun mergeResponse(existing: ResponseRecord?, incoming: ResponseRecord): ResponseRecord {
        if (existing == null) return incoming
        return existing.copy(
            statusCode = if (existing.statusCode.isKnown) existing.statusCode else incoming.statusCode,
            reasonPhrase = if (existing.reasonPhrase.isKnown) existing.reasonPhrase else incoming.reasonPhrase,
            headers = if (existing.headers.isEmpty()) incoming.headers else existing.headers + extraHeaders(existing, incoming),
            contentType = if (existing.contentType.isKnown) existing.contentType else incoming.contentType,
            receivedAtMillis = if (existing.receivedAtMillis.isKnown) {
                existing.receivedAtMillis
            } else {
                incoming.receivedAtMillis
            },
            finalUrl = if (existing.finalUrl.isKnown) existing.finalUrl else incoming.finalUrl,
            durationMillis = existing.durationMillis ?: incoming.durationMillis,
            errorDescription = existing.errorDescription ?: incoming.errorDescription,
            body = existing.body ?: incoming.body,
        )
    }

    /** Headers present on the incoming message but not on the existing one. */
    private fun extraHeaders(existing: ResponseRecord, incoming: ResponseRecord): List<HttpField> {
        val known = existing.headers.mapTo(HashSet()) { it.name.lowercase() }
        return incoming.headers.filter { it.name.lowercase() !in known }
    }

    // ------------------------------------------------------------------------- page-JS record handling

    private fun handlePageRecord(
        tabId: String,
        bucket: ConcurrentHashMap<Long, Long>,
        record: PageJsRecord,
        policy: CapturePolicy,
    ) {
        if (bucket.size >= InspectorLimits.MAX_TRACKED_REQUEST_IDS) bucket.clear()

        when (record.kind) {
            PageJsRecord.Kind.CONSOLE_ERROR -> handlePageConsoleError(tabId, record, policy)
            PageJsRecord.Kind.REQUEST -> handlePageRequest(tabId, bucket, record, policy)
            PageJsRecord.Kind.RESPONSE -> handlePageResponse(tabId, bucket, record, policy)
            PageJsRecord.Kind.FAILURE -> handlePageFailure(tabId, bucket, record, policy)
        }
    }

    private fun handlePageConsoleError(tabId: String, record: PageJsRecord, policy: CapturePolicy) {
        if (!policy.captureConsole) return
        val message = record.errorText ?: record.url.ifBlank { "Uncaught error" }
        val scrubbed = Redaction.scrubText(
            message,
            InspectorLimits.MAX_CONSOLE_MESSAGE_CHARS,
            policy.revealSensitiveValues,
        )
        val stack = record.stackTrace?.let {
            Redaction.scrubText(it, InspectorLimits.MAX_STACK_TRACE_CHARS, policy.revealSensitiveValues)
        }
        val now = System.currentTimeMillis()

        // WebView usually reports the same uncaught error through onConsoleMessage; attach the stack to
        // that row instead of adding a duplicate.
        if (stack != null && store.attachConsoleStack(scrubbed.text, stack.text, now)) {
            consoleMessagesSinceCheck.incrementAndGet()
            return
        }

        store.addConsole(
            ConsoleEntry(
                id = store.allocateConsoleId(),
                tabId = tabId,
                level = ConsoleLevel.ERROR,
                message = scrubbed.text,
                masked = scrubbed.masked,
                source = record.url.ifBlank { null },
                lineNumber = null,
                timestampMillis = now,
                stackTrace = stack?.text,
                evidence = EvidenceSource.PAGE_JAVASCRIPT,
            )
        )
        consoleMessagesSinceCheck.incrementAndGet()
    }

    private fun handlePageRequest(
        tabId: String,
        bucket: ConcurrentHashMap<Long, Long>,
        record: PageJsRecord,
        policy: CapturePolicy,
    ) {
        val now = System.currentTimeMillis()
        if (!messageBudget.allow(now)) {
            droppedByRate.incrementAndGet()
            return
        }

        val key = keyFor(record.url, record.method, policy)
        val timeSource = if (record.pageTimeMillis != null) EvidenceSource.PAGE_JAVASCRIPT else EvidenceSource.APP_CLOCK
        val startedAt = record.pageTimeMillis ?: now

        val existing = store.findEntry(tabId, key, skipPageObserved = true, maxScan = 60)
        if (existing != null) {
            val updated = store.updateEntry(existing.id) { entry ->
                entry.copy(
                    request = entry.request.copy(
                        headers = mergePageHeaders(entry.request.headers, record.requestHeaders, policy),
                        body = entry.request.body ?: pageBodyRecord(record, policy),
                        initiator = record.initiator ?: entry.request.initiator,
                        headersCompleteness = if (entry.request.headers.isEmpty()) {
                            HeaderCompleteness.FROM_PAGE_JAVASCRIPT
                        } else {
                            entry.request.headersCompleteness
                        },
                    ),
                    pageObserved = true,
                    notes = appendNote(entry.notes, InspectorExplanations.PAGE_JS_OBSERVED),
                )
            }
            if (updated != null) {
                bucket[record.requestId] = updated.id
                return
            }
        }

        val observation = RequestObservation(
            tabId = tabId,
            url = record.url,
            method = record.method,
            observedAtMillis = startedAt,
            timeSource = timeSource,
            isForMainFrame = false,
            isRedirect = null,
            hasGesture = null,
            headers = record.requestHeaders,
            headersReport = HeadersReport.PAGE_JAVASCRIPT,
            initiator = record.initiator,
            observedVia = EvidenceSource.PAGE_JAVASCRIPT,
            body = pageBodyRecord(record, policy),
        )
        val entryId = store.allocateEntryId()
        store.addEntry(
            buildEntry(
                entryId,
                observation,
                policy,
                notes = listOf(InspectorExplanations.PAGE_JS_OBSERVED),
            )
        )
        bucket[record.requestId] = entryId
    }

    private fun handlePageResponse(
        tabId: String,
        bucket: ConcurrentHashMap<Long, Long>,
        record: PageJsRecord,
        policy: CapturePolicy,
    ) {
        val entryId = bucket[record.requestId]
        val response = pageResponseRecord(record, policy)
        val target = if (entryId != null) entryId else findEntryIdForPageRecord(tabId, record, policy)

        if (target != null) {
            val updated = store.updateEntry(target) { entry ->
                entry.copy(
                    response = mergeResponse(entry.response, response),
                    state = if (entry.state == EntryState.FAILED) EntryState.FAILED else EntryState.RECEIVED,
                    request = refineCategory(entry.request, record.contentType),
                    pageObserved = true,
                )
            }
            if (updated != null) {
                bucket[record.requestId] = updated.id
                return
            }
        }

        val observation = RequestObservation(
            tabId = tabId,
            url = record.url,
            method = record.method,
            observedAtMillis = record.pageTimeMillis ?: System.currentTimeMillis(),
            timeSource = if (record.pageTimeMillis != null) EvidenceSource.PAGE_JAVASCRIPT else EvidenceSource.APP_CLOCK,
            isForMainFrame = false,
            isRedirect = null,
            hasGesture = null,
            headers = record.requestHeaders,
            headersReport = HeadersReport.PAGE_JAVASCRIPT,
            initiator = record.initiator,
            observedVia = EvidenceSource.PAGE_JAVASCRIPT,
            body = pageBodyRecord(record, policy),
        )
        val newId = store.allocateEntryId()
        val entry = buildEntry(
            newId,
            observation,
            policy,
            notes = listOf(InspectorExplanations.PAGE_JS_OBSERVED, InspectorExplanations.ORPHAN_RESPONSE),
        )
        store.addEntry(entry.copy(response = response, state = EntryState.RECEIVED, pageObserved = true))
        bucket[record.requestId] = newId
    }

    private fun handlePageFailure(
        tabId: String,
        bucket: ConcurrentHashMap<Long, Long>,
        record: PageJsRecord,
        policy: CapturePolicy,
    ) {
        val entryId = bucket[record.requestId]
        val target = if (entryId != null) entryId else findEntryIdForPageRecord(tabId, record, policy)
        val failureText = record.errorText ?: "The page's fetch/XHR call failed."

        if (target != null) {
            val updated = store.updateEntry(target) { entry ->
                val base = entry.response ?: emptyResponse(entry.request.startedAtMillis)
                entry.copy(
                    response = base.copy(
                        errorDescription = InspectorValue.known(
                            Redaction.scrubText(failureText, 500, policy.revealSensitiveValues).text,
                            EvidenceSource.PAGE_JAVASCRIPT,
                        ),
                        receivedAtMillis = InspectorValue.known(
                            record.responseTimeMillis ?: System.currentTimeMillis(),
                            if (record.responseTimeMillis != null) EvidenceSource.PAGE_JAVASCRIPT else EvidenceSource.APP_CLOCK,
                        ),
                    ),
                    state = EntryState.FAILED,
                    notes = appendNote(
                        entry.notes,
                        record.stackTrace?.let { stack ->
                            val text = Redaction
                                .scrubText(stack, 600, policy.revealSensitiveValues)
                                .text
                            "Stack (from window.onerror): $text"
                        },
                    ),
                )
            }
            if (updated != null) {
                bucket[record.requestId] = updated.id
                return
            }
        }

        val observation = RequestObservation(
            tabId = tabId,
            url = record.url,
            method = record.method,
            observedAtMillis = record.pageTimeMillis ?: System.currentTimeMillis(),
            timeSource = if (record.pageTimeMillis != null) EvidenceSource.PAGE_JAVASCRIPT else EvidenceSource.APP_CLOCK,
            isForMainFrame = false,
            isRedirect = null,
            hasGesture = null,
            headers = record.requestHeaders,
            headersReport = HeadersReport.PAGE_JAVASCRIPT,
            initiator = record.initiator,
            observedVia = EvidenceSource.PAGE_JAVASCRIPT,
            body = pageBodyRecord(record, policy),
        )
        val newId = store.allocateEntryId()
        val entry = buildEntry(
            newId,
            observation,
            policy,
            notes = listOf(InspectorExplanations.PAGE_JS_OBSERVED, InspectorExplanations.ORPHAN_RESPONSE),
        )
        store.addEntry(
            entry.copy(
                response = emptyResponse(entry.request.startedAtMillis).copy(
                    errorDescription = InspectorValue.known(
                        Redaction.scrubText(failureText, 500, policy.revealSensitiveValues).text,
                        EvidenceSource.PAGE_JAVASCRIPT,
                    ),
                ),
                state = EntryState.FAILED,
                pageObserved = true,
            )
        )
        bucket[record.requestId] = newId
    }

    private fun findEntryIdForPageRecord(tabId: String, record: PageJsRecord, policy: CapturePolicy): Long? =
        store.findEntry(
            tabId = tabId,
            key = keyFor(record.url, record.method, policy),
            skipPageObserved = false,
            maxScan = 60,
        )?.id

    private fun pageBodyRecord(record: PageJsRecord, policy: CapturePolicy): BodyRecord? {
        val preview = record.bodyPreview
        if (preview == null) {
            return null
        }
        if (!policy.captureRequestBodies) {
            return BodyRecord(
                preview = InspectorValue.unknown(InspectorExplanations.REQUEST_BODY_NOT_CAPTURED),
                kind = record.bodyKind,
                reportedLength = record.bodyLength,
                truncated = record.bodyTruncated,
            )
        }
        val scrubbed = Redaction.scrubText(
            preview,
            InspectorLimits.MAX_BODY_PREVIEW_CHARS,
            policy.revealSensitiveValues,
        )
        return BodyRecord(
            preview = InspectorValue.known(scrubbed.text, EvidenceSource.PAGE_JAVASCRIPT),
            kind = record.bodyKind,
            reportedLength = record.bodyLength,
            truncated = record.bodyTruncated || scrubbed.text.length < preview.length,
        )
    }

    private fun pageResponseRecord(record: PageJsRecord, policy: CapturePolicy): ResponseRecord {
        val status = record.statusCode
        val timeSource = if (record.responseTimeMillis != null) {
            EvidenceSource.PAGE_JAVASCRIPT
        } else {
            EvidenceSource.APP_CLOCK
        }
        val responseBodyKind = bodyKindFromContentType(record.contentType)
        val responseBody = if (record.responseBodyPreview != null && policy.captureResponseBodies) {
            val scrubbed = Redaction.scrubText(
                record.responseBodyPreview,
                InspectorLimits.MAX_BODY_PREVIEW_CHARS,
                policy.revealSensitiveValues,
            )
            BodyRecord(
                preview = InspectorValue.known(scrubbed.text, EvidenceSource.PAGE_JAVASCRIPT),
                kind = responseBodyKind,
                reportedLength = null,
                truncated = record.responseBodyTruncated || scrubbed.text.length < record.responseBodyPreview.length,
            )
        } else if (record.responseBodyPreview != null) {
            BodyRecord(
                preview = InspectorValue.unknown(InspectorExplanations.RESPONSE_BODY_UNAVAILABLE),
                kind = responseBodyKind,
                reportedLength = null,
                truncated = record.responseBodyTruncated,
            )
        } else {
            null
        }

        return ResponseRecord(
            statusCode = if (status != null) {
                InspectorValue.known(status, EvidenceSource.PAGE_JAVASCRIPT)
            } else {
                InspectorValue.unknown("The page's hook reported no status for this response.")
            },
            reasonPhrase = record.statusText?.let {
                InspectorValue.known(it, EvidenceSource.PAGE_JAVASCRIPT)
            } ?: InspectorValue.unknown(InspectorExplanations.REASON_PHRASE_UNAVAILABLE),
            headers = headerFields(record.responseHeaders, policy),
            contentType = record.contentType?.let {
                InspectorValue.known(it, EvidenceSource.PAGE_JAVASCRIPT)
            } ?: record.responseHeaders.entries
                .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                ?.let { InspectorValue.known(it.value, EvidenceSource.PAGE_JAVASCRIPT) }
                ?: InspectorValue.unknown("No Content-Type was reported for this response."),
            receivedAtMillis = InspectorValue.known(
                record.responseTimeMillis ?: System.currentTimeMillis(),
                timeSource,
            ),
            finalUrl = record.finalUrl?.let {
                InspectorValue.known(displayUrl(it, policy), EvidenceSource.PAGE_JAVASCRIPT)
            } ?: InspectorValue.unknown(
                "Not available: the page's hook reported no final URL (fetch exposes it only through " +
                    "response.url, which the hook reports when it can)."
            ),
            durationMillis = record.durationMillis,
            body = responseBody,
        )
    }

    private fun refineCategory(request: RequestRecord, contentType: String?): RequestRecord {
        val refined = ResourceClassifier.refineWithResponse(
            ResourceClassifier.Classification(request.category, request.categorySource, request.categoryDetail),
            contentType,
        )
        return request.copy(
            category = refined.category,
            categorySource = refined.source,
            categoryDetail = refined.detail,
        )
    }

    private fun mergePageHeaders(
        existing: List<HttpField>,
        pageHeaders: Map<String, String>,
        policy: CapturePolicy,
    ): List<HttpField> {
        if (pageHeaders.isEmpty()) return existing
        val known = existing.mapTo(HashSet()) { it.name.lowercase() }
        val additions = pageHeaders
            .filterKeys { it.lowercase() !in known }
            .map { (name, value) ->
                Redaction.headerField(
                    name = name,
                    value = value,
                    revealSensitiveValues = policy.revealSensitiveValues,
                    extraNote = "Reported by the page's fetch()/XMLHttpRequest call; the browser can add " +
                        "or change headers after that point.",
                )
            }
        return (existing + additions).sortedBy { it.name.lowercase() }
    }

    // ------------------------------------------------------------------------------------ utilities

    private fun displayUrl(url: String, policy: CapturePolicy): String =
        Redaction.displayUrl(url, maskSensitiveParams = !policy.revealSensitiveValues)

    private fun keyFor(url: String, method: String, policy: CapturePolicy): String =
        UrlParts.correlationKey(displayUrl(url, policy), method)

    private fun headerFields(headers: Map<String, String>, policy: CapturePolicy): List<HttpField> {
        if (headers.isEmpty()) return emptyList()
        val fields = ArrayList<HttpField>(headers.size.coerceAtMost(InspectorLimits.MAX_HEADERS_PER_MESSAGE) + 1)
        var dropped = 0
        for ((name, value) in headers) {
            if (fields.size >= InspectorLimits.MAX_HEADERS_PER_MESSAGE) {
                dropped++
                continue
            }
            fields.add(Redaction.headerField(name, value, policy.revealSensitiveValues))
        }
        val sorted = fields.sortedBy { it.name.lowercase() }
        if (dropped == 0) return sorted
        // Never silently shorten the list: say exactly how many headers the inspector dropped.
        return sorted + HttpField(
            name = "(headers omitted)",
            display = "$dropped header(s) exceeded the inspector's per-message cap of " +
                "${InspectorLimits.MAX_HEADERS_PER_MESSAGE} and were not stored.",
            redaction = RedactionKind.NEVER_STORED,
        )
    }

    private fun bodyKindFromContentType(contentType: String?): BodyKind {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return BodyKind.UNKNOWN
        return when {
            mime.contains("json") -> BodyKind.JSON
            mime.startsWith("text/") -> BodyKind.TEXT
            mime.contains("xml") -> BodyKind.TEXT
            mime.contains("javascript") -> BodyKind.TEXT
            mime.contains("form-urlencoded") -> BodyKind.FORM_URLENCODED
            mime.contains("multipart") -> BodyKind.MULTIPART_FORM_DATA
            mime.isEmpty() -> BodyKind.UNKNOWN
            else -> BodyKind.BINARY
        }
    }

    private fun loweredHeaders(headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return emptyMap()
        val lowered = HashMap<String, String>(headers.size)
        for ((name, value) in headers) {
            lowered[name.lowercase()] = value
        }
        return lowered
    }

    private fun appendNote(notes: List<String>, note: String?): List<String> {
        if (note == null) return notes
        if (notes.contains(note)) return notes
        return notes + note
    }

    private fun ambiguityNote(ambiguous: Boolean): String? =
        if (ambiguous) InspectorExplanations.AMBIGUOUS_CORRELATION else null

    private fun documentBucketKey(tabId: String, documentId: String?): String =
        tabId + "/" + (documentId ?: "unknown")

    private fun requestIdBucket(bucketKey: String): ConcurrentHashMap<Long, Long> {
        if (requestIdBuckets.size >= InspectorLimits.MAX_TRACKED_DOCUMENTS && !requestIdBuckets.containsKey(bucketKey)) {
            synchronized(requestIdBuckets) {
                val keys = requestIdBuckets.keys.toList()
                val excess = keys.size - (InspectorLimits.MAX_TRACKED_DOCUMENTS - 1)
                for (index in 0 until excess.coerceAtLeast(0)) {
                    requestIdBuckets.remove(keys[index])
                }
            }
        }
        return requestIdBuckets.computeIfAbsent(bucketKey) { ConcurrentHashMap() }
    }

    // ------------------------------------------------------------------------------- correlation

    private class PendingRequest(
        val entryId: Long,
        val tabId: String,
        val correlationKey: String,
        val urlKey: String,
        /** When the app registered the pending request, always on the app clock. */
        val recordedAtAppMillis: Long,
        /** True when the request timestamp came from the app clock (so durations are measurable). */
        val appClock: Boolean,
        /** True when the pending request came from a WebView callback rather than from page JS. */
        val nativeObserved: Boolean,
    ) {
        @Volatile
        var live: Boolean = true
    }

    private class PendingMatch(val pending: PendingRequest, val ambiguous: Boolean)

    private fun addPending(pending: PendingRequest) {
        addToIndex(pendingByKey, pending.correlationKey, pending)
        addToIndex(pendingByUrl, pending.urlKey, pending)

        val tabQueue = pendingByTab.computeIfAbsent(pending.tabId) { ArrayDeque() }
        var overflow: PendingRequest? = null
        synchronized(tabQueue) {
            tabQueue.addLast(pending)
            if (tabQueue.size > InspectorLimits.MAX_PENDING_CORRELATIONS) {
                overflow = tabQueue.removeFirst()
            }
        }
        overflow?.let { retire(it) }
    }

    private fun addToIndex(
        index: ConcurrentHashMap<String, ArrayDeque<PendingRequest>>,
        key: String,
        pending: PendingRequest,
    ) {
        val queue = index.computeIfAbsent(key) { ArrayDeque() }
        var drop: PendingRequest? = null
        synchronized(queue) {
            queue.addLast(pending)
            if (queue.size > MAX_PENDING_PER_KEY) drop = queue.removeFirst()
        }
        drop?.let { retire(it) }
    }

    private fun retire(pending: PendingRequest) {
        pending.live = false
        removeFromIndex(pendingByKey, pending.correlationKey, pending)
        removeFromIndex(pendingByUrl, pending.urlKey, pending)
        removeFromIndex(pendingByTab, pending.tabId, pending)
    }

    private fun removeFromIndex(
        index: ConcurrentHashMap<String, ArrayDeque<PendingRequest>>,
        key: String,
        pending: PendingRequest,
    ) {
        val queue = index[key] ?: return
        var empty = false
        synchronized(queue) {
            queue.remove(pending)
            empty = queue.isEmpty()
        }
        if (empty) index.remove(key, queue)
    }

    private fun takePending(
        key: String,
        urlKey: String,
        preferNative: Boolean,
        nowAppMillis: Long,
    ): PendingMatch? {
        extractPending(pendingByKey, key, preferNative, nowAppMillis)?.let { return it }
        return extractPending(pendingByUrl, urlKey, preferNative, nowAppMillis)
    }

    private fun extractPending(
        index: ConcurrentHashMap<String, ArrayDeque<PendingRequest>>,
        key: String,
        preferNative: Boolean,
        nowAppMillis: Long,
    ): PendingMatch? {
        val queue = index[key] ?: return null
        val candidates = ArrayList<PendingRequest>(4)
        synchronized(queue) {
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (!candidate.live ||
                    nowAppMillis - candidate.recordedAtAppMillis > InspectorLimits.CORRELATION_WINDOW_MS
                ) {
                    iterator.remove()
                    candidate.live = false
                    continue
                }
                candidates.add(candidate)
            }
        }
        if (candidates.isEmpty()) {
            index.remove(key, queue)
            return null
        }
        // Prefer a pending request created by the same kind of observation as the response (a WebView
        // response should pair with a WebView request), otherwise take the most recent one.
        val chosen = candidates.lastOrNull { it.nativeObserved == preferNative } ?: candidates.last()
        retire(chosen)
        return PendingMatch(chosen, ambiguous = candidates.size > 1)
    }

    private fun clearPendingForTab(tabId: String) {
        val queue = pendingByTab.remove(tabId) ?: return
        val snapshot = ArrayList<PendingRequest>(queue.size)
        synchronized(queue) { snapshot.addAll(queue) }
        snapshot.forEach { retire(it) }
    }

    /**
     * Forgets every in-flight correlation.
     *
     * Correlation keys are built from the URL *as the inspector stores it*, so a request that was
     * still waiting when the masking policy changed could never be paired with its response. Called by
     * [InspectorController.setSetting] when the reveal switch is toggled; the rows already captured
     * keep the form they were stored in, and the next response simply arrives as a new row.
     */
    fun clearPendingCorrelations() {
        // addPending registers every request in all three indexes, so walking the per-tab index sees
        // each of them exactly once.
        pendingByTab.values.forEach { queue ->
            val snapshot = ArrayList<PendingRequest>(queue.size)
            synchronized(queue) { snapshot.addAll(queue) }
            snapshot.forEach { it.live = false }
        }
        pendingByKey.clear()
        pendingByUrl.clear()
        pendingByTab.clear()
    }

    /** `HTTP ERROR 404` style titles are the only status signal for responses the app does not intercept. */
    fun httpStatusFromErrorPageTitle(title: String?): Int? {
        val text = title?.trim() ?: return null
        val match = HTTP_ERROR_TITLE_PATTERN.find(text) ?: return null
        val code = match.groupValues[1].toIntOrNull() ?: return null
        return if (code in 400..599) code else null
    }

    /** Per-second token bucket. Lock-free in the common case; the rollover takes a short lock. */
    private class SecondBudget(private val maxPerSecond: Int) {
        private val windowStartMillis = AtomicLong(0L)
        private val usedInWindow = AtomicInteger(0)

        fun allow(nowMillis: Long): Boolean {
            if (nowMillis - windowStartMillis.get() >= WINDOW_MS) {
                synchronized(this) {
                    if (nowMillis - windowStartMillis.get() >= WINDOW_MS) {
                        windowStartMillis.set(nowMillis)
                        usedInWindow.set(0)
                    }
                }
            }
            return usedInWindow.incrementAndGet() <= maxPerSecond
        }

        private companion object {
            const val WINDOW_MS = 1_000L
        }
    }

    private companion object {
        /** In-flight requests kept per URL; more than this means something never completes. */
        const val MAX_PENDING_PER_KEY = 16

        /** How long a request may wait for a response before it is marked unobserved. */
        const val PENDING_EXPIRY_MS = 20_000L

        /** Minimum spacing between expiry sweeps. */
        const val EXPIRY_SWEEP_INTERVAL_MS = 2_000L

        val HTTP_ERROR_TITLE_PATTERN = Regex("(?i)^http\\s*error\\s+(\\d{3})\\b")
    }
}
