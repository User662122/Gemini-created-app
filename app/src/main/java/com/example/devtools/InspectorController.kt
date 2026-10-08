package com.example.devtools

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The inspector's brain: owns the capture engine ([LiveNetworkObserver]), the cookie reader, the
 * settings, and the throttled publication of snapshots to the UI.
 *
 * Performance contract:
 *
 *  * Capture happens on WebView threads and never blocks them for longer than a few microseconds
 *    (see [LiveNetworkObserver]).
 *  * The UI is refreshed by a ticker that runs at most four times a second, and only while an
 *    inspector screen is open. While browsing with the inspector closed, nothing is rendered and no
 *    snapshot is built.
 *  * Cookie reads (which cross the browser process) run on a dedicated looper thread with a timeout,
 *    triggered only when the Cookie Inspector is on screen or the cookie store actually changed.
 */
class InspectorController(
    context: Context,
    private val scope: CoroutineScope,
) : InspectorRuntime {

    private val appContext = context.applicationContext
    private val settings = InspectorSettingsStore(appContext)
    private val liveObserver = LiveNetworkObserver { settings.policy() }
    private val cookieInspector = CookieInspector(
        cookieManager = { CookieManager.getInstance() },
        store = liveObserver.store,
        revealSensitiveValues = { settings.policy().revealSensitiveValues },
    )

    override val observer: NetworkObserver = liveObserver
    override val enabled: Boolean get() = settings.policy().enabled
    override val scriptToken: Long get() = settings.version().toLong()

    private val _uiState = MutableStateFlow(
        InspectorUiState(
            available = true,
            enabled = settings.policy().enabled,
            settings = settingsSnapshot(),
            unavailableReason = "",
        )
    )
    override val uiState: StateFlow<InspectorUiState> = _uiState.asStateFlow()

    private var currentTabId: String = ""
    private var screensVisible: Boolean = false
    private var tickerJob: Job? = null
    private var lastRevision: Long = -1L
    private var lastSettingsVersion: Int = -1
    private var cookieRefreshInFlight: Boolean = false
    private var cookieRefreshQueued: Boolean = false

    init {
        liveObserver.onCookieStoreChanged = {
            // Called on the CookieManager callback thread; only flips a flag.
            cookieRefreshQueued = true
        }
        startTicker()
    }

    // ------------------------------------------------------------------------- InspectorRuntime

    override fun installScript(): String = settings.installScript()

    override fun dispatch(message: InspectorMessage) {
        when (message) {
            is InspectorMessage.RequestStarted -> liveObserver.onRequestStarted(message.observation)
            is InspectorMessage.ResponseReceived -> liveObserver.onResponseReceived(message.observation)
            is InspectorMessage.RequestFailed -> liveObserver.onRequestFailed(message.observation)
            is InspectorMessage.HttpError -> liveObserver.onHttpError(message.observation)
            is InspectorMessage.SslError -> liveObserver.onSslError(message.observation)
            is InspectorMessage.DocumentLoadTimeout -> liveObserver.onDocumentLoadTimeout(
                tabId = message.tabId,
                url = message.url,
                elapsedMillis = message.elapsedMillis,
            )
            is InspectorMessage.AuthChallenge -> liveObserver.onAuthenticationRequest(message.observation)
            is InspectorMessage.PageRecords -> liveObserver.onPageRecords(message.tabId, message.json)
            is InspectorMessage.Console -> liveObserver.onConsoleMessage(
                tabId = message.tabId,
                level = message.level,
                message = message.message,
                source = message.source,
                lineNumber = message.lineNumber,
                stackTrace = null,
            )
            is InspectorMessage.AppHttpExchange ->
                liveObserver.onAppHttpExchange(message.request, message.response)
            is InspectorMessage.DocumentStarted -> liveObserver.onDocumentStarted(message.tabId)
            is InspectorMessage.DocumentFinished ->
                liveObserver.onDocumentFinished(message.tabId, message.url, message.title)
            is InspectorMessage.CookieStoreChanged -> liveObserver.onCookieStoreChanged(message.url)
            is InspectorMessage.WebViewDestroyed -> liveObserver.onWebViewDestroyed(message.tabId)
        }
    }

    override fun attachCookieScopeProvider(provider: CookieScopeProvider) {
        cookieInspector.scopeProvider = provider
        cookieRefreshQueued = true
    }

    override fun setCurrentTab(tabId: String) {
        if (tabId == currentTabId) return
        currentTabId = tabId
        cookieRefreshQueued = true
        if (screensVisible) publish(System.currentTimeMillis())
    }

    override fun setScreensVisible(visible: Boolean) {
        if (screensVisible == visible) return
        screensVisible = visible
        if (!visible) return
        cookieRefreshQueued = true
        publish(System.currentTimeMillis())
    }

    override fun setSetting(setting: InspectorSetting, value: Boolean) {
        val policyChanged = setting == InspectorSetting.FULL_CAPTURE
        settings.set(setting, value)
        if (policyChanged) {
            // Correlation keys depend on how URLs are stored, so in-flight requests from the old mode
            // are dropped instead of being paired with a response indexed under the other form.
            liveObserver.clearPendingCorrelations()
        }
        if (setting == InspectorSetting.ENABLED && !value) {
            // Switching off must leave nothing behind, in memory or on screen.
            liveObserver.store.clearAll()
            _uiState.value = _uiState.value.copy(
                entries = emptyList(),
                console = emptyList(),
                cookies = emptyList(),
                openEntry = null,
                totalEntries = 0,
                consoleTotal = 0,
            )
        }
        if (setting == InspectorSetting.ENABLED || setting == InspectorSetting.CONSOLE) {
            lastRevision = -1L
        }
        publish(System.currentTimeMillis())
    }

    override fun updateFilter(filter: InspectorFilterState) {
        _uiState.value = _uiState.value.copy(filter = filter)
        publish(System.currentTimeMillis())
    }

    override fun clearEntries() {
        liveObserver.store.clearEntries()
        _uiState.value = _uiState.value.copy(openEntry = null)
        publish(System.currentTimeMillis())
    }

    override fun clearConsole() {
        liveObserver.store.clearConsole()
        publish(System.currentTimeMillis())
    }

    override fun clearAll() {
        liveObserver.store.clearAll()
        _uiState.value = _uiState.value.copy(openEntry = null, cookies = emptyList())
        publish(System.currentTimeMillis())
    }

    override fun openEntry(id: Long) {
        val snapshot = liveObserver.store.snapshot(System.currentTimeMillis())
        val entry = snapshot.entries.firstOrNull { it.id == id }
        if (entry != null) {
            _uiState.value = _uiState.value.copy(openEntry = entry)
        }
    }

    override fun closeEntry() {
        if (_uiState.value.openEntry != null) {
            _uiState.value = _uiState.value.copy(openEntry = null)
        }
    }

    override fun refreshCookies() {
        cookieRefreshQueued = true
        publish(System.currentTimeMillis())
    }

    override fun buildExport(format: ExportFormat): String {
        val policy = settings.policy()
        if (!policy.enabled) return ""
        val now = System.currentTimeMillis()
        val store = liveObserver.store
        val snapshot = store.snapshot(now)
        val dropped = store.droppedCounts()
        return InspectorExport.render(
            format = format,
            exportedAtMillis = now,
            settings = settingsSnapshot(),
            snapshot = snapshot,
            droppedConsoleRows = dropped.second,
            cookies = _uiState.value.cookies,
            droppedByRateLimit = liveObserver.droppedByRateLimit(),
            droppedByPageScript = liveObserver.droppedByPageScript(),
            sessionStartedAtMillis = store.sessionStartedAt(),
            incidents = store.incidents(),
        )
    }

    /**
     * End of a browsing session: the captured buffers are dropped, the switches are not.
     *
     * The buffers are memory-only, so leaving them behind would just hold tokens in a process that
     * nobody is watching. Anything the developer wants to keep should be exported first — that is what
     * the download action is for.
     */
    override fun endSession() {
        liveObserver.store.clearAll()
        _uiState.value = _uiState.value.copy(openEntry = null, cookies = emptyList())
        publish(System.currentTimeMillis())
    }

    override fun close() {
        tickerJob?.cancel()
        tickerJob = null
        cookieInspector.close()
    }

    // ------------------------------------------------------------------------------- publishing

    private fun startTicker() {
        if (tickerJob != null) return
        tickerJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                delay(InspectorLimits.PUBLISH_INTERVAL_MS)
                tick()
            }
        }
    }

    /** Runs on the main thread at most four times a second. */
    private fun tick() {
        val now = System.currentTimeMillis()

        // Requests that never received any callback are marked "unobserved" so the list cannot show a
        // request as in-flight forever. Internally throttled.
        liveObserver.expireStalePendingEntries(now)

        if (!screensVisible) return

        if (cookieRefreshQueued && !cookieRefreshInFlight) {
            cookieRefreshQueued = false
            refreshCookiesAsync()
        }

        val revision = liveObserver.store.revision
        val version = settings.version()
        val consoleCount = liveObserver.consumeConsoleCount()
        val cookieEvents = liveObserver.consumeCookieEventCount()
        val needsPublish = revision != lastRevision ||
            version != lastSettingsVersion ||
            consoleCount > 0L ||
            cookieEvents > 0L
        if (!needsPublish) return
        publish(now)
    }

    /**
     * Builds one immutable snapshot for the UI. Main thread only: the display formatters it uses are
     * not thread-safe, and all the heavy capture work has already happened elsewhere.
     */
    private fun publish(now: Long) {
        val policy = settings.policy()
        val store = liveObserver.store
        val snapshot = store.snapshot(now)
        val filter = _uiState.value.filter

        val scopedEntries = when {
            filter.onlyCurrentTab && currentTabId.isNotEmpty() ->
                snapshot.entries.filter { it.tabId == currentTabId }
            else -> snapshot.entries
        }
        val filtered = InspectorFilters.apply(scopedEntries, filter)
        val scopedConsole = if (filter.onlyCurrentTab && currentTabId.isNotEmpty()) {
            snapshot.console.filter { it.tabId == currentTabId }
        } else {
            snapshot.console
        }

        val openEntryId = _uiState.value.openEntry?.id
        val refreshedOpenEntry = openEntryId?.let { id -> snapshot.entries.firstOrNull { it.id == id } }

        _uiState.value = InspectorUiState(
            available = true,
            enabled = policy.enabled,
            settings = settingsSnapshot(),
            unavailableReason = "",
            filter = filter,
            entries = filtered.map { toView(it) },
            totalEntries = scopedEntries.size,
            console = scopedConsole,
            consoleTotal = scopedConsole.size,
            cookies = _uiState.value.cookies,
            cookieRefreshPending = cookieRefreshInFlight,
            openEntry = refreshedOpenEntry ?: _uiState.value.openEntry,
            droppedEntries = snapshot.droppedEntryCount,
            droppedConsole = store.droppedConsoleRows(),
            droppedByRateLimit = liveObserver.droppedByRateLimit(),
            droppedByPageScript = liveObserver.droppedByPageScript(),
            currentTabId = currentTabId,
            scriptToken = scriptToken,
        )

        lastRevision = snapshot.revision
        lastSettingsVersion = settings.version()
    }

    private fun toView(entry: NetworkEntry): InspectorEntryView = InspectorEntryView(
        id = entry.id,
        url = entry.url,
        displayUrl = InspectorFormatting.shortUrl(entry.url),
        host = UrlParts.host(entry.url) ?: "",
        method = if (entry.method == UNKNOWN_METHOD) "—" else entry.method,
        statusLabel = InspectorFormatting.statusLabel(entry),
        statusCode = entry.statusCode,
        state = entry.state,
        categoryLabel = entry.request.category.label,
        categorySourceLabel = if (entry.request.categorySource == EvidenceSource.DERIVED) "inferred" else null,
        timeLabel = InspectorFormatting.time(entry.request.startedAtMillis),
        durationLabel = InspectorFormatting.duration(entry.response?.durationMillis),
        redacted = InspectorFilters.hasRedactions(entry),
        noteCount = entry.notes.size,
        pageObserved = entry.pageObserved,
    )

    private fun settingsSnapshot(): Map<InspectorSetting, Boolean> =
        InspectorSetting.values().associateWith { settings.get(it) }

    private fun refreshCookiesAsync() {
        cookieRefreshInFlight = true
        val tabId = currentTabId
        _uiState.value = _uiState.value.copy(cookieRefreshPending = true)
        scope.launch(Dispatchers.IO) {
            val records = try {
                cookieInspector.collect(tabId)
            } catch (_: Throwable) {
                emptyList()
            }
            withContext(Dispatchers.Main.immediate) {
                cookieRefreshInFlight = false
                _uiState.value = _uiState.value.copy(
                    cookies = records,
                    cookieRefreshPending = false,
                )
                if (cookieRefreshQueued) {
                    cookieRefreshQueued = false
                    publish(System.currentTimeMillis())
                }
            }
        }
    }

    companion object {
        /** Convenience factory used by the debug `Application` subclass. */
        fun create(context: Context, scope: CoroutineScope): InspectorRuntime =
            if (DevToolsGate.isInspectorAvailable(context)) {
                InspectorController(context, scope)
            } else {
                NullInspectorRuntime
            }
    }
}
