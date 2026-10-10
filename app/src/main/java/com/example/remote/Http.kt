package com.example.remote

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.security.MessageDigest

/** A parsed HTTP/1.x request. Header names are lower-cased. */
internal class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val bodyText: String get() = String(body, Charsets.UTF_8)
}

internal class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    companion object {
        fun text(status: Int, text: String, contentType: String = "text/plain; charset=utf-8") =
            HttpResponse(status, contentType, text.toByteArray(Charsets.UTF_8))
    }
}

/** Thrown while parsing or handling a request; mapped to an error response. */
internal class HttpException(val status: Int, message: String) : Exception(message)

/** Minimal, dependency-free HTTP/1.x request parser and response writer. */
internal object Http {

    const val MAX_HEADER_BYTES = 32 * 1024
    const val MAX_BODY_BYTES = 16 * 1024 * 1024

    /** Returns null if the client closed the connection before sending anything. */
    fun readRequest(input: InputStream): HttpRequest? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val requestLine = lines.first().split(' ')
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/")) {
            throw HttpException(400, "Malformed request line")
        }
        val method = requestLine[0].uppercase()
        val target = requestLine[1]

        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpException(400, "Malformed header")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            throw HttpException(411, "Chunked request bodies are not supported; send Content-Length")
        }
        val length = headers["content-length"]?.let {
            it.toIntOrNull() ?: throw HttpException(400, "Invalid Content-Length")
        } ?: 0
        if (length < 0) throw HttpException(400, "Invalid Content-Length")
        if (length > MAX_BODY_BYTES) throw HttpException(413, "Request body too large")
        val body = readExactly(input, length)

        val pathPart = target.substringBefore('?').substringBefore('#')
        val queryPart = if (target.contains('?')) target.substringAfter('?').substringBefore('#') else ""
        return HttpRequest(
            method = method,
            path = normalizePath(decode(pathPart)),
            query = parseQuery(queryPart),
            headers = headers,
            body = body,
        )
    }

    fun writeResponse(output: OutputStream, response: HttpResponse, headOnly: Boolean = false) {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            .append("Content-Type: ").append(response.contentType).append("\r\n")
            .append("Content-Length: ").append(response.body.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
        response.extraHeaders.forEach { (name, value) -> head.append(name).append(": ").append(value).append("\r\n") }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) output.write(response.body)
        output.flush()
    }

    fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('='))
            val value = if (pair.contains('=')) decode(pair.substringAfter('=')) else ""
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }

    /** Collapses duplicate slashes and strips a trailing slash, e.g. "//tabs/" -> "/tabs". */
    fun normalizePath(path: String): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        return "/" + segments.joinToString("/")
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            throw HttpException(400, "Invalid URL encoding")
        }

    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var matched = 0 // how much of "\r\n\r\n" has been seen
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buffer.size() == 0) return null
                throw HttpException(400, "Incomplete request")
            }
            buffer.write(b)
            if (buffer.size() > MAX_HEADER_BYTES) throw HttpException(431, "Request headers too large")
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        val bytes = buffer.toByteArray()
        return String(bytes, 0, bytes.size - 4, Charsets.ISO_8859_1)
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) throw HttpException(400, "Request body ended early")
            offset += read
        }
        return body
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Status"
    }
}

/** Access checks for the localhost control server. */
internal object RemoteAuth {

    /**
     * Accepts the token from `Authorization: Bearer <token>`, `X-Browser-Token: <token>`, or a
     * `token` query parameter (handy for quick curl tests).
     */
    fun isAuthorized(request: HttpRequest, expectedToken: String): Boolean {
        if (expectedToken.isEmpty()) return false
        val supplied = request.headers["authorization"]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()
            ?: request.headers["x-browser-token"]?.trim()
            ?: request.query["token"]
            ?: return false
        return MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            expectedToken.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Rejects requests whose Host header is not a loopback name. This blocks DNS-rebinding attacks
     * where a web page tries to reach the server through an attacker-controlled host name.
     */
    fun isLoopbackHost(hostHeader: String?): Boolean {
        if (hostHeader.isNullOrBlank()) return true // HTTP/1.0 clients may omit Host.
        val host = when {
            hostHeader.startsWith("[") -> hostHeader.substringBefore(']').removePrefix("[")
            else -> hostHeader.substringBefore(':')
        }.lowercase()
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host.startsWith("127.")
    }

    /**
     * Web pages always send an Origin header with fetch/XHR POSTs; Termux scripts (Python urllib,
     * requests, curl) never do. Refusing such requests keeps websites from talking to the server.
     */
    fun isFromWebPage(request: HttpRequest): Boolean =
        request.headers.containsKey("origin") || request.headers.containsKey("sec-fetch-mode")
}
