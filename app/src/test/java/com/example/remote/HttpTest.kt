package com.example.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpTest {

    private fun parse(raw: String): HttpRequest? =
        Http.readRequest(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))

    private fun request(headers: Map<String, String> = emptyMap(), query: Map<String, String> = emptyMap()) =
        HttpRequest("POST", "/status", query, headers, ByteArray(0))

    @Test
    fun parsesPostWithJsonBody() {
        val body = """{"url":"https://example.com"}"""
        val request = parse(
            "POST /navigate/?tab=1&x=a%20b HTTP/1.1\r\n" +
                "Host: 127.0.0.1:9876\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.length}\r\n\r\n" + body,
        )!!
        assertEquals("POST", request.method)
        assertEquals("/navigate", request.path)
        assertEquals(mapOf("tab" to "1", "x" to "a b"), request.query)
        assertEquals("application/json", request.headers["content-type"])
        assertEquals(body, request.bodyText)
    }

    @Test
    fun parsesGetWithoutBody() {
        val request = parse("GET //tabs HTTP/1.0\r\n\r\n")!!
        assertEquals("GET", request.method)
        assertEquals("/tabs", request.path)
        assertEquals(0, request.body.size)
    }

    @Test
    fun emptyConnectionReturnsNull() {
        assertNull(parse(""))
    }

    @Test
    fun rejectsMalformedRequests() {
        assertStatus(400) { parse("NONSENSE\r\n\r\n") }
        assertStatus(400) { parse("GET / HTTP/1.1\r\nBadHeader\r\n\r\n") }
        assertStatus(400) { parse("GET / HTTP/1.1\r\nHost: x") }
        assertStatus(400) { parse("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort") }
        assertStatus(411) { parse("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n") }
        assertStatus(413) { parse("POST / HTTP/1.1\r\nContent-Length: ${Http.MAX_BODY_BYTES + 1}\r\n\r\n") }
        assertStatus(431) { parse("GET / HTTP/1.1\r\nX: ${"a".repeat(Http.MAX_HEADER_BYTES)}\r\n\r\n") }
    }

    @Test
    fun writesResponse() {
        val out = ByteArrayOutputStream()
        Http.writeResponse(out, HttpResponse.text(200, "hi"))
        val text = out.toString("UTF-8")
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 2\r\n"))
        assertTrue(text.endsWith("\r\n\r\nhi"))
    }

    @Test
    fun normalizesPaths() {
        assertEquals("/", Http.normalizePath(""))
        assertEquals("/", Http.normalizePath("/"))
        assertEquals("/tabs/new", Http.normalizePath("//tabs//new/"))
    }

    @Test
    fun acceptsTokenFromHeadersOrQuery() {
        assertTrue(RemoteAuth.isAuthorized(request(mapOf("authorization" to "Bearer secret")), "secret"))
        assertTrue(RemoteAuth.isAuthorized(request(mapOf("x-browser-token" to "secret")), "secret"))
        assertTrue(RemoteAuth.isAuthorized(request(query = mapOf("token" to "secret")), "secret"))
    }

    @Test
    fun rejectsMissingOrWrongToken() {
        assertFalse(RemoteAuth.isAuthorized(request(), "secret"))
        assertFalse(RemoteAuth.isAuthorized(request(mapOf("authorization" to "Bearer nope")), "secret"))
        assertFalse(RemoteAuth.isAuthorized(request(mapOf("authorization" to "Basic secret")), "secret"))
        assertFalse(RemoteAuth.isAuthorized(request(mapOf("authorization" to "Bearer ")), ""))
    }

    @Test
    fun onlyLoopbackHostsAreAllowed() {
        assertTrue(RemoteAuth.isLoopbackHost("127.0.0.1:9876"))
        assertTrue(RemoteAuth.isLoopbackHost("localhost:9876"))
        assertTrue(RemoteAuth.isLoopbackHost("LOCALHOST"))
        assertTrue(RemoteAuth.isLoopbackHost("[::1]:9876"))
        assertTrue(RemoteAuth.isLoopbackHost(null))
        assertFalse(RemoteAuth.isLoopbackHost("evil.example:9876"))
        assertFalse(RemoteAuth.isLoopbackHost("localhost.evil.example"))
    }

    @Test
    fun detectsRequestsFromWebPages() {
        assertTrue(RemoteAuth.isFromWebPage(request(mapOf("origin" to "https://evil.example"))))
        assertTrue(RemoteAuth.isFromWebPage(request(mapOf("sec-fetch-mode" to "no-cors"))))
        assertFalse(RemoteAuth.isFromWebPage(request(mapOf("user-agent" to "Python-urllib/3.12"))))
    }

    @Test
    fun escapesJsonStrings() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", RemoteControlServer.jsonString("a\"b\\c\nd\u0001"))
        val error = RemoteControlServer.jsonError(401, "bad \"token\"")
        assertEquals(401, error.status)
        assertArrayEquals(
            "{\"ok\":false,\"error\":\"bad \\\"token\\\"\"}".toByteArray(),
            error.body,
        )
    }

    private fun assertStatus(status: Int, block: () -> Unit) {
        try {
            block()
            fail("Expected HTTP $status")
        } catch (e: HttpException) {
            assertEquals(status, e.status)
        }
    }
}
