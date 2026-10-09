package com.example.ui

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** State and GeckoSession for one browser tab. */
class BrowserTabState internal constructor(
    val id: String,
    val session: GeckoSession,
) {
    var url by mutableStateOf("")
        internal set
    var title by mutableStateOf("New tab")
        internal set
    var isLoading by mutableStateOf(false)
        internal set
    var progress by mutableIntStateOf(0)
        internal set
    var canGoBack by mutableStateOf(false)
        internal set
    var canGoForward by mutableStateOf(false)
        internal set

    internal var isClosing: Boolean = false
}

/** A minimal multi-tab browser backed only by GeckoView (Gecko + SpiderMonkey). */
class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val runtime = BrowserRuntime.get(application)
    private val ownedTabs = LinkedHashMap<String, BrowserTabState>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkExtension: WebExtension? = null
    private var networkPort: WebExtension.Port? = null
    private var isViewModelCleared = false
    private val bodyBuffers = mutableMapOf<String, BodyAccumulator>()
    private var storedBodyBytes = 0

    val tabs = mutableStateListOf<BrowserTabState>()
    val networkEntries = mutableStateListOf<NetworkRequestLog>()

    var isNetworkCaptureReady by mutableStateOf(false)
        private set
    var isNetworkRecording by mutableStateOf(false)
        private set
    var networkCaptureError by mutableStateOf<String?>(null)
        private set
    var networkExportStatus by mutableStateOf<String?>(null)
        private set

    var activeTabId by mutableStateOf<String?>(null)
        private set

    val activeTab: BrowserTabState?
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull()

    private val networkMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            runOnMain { onNetworkPortConnected(port) }
        }
    }

    private val networkPortDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val event = message as? JSONObject ?: return
            runOnMain {
                if (networkPort === port) handleNetworkEvent(event)
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            runOnMain {
                if (networkPort === port) {
                    networkPort = null
                    isNetworkCaptureReady = false
                    isNetworkRecording = false
                    networkCaptureError = "Network capture disconnected; reconnecting…"
                }
            }
        }
    }

    init {
        installNetworkInspector()
        openNewTab()
    }

    /** Starts or stops an explicit, local-only capture session. */
    fun setNetworkRecording(enabled: Boolean) {
        if (enabled && !isNetworkCaptureReady) return
        if (isNetworkRecording == enabled) return
        isNetworkRecording = enabled
        postRecorderCommand("setRecording", enabled)
    }

    fun clearNetworkLogs() {
        networkEntries.clear()
        bodyBuffers.clear()
        storedBodyBytes = 0
        networkExportStatus = null
        if (isNetworkCaptureReady) postRecorderCommand("setRecording", isNetworkRecording)
    }

    /** Writes a HAR file to the user-selected Android document URI. */
    fun exportNetworkLog(contentResolver: ContentResolver, uri: Uri) {
        val snapshot = snapshotNetworkEntries()
        networkExportStatus = "Writing the HAR file…"
        viewModelScope.launch {
            networkExportStatus = withContext(Dispatchers.IO) {
                runCatching {
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw IOException("Android could not open the selected file.")
                    output.use { NetworkHarExporter.write(snapshot, it) }
                }.fold(
                    onSuccess = { "HAR export saved. It contains unredacted sensitive data." },
                    onFailure = { error -> "Export failed: ${error.localizedMessage ?: "could not write the file"}" },
                )
            }
        }
    }

    private fun installNetworkInspector() {
        runtime.webExtensionController.ensureBuiltIn(
            NETWORK_EXTENSION_LOCATION,
            NETWORK_EXTENSION_ID,
        ).accept(
            { extension ->
                runOnMain {
                    if (isViewModelCleared) return@runOnMain
                    if (extension == null) {
                        networkCaptureError = "Network capture could not start: GeckoView returned no extension."
                    } else {
                        networkExtension = extension
                        extension.setMessageDelegate(networkMessageDelegate, NETWORK_NATIVE_APP)
                        networkCaptureError = null
                    }
                }
            },
            { error ->
                runOnMain {
                    if (isViewModelCleared) return@runOnMain
                    isNetworkCaptureReady = false
                    networkCaptureError = "Network capture could not start: ${error?.localizedMessage ?: "GeckoView extension installation failed"}"
                }
            },
        )
    }

    private fun onNetworkPortConnected(port: WebExtension.Port) {
        if (isViewModelCleared) {
            runCatching { port.disconnect() }
            return
        }
        networkPort?.takeIf { it !== port }?.let { previous ->
            runCatching { previous.disconnect() }
        }
        networkPort = port
        port.setDelegate(networkPortDelegate)
    }

    private fun postRecorderCommand(type: String, enabled: Boolean) {
        val port = networkPort ?: return
        runCatching {
            port.postMessage(JSONObject()
                .put("type", type)
                .put("enabled", enabled)
                .put("bodyBudgetBytes", (MAX_CAPTURED_TOTAL_BYTES - storedBodyBytes).coerceAtLeast(0)))
        }.onFailure { error ->
            networkCaptureError = "Could not update capture state: ${error.localizedMessage ?: "bridge error"}"
        }
    }

    private fun handleNetworkEvent(event: JSONObject) {
        when (event.optString("type")) {
            "ready" -> {
                isNetworkCaptureReady = true
                isNetworkRecording = false
                networkCaptureError = null
                postRecorderCommand("setRecording", false)
            }
            "captureState" -> isNetworkRecording = event.optBoolean("enabled")
            "requestStarted" -> addNetworkRequest(event)
            "requestHeaders" -> updateNetworkRequest(event.optString("captureId")) { entry ->
                entry.copy(requestHeaders = parseHeaders(event.optJSONArray("headers")))
            }
            "requestBodyMeta" -> updateNetworkRequest(event.optString("captureId")) { entry ->
                entry.copy(
                    requestBodyAvailable = event.optBoolean("available", entry.requestBodyAvailable),
                    requestBodyFormat = event.optNullableString("format"),
                    requestBodyNote = event.optNullableString("note"),
                    requestBodyTruncated = entry.requestBodyTruncated || event.optBoolean("truncated"),
                )
            }
            "responseHeaders" -> updateNetworkRequest(event.optString("captureId")) { entry ->
                entry.copy(
                    statusCode = event.optIntOrNull("statusCode"),
                    statusLine = event.optNullableString("statusLine"),
                    responseHeaders = parseHeaders(event.optJSONArray("headers")),
                    responseBodyMimeType = event.optNullableString("mimeType"),
                )
            }
            "bodyChunk" -> appendBodyChunk(event)
            "requestFinished" -> finishNetworkRequest(event)
            "error" -> {
                isNetworkCaptureReady = false
                isNetworkRecording = false
                networkCaptureError = "Capture extension error: ${event.optString("message", "unknown error")}"
            }
        }
    }

    private fun addNetworkRequest(event: JSONObject) {
        val id = event.optString("captureId").takeIf { it.isNotBlank() } ?: return
        if (networkEntries.any { it.id == id }) return
        val entry = NetworkRequestLog(
            id = id,
            requestId = event.optString("requestId"),
            url = event.optString("url"),
            method = event.optString("method", "GET"),
            resourceType = event.optString("resourceType", "other"),
            tabId = event.optIntOrNull("tabId"),
            frameId = event.optIntOrNull("frameId"),
            startedAtEpochMs = event.optLong("startedAt", System.currentTimeMillis()),
            initiator = event.optNullableString("initiator"),
            requestBodyAvailable = event.optBoolean("requestBodyAvailable"),
        )
        networkEntries.add(0, entry)
        trimNetworkLogs()
    }

    private fun appendBodyChunk(event: JSONObject) {
        val id = event.optString("captureId")
        val direction = event.optString("direction")
        if (id.isBlank() || (direction != "request" && direction != "response")) return
        val index = networkEntries.indexOfFirst { it.id == id }
        if (index < 0) return

        val encoded = event.optString("data")
        if (encoded.length > MAX_BODY_CHUNK_BASE64_CHARACTERS) return
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return
        val bufferKey = "$id:$direction"
        val accumulator = bodyBuffers.getOrPut(bufferKey) { BodyAccumulator() }
        val remainingForBody = (MAX_CAPTURED_BODY_BYTES - accumulator.stream.size()).coerceAtLeast(0)
        val remainingOverall = (MAX_CAPTURED_TOTAL_BYTES - storedBodyBytes).coerceAtLeast(0)
        val accepted = minOf(bytes.size, remainingForBody, remainingOverall)
        if (accepted > 0) {
            accumulator.stream.write(bytes, 0, accepted)
            storedBodyBytes += accepted
        }
        if (accepted < bytes.size) accumulator.truncated = true

        val entry = networkEntries[index]
        networkEntries[index] = if (direction == "request") {
            entry.copy(
                requestBodyBytesCaptured = accumulator.stream.size(),
                requestBodyTruncated = entry.requestBodyTruncated || accumulator.truncated,
            )
        } else {
            entry.copy(
                responseBodyBytesCaptured = accumulator.stream.size(),
                responseBodyTruncated = entry.responseBodyTruncated || accumulator.truncated,
            )
        }
    }

    private fun finishNetworkRequest(event: JSONObject) {
        val id = event.optString("captureId")
        if (id.isBlank()) return
        val requestBuffer = bodyBuffers.remove("$id:request")
        val responseBuffer = bodyBuffers.remove("$id:response")
        updateNetworkRequest(id) { entry ->
            val requestBytes = requestBuffer?.stream?.toByteArray()
            val responseBytes = responseBuffer?.stream?.toByteArray()
            entry.copy(
                requestBody = requestBytes ?: entry.requestBody,
                responseBody = responseBytes ?: entry.responseBody,
                requestBodyBytesCaptured = requestBytes?.size ?: entry.requestBodyBytesCaptured,
                responseBodyBytesCaptured = responseBytes?.size ?: entry.responseBodyBytesCaptured,
                requestBodyTruncated = entry.requestBodyTruncated ||
                    event.optBoolean("requestBodyTruncated") || requestBuffer?.truncated == true,
                responseBodyTruncated = entry.responseBodyTruncated ||
                    event.optBoolean("responseBodyTruncated") || responseBuffer?.truncated == true,
                requestBodyNote = event.optNullableString("requestBodyNote") ?: entry.requestBodyNote,
                responseBodyNote = event.optNullableString("responseBodyNote") ?: entry.responseBodyNote,
                durationMs = event.optLongOrNull("durationMs"),
                fromCache = if (event.has("fromCache") && !event.isNull("fromCache")) event.optBoolean("fromCache") else entry.fromCache,
                redirectUrl = event.optNullableString("redirectUrl") ?: entry.redirectUrl,
                error = event.optNullableString("error") ?: entry.error,
                isComplete = true,
            )
        }
    }

    private fun updateNetworkRequest(id: String, transform: (NetworkRequestLog) -> NetworkRequestLog) {
        if (id.isBlank()) return
        val index = networkEntries.indexOfFirst { it.id == id }
        if (index >= 0) networkEntries[index] = transform(networkEntries[index])
    }

    private fun parseHeaders(array: org.json.JSONArray?): List<NetworkHeader> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val header = array.optJSONObject(index) ?: continue
                val name = header.optString("name")
                val binaryValue = header.optNullableString("binaryValue")
                val value = binaryValue ?: header.optString("value", "")
                if (name.isNotBlank()) add(NetworkHeader(name, value, isBase64 = binaryValue != null))
            }
        }
    }

    private fun trimNetworkLogs() {
        while (networkEntries.size > MAX_NETWORK_ENTRIES) {
            val removed = networkEntries.removeAt(networkEntries.lastIndex)
            releaseNetworkRequestBody(removed)
        }
    }

    private fun releaseNetworkRequestBody(entry: NetworkRequestLog) {
        storedBodyBytes = (storedBodyBytes - (entry.requestBody?.size ?: 0) - (entry.responseBody?.size ?: 0))
            .coerceAtLeast(0)
        listOf("request", "response").forEach { direction ->
            val removed = bodyBuffers.remove("${entry.id}:$direction")
            if (removed != null) {
                storedBodyBytes = (storedBodyBytes - removed.stream.size()).coerceAtLeast(0)
            }
        }
    }

    private fun snapshotNetworkEntries(): List<NetworkRequestLog> = networkEntries.map { entry ->
        val requestBuffer = bodyBuffers["${entry.id}:request"]
        val responseBuffer = bodyBuffers["${entry.id}:response"]
        entry.copy(
            requestBody = entry.requestBody ?: requestBuffer?.stream?.toByteArray(),
            responseBody = entry.responseBody ?: responseBuffer?.stream?.toByteArray(),
            requestBodyBytesCaptured = maxOf(entry.requestBodyBytesCaptured, requestBuffer?.stream?.size() ?: 0),
            responseBodyBytesCaptured = maxOf(entry.responseBodyBytesCaptured, responseBuffer?.stream?.size() ?: 0),
            requestBodyTruncated = entry.requestBodyTruncated || requestBuffer?.truncated == true,
            responseBodyTruncated = entry.responseBodyTruncated || responseBuffer?.truncated == true,
        )
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private class BodyAccumulator {
        val stream = ByteArrayOutputStream()
        var truncated: Boolean = false
    }

    fun openNewTab(initialUrl: String? = null) {
        val previousTab = activeTab
        val tab = createTab()
        tabs.add(tab)
        previousTab?.session?.setActive(false)
        activeTabId = tab.id
        tab.session.setActive(true)

        if (!initialUrl.isNullOrBlank() && initialUrl != "about:blank") {
            load(tab, initialUrl)
        }
    }

    fun selectTab(tabId: String) {
        if (activeTabId == tabId) return
        activeTab?.session?.setActive(false)
        activeTabId = tabId
        activeTab?.session?.setActive(true)
    }

    fun closeTab(tabId: String) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return

        val closing = tabs[index]
        val wasActive = closing.id == activeTabId
        closing.isClosing = true
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            val freshTab = createTab()
            tabs.add(freshTab)
            activeTabId = freshTab.id
            freshTab.session.setActive(true)
        } else if (wasActive) {
            activeTabId = tabs[index.coerceAtMost(tabs.lastIndex)].id
        }

        // The visible GeckoView releases its session from BrowserScreen's dispose effect before
        // the active tab is closed. A hidden tab has no view attached and can be released now.
        if (!wasActive) finishClosingTab(tabId)
    }

    /** Called after a closed tab's GeckoView has detached. */
    fun finishClosingTab(tabId: String) {
        val closing = ownedTabs.remove(tabId) ?: return
        closing.session.setActive(false)
        runCatching { closing.session.close() }
    }

    fun navigate(address: String) {
        val tab = activeTab ?: return
        val url = BrowserAddress.resolve(address) ?: return
        load(tab, url)
    }

    fun goBack() {
        activeTab?.takeIf { it.canGoBack }?.session?.goBack()
    }

    fun goForward() {
        activeTab?.takeIf { it.canGoForward }?.session?.goForward()
    }

    fun reload() {
        activeTab?.let { tab ->
            if (tab.url.isNotBlank()) {
                tab.session.reload()
            }
        }
    }

    fun stopLoading() {
        activeTab?.let { tab ->
            tab.session.stop()
            tab.isLoading = false
        }
    }

    private fun load(tab: BrowserTabState, url: String) {
        tab.url = url.takeUnless { it == "about:blank" }.orEmpty()
        tab.title = if (tab.url.isBlank()) "New tab" else tab.url
        tab.isLoading = true
        tab.progress = 0
        tab.session.loadUri(url)
    }

    private fun createTab(): BrowserTabState {
        val sessionSettings = GeckoSessionSettings.Builder()
            .allowJavascript(true)
            .useTrackingProtection(true)
            .displayMode(GeckoSessionSettings.DISPLAY_MODE_BROWSER)
            .build()
        val session = GeckoSession(sessionSettings)
        session.open(runtime)

        val tab = BrowserTabState(
            id = UUID.randomUUID().toString(),
            session = session,
        )
        ownedTabs[tab.id] = tab

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                val url = request.uri
                val scheme = Uri.parse(url).scheme?.lowercase(Locale.ROOT)
                if (scheme == "javascript") return GeckoResult.deny()
                if (scheme != null && scheme in BROWSER_SCHEMES) return GeckoResult.allow()
                openExternalUrl(url)
                return GeckoResult.deny()
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String,
            ): GeckoResult<GeckoSession>? {
                val scheme = Uri.parse(uri).scheme?.lowercase(Locale.ROOT)
                if (scheme != null && scheme in BROWSER_SCHEMES) {
                    openNewTab(uri)
                } else {
                    openExternalUrl(uri)
                }
                return GeckoResult.fromValue(null)
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                permissions: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                tab.url = url?.takeUnless { it == "about:blank" }.orEmpty()
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward = canGoForward
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: org.mozilla.geckoview.WebRequestError,
            ): GeckoResult<String>? {
                tab.isLoading = false
                tab.progress = 100
                return null // Gecko renders its own network error page.
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.url = url.takeUnless { it == "about:blank" }.orEmpty()
                tab.title = if (tab.url.isBlank()) "New tab" else tab.url
                tab.isLoading = true
                tab.progress = 0
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.isLoading = false
                tab.progress = 100
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                tab.progress = progress.coerceIn(0, 100)
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrBlank()) tab.title = title
            }

            override fun onCloseRequest(session: GeckoSession) {
                closeTab(tab.id)
            }

            override fun onCrash(session: GeckoSession) {
                tab.isLoading = false
            }

            override fun onKill(session: GeckoSession) {
                tab.isLoading = false
            }
        }

        return tab
    }

    private fun openExternalUrl(url: String) {
        val context = getApplication<Application>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    override fun onCleared() {
        isViewModelCleared = true
        runCatching {
            networkPort?.postMessage(JSONObject()
                .put("type", "setRecording")
                .put("enabled", false)
                .put("bodyBudgetBytes", (MAX_CAPTURED_TOTAL_BYTES - storedBodyBytes).coerceAtLeast(0)))
            networkPort?.setDelegate(null)
            networkPort?.disconnect()
            networkExtension?.setMessageDelegate(null, NETWORK_NATIVE_APP)
        }
        networkPort = null
        networkExtension = null
        bodyBuffers.clear()
        networkEntries.clear()
        ownedTabs.values.toList().forEach { tab ->
            runCatching {
                tab.session.setActive(false)
                tab.session.close()
            }
        }
        ownedTabs.clear()
        tabs.clear()
        super.onCleared()
    }

    private companion object {
        const val NETWORK_EXTENSION_LOCATION = "resource://android/assets/web_extensions/network_inspector/"
        const val NETWORK_EXTENSION_ID = "network-inspector@gecko-browser.local"
        const val NETWORK_NATIVE_APP = "browser"
        const val MAX_NETWORK_ENTRIES = 1_200
        const val MAX_CAPTURED_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_CAPTURED_TOTAL_BYTES = 16 * 1024 * 1024
        const val MAX_BODY_CHUNK_BASE64_CHARACTERS = 24 * 1024

        val BROWSER_SCHEMES = setOf("http", "https", "about", "file", "data", "blob", "resource", "chrome")
    }
}

private object BrowserRuntime {
    @Volatile
    private var instance: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .webFontsEnabled(true)
                    .build(),
            ).also { instance = it }
        }
    }
}


private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeUnless { it == "null" }
}

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name)

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (!has(name) || isNull(name)) null else optLong(name)
