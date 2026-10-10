package com.example.automation

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal const val AUTOMATION_EXTENSION_URI = "resource://android/assets/automation/"
internal const val AUTOMATION_EXTENSION_ID = "automation-bridge@gecko-browser.local"

private const val NATIVE_APP = "automation"
private const val MAIN_THREAD_TIMEOUT_SECONDS = 10L
private const val PAGE_TIMEOUT_SECONDS = 15L
private const val TAG = "AutomationBridge"

private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

/**
 * Runs [block] on the Android main thread and returns its result. GeckoView and
 * the tab state must only be touched there. Called from HTTP worker threads.
 */
internal fun <T> runOnMainThread(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val future = CompletableFuture<T>()
    mainHandler.post {
        try {
            future.complete(block())
        } catch (error: Throwable) {
            future.completeExceptionally(error)
        }
    }
    try {
        return future.get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }
}

/** An error that should be reported to the API client with [status]. */
internal class BridgeException(val status: Int, message: String) : Exception(message)

/**
 * Talks to the built-in "Automation Bridge" WebExtension. Every page has a content
 * script that opens a native port; each tab's port is used to send page operations
 * and to receive their results.
 */
internal class AutomationBridge(private val runtime: GeckoRuntime) {

    private val sessions = ConcurrentHashMap<String, GeckoSession>()
    private val ports = ConcurrentHashMap<String, WebExtension.Port>()
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()

    @Volatile
    private var extension: WebExtension? = null

    val isReady: Boolean
        get() = extension != null

    /** Installs the bundled extension from assets. Safe to call more than once. */
    fun install() {
        runtime.webExtensionController
            .ensureBuiltIn(AUTOMATION_EXTENSION_URI, AUTOMATION_EXTENSION_ID)
            .accept(
                { ext -> onInstalled(ext) },
                { error -> Log.w(TAG, "Automation extension failed to install", error) },
            )
    }

    /** Registers a tab's session so its page scripts can reach this bridge. Main thread only. */
    fun attach(tabId: String, session: GeckoSession) {
        sessions[tabId] = session
        extension?.let { attachDelegate(tabId, session, it) }
    }

    /** Forgets a closed tab. Main thread only. */
    fun detach(tabId: String) {
        sessions.remove(tabId)
        ports.remove(tabId)
    }

    /**
     * Sends [op] with [args] to the page in [tabId] and waits for its reply.
     * Called from HTTP worker threads; returns the JSON value produced by the page.
     */
    fun request(tabId: String, op: String, args: JSONObject): Any {
        val id = UUID.randomUUID().toString()
        val reply = CompletableFuture<JSONObject>()
        pending[id] = reply
        try {
            val payload = JSONObject()
                .put("id", id)
                .put("op", op)
                .put("args", args)
            val sent = runOnMainThread {
                val port = ports[tabId]
                if (port == null) {
                    false
                } else {
                    port.postMessage(payload)
                    true
                }
            }
            if (!sent) {
                throw BridgeException(
                    409,
                    "This tab has no page script attached. Navigate it to an http(s) page and wait for it to load.",
                )
            }
            val response = try {
                reply.get(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (error: TimeoutException) {
                throw BridgeException(504, "The page did not respond in time.")
            }
            if (!response.optBoolean("ok", false)) {
                throw BridgeException(422, response.optString("error", "The page script failed."))
            }
            return response.opt("value") ?: JSONObject.NULL
        } finally {
            pending.remove(id)
        }
    }

    private fun onInstalled(ext: WebExtension) {
        extension = ext
        sessions.forEach { (tabId, session) -> attachDelegate(tabId, session, ext) }
    }

    private fun attachDelegate(tabId: String, session: GeckoSession, ext: WebExtension) {
        session.webExtensionController.setMessageDelegate(
            ext,
            object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    // A new page load replaces the previous port for this tab.
                    ports[tabId] = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            handleReply(message)
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            ports.remove(tabId, port)
                        }
                    })
                }
            },
            NATIVE_APP,
        )
    }

    private fun handleReply(message: Any) {
        val reply = message as? JSONObject ?: return
        pending.remove(reply.optString("id"))?.complete(reply)
    }
}
