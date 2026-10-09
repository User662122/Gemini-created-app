package com.example.devtools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything a WebView callback needs to talk to the inspector.
 *
 * The messages are plain data classes so the WebView integration can stay a thin adapter: the client
 * subclasses build an observation and hand it over, and all the logic lives behind this interface.
 */
sealed class InspectorMessage {
    data class RequestStarted(val observation: RequestObservation) : InspectorMessage()
    data class ResponseReceived(val observation: ResponseObservation) : InspectorMessage()
    data class RequestFailed(val observation: FailureObservation) : InspectorMessage()
    data class HttpError(val observation: HttpErrorObservation) : InspectorMessage()
    data class SslError(val observation: SslErrorObservation) : InspectorMessage()
    data class DocumentLoadTimeout(
        val tabId: String,
        val url: String?,
        val elapsedMillis: Long,
    ) : InspectorMessage()
    data class AuthChallenge(val observation: AuthObservation) : InspectorMessage()
    data class PageRecords(val tabId: String, val json: String) : InspectorMessage()
    data class Console(
        val tabId: String,
        val level: ConsoleLevel,
        val message: String,
        val source: String?,
        val lineNumber: Int?,
    ) : InspectorMessage()

    data class AppHttpExchange(
        val request: RequestObservation,
        val response: ResponseObservation,
    ) : InspectorMessage()

    data class DocumentStarted(val tabId: String) : InspectorMessage()
    data class DocumentFinished(val tabId: String, val url: String?, val title: String?) : InspectorMessage()
    data class CookieStoreChanged(val url: String?) : InspectorMessage()
    data class WebViewDestroyed(val tabId: String) : InspectorMessage()
    /** One HTTP exchange as the embedded engine's webRequest saw it. */
    data class EngineRequest(val record: EngineRequestRecord) : InspectorMessage()
    /** The embedded engine's cookie store: a full dump, or one changed/removed cookie. */
    data class EngineCookies(val cookies: List<EngineCookie>, val removed: Boolean) : InspectorMessage()
}

/**
 * The inspector as the rest of the app sees it.
 *
 * Two implementations exist: [InspectorController] (debug builds, real capture) and
 * [NullInspectorRuntime] (release builds and any build where the inspector is switched off). The UI
 * and the WebView integration are written against this interface only, so a release build cannot
 * reach capture code even by accident.
 */
interface InspectorRuntime {

    /** The observer handed to the WebView clients. Always safe to call. */
    val observer: NetworkObserver

    /** True while the inspector is capturing. */
    val enabled: Boolean

    /**
     * The capture switches as one immutable snapshot. The Gecko engine's capture channel reads this
     * to answer the bridge extension's settings pull, so the extension attaches its listeners only
     * while capture is actually on (and never in a release build, where this is
     * [CapturePolicy.DISABLED]).
     */
    fun capturePolicy(): CapturePolicy

    /**
     * Diagnostics for the export's CAPTURE SOURCE section: where the engine's capture pipeline
     * currently stands (extension version, delegate registered, records received). Empty under the
     * WebView engine and in release builds, where there is nothing engine-specific to report.
     */
    fun engineCaptureDiagnostics(): List<Pair<String, String>>

    /** Changes whenever the injected script must be re-evaluated (settings changes). */
    val scriptToken: Long

    val uiState: StateFlow<InspectorUiState>

    fun installScript(): String

    fun dispatch(message: InspectorMessage)

    fun attachCookieScopeProvider(provider: CookieScopeProvider)

    /** Tells the inspector which tab is on screen; used for tab scoping and cookie queries. */
    fun setCurrentTab(tabId: String)

    /** Keeps the publishing ticker idle while no inspector screen is open. */
    fun setScreensVisible(visible: Boolean)

    fun setSetting(setting: InspectorSetting, value: Boolean)

    fun updateFilter(filter: InspectorFilterState)

    fun clearEntries()

    fun clearConsole()

    fun clearAll()

    fun openEntry(id: Long)

    fun closeEntry()

    fun refreshCookies()

    /**
     * Renders everything the inspector currently holds (entries, console, cookies, settings,
     * counters) as a file body in the requested format. Empty in release builds.
     */
    fun buildExport(format: ExportFormat): String

    /** Drops the captured buffers at the end of a session; the switches themselves persist. */
    fun endSession()

    fun close()
}

/** No-op runtime: release builds, non-debuggable packages, and "inspector switched off". */
object NullInspectorRuntime : InspectorRuntime {

    private val emptyState = MutableStateFlow(
        InspectorUiState(available = false, unavailableReason = DevToolsGate.unavailableReason())
    )

    override val observer: NetworkObserver = NullNetworkObserver
    override val enabled: Boolean = false
    override fun capturePolicy(): CapturePolicy = CapturePolicy.DISABLED
    override fun engineCaptureDiagnostics(): List<Pair<String, String>> = emptyList()
    override val scriptToken: Long = 0L
    override val uiState: StateFlow<InspectorUiState> = emptyState

    override fun installScript(): String = ""
    override fun dispatch(message: InspectorMessage) = Unit
    override fun attachCookieScopeProvider(provider: CookieScopeProvider) = Unit
    override fun setCurrentTab(tabId: String) = Unit
    override fun setScreensVisible(visible: Boolean) = Unit
    override fun setSetting(setting: InspectorSetting, value: Boolean) = Unit
    override fun updateFilter(filter: InspectorFilterState) = Unit
    override fun clearEntries() = Unit
    override fun clearConsole() = Unit
    override fun clearAll() = Unit
    override fun openEntry(id: Long) = Unit
    override fun closeEntry() = Unit
    override fun refreshCookies() = Unit
    override fun buildExport(format: ExportFormat): String = ""
    override fun endSession() = Unit
    override fun close() = Unit
}


