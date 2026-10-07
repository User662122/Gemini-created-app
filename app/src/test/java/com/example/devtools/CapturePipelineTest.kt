package com.example.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end tests of the capture pipeline.
 *
 * `LiveNetworkObserver` deliberately depends on nothing from `android.*` (no Context, no WebView), so
 * the correlation logic — the part most likely to silently produce wrong pairings — runs here without
 * a device. Robolectric is needed only because the page-hook batches are parsed with `org.json`, which
 * the plain JVM unit-test runtime stubs out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CapturePipelineTest {

    private val fullPolicy = CapturePolicy(
        enabled = true,
        rawCapture = true,
        captureRequestBodies = true,
        captureResponseBodies = true,
        captureConsole = true,
        captureConsoleVerbose = true,
    )

    private fun newObserver(policy: CapturePolicy = fullPolicy): LiveNetworkObserver =
        LiveNetworkObserver { policy }

    private fun requestObservation(
        url: String,
        method: String = "GET",
        tabId: String = "tab",
        headers: Map<String, String> = emptyMap(),
        isMainFrame: Boolean = false,
    ) = RequestObservation(
        tabId = tabId,
        url = url,
        method = method,
        observedAtMillis = System.currentTimeMillis(),
        timeSource = EvidenceSource.APP_CLOCK,
        isForMainFrame = isMainFrame,
        isRedirect = false,
        hasGesture = null,
        headers = headers,
        headersReport = HeadersReport.WEBVIEW_SUBSET,
        initiator = if (isMainFrame) Initiator.DOCUMENT_NAVIGATION else Initiator.RESOURCE_LOAD,
        observedVia = EvidenceSource.WEBVIEW_CALLBACK,
    )

    private fun responseObservation(
        url: String,
        method: String = "GET",
        status: Int? = 200,
        reasonPhrase: String? = null,
        headers: Map<String, String> = emptyMap(),
        contentType: String? = "application/json",
        tabId: String = "tab",
        isMainFrame: Boolean = false,
    ) = ResponseObservation(
        tabId = tabId,
        url = url,
        method = method,
        statusCode = status,
        reasonPhrase = reasonPhrase,
        statusSource = EvidenceSource.WEBVIEW_CALLBACK,
        headers = headers,
        headersReport = HeadersReport.WEBVIEW_SUBSET,
        contentType = contentType,
        observedAtMillis = System.currentTimeMillis(),
        timeSource = EvidenceSource.APP_CLOCK,
        durationMillis = null,
        isForMainFrame = isMainFrame,
        observedVia = EvidenceSource.WEBVIEW_CALLBACK,
    )

    private fun entries(observer: LiveNetworkObserver) = observer.store.snapshot(0L).entries

    @Test
    fun `a request and its response become one entry`() {
        val observer = newObserver()

        observer.onRequestStarted(requestObservation("https://example.com/api/items", "POST"))
        observer.onResponseReceived(responseObservation("https://example.com/api/items", "POST", status = 201))

        val snapshot = entries(observer)
        assertEquals(1, snapshot.size)
        assertEquals(EntryState.RECEIVED, snapshot[0].state)
        assertEquals(201, snapshot[0].statusCode)
    }

    @Test
    fun `an unmatched response is still stored, and says the request was not observed`() {
        val observer = newObserver()

        observer.onResponseReceived(responseObservation("https://example.com/only-a-response.png"))

        val snapshot = entries(observer)
        assertEquals(1, snapshot.size)
        assertEquals(EntryState.RECEIVED, snapshot[0].state)
        assertTrue(snapshot[0].notes.any { it == InspectorExplanations.ORPHAN_RESPONSE })
    }

    @Test
    fun `safe mode masks a token in the stored URL`() {
        val observer = newObserver(fullPolicy.copy(rawCapture = false))

        observer.onRequestStarted(
            requestObservation("https://example.com/api?access_token=supersecret&page=2")
        )

        val storedUrl = entries(observer)[0].url
        assertFalse(storedUrl.contains("supersecret"))
        assertTrue(storedUrl.contains("page=2"))
        assertTrue(storedUrl.contains(Redaction.MASK))
    }

    @Test
    fun `sensitive request headers are masked at capture time`() {
        val observer = newObserver(fullPolicy.copy(rawCapture = false))

        observer.onRequestStarted(
            requestObservation(
                url = "https://example.com/api",
                headers = mapOf("Authorization" to "Bearer abcdefghijklmnop", "Accept" to "*/*"),
            )
        )

        val headers = entries(observer)[0].request.headers
        val auth = headers.first { it.name == "Authorization" }
        assertTrue(auth.isRedacted)
        assertNull(auth.raw)
        assertFalse(auth.display.contains("abcdefghijklmnop"))
        assertEquals(RedactionKind.NONE, headers.first { it.name == "Accept" }.redaction)
    }

    @Test
    fun `an HTTP error fills the pending entry instead of adding a second row`() {
        val observer = newObserver()
        val url = "https://example.com/missing.js"

        observer.onRequestStarted(requestObservation(url))
        observer.onHttpError(
            HttpErrorObservation(
                tabId = "tab",
                url = url,
                method = "GET",
                statusCode = 404,
                reasonPhrase = "Not Found",
                headers = emptyMap(),
                isForMainFrame = false,
                observedAtMillis = System.currentTimeMillis(),
            )
        )

        val snapshot = entries(observer)
        assertEquals(1, snapshot.size)
        assertEquals(404, snapshot[0].statusCode)
        assertTrue(snapshot[0].notes.any { it == InspectorExplanations.STATUS_FROM_HTTP_ERROR_CHANNEL })
    }

    @Test
    fun `a main frame error page title infers the status it cannot otherwise report`() {
        val observer = newObserver()
        val url = "https://example.com/gone"

        observer.onRequestStarted(requestObservation(url, isMainFrame = true))
        observer.onDocumentFinished("tab", url, "HTTP ERROR 410")

        val snapshot = entries(observer)
        assertEquals(1, snapshot.size)
        assertEquals(410, snapshot[0].statusCode)
        assertTrue(snapshot[0].response!!.statusCode.isInferred)
        assertEquals(EvidenceSource.DERIVED, snapshot[0].response!!.statusCode.source)
    }

    @Test
    fun `a normal page title never invents a status`() {
        val observer = newObserver()
        val url = "https://example.com/ok"

        observer.onRequestStarted(requestObservation(url, isMainFrame = true))
        observer.onDocumentFinished("tab", url, "Example Domain")

        val snapshot = entries(observer)
        assertEquals(1, snapshot.size)
        assertNull(snapshot[0].statusCode)
    }

    @Test
    fun `a network failure marks the entry failed with the WebResourceError text`() {
        val observer = newObserver()
        val url = "https://example.com/unreachable"

        observer.onRequestStarted(requestObservation(url))
        observer.onRequestFailed(
            FailureObservation(
                tabId = "tab",
                url = url,
                description = "net::ERR_NAME_NOT_RESOLVED",
                errorCode = -105,
                isForMainFrame = false,
                method = "GET",
                observedAtMillis = System.currentTimeMillis(),
            )
        )

        val entry = entries(observer)[0]
        assertEquals(EntryState.FAILED, entry.state)
        assertTrue(entry.response!!.errorDescription!!.value!!.contains("ERR_NAME_NOT_RESOLVED"))
    }

    @Test
    fun `page JavaScript details merge into the WebView entry instead of duplicating it`() {
        val observer = newObserver()
        val url = "https://example.com/api/submit"

        observer.onRequestStarted(requestObservation(url, "POST"))
        observer.onPageRecords("tab", jsRequestBatch(id = 1, url = url, method = "POST"))

        val snapshot = entries(observer)
        assertEquals("the same request must not be listed twice", 1, snapshot.size)
        val entry = snapshot[0]
        assertTrue(entry.pageObserved)
        assertEquals(BodyKind.JSON, entry.request.body?.kind)
        assertTrue(entry.request.body?.preview?.value?.contains("\"a\":1") == true)
        assertTrue(entry.request.headers.any { it.name.equals("content-type", true) })
        assertTrue(entry.notes.any { it == InspectorExplanations.PAGE_JS_OBSERVED })
    }

    @Test
    fun `a request only seen by the page hook is still recorded with its origin`() {
        val observer = newObserver()
        val url = "https://example.com/analytics"

        observer.onPageRecords("tab", jsRequestBatch(id = 7, url = url, method = "POST"))

        val entry = entries(observer)[0]
        assertEquals(HeaderCompleteness.FROM_PAGE_JAVASCRIPT, entry.request.headersCompleteness)
        assertTrue(entry.notes.any { it == InspectorExplanations.PAGE_JS_OBSERVED })
        assertTrue(entry.pageObserved)
    }

    @Test
    fun `a page response pairs with its request through the page request id`() {
        val observer = newObserver()
        val url = "https://example.com/api/upload"

        observer.onPageRecords("tab", jsRequestBatch(id = 42, url = url, method = "POST"))
        observer.onPageRecords("tab", jsResponseBatch(id = 42, url = url, method = "POST", status = 500))

        val snapshot = entries(observer)
        assertEquals(1, snapshot.size)
        assertEquals(500, snapshot[0].statusCode)
        assertEquals(EvidenceSource.PAGE_JAVASCRIPT, snapshot[0].response!!.statusCode.source)
    }

    @Test
    fun `console lines are scrubbed, merged and counted`() {
        val observer = newObserver()

        observer.onConsoleMessage("tab", ConsoleLevel.LOG, "token=abcdef123456", "app.js", 12, null)
        observer.onConsoleMessage("tab", ConsoleLevel.LOG, "token=abcdef123456", "app.js", 12, null)
        observer.onConsoleMessage("tab", ConsoleLevel.ERROR, "boom", "app.js", 40, null)

        val console = observer.store.snapshot(0L).console
        assertEquals(2, console.size)
        val merged = console.first { it.level == ConsoleLevel.LOG }
        assertEquals(2, merged.repeatCount)
        assertFalse(merged.message.contains("abcdef123456"))
        assertTrue(merged.masked)
    }

    @Test
    fun `verbose console lines are dropped unless verbose capture is on`() {
        val observer = newObserver(fullPolicy.copy(captureConsoleVerbose = false))

        observer.onConsoleMessage("tab", ConsoleLevel.VERBOSE, "very chatty", null, null, null)

        assertTrue(observer.store.snapshot(0L).console.isEmpty())
    }

    @Test
    fun `a console storm is rate limited and the drops are counted`() {
        val observer = newObserver()

        repeat(InspectorLimits.MAX_CONSOLE_MESSAGES_PER_SECOND + 25) { index ->
            observer.onConsoleMessage("tab", ConsoleLevel.LOG, "burst $index", null, null, null)
        }

        assertTrue(observer.droppedByRateLimit() >= 25)
        assertTrue(observer.store.consoleCount() <= InspectorLimits.MAX_CONSOLE_MESSAGES_PER_SECOND)
    }

    @Test
    fun `the ring buffer evicts oldest rows and reports how many were dropped`() {
        val observer = newObserver()
        val store = observer.store
        val now = System.currentTimeMillis()

        // Written straight into the store: this asserts the buffer's own limits, not the observer's
        // message rate limit (which is covered by the test below).
        repeat(InspectorLimits.MAX_ENTRIES + 10) { index ->
            store.addEntry(
                networkEntry(
                    id = store.allocateEntryId(),
                    url = "https://example.com/item/$index",
                    recordedAtAppMillis = now + index,
                )
            )
        }

        assertEquals(InspectorLimits.MAX_ENTRIES, store.entryCount())
        assertEquals(10, store.droppedCounts().first)
        // Newest first, and the oldest rows are the ones that went away.
        assertEquals("https://example.com/item/${InspectorLimits.MAX_ENTRIES + 9}", entries(observer)[0].url)
        assertTrue(entries(observer).none { it.url == "https://example.com/item/0" })
    }

    @Test
    fun `a request storm is rate limited and the excess is counted, not queued`() {
        val observer = newObserver()

        repeat(InspectorLimits.MAX_MESSAGES_PER_SECOND + 290) { index ->
            observer.onRequestStarted(requestObservation("https://example.com/storm/$index"))
        }

        // A page cannot push the app into unbounded work: the per-second budget caps what is stored,
        // and everything above it is dropped and reported.
        assertTrue(observer.store.entryCount() <= InspectorLimits.MAX_MESSAGES_PER_SECOND)
        assertTrue(observer.droppedByRateLimit() >= 200)
    }

    @Test
    fun `identical concurrent requests record that the pairing was ambiguous`() {
        val observer = newObserver()
        val url = "https://example.com/poll"

        observer.onRequestStarted(requestObservation(url))
        observer.onRequestStarted(requestObservation(url))
        observer.onResponseReceived(responseObservation(url))

        val matched = entries(observer).first { it.state == EntryState.RECEIVED }
        assertTrue(matched.notes.any { it == InspectorExplanations.AMBIGUOUS_CORRELATION })
    }

    @Test
    fun `a Set-Cookie header yields a cookie row with real attributes`() {
        val observer = newObserver()

        observer.onRequestStarted(requestObservation("https://shop.example.com/checkout"))
        observer.onResponseReceived(
            responseObservation(
                url = "https://shop.example.com/checkout",
                headers = mapOf(
                    "Set-Cookie" to "sid=abc123; Domain=.example.com; Path=/shop; Secure; HttpOnly; Max-Age=3600"
                ),
                contentType = "text/html",
            )
        )

        val cookie = observer.store.cookieObservations().single()
        assertEquals("sid", cookie.name)
        assertEquals("example.com", cookie.domain.value)
        assertEquals("/shop", cookie.path.value)
        assertEquals(true, cookie.secure.value)
        assertEquals(true, cookie.httpOnly.value)
        assertTrue(cookie.expiration.value!!.contains("3600"))
        assertFalse(cookie.value.value!!.contains("abc123"))
    }

    @Test
    fun `requests that never receive a callback are marked unobserved, not failed`() {
        val observer = newObserver()
        val store = observer.store
        val now = System.currentTimeMillis()

        store.addEntry(
            networkEntry(
                id = store.allocateEntryId(),
                url = "https://example.com/cached.png",
                category = ResourceCategory.IMAGE,
                recordedAtAppMillis = now - 60_000,
                startedAtMillis = now - 60_000,
            )
        )

        store.expirePendingEntries(now - 20_000, InspectorExplanations.RESPONSE_NEVER_OBSERVED)

        val entry = store.snapshot(0L).entries.single()
        assertEquals(EntryState.UNOBSERVED, entry.state)
        assertTrue(entry.notes.contains(InspectorExplanations.RESPONSE_NEVER_OBSERVED))
    }

    private fun networkEntry(
        id: Long,
        url: String,
        category: ResourceCategory = ResourceCategory.XHR_FETCH,
        recordedAtAppMillis: Long = System.currentTimeMillis(),
        startedAtMillis: Long = System.currentTimeMillis(),
    ): NetworkEntry = NetworkEntry(
        id = id,
        tabId = "tab",
        request = RequestRecord(
            url = url,
            method = "GET",
            startedAtMillis = startedAtMillis,
            startedAtSource = EvidenceSource.APP_CLOCK,
            isForMainFrame = false,
            isRedirect = null,
            hasGesture = null,
            headers = emptyList(),
            headersCompleteness = HeaderCompleteness.SUBSET_REPORTED_BY_WEBVIEW,
            body = null,
            category = category,
            categorySource = EvidenceSource.DERIVED,
            categoryDetail = null,
            initiator = Initiator.RESOURCE_LOAD,
        ),
        recordedAtAppMillis = recordedAtAppMillis,
    )

    @Test
    fun `the observer does nothing at all when capture is disabled`() {
        val observer = newObserver(CapturePolicy.DISABLED)

        observer.onRequestStarted(requestObservation("https://example.com/x"))
        observer.onConsoleMessage("tab", ConsoleLevel.LOG, "hello", null, null, null)

        assertFalse(observer.isActive)
        assertEquals(0, observer.store.entryCount())
        assertTrue(observer.store.snapshot(0L).console.isEmpty())
    }

    @Test
    fun `the injected script respects the body switches it is built with`() {
        val withoutBodies = InspectorScripts.build(captureRequestBodies = false, captureResponseBodies = false)
        val withBodies = InspectorScripts.build(captureRequestBodies = true, captureResponseBodies = true)

        assertTrue(withoutBodies.contains("bodies: false"))
        assertTrue(withBodies.contains("bodies: true"))
        assertTrue(withBodies.contains("responses: true"))
        assertFalse(withoutBodies.contains("__BODIES__"))
    }

    @Test
    fun `the report never leaks a secret that safe mode masked`() {
        val observer = newObserver(fullPolicy.copy(rawCapture = false))
        observer.onRequestStarted(
            requestObservation(
                url = "https://example.com/api?access_token=supersecret",
                headers = mapOf("Authorization" to "Bearer abcdefghijklmnop"),
            )
        )

        val report = InspectorReport.entryReport(entries(observer)[0], rawCapture = false)

        assertFalse(report.contains("supersecret"))
        assertFalse(report.contains("abcdefghijklmnop"))
        assertTrue(report.contains("not available") || report.contains("masked"))
    }

    private fun jsRequestBatch(id: Long, url: String, method: String): String =
        """{"doc":"doc-1","r":[{"k":"req","id":$id,"url":"$url","m":"$method","t":1759800000000,""" +
            """"h":{"content-type":"application/json"},"b":"{\"a\":1}","bk":"json","bl":7,"i":"fetch"}]}"""

    private fun jsResponseBatch(id: Long, url: String, method: String, status: Int): String =
        """{"doc":"doc-1","r":[{"k":"res","id":$id,"url":"$url","m":"$method","t":1759800000500,""" +
            """"st":$status,"sx":"Server Error","rt":1759800000500,"du":100,""" +
            """"rh":{"content-type":"application/json"},"ct":"application/json"}]}"""
}
