package com.example.ui.engine.gecko

import android.content.Context
import com.example.data.model.AutomationAction
import com.example.data.model.AutomationStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

private const val MAX_AUTOMATION_SELECTOR_LENGTH = 1_000
private const val MAX_AUTOMATION_VALUE_LENGTH = 2_000
private const val MAX_MESSAGE_STRING_LENGTH = 2_000

/** How long a page command may take before it is reported as a timeout rather than hanging. */
private const val COMMAND_TIMEOUT_MS = 5_000L

/**
 * The app <-> page JavaScript bridge, built on what GeckoView actually offers.
 *
 * Android WebView lets an app inject Java objects into pages with `addJavascriptInterface`.
 * GeckoView deliberately has no such API, and no `evaluateJavascript` either: the supported channel
 * is a WebExtension using native messaging, which is what `assets/browserbridge/` is. Its content
 * script runs in an isolated world that still shares the page's DOM, so it can:
 *
 *  * observe and synthesise DOM events (clicks, input) — this is what the automation recorder and
 *    the automation player use;
 *  * report uncaught page errors.
 *
 * It cannot replace page-world JavaScript objects such as `window.fetch` or `console.log`, so the
 * Network Inspector's page hooks are not installed under this engine — see
 * `docs/ENGINE_MIGRATION.md` for what that means for the inspector and what the supported
 * alternative is.
 *
 * Every failure here is non-fatal and explicit: if the extension did not load, or a tab's content
 * script has not connected yet, callers get [BRIDGE_UNAVAILABLE] (or a timeout) instead of silence.
 */
class GeckoBridge(
    /** Called on the UI thread when the page recorded an automation step. */
    private val onRecordedStep: (String, AutomationStep) -> Unit,
    /** Called on the UI thread for an uncaught page error: (tabId, message, source, line). */
    private val onPageError: (String, String, String?, Int?) -> Unit = { _, _, _, _ -> },
) {

    companion object {
        /** No content script is connected for this tab, so the command was not delivered at all. */
        const val BRIDGE_UNAVAILABLE = "bridge-unavailable"
        const val RESULT_DONE = "done"
        const val RESULT_MISSING = "missing"
        const val RESULT_ERROR = "error"
        const val RESULT_TIMEOUT = "timeout"

        private const val TYPE_SET_RECORDER = "set-recorder"
        private const val TYPE_AUTOMATION_STEP = "automation-step"
        private const val TYPE_AUTOMATION_STEP_RESULT = "automation-step-result"
        private const val TYPE_RECORDED_STEP = "recorded-step"
        private const val TYPE_PAGE_ERROR = "page-error"
    }

    private val sessions = HashMap<String, GeckoSession>()
    private val ports = HashMap<String, WebExtension.Port>()
    private val recorderTabs = HashSet<String>()
    private val pendingCommands = HashMap<String, CompletableDeferred<JSONObject>>()
    private val commandTabIds = HashMap<String, String>()
    private var nextRequestId = 0L

    fun install(context: Context) = GeckoRuntimeManager.ensureBridgeExtension(context)

    /**
     * Registers the message delegate for one tab's session. Call again after the extension finishes
     * installing; `setMessageDelegate` is idempotent.
     */
    fun attach(tabId: String, session: GeckoSession) {
        sessions[tabId] = session
        val extension = GeckoRuntimeManager.bridgeExtension.value ?: return
        session.webExtensionController.setMessageDelegate(
            extension,
            SessionBridgeDelegate(tabId),
            GeckoRuntimeManager.BRIDGE_NATIVE_APP
        )
    }

    fun detach(tabId: String) {
        sessions.remove(tabId)
        ports.remove(tabId)
        recorderTabs.remove(tabId)
        val orphaned = commandTabIds.filterValues { it == tabId }.keys
        orphaned.forEach { requestId ->
            commandTabIds.remove(requestId)
            pendingCommands.remove(requestId)?.cancel()
        }
    }

    /** Turns automation recording on or off for one tab; the content script decides what it sends. */
    fun setRecorderEnabled(tabId: String, enabled: Boolean) {
        if (enabled) recorderTabs.add(tabId) else recorderTabs.remove(tabId)
        post(tabId, JSONObject().put("type", TYPE_SET_RECORDER).put("enabled", enabled))
    }

    /**
     * Runs one automation step in the page and returns one of the RESULT_* values.
     *
     * Never throws and never hangs: a tab whose content script is not connected returns
     * [BRIDGE_UNAVAILABLE], and a page that does not answer in time returns [RESULT_TIMEOUT].
     */
    suspend fun runAutomationStep(tabId: String, step: AutomationStep): String {
        val requestId = "step-${++nextRequestId}"
        val deferred = CompletableDeferred<JSONObject>()
        pendingCommands[requestId] = deferred
        commandTabIds[requestId] = tabId
        try {
            val delivered = post(
                tabId,
                JSONObject()
                    .put("type", TYPE_AUTOMATION_STEP)
                    .put("requestId", requestId)
                    .put("action", step.action.wireValue)
                    .put("selector", step.selector)
                    .put("value", step.value)
            )
            if (!delivered) return BRIDGE_UNAVAILABLE

            val response = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { deferred.await() }
                ?: return RESULT_TIMEOUT
            return when (val result = response.optString("result")) {
                RESULT_DONE, RESULT_MISSING, RESULT_ERROR -> result
                else -> RESULT_ERROR
            }
        } finally {
            pendingCommands.remove(requestId)
            commandTabIds.remove(requestId)
        }
    }

    private fun post(tabId: String, payload: JSONObject): Boolean {
        val port = ports[tabId] ?: return false
        return try {
            port.postMessage(payload)
            true
        } catch (_: Exception) {
            // A port can be disconnected between the check and the post; that is not fatal, the
            // caller is told the command did not reach the page.
            ports.remove(tabId)
            false
        }
    }

    private fun handleMessage(tabId: String, message: Any?) {
        val json = message as? JSONObject ?: return
        when (json.optString("type")) {
            TYPE_AUTOMATION_STEP_RESULT -> {
                val requestId = json.optString("requestId")
                pendingCommands[requestId]?.complete(json)
            }

            TYPE_RECORDED_STEP -> {
                if (tabId !in recorderTabs) return
                parseRecordedStep(json)?.let { step -> onRecordedStep(tabId, step) }
            }

            TYPE_PAGE_ERROR -> {
                val text = json.optString("message").take(MAX_MESSAGE_STRING_LENGTH)
                if (text.isBlank()) return
                val source = json.optString("source").takeIf { it.isNotBlank() }
                val line = json.optInt("line").takeIf { it > 0 }
                onPageError(tabId, text, source, line)
            }
        }
    }

    private fun parseRecordedStep(json: JSONObject): AutomationStep? = runCatching {
        val action = AutomationAction.fromWireValue(json.optString("action")) ?: return null
        val selector = json.optString("selector").take(MAX_AUTOMATION_SELECTOR_LENGTH)
        if (selector.isBlank()) return null
        AutomationStep(
            action = action,
            selector = selector,
            value = json.optString("value").take(MAX_AUTOMATION_VALUE_LENGTH)
        )
    }.getOrNull()

    private inner class SessionBridgeDelegate(private val tabId: String) : WebExtension.MessageDelegate {

        override fun onConnect(port: WebExtension.Port) {
            ports[tabId] = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    handleMessage(tabId, message)
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    if (ports[tabId] === port) ports.remove(tabId)
                }
            })
            // A freshly loaded document has no idea whether recording is on. Tell it, so a recording
            // session that survives a navigation keeps recording.
            post(tabId, JSONObject().put("type", TYPE_SET_RECORDER).put("enabled", tabId in recorderTabs))
        }

        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            handleMessage(tabId, message)
            return null
        }
    }
}
