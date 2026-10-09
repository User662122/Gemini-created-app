package com.example.ui.engine.gecko

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.data.DownloadRegistry
import com.example.data.model.AutomationStep
import com.example.devtools.InspectorController
import com.example.devtools.InspectorRuntime
import com.example.devtools.NullInspectorRuntime
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.GeckoSession

/**
 * Per-tab engine events, for the one container that is currently showing the tab.
 *
 * The app-level callbacks below already update the tab model; this stream exists so the surface can
 * keep its own load counters and watchdog without re-implementing the delegate wiring, and so hidden
 * tabs cost nothing (buffered, best-effort delivery).
 */
sealed interface TabEngineEvent {
    val tabId: String

    data class PageStarted(override val tabId: String, val url: String) : TabEngineEvent
    data class PageFinished(override val tabId: String, val url: String, val title: String?) : TabEngineEvent
    data class UrlChanged(override val tabId: String, val url: String) : TabEngineEvent
    data class Progress(override val tabId: String, val progress: Int) : TabEngineEvent
    data class NavigationState(
        override val tabId: String,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
    ) : TabEngineEvent
}

/** A text selection request for one tab, or null when it was dismissed. */
data class SelectionEvent(
    val tabId: String,
    val selection: GeckoSession.SelectionActionDelegate.Selection?,
)

/**
 * The Gecko engine as the browser UI sees it: one object, created once per screen, that owns the
 * runtime, the per-tab sessions, the bridge extension, the download writer and the prompt queue.
 *
 * It exists so that nothing above it has to know about GeckoView types beyond this file and
 * [GeckoBrowserSurface], and so that session lifetime is tied to the app's tab list rather than to a
 * Compose recomposition.
 */
class GeckoEngine(
    private val context: Context,
    private val registry: DownloadRegistry,
    onDownloadStatus: (String) -> Unit,
    onExternalApp: (String) -> Unit,
    onRecordedStep: (String, AutomationStep) -> Unit,
    onPageError: (String, String, String?, Int?) -> Unit,
    onPageStarted: (String, String) -> Unit,
    onUrlChanged: (String, String) -> Unit,
    onPageFinished: (String, String, String?) -> Unit,
    onProgressChanged: (String, Int) -> Unit,
    onPageEvent: (String, String) -> Unit,
    onNavigationStateChanged: (String, Boolean, Boolean) -> Unit,
    onNewTab: (String) -> String,
    onCloseTab: (String) -> Unit,
    /** A load or content-process failure, for the developer inspector: (tabId, url, why, code). */
    onLoadFailure: (String, String?, String, Int) -> Unit = { _, _, _, _ -> },
    /** The developer inspector, fed by [GeckoNetworkCapture] under this engine. */
    inspector: InspectorRuntime = NullInspectorRuntime,
    /** The app's tab ids mapped to their current URLs; used to attribute captured requests. */
    tabUrls: () -> Map<String, String> = { emptyMap() },
) {

    val promptHost = GeckoPromptHost()

    private val bridge = GeckoBridge(
        onRecordedStep = onRecordedStep,
        onPageError = onPageError,
    )

    /**
     * The Network Inspector's capture channel: Gecko's `webRequest`/`cookies` observations,
     * relayed by the bridge extension's background script. Inert in release builds, where
     * [inspector] is [NullInspectorRuntime] and the extension attaches no listeners.
     */
    private val networkCapture = GeckoNetworkCapture(inspector, tabUrls)

    private val downloads = GeckoDownloader(
        context = context,
        registry = registry,
        onStatus = onDownloadStatus,
        onExternalApp = onExternalApp,
    )

    /** Tabs whose content process died and whose session has to be replaced. */
    val rendererGone = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val _events = MutableSharedFlow<TabEngineEvent>(extraBufferCapacity = 64)

    /** Load-lifecycle events for the surface that is showing a tab. */
    val events: SharedFlow<TabEngineEvent> = _events.asSharedFlow()

    private val _selection = MutableStateFlow<SelectionEvent?>(null)

    /** The most recent text-selection request, for the container to draw actions for. */
    val selection: StateFlow<SelectionEvent?> = _selection.asStateFlow()

    val sessions = GeckoTabSessions(
        context = context,
        promptHost = promptHost,
        downloads = downloads,
        bridge = bridge,
        callbacks = GeckoEngineCallbacks(
            onPageStarted = { tabId, url ->
                onPageStarted(tabId, url)
                _events.tryEmit(TabEngineEvent.PageStarted(tabId, url))
            },
            onUrlChanged = { tabId, url ->
                onUrlChanged(tabId, url)
                _events.tryEmit(TabEngineEvent.UrlChanged(tabId, url))
            },
            onPageFinished = { tabId, url, title ->
                onPageFinished(tabId, url, title)
                _events.tryEmit(TabEngineEvent.PageFinished(tabId, url, title))
            },
            onProgressChanged = { tabId, progress ->
                onProgressChanged(tabId, progress)
                _events.tryEmit(TabEngineEvent.Progress(tabId, progress))
            },
            onPageEvent = onPageEvent,
            onNavigationStateChanged = { tabId, canGoBack, canGoForward ->
                onNavigationStateChanged(tabId, canGoBack, canGoForward)
                _events.tryEmit(TabEngineEvent.NavigationState(tabId, canGoBack, canGoForward))
            },
            onExternalApp = onExternalApp,
            onNewTab = onNewTab,
            onCloseTab = onCloseTab,
            onRendererGone = { tabId, _ -> rendererGone.tryEmit(tabId) },
            onSelectionChanged = { tabId, selection -> _selection.value = SelectionEvent(tabId, selection) },
            onShareRequested = ::share,
            onLoadFailure = onLoadFailure,
        ),
    )

    /**
     * Creates the Gecko runtime and installs the bundled bridge extension.
     *
     * Both are process-wide and idempotent, so this is called on first use rather than at startup:
     * the app only pays for a browser engine once a useful engine choice has been made.
     */
    fun start() {
        GeckoRuntimeManager.get(context)
        bridge.install(context)
        networkCapture.attach()
        // The inspector's export prints where this engine's capture pipeline stands.
        (inspector as? InspectorController)?.engineDiagnosticsProvider = { GeckoNetworkCapture.diagnostics() }
    }

    /** Re-registers the bridge on every live session, for when the extension finished loading late. */
    fun attachBridgeToOpenSessions() {
        sessions.liveTabIds().forEach { tabId ->
            sessions.session(tabId)?.let { session -> bridge.attach(tabId, session) }
        }
    }

    fun setRecorderEnabled(tabId: String, enabled: Boolean) = bridge.setRecorderEnabled(tabId, enabled)

    /** Returns one of [GeckoBridge.RESULT_DONE], [GeckoBridge.RESULT_MISSING], … or a timeout/absence. */
    suspend fun runAutomationStep(tabId: String, step: AutomationStep): String =
        bridge.runAutomationStep(tabId, step)

    fun dismissSelection() {
        _selection.value = null
    }

    /** Closes sessions for tabs the app no longer has. */
    fun keepSessionsFor(liveTabIds: Set<String>) = sessions.keepOnly(liveTabIds)

    /**
     * Releases every session this engine owns.
     *
     * Called when the screen that holds the engine goes away for good (the activity being recreated,
     * for example). Tab *state* lives in the ViewModel and survives that; the engine's sessions do
     * not, so they are closed here rather than left to leak native resources.
     */
    fun close() {
        promptHost.clear()
        sessions.closeAll()
    }

    /**
     * `navigator.share` and the selection bar's Share action.
     *
     * Returns whether Android's share sheet was shown; a device with no share target gets `false`
     * rather than a page that waits forever for a share that never happened.
     */
    fun share(text: String?, uri: String?): Boolean {
        val payload = listOfNotNull(
            text?.takeIf { it.isNotBlank() },
            uri?.takeIf { it.isNotBlank() }
        ).joinToString(" ").takeIf { it.isNotBlank() } ?: return false

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, payload)
        }
        val chooser = Intent.createChooser(intent, "Share").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            ContextCompat.startActivity(context, chooser, null)
            true
        }.getOrDefault(false)
    }
}
