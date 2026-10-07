package com.example.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPartsTest {

    @Test
    fun `scheme and host are parsed defensively`() {
        assertEquals("https", UrlParts.scheme("https://example.com/a/b?c=1"))
        assertEquals("example.com", UrlParts.host("https://user:pw@example.com:8443/a"))
        assertEquals("[::1]", UrlParts.host("http://[::1]:8080/x"))
        assertNull(UrlParts.scheme("not a url"))
        assertNull(UrlParts.host("file:///android_asset/index.html"))
        assertEquals("example.com", UrlParts.host("HTTPS://EXAMPLE.com/Path"))
    }

    @Test
    fun `path and extension come out of the URL`() {
        assertEquals("/a/b", UrlParts.path("https://example.com/a/b?q=1#frag"))
        assertEquals("js", UrlParts.fileExtension("https://cdn.example.com/app.main.js?v=3"))
        assertEquals("png", UrlParts.fileExtension("https://example.com/i/logo.PNG"))
        assertNull(UrlParts.fileExtension("https://example.com/api/v1/items"))
    }

    @Test
    fun `origin groups requests per site`() {
        assertEquals("https://example.com", UrlParts.origin("https://example.com/a/b?c=d"))
        assertNull(UrlParts.origin("data:text/plain,hello"))
    }

    @Test
    fun `correlation keys distinguish query strings and upper-case methods`() {
        val first = UrlParts.correlationKey("https://example.com/api?page=1", "get")
        val sameRequest = UrlParts.correlationKey("https://example.com/api?page=1", "GET")
        val otherPage = UrlParts.correlationKey("https://example.com/api?page=2", "GET")

        assertEquals(first, sameRequest)
        assertFalse(first == otherPage)
    }

    @Test
    fun `default cookie path follows RFC 6265`() {
        assertEquals("/a/b", UrlParts.defaultCookiePath("https://example.com/a/b/c"))
        assertEquals("/", UrlParts.defaultCookiePath("https://example.com/"))
        assertEquals("/", UrlParts.defaultCookiePath("https://example.com"))
    }

    @Test
    fun `query parameters are decoded without throwing on garbage`() {
        val params = UrlParts.queryParameters("https://example.com/?q=hello%20world&flag&bad=%E0%A4")
        assertEquals("hello world", params.first { it.first == "q" }.second)
        assertEquals("", params.first { it.first == "flag" }.second)
    }

    @Test
    fun `decode never throws on malformed escapes`() {
        assertEquals("100%", UrlParts.decode("100%"))
    }
}

class ResourceClassifierTest {

    private fun classification(
        url: String,
        headers: Map<String, String> = emptyMap(),
        isMainFrame: Boolean? = false,
        initiator: Initiator? = null,
        method: String = "GET",
    ): ResourceClassifier.Classification = ResourceClassifier.classify(
        url = url,
        method = method,
        header = { name -> headers.entries.firstOrNull { it.key.equals(name, true) }?.value },
        isMainFrame = isMainFrame,
        initiator = initiator,
    )

    @Test
    fun `main frame requests are documents`() {
        val result = classification("https://example.com/", isMainFrame = true)
        assertEquals(ResourceCategory.DOCUMENT, result.category)
        assertEquals(EvidenceSource.WEBVIEW_CALLBACK, result.source)
    }

    @Test
    fun `sec fetch dest is trusted over the extension`() {
        val result = classification(
            url = "https://example.com/api/data.js",
            headers = mapOf("Sec-Fetch-Dest" to "empty"),
            initiator = Initiator.FETCH,
        )
        assertEquals(ResourceCategory.XHR_FETCH, result.category)
        assertEquals(EvidenceSource.REQUEST_HEADER, result.source)
    }

    @Test
    fun `style and image destinations map to their buckets`() {
        assertEquals(
            ResourceCategory.CSS,
            classification("https://example.com/x", mapOf("Sec-Fetch-Dest" to "style")).category,
        )
        assertEquals(
            ResourceCategory.IMAGE,
            classification("https://example.com/x", mapOf("Sec-Fetch-Dest" to "image")).category,
        )
    }

    @Test
    fun `accept header decides when sec fetch dest is absent`() {
        assertEquals(
            ResourceCategory.CSS,
            classification("https://example.com/x", mapOf("Accept" to "text/css,*/*;q=0.1")).category,
        )
    }

    @Test
    fun `extension guesses are marked as inferred`() {
        val result = classification("https://example.com/main.js")
        assertEquals(ResourceCategory.JAVASCRIPT, result.category)
        assertEquals(EvidenceSource.DERIVED, result.source)
    }

    @Test
    fun `unknown requests land in other and say so`() {
        val result = classification("https://example.com/api/v1/items")
        assertEquals(ResourceCategory.OTHER, result.category)
        assertEquals(EvidenceSource.UNAVAILABLE, result.source)
        assertTrue(result.detail!!.contains("no resource type"))
    }

    @Test
    fun `non-GET requests are treated as script or form submissions`() {
        val result = classification("https://example.com/api/v1/items", method = "POST")
        assertEquals(ResourceCategory.XHR_FETCH, result.category)
    }

    @Test
    fun `a content type refines a guess but never overrides direct evidence`() {
        val guessed = ResourceClassifier.Classification(
            ResourceCategory.OTHER,
            EvidenceSource.DERIVED,
            "inferred from the .bin file extension",
        )
        assertEquals(
            ResourceCategory.IMAGE,
            ResourceClassifier.refineWithResponse(guessed, "image/png").category,
        )

        val direct = ResourceClassifier.Classification(
            ResourceCategory.CSS,
            EvidenceSource.REQUEST_HEADER,
            "Sec-Fetch-Dest: style",
        )
        assertEquals(
            ResourceCategory.CSS,
            ResourceClassifier.refineWithResponse(direct, "image/png").category,
        )
    }
}

class InspectorFiltersTest {

    private fun entry(
        url: String = "https://example.com/api/items?page=1",
        method: String = "GET",
        status: Int? = 200,
        category: ResourceCategory = ResourceCategory.XHR_FETCH,
        state: EntryState = EntryState.RECEIVED,
        withBody: Boolean = false,
        headers: List<HttpField> = emptyList(),
    ): NetworkEntry = NetworkEntry(
        id = 1,
        tabId = "tab",
        request = RequestRecord(
            url = url,
            method = method,
            startedAtMillis = 1_000L,
            startedAtSource = EvidenceSource.APP_CLOCK,
            isForMainFrame = false,
            isRedirect = false,
            hasGesture = null,
            headers = headers,
            headersCompleteness = HeaderCompleteness.SUBSET_REPORTED_BY_WEBVIEW,
            body = if (withBody) {
                BodyRecord(InspectorValue.known("{}", EvidenceSource.PAGE_JAVASCRIPT), BodyKind.JSON)
            } else {
                null
            },
            category = category,
            categorySource = EvidenceSource.DERIVED,
            categoryDetail = null,
            initiator = Initiator.FETCH,
        ),
        response = ResponseRecord(
            statusCode = if (status == null) {
                InspectorValue.unknown("none")
            } else {
                InspectorValue.known(status, EvidenceSource.WEBVIEW_CALLBACK)
            },
            reasonPhrase = InspectorValue.unknown("none"),
            headers = emptyList(),
            contentType = InspectorValue.known("application/json", EvidenceSource.WEBVIEW_CALLBACK),
            receivedAtMillis = InspectorValue.known(1_050L, EvidenceSource.APP_CLOCK),
            finalUrl = InspectorValue.unknown("none"),
            durationMillis = 50L,
        ),
        state = state,
    )

    @Test
    fun `category filter keeps only the requested bucket`() {
        val filters = InspectorFilterState(category = ResourceCategory.IMAGE)
        assertFalse(InspectorFilters.matches(entry(), filters))
        assertTrue(InspectorFilters.matches(entry(category = ResourceCategory.IMAGE), filters))
    }

    @Test
    fun `status filter matches status ranges and states`() {
        assertTrue(InspectorFilters.matches(entry(status = 404), InspectorFilterState(status = StatusFilter.CLIENT_ERROR)))
        assertFalse(InspectorFilters.matches(entry(status = 200), InspectorFilterState(status = StatusFilter.SERVER_ERROR)))
        assertTrue(
            InspectorFilters.matches(
                entry(status = null, state = EntryState.FAILED),
                InspectorFilterState(status = StatusFilter.FAILED),
            )
        )
    }

    @Test
    fun `method filter is case insensitive`() {
        assertTrue(InspectorFilters.matches(entry(method = "POST"), InspectorFilterState(method = "post")))
    }

    @Test
    fun `search matches url, method, status code and header names`() {
        assertTrue(InspectorFilters.matchesQuery(entry(), "items"))
        assertTrue(InspectorFilters.matchesQuery(entry(method = "POST"), "post"))
        assertTrue(InspectorFilters.matchesQuery(entry(status = 503), "503"))
        assertTrue(InspectorFilters.matchesQuery(entry(), "xhr"))
        assertFalse(InspectorFilters.matchesQuery(entry(), "websocket"))
    }

    @Test
    fun `search requires every term to match`() {
        assertTrue(InspectorFilters.matchesQuery(entry(method = "POST", status = 201), "post 201"))
        assertFalse(InspectorFilters.matchesQuery(entry(method = "POST", status = 201), "post 404"))
    }

    @Test
    fun `body filter only keeps entries with a captured body`() {
        assertFalse(
            InspectorFilters.matches(entry(), InspectorFilterState(onlyWithRequestBody = true))
        )
        assertTrue(
            InspectorFilters.matches(entry(withBody = true), InspectorFilterState(onlyWithRequestBody = true))
        )
    }

    @Test
    fun `masked filter finds entries the inspector had to redact`() {
        val masked = entry(
            headers = listOf(
                Redaction.headerField("Authorization", "Bearer abcdefghijklmnop", captureRawValues = false)
            )
        )
        assertTrue(InspectorFilters.matches(masked, InspectorFilterState(onlyWithRedactions = true)))
        assertFalse(InspectorFilters.matches(entry(), InspectorFilterState(onlyWithRedactions = true)))
    }

    @Test
    fun `default filter is a no-op`() {
        val entries = listOf(entry(), entry(url = "https://example.com/x.css"))
        assertEquals(entries, InspectorFilters.apply(entries, InspectorFilterState()))
    }
}
