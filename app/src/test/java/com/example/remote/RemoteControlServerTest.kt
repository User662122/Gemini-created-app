package com.example.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteControlServerTest {

    @Test
    fun servesRequestsOnLoopback() {
        val port = ServerSocket(0).use { it.localPort }
        val started = CountDownLatch(1)
        val server = RemoteControlServer(
            port = port,
            handler = { request ->
                HttpResponse.text(200, "${request.method} ${request.path} ${request.bodyText}")
            },
            onStarted = { started.countDown() },
        )
        server.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val connection = URL("http://127.0.0.1:$port/eval/").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.use { it.write("{\"script\":\"1\"}".toByteArray()) }
            assertEquals(200, connection.responseCode)
            assertEquals("POST /eval {\"script\":\"1\"}", connection.inputStream.bufferedReader().readText())
        } finally {
            server.stop()
        }
    }

    @Test
    fun reportsPortConflicts() {
        ServerSocket(0).use { busy ->
            val stopped = CountDownLatch(1)
            var error: String? = null
            RemoteControlServer(
                port = busy.localPort,
                handler = { HttpResponse.text(200, "") },
                onStopped = { error = it; stopped.countDown() },
            ).start()
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            assertTrue(error.orEmpty().contains("Could not listen"))
        }
    }
}
