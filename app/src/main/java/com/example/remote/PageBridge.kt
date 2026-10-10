package com.example.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.CompletableFuture

/** The page went away (usually because the command itself navigated) before it answered. */
internal class PageNavigatedException : Exception("The page navigated away before it answered")

/** No content script is connected for the tab (blank/internal page, or still starting to load). */
internal class PageNotReadyException(message: String) : Exception(message)

/** The content script reported an error, e.g. a JavaScript exception or a missing element. */
internal class PageScriptException(message: String) : Exception(message)

/**
 * Talks to the built-in "remote control" WebExtension's content script in each tab through
 * GeckoView native messaging. All methods must be called on the main thread.
 */
internal class PageBridge(private val runtime: GeckoRuntime) {

    private class Pending(val tabId: String, val port: WebExtension.Port, val future: CompletableFuture<Any?>)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = LinkedHashMap<String, GeckoSession>()
    private val ports = HashMap<String, WebExtension.Port>()
    private val pending = HashMap<Int, Pending>()
    private var nextRequestId = 1
    private var extension: WebExtension? = null
    private var released = false

    var installError: String? = null
        private set

    val isReady: Boolean get() = extension != null

    fun install() {
        runtime.webExtensionController
            // installBuiltIn (rather than ensureBuiltIn) refreshes the extension after app updates.
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    if (ext == null || released) return@accept
                    extension = ext
                    installError = null
                    sessions.forEach { (tabId, session) -> registerDelegate(tabId, session, ext) }
                },
                { error ->
                    installError = error?.message ?: "Unknown error"
                    Log.e(TAG, "Could not install the remote control extension", error)
                },
            )
    }

    fun attach(tabId: String, session: GeckoSession) {
        sessions[tabId] = session
        extension?.let { registerDelegate(tabId, session, it) }
    }

    fun detach(tabId: String) {
        val session = sessions.remove(tabId)
        val ext = extension
        if (session != null && ext != null) {
            runCatching { session.webExtensionController.setMessageDelegate(ext, null, NATIVE_APP) }
        }
        ports.remove(tabId)
        failPending { it.tabId == tabId }
    }

    fun release() {
        released = true
        sessions.keys.toList().forEach(::detach)
    }

    fun isConnected(tabId: String): Boolean = ports.containsKey(tabId)

    /** Sends a command to the tab's content script. The future completes on the main thread. */
    fun send(tabId: String, command: String, args: JSONObject, timeoutMs: Long): CompletableFuture<Any?> {
        val future = CompletableFuture<Any?>()
        val port = ports[tabId]
        if (port == null) {
            future.completeExceptionally(PageNotReadyException(notReadyMessage()))
            return future
        }
        val id = nextRequestId++
        pending[id] = Pending(tabId, port, future)
        try {
            port.postMessage(JSONObject().put("id", id).put("cmd", command).put("args", args))
        } catch (e: Exception) {
            pending.remove(id)
            future.completeExceptionally(e)
            return future
        }
        mainHandler.postDelayed({
            pending.remove(id)?.future?.completeExceptionally(
                java.util.concurrent.TimeoutException("The page did not answer within ${timeoutMs / 1000.0}s"),
            )
        }, timeoutMs)
        return future
    }

    fun notReadyMessage(): String = when {
        installError != null -> "The page bridge extension failed to install: $installError"
        extension == null -> "The page bridge extension is still starting"
        else -> "This tab has no scriptable page yet (blank, internal or still-loading page)"
    }

    private fun registerDelegate(tabId: String, session: GeckoSession, ext: WebExtension) {
        session.webExtensionController.setMessageDelegate(
            ext,
            object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    onPortConnected(tabId, port)
                }
            },
            NATIVE_APP,
        )
    }

    private fun onPortConnected(tabId: String, port: WebExtension.Port) {
        if (!sessions.containsKey(tabId)) {
            port.disconnect()
            return
        }
        val previous = ports.put(tabId, port)
        if (previous != null && previous !== port) failPending { it.port === previous }

        port.setDelegate(object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                val json = message as? JSONObject ?: return
                if (!json.has("id")) return // "hello" and other notifications
                val request = pending.remove(json.optInt("id")) ?: return
                if (json.optBoolean("ok", false)) {
                    request.future.complete(json.opt("result").takeUnless { it == JSONObject.NULL })
                } else {
                    request.future.completeExceptionally(
                        PageScriptException(json.optString("error", "Unknown page error")),
                    )
                }
            }

            override fun onDisconnect(port: WebExtension.Port) {
                if (ports[tabId] === port) ports.remove(tabId)
                failPending { it.port === port }
            }
        })
    }

    private fun failPending(predicate: (Pending) -> Boolean) {
        val iterator = pending.values.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (predicate(request)) {
                iterator.remove()
                request.future.completeExceptionally(PageNavigatedException())
            }
        }
    }

    private companion object {
        const val TAG = "PageBridge"
        const val EXTENSION_URI = "resource://android/assets/remote_control/"
        const val NATIVE_APP = "browser"
    }
}
