package com.example.automation

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** An error with the HTTP status that should be returned to the client. */
internal class HttpException(val status: Int, message: String) : Exception(message)

internal class HttpRequest(
    val method: String,
    val path: String,
    private val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]

    /** The body as a JSON object. An empty body is an empty object. */
    fun jsonBody(): JSONObject =
        if (body.isEmpty()) JSONObject() else JSONObject(String(body, Charsets.UTF_8))

    companion object {
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024

        /** Reads one HTTP/1.1 request. Only Content-Length bodies are supported. */
        fun read(input: InputStream): HttpRequest {
            val requestLine = readLine(input) ?: throw HttpException(400, "Empty request")
            val parts = requestLine.split(' ')
            if (parts.size < 2) throw HttpException(400, "Malformed request line")

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] =
                        line.substring(colon + 1).trim()
                }
            }

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY_BYTES) throw HttpException(413, "Request body is too large")
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(body, offset, length - offset)
                if (read < 0) throw HttpException(400, "Request body ended early")
                offset += read
            }
            return HttpRequest(
                method = parts[0].uppercase(Locale.ROOT),
                path = parts[1].substringBefore('?'),
                headers = headers,
                body = body,
            )
        }

        /** Reads a CRLF-terminated line. Returns null at end of stream. */
        private fun readLine(input: InputStream): String? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.ISO_8859_1.name())
                if (byte == LF) break
                if (byte != CR) buffer.write(byte)
                if (buffer.size() > MAX_LINE_BYTES) throw HttpException(431, "Request header is too large")
            }
            return buffer.toString(Charsets.ISO_8859_1.name())
        }

        private const val CR = 13
        private const val LF = 10
    }
}

internal class HttpResponse(val status: Int, val body: String) {

    fun toWireBytes(): ByteArray {
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${reasonPhrase(status)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${payload.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        return head.toByteArray(Charsets.US_ASCII) + payload
    }

    companion object {
        fun error(status: Int, message: String): HttpResponse =
            HttpResponse(status, JSONObject().put("error", message).toString())

        private fun reasonPhrase(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            408 -> "Request Timeout"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            422 -> "Unprocessable Entity"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            504 -> "Gateway Timeout"
            else -> "Error"
        }
    }
}

/**
 * A small HTTP/1.1 server bound to the loopback interface only, so it is reachable from
 * this device (Termux, or a PC through `adb forward`) and not from the network.
 */
internal class AutomationServer(
    private val port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
) {
    private var serverSocket: ServerSocket? = null
    private var workers: ExecutorService? = null

    @Synchronized
    fun start() {
        check(serverSocket == null) { "Server already running" }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        } catch (error: IOException) {
            socket.close()
            throw error
        }
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "automation-client").apply { isDaemon = true }
        }
        serverSocket = socket
        workers = pool
        Thread({ acceptLoop(socket, pool) }, "automation-accept").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        serverSocket?.close()
        serverSocket = null
        workers?.shutdownNow()
        workers = null
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (error: IOException) {
                break
            }
            try {
                pool.execute { serve(client) }
            } catch (error: RejectedExecutionException) {
                client.close()
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = CLIENT_TIMEOUT_MS
            val response = try {
                handler(HttpRequest.read(BufferedInputStream(socket.getInputStream())))
            } catch (error: HttpException) {
                HttpResponse.error(error.status, error.message ?: "Bad request")
            } catch (error: SocketTimeoutException) {
                HttpResponse.error(408, "Timed out reading the request")
            } catch (error: IOException) {
                return@use
            } catch (error: Exception) {
                HttpResponse.error(500, error.message ?: "Internal error")
            }
            val output = socket.getOutputStream()
            output.write(response.toWireBytes())
            output.flush()
        }
    }

    private companion object {
        const val CLIENT_TIMEOUT_MS = 15_000
    }
}
