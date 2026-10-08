package com.example.ui.engine.gecko

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.AutomationAction
import com.example.data.model.AutomationPlayback
import com.example.data.model.AutomationStep
import com.example.data.model.BrowserTab
import com.example.devtools.InspectorMessage
import com.example.devtools.InspectorRuntime
import com.example.ui.theme.IncognitoBg
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * How long a document may stay unfinished before the app says so.
 *
 * GeckoSessions have no navigation timeout of their own, so a server that accepts the connection and
 * then stalls leaves the spinner running with nothing recorded anywhere. This only ever *reports*:
 * the load is never cancelled, because a slow site that is still working must be allowed to finish.
 */
private const val LOAD_WATCHDOG_MS = 45_000L

/** A page under heavy load can take far longer than this to become interactive. */
private const val PAGE_READY_TIMEOUT_MS = 60_000L

private const val AUTOMATION_ELEMENT_WAIT_MS = 500L
private const val AUTOMATION_ELEMENT_TIMEOUT_MS = 15_000L

/**
 * How long a click may take to start a navigation before it counts as a click that does not
 * navigate. Sampling once after a fixed sleep misreads every slow server as "no navigation".
 */
private const val AUTOMATION_NAVIGATION_START_TIMEOUT_MS = 2_500L

/**
 * One tab's browser surface, rendered by Gecko.
 *
 * The shape of this composable is deliberately the same as the WebView container's: the same
 * triggers in, the same callbacks out, the same watchdog and the same automation semantics. What
 * differs is what happens underneath — a [GeckoSession] per tab that survives being hidden, a
 * `GeckoView` showing it, and the bridge extension instead of `addJavascriptInterface`.
 */
@Composable
fun GeckoBrowserSurface(
    tab: BrowserTab,
    engine: GeckoEngine,
    isJavaScriptEnabled: Boolean,
    reloadTrigger: Long,
    navigateBackTrigger: Long,
    navigateForwardTrigger: Long,
    findQuery: String,
    findTriggerNext: Long,
    findTriggerPrev: Long,
    isRecordingAutomation: Boolean,
    automationPlayback: AutomationPlayback?,
    inspector: InspectorRuntime,
    onAutomationPlaybackFinished: (Long, String) -> Unit,
    onPageEvent: (String, String) -> Unit,
    onFindMatchesChanged: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var geckoView by remember(tab.id) { mutableStateOf<GeckoView?>(null) }
    var pageIsLoading by remember(tab.id) { mutableStateOf(tab.isLoading) }
    var pageLoadCount by remember(tab.id) { mutableIntStateOf(0) }
    var pageFinishCount by remember(tab.id) { mutableIntStateOf(0) }
    var desktopSiteApplied by remember(tab.id) { mutableStateOf(tab.isDesktopSite) }

    // Compose effects survive ordinary page progress updates. Remember the values present at mount so
    // selecting a tab does not replay a stale global reload/back/forward command.
    var lastNavigationTrigger by remember(tab.id) { mutableLongStateOf(tab.navigationTrigger) }
    var lastReloadTrigger by remember(tab.id) { mutableLongStateOf(reloadTrigger) }
    var lastBackTrigger by remember(tab.id) { mutableLongStateOf(navigateBackTrigger) }
    var lastForwardTrigger by remember(tab.id) { mutableLongStateOf(navigateForwardTrigger) }

    val session = remember(tab.id) { engine.sessions.obtain(tab, isJavaScriptEnabled) }

    val surfaceColor = if (tab.isIncognito) IncognitoBg else MaterialTheme.colorScheme.background

    // ---------------------------------------------------------------- engine events

    LaunchedEffect(tab.id) {
        engine.events.collect { event ->
            if (event.tabId != tab.id) return@collect
            when (event) {
                is TabEngineEvent.PageStarted -> {
                    pageIsLoading = true
                    pageLoadCount += 1
                }

                is TabEngineEvent.PageFinished -> {
                    pageIsLoading = false
                    pageFinishCount += 1
                }

                else -> Unit
            }
        }
    }

    // Load watchdog: one timer per navigation attempt, restarted by each onPageStart.
    LaunchedEffect(pageLoadCount) {
        if (pageLoadCount == 0) return@LaunchedEffect
        delay(LOAD_WATCHDOG_MS)
        if (!pageIsLoading) return@LaunchedEffect
        val stalledUrl = engine.sessions.currentUrl(tab.id) ?: tab.url
        onPageEvent(
            tab.id,
            "This page has still been loading after ${LOAD_WATCHDOG_MS / 1000} seconds. The site is " +
                "very slow or overloaded. Nothing has been cancelled — it may still finish."
        )
        inspector.dispatch(InspectorMessage.DocumentLoadTimeout(tab.id, stalledUrl, LOAD_WATCHDOG_MS))
    }

    // ---------------------------------------------------------------- navigation triggers

    LaunchedEffect(tab.navigationTrigger) {
        if (tab.navigationTrigger != lastNavigationTrigger) {
            lastNavigationTrigger = tab.navigationTrigger
            if (!tab.isNewTabPage) session.loadUri(tab.url)
        }
    }

    LaunchedEffect(reloadTrigger) {
        if (reloadTrigger != lastReloadTrigger) {
            lastReloadTrigger = reloadTrigger
            session.reload()
        }
    }

    LaunchedEffect(navigateBackTrigger) {
        if (navigateBackTrigger != lastBackTrigger) {
            lastBackTrigger = navigateBackTrigger
            if (tab.canGoBack) session.goBack()
        }
    }

    LaunchedEffect(navigateForwardTrigger) {
        if (navigateForwardTrigger != lastForwardTrigger) {
            lastForwardTrigger = navigateForwardTrigger
            if (tab.canGoForward) session.goForward()
        }
    }

    LaunchedEffect(tab.isDesktopSite) {
        if (desktopSiteApplied != tab.isDesktopSite) {
            desktopSiteApplied = tab.isDesktopSite
            // Gecko's own desktop mode: its real desktop user agent and viewport, not a hand-written
            // Chrome user-agent string pasted onto a mobile engine.
            engine.sessions.setDesktopSite(tab.id, tab.isDesktopSite)
            session.reload()
        }
    }

    LaunchedEffect(isJavaScriptEnabled) {
        engine.sessions.setJavaScriptEnabled(isJavaScriptEnabled)
    }

    // ---------------------------------------------------------------- find in page

    LaunchedEffect(findQuery) {
        if (findQuery.isBlank()) {
            session.finder.clear()
            onFindMatchesChanged(0, 0)
        } else {
            runFind(session, findQuery, forward = true, onFindMatchesChanged)
        }
    }

    LaunchedEffect(findTriggerNext) {
        if (findTriggerNext > 0L && findQuery.isNotBlank()) {
            runFind(session, findQuery, forward = true, onFindMatchesChanged)
        }
    }

    LaunchedEffect(findTriggerPrev) {
        if (findTriggerPrev > 0L && findQuery.isNotBlank()) {
            runFind(session, findQuery, forward = false, onFindMatchesChanged)
        }
    }

    // ---------------------------------------------------------------- automation

    LaunchedEffect(isRecordingAutomation) {
        engine.setRecorderEnabled(tab.id, isRecordingAutomation)
    }

    LaunchedEffect(automationPlayback?.runId) {
        val playback = automationPlayback ?: return@LaunchedEffect
        if (playback.tabId != tab.id) return@LaunchedEffect

        suspend fun waitUntilPageIsReady(): Boolean = withTimeoutOrNull(PAGE_READY_TIMEOUT_MS) {
            snapshotFlow { pageIsLoading }.first { isLoading -> !isLoading }
        } != null

        suspend fun waitForPageAfter(finishCountBeforeLoad: Int): Boolean =
            withTimeoutOrNull(PAGE_READY_TIMEOUT_MS) {
                snapshotFlow { pageFinishCount to pageIsLoading }.first { (finished, isLoading) ->
                    finished > finishCountBeforeLoad && !isLoading
                }
            } != null

        try {
            val currentUrl = engine.sessions.currentUrl(tab.id) ?: tab.url
            if (!samePageUrl(currentUrl, playback.automation.startUrl)) {
                val finishesBeforeLoad = pageFinishCount
                pageIsLoading = true
                session.loadUri(playback.automation.startUrl)
                if (!waitForPageAfter(finishesBeforeLoad)) {
                    onAutomationPlaybackFinished(
                        playback.runId,
                        "Timed out: the automation page had not finished loading after " +
                            "${PAGE_READY_TIMEOUT_MS / 1000} s."
                    )
                    return@LaunchedEffect
                }
            } else if (!waitUntilPageIsReady()) {
                onAutomationPlaybackFinished(
                    playback.runId,
                    "Timed out: the page was still loading after ${PAGE_READY_TIMEOUT_MS / 1000} s."
                )
                return@LaunchedEffect
            }

            for ((stepIndex, step) in playback.automation.steps.withIndex()) {
                delay(step.delayBeforeMs.coerceIn(0L, 5_000L))
                if (!waitUntilPageIsReady()) {
                    onAutomationPlaybackFinished(
                        playback.runId,
                        "Stopped at step ${stepIndex + 1}: the page did not finish loading within " +
                            "${PAGE_READY_TIMEOUT_MS / 1000} s."
                    )
                    return@LaunchedEffect
                }

                // Poll for the element until the budget runs out: a single-page app still rendering
                // under load is not the same thing as an element that does not exist.
                var result = GeckoBridge.RESULT_MISSING
                var waitedMs = 0L
                while (true) {
                    result = engine.runAutomationStep(tab.id, step)
                    if (result != GeckoBridge.RESULT_MISSING) break
                    if (waitedMs >= AUTOMATION_ELEMENT_TIMEOUT_MS) break
                    delay(AUTOMATION_ELEMENT_WAIT_MS)
                    waitedMs += AUTOMATION_ELEMENT_WAIT_MS
                }
                if (result != GeckoBridge.RESULT_DONE) {
                    onAutomationPlaybackFinished(
                        playback.runId,
                        "Stopped at step ${stepIndex + 1}: ${describeAutomationFailure(result, waitedMs)}."
                    )
                    return@LaunchedEffect
                }

                // Give click handlers time to start a document navigation; if they do, continue only
                // after the new document has finished rather than against the old page.
                if (step.action == AutomationAction.CLICK) {
                    val finishesBeforeClick = pageFinishCount
                    val loadsBeforeClick = pageLoadCount
                    val navigationStarted = withTimeoutOrNull(AUTOMATION_NAVIGATION_START_TIMEOUT_MS) {
                        snapshotFlow { Triple(pageIsLoading, pageLoadCount, pageFinishCount) }
                            .first { (loading, loads, finishes) ->
                                loading || loads > loadsBeforeClick || finishes > finishesBeforeClick
                            }
                    } != null
                    if (navigationStarted && pageFinishCount <= finishesBeforeClick) {
                        if (!waitForPageAfter(finishesBeforeClick)) {
                            onAutomationPlaybackFinished(
                                playback.runId,
                                "Stopped at step ${stepIndex + 1}: the next page did not finish " +
                                    "loading within ${PAGE_READY_TIMEOUT_MS / 1000} s."
                            )
                            return@LaunchedEffect
                        }
                    }
                } else {
                    delay(100L)
                }
            }

            onAutomationPlaybackFinished(
                playback.runId,
                "Completed ${playback.automation.steps.size} steps in \"${playback.automation.name}\"."
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onAutomationPlaybackFinished(
                playback.runId,
                "Automation stopped: ${error.message ?: "could not run the page actions"}."
            )
        }
    }

    // ---------------------------------------------------------------- renderer replacement

    LaunchedEffect(tab.id) {
        engine.rendererGone.collect { goneTabId ->
            if (goneTabId != tab.id) return@collect
            geckoView?.releaseSession()
            val replacement = engine.sessions.recreate(tab, isJavaScriptEnabled)
            geckoView?.setSession(replacement)
            pageIsLoading = false
            if (!tab.isNewTabPage) replacement.loadUri(tab.url)
        }
    }

    // The page's content script connects once the document starts; until then automation commands
    // have nowhere to go, so recording state is re-sent on every page start.
    LaunchedEffect(pageFinishCount) {
        if (isRecordingAutomation) engine.setRecorderEnabled(tab.id, true)
    }

    // ---------------------------------------------------------------- surface

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surfaceColor)
            .testTag("gecko_container_${tab.id}")
    ) {
        key(tab.id) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    GeckoView(viewContext).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setSession(session)
                        // The factory is the sole initial-load path, matching the WebView container.
                        if (!tab.isNewTabPage) session.loadUri(tab.url)
                        geckoView = this
                    }
                },
                update = { view -> geckoView = view }
            )
        }

        val selectionEvent by engine.selection.collectAsState()
        val selection = selectionEvent?.takeIf { it.tabId == tab.id }?.selection
        if (selection != null) {
            SelectionActionsBar(
                selection = selection,
                onCopy = { selection.copy() },
                onCut = { selection.cut() },
                onPaste = { selection.paste() },
                onSelectAll = { selection.selectAll() },
                onShare = { engine.share(selection.text, null) },
                onDismiss = { selection.hide() },
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
            )
        }
    }

    DisposableEffect(tab.id) {
        engine.sessions.session(tab.id)?.setActive(true)
        onDispose {
            geckoView?.releaseSession()
            geckoView = null
            // The session is *not* closed here: it keeps the tab's history for when the user comes
            // back to this tab. Sessions are closed when the tab is closed.
            engine.sessions.session(tab.id)?.setActive(false)
            engine.dismissSelection()
        }
    }

}

private fun describeAutomationFailure(result: String, waitedMs: Long): String = when (result) {
    GeckoBridge.RESULT_MISSING ->
        "the element was still not on the page after ${AUTOMATION_ELEMENT_TIMEOUT_MS / 1000} s of waiting"

    GeckoBridge.RESULT_ERROR -> "the page rejected the action"
    GeckoBridge.RESULT_TIMEOUT -> "the page did not answer the command in time"
    GeckoBridge.BRIDGE_UNAVAILABLE ->
        "the page bridge is not connected yet (the browser extension may still be installing, or the " +
            "document has not started)"
    else -> "the action could not be run (${result}, after ${waitedMs} ms)"
}

private fun runFind(
    session: GeckoSession,
    query: String,
    forward: Boolean,
    onFindMatchesChanged: (Int, Int) -> Unit,
) {
    val flags = if (forward) {
        GeckoSession.FINDER_FIND_FORWARD
    } else {
        GeckoSession.FINDER_FIND_BACKWARDS
    }
    session.finder.find(query, flags).accept(
        { result -> if (result != null) onFindMatchesChanged(result.current, result.total) },
        { onFindMatchesChanged(0, 0) }
    )
}

private fun samePageUrl(first: String?, second: String?): Boolean {
    if (first.isNullOrBlank() || second.isNullOrBlank()) return false
    fun withoutFragment(url: String): String = url.substringBefore('#').trimEnd('/')
    return withoutFragment(first) == withoutFragment(second)
}

/**
 * The actions bar for a text selection.
 *
 * Android WebView drew its own selection menu, so an app that implemented nothing still got copy and
 * paste. GeckoView deliberately leaves it to the embedder (`SelectionActionDelegate`), and a
 * selection with no bar would look like a broken long-press — so this reproduces the actions the
 * engine says are available for the current selection.
 */
@Composable
private fun SelectionActionsBar(
    selection: GeckoSession.SelectionActionDelegate.Selection,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 6.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (selection.isActionAvailable(GeckoSession.SelectionActionDelegate.ACTION_COPY)) {
                TextButton(onClick = { onCopy(); onDismiss() }) { Text("Copy") }
            }
            if (selection.isActionAvailable(GeckoSession.SelectionActionDelegate.ACTION_CUT)) {
                TextButton(onClick = { onCut(); onDismiss() }) { Text("Cut") }
            }
            if (selection.isActionAvailable(GeckoSession.SelectionActionDelegate.ACTION_PASTE)) {
                TextButton(onClick = { onPaste(); onDismiss() }) { Text("Paste") }
            }
            if (selection.isActionAvailable(GeckoSession.SelectionActionDelegate.ACTION_SELECT_ALL)) {
                TextButton(onClick = onSelectAll) { Text("Select all") }
            }
            if (selection.text.isNotBlank()) {
                TextButton(onClick = { onShare(); onDismiss() }) { Text("Share") }
            }
        }
    }
}
