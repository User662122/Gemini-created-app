package com.example.remote

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs code on the Android main thread from server worker threads. */
internal object MainThread {
    private val handler = Handler(Looper.getMainLooper())

    val isCurrent: Boolean get() = Looper.myLooper() == Looper.getMainLooper()

    fun post(block: () -> Unit) {
        handler.post { block() }
    }

    fun <T> call(timeoutMs: Long = 15_000, block: () -> T): T {
        if (isCurrent) return block()
        val task = FutureTask(Callable { block() })
        handler.post(task)
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            task.cancel(false)
            throw HttpException(503, "The browser's UI thread is busy; try again")
        }
    }
}
