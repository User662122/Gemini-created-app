package com.example.remote

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A tiny HTTP server bound to the loopback interface only, so it is reachable from Termux and other
 * apps on this device but never from the network.
 */
internal class RemoteControlServer(
    val port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
    private val onStarted: () -> Unit = {},
    private val onStopped: (error: String?) -> Unit = {},
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var stopped = false

    private val workers = ThreadPoolExecutor(
        0, MAX_CONCURRENT_REQUESTS, 30L, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
        java.util.concurrent.ThreadFactory { runnable -> Thread(runnable, "remote-control-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun start() {
        Thread({ acceptLoop() }, "remote-control-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopped = true
        runCatching { serverSocket?.close() }
        workers.shutdownNow()
    }

    private fun acceptLoop() {
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32)
            }
        } catch (e: IOException) {
            onStopped("Could not listen on 127.0.0.1:$port (${e.message ?: "port unavailable"})")
            return
        }
        serverSocket = socket
        if (stopped) {
            runCatching { socket.close() }
            onStopped(null)
            return
        }
        onStarted()

        var error: String? = null
        try {
            while (!stopped) {
                val client = socket.accept()
                try {
                    workers.execute { serve(client) }
                } catch (e: Exception) {
                    // Too many concurrent requests: answer immediately instead of queueing forever.
                    runCatching {
                        client.use {
                            Http.writeResponse(
                                it.getOutputStream(),
                                jsonError(503, "Too many concurrent requests"),
                            )
                        }
                    }
                }
            }
        } catch (e: SocketException) {
            if (!stopped) error = "Server socket closed: ${e.message}"
        } catch (e: IOException) {
            error = "Server error: ${e.message}"
        } finally {
            runCatching { socket.close() }
            onStopped(error)
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            var request: HttpRequest? = null
            val response = try {
                request = Http.readRequest(input) ?: return
                handler(request)
            } catch (e: HttpException) {
                jsonError(e.status, e.message ?: "Error")
            } catch (e: SocketTimeoutException) {
                jsonError(408, "Timed out reading the request")
            } catch (e: Exception) {
                jsonError(500, "${e.javaClass.simpleName}: ${e.message}")
            }
            runCatching { Http.writeResponse(output, response, headOnly = request?.method == "HEAD") }
        }
    }

    companion object {
        private const val MAX_CONCURRENT_REQUESTS = 16
        private const val READ_TIMEOUT_MS = 30_000

        /** JSON error body built by hand so this class stays free of Android dependencies. */
        fun jsonError(status: Int, message: String): HttpResponse =
            HttpResponse.text(
                status,
                "{\"ok\":false,\"error\":${jsonString(message)}}",
                "application/json; charset=utf-8",
            )

        fun jsonString(value: String): String {
            val out = StringBuilder(value.length + 2).append('"')
            for (c in value) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
                }
            }
            return out.append('"').toString()
        }
    }
}
