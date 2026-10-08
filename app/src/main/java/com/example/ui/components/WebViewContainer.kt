package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.AutomationAction
import com.example.data.model.AutomationPlayback
import com.example.data.model.AutomationStep
import com.example.data.model.BrowserTab
import com.example.devtools.InspectorJsBridge
import com.example.devtools.InspectorMessage
import com.example.devtools.InspectorRuntime
import com.example.devtools.InspectorScripts
import com.example.devtools.NetworkInspectorWebChromeClient
import com.example.devtools.NetworkInspectorWebViewClient
import com.example.ui.theme.IncognitoBg
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
private const val AUTOMATION_BRIDGE_NAME = "BrowserAutomation"

/**
 * How long a document may stay unfinished before the app says so.
 *
 * WebView has no navigation timeout of its own, so a server that accepts the connection and then
 * stalls leaves the spinner running forever with nothing recorded anywhere. This is generous on
 * purpose — it is well past a normal page load — and it only ever *reports*: the load is never
 * cancelled, because a slow site that is still working must be allowed to finish.
 */
private const val LOAD_WATCHDOG_MS = 45_000L

/** A page under heavy load can take far longer than this to become interactive, so keep it generous. */
private const val PAGE_READY_TIMEOUT_MS = 60_000L
private const val AUTOMATION_ELEMENT_WAIT_MS = 500L

/** Total time one automation step may wait for its element to appear on a slow page. */
private const val AUTOMATION_ELEMENT_TIMEOUT_MS = 15_000L

/**
 * How long a click may take to start a navigation before it is treated as a click that does not
 * navigate. Sampling once after a short fixed sleep instead misreads every slow server as
 * "no navigation happened" and runs the next step against a page that is about to be replaced.
 */
private const val AUTOMATION_NAVIGATION_START_TIMEOUT_MS = 2_500L
private const val MAX_AUTOMATION_SELECTOR_LENGTH = 1_000
private const val MAX_AUTOMATION_VALUE_LENGTH = 2_000

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewContainer(
    tab: BrowserTab,
    isJavaScriptEnabled: Boolean,
    reloadTrigger: Long,
    navigateBackTrigger: Long,
    navigateForwardTrigger: Long,
    findQuery: String,
    findTriggerNext: Long,
    findTriggerPrev: Long,
    isRecordingAutomation: Boolean,
    automationPlayback: AutomationPlayback?,
    /** Developer-mode Network Inspector. Always present; a release build resolves to a no-op. */
    inspector: InspectorRuntime,
    /** Changes when the inspector's settings change, so the page hooks are re-installed. */
    inspectorScriptToken: Long,
    onAutomationStepRecorded: (String, AutomationStep) -> Unit,
    onAutomationPlaybackFinished: (Long, String) -> Unit,
    onPageStarted: (String, String) -> Unit,
    onPageFinished: (String, String, String?) -> Unit,
    onProgressChanged: (String, Int) -> Unit,
    /**
     * Reports why a page did not work, in words a person can act on: a refused certificate, a
     * main-frame transport error, an HTTP status the server rejected the navigation with, a renderer
     * the system killed, or a document that stalled.
     *
     * These are the failures that otherwise show up as a blank screen with no explanation, because
     * WebView cancels or abandons the load and the page never runs far enough to report anything.
     */
    onPageEvent: (String, String) -> Unit,
    onNavigationStateChanged: (String, Boolean, Boolean) -> Unit,
    onFindMatchesChanged: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var webViewInstance by remember(tab.id) { mutableStateOf<WebView?>(null) }
    var defaultUserAgent by remember(tab.id) { mutableStateOf<String?>(null) }
    var currentDesktopMode by remember(tab.id) { mutableStateOf(tab.isDesktopSite) }
    var webViewGeneration by remember(tab.id) { mutableIntStateOf(0) }
    var pageIsLoading by remember(tab.id) { mutableStateOf(tab.isLoading) }
    var pageLoadCount by remember(tab.id) { mutableIntStateOf(0) }
    var pageFinishCount by remember(tab.id) { mutableIntStateOf(0) }

    // Compose effects survive ordinary page progress updates. Remember the values present at
    // mount so selecting a tab does not replay stale global reload/back/forward commands.
    var lastNavigationTrigger by remember(tab.id) { mutableLongStateOf(tab.navigationTrigger) }
    var lastReloadTrigger by remember(tab.id) { mutableLongStateOf(reloadTrigger) }
    var lastBackTrigger by remember(tab.id) { mutableLongStateOf(navigateBackTrigger) }
    var lastForwardTrigger by remember(tab.id) { mutableLongStateOf(navigateForwardTrigger) }

    val automationRecordingEnabled = remember(tab.id) { AtomicBoolean(isRecordingAutomation) }
    val automationStepCallback = remember(tab.id) { AtomicReference(onAutomationStepRecorded) }
    val automationPlaybackReference = remember(tab.id) { AtomicReference(automationPlayback) }
    SideEffect {
        automationRecordingEnabled.set(isRecordingAutomation)
        automationStepCallback.set(onAutomationStepRecorded)
        automationPlaybackReference.set(automationPlayback)
    }

    val surfaceColor = if (tab.isIncognito) IncognitoBg else MaterialTheme.colorScheme.background
    val webViewBackgroundColor = surfaceColor.toArgb()

    // One explicit navigation trigger must result in one load. The original factory plus this
    // effect both loaded the initial URL, which caused a duplicate navigation and a blank flash.
    LaunchedEffect(tab.navigationTrigger) {
        if (tab.navigationTrigger != lastNavigationTrigger) {
            lastNavigationTrigger = tab.navigationTrigger
            if (!tab.isNewTabPage) webViewInstance?.loadUrl(tab.url)
        }
    }

    LaunchedEffect(reloadTrigger) {
        if (reloadTrigger != lastReloadTrigger) {
            lastReloadTrigger = reloadTrigger
            webViewInstance?.reload()
        }
    }

    LaunchedEffect(navigateBackTrigger) {
        if (navigateBackTrigger != lastBackTrigger) {
            lastBackTrigger = navigateBackTrigger
            webViewInstance?.let { webView ->
                if (webView.canGoBack()) webView.goBack()
            }
        }
    }

    LaunchedEffect(navigateForwardTrigger) {
        if (navigateForwardTrigger != lastForwardTrigger) {
            lastForwardTrigger = navigateForwardTrigger
            webViewInstance?.let { webView ->
                if (webView.canGoForward()) webView.goForward()
            }
        }
    }

    LaunchedEffect(tab.isDesktopSite) {
        if (currentDesktopMode != tab.isDesktopSite) {
            currentDesktopMode = tab.isDesktopSite
            webViewInstance?.let { webView ->
                val targetUserAgent = if (tab.isDesktopSite) DESKTOP_USER_AGENT else defaultUserAgent
                webView.settings.userAgentString = targetUserAgent
                webView.reload()
            }
        }
    }

    LaunchedEffect(isJavaScriptEnabled) {
        webViewInstance?.settings?.javaScriptEnabled = isJavaScriptEnabled
    }

    // Load watchdog: one timer per navigation attempt (pageLoadCount increments in onPageStarted),
    // so each new load restarts it and a finished load cancels it when pageIsLoading flips.
    LaunchedEffect(pageLoadCount) {
        if (pageLoadCount == 0) return@LaunchedEffect
        delay(LOAD_WATCHDOG_MS)
        if (!pageIsLoading) return@LaunchedEffect
        val stalledUrl = webViewInstance?.url ?: tab.url
        onPageEvent(
            tab.id,
            "This page has still been loading after ${LOAD_WATCHDOG_MS / 1000} seconds. The site is " +
                "very slow or overloaded. Nothing has been cancelled — it may still finish."
        )
        inspector.dispatch(
            InspectorMessage.DocumentLoadTimeout(tab.id, stalledUrl, LOAD_WATCHDOG_MS)
        )
    }

    LaunchedEffect(isRecordingAutomation) {
        webViewInstance?.evaluateJavascript(
            if (isRecordingAutomation) AutomationRecorderScripts.START else AutomationRecorderScripts.STOP,
            null
        )
    }

    // Install (or refresh) the Network Inspector's page hooks. Keyed on the page-finish counter so a
    // new document gets the hooks, and on the script token so a settings change reaches open tabs.
    // evaluateJavascript runs on the UI thread, and the script itself does no work unless the page
    // actually performs a fetch/XHR/console call.
    LaunchedEffect(webViewInstance, pageFinishCount, inspectorScriptToken) {
        val webView = webViewInstance ?: return@LaunchedEffect
        val script = if (inspector.enabled) inspector.installScript() else InspectorScripts.DISABLE
        if (script.isNotEmpty()) {
            webView.evaluateJavascript(script, null)
        }
    }

    LaunchedEffect(findQuery) {
        webViewInstance?.let { webView ->
            if (findQuery.isNotBlank()) {
                webView.findAllAsync(findQuery)
            } else {
                webView.clearMatches()
                onFindMatchesChanged(0, 0)
            }
        }
    }

    LaunchedEffect(findTriggerNext) {
        if (findTriggerNext > 0L) webViewInstance?.findNext(true)
    }

    LaunchedEffect(findTriggerPrev) {
        if (findTriggerPrev > 0L) webViewInstance?.findNext(false)
    }

    LaunchedEffect(automationPlayback?.runId) {
        val playback = automationPlayback ?: return@LaunchedEffect
        if (playback.tabId != tab.id) return@LaunchedEffect
        val webView = snapshotFlow { webViewInstance }.filterNotNull().first()

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
            val currentUrl = webView.url ?: tab.url
            if (!samePageUrl(currentUrl, playback.automation.startUrl)) {
                val finishesBeforeLoad = pageFinishCount
                pageIsLoading = true
                webView.loadUrl(playback.automation.startUrl)
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

                // Poll for the element until the budget runs out. The old loop gave up after six
                // 400 ms attempts — about 2.4 s — which is enough for an idle page and nowhere near
                // enough for a single-page app still rendering under load, so the run aborted with
                // "element was not found" while the form was merely on its way.
                var result = "missing"
                var waitedMs = 0L
                while (true) {
                    result = evaluateAutomationStep(webView, step)
                    if (result != "missing") break
                    if (waitedMs >= AUTOMATION_ELEMENT_TIMEOUT_MS) break
                    delay(AUTOMATION_ELEMENT_WAIT_MS)
                    waitedMs += AUTOMATION_ELEMENT_WAIT_MS
                }
                if (result != "done") {
                    val detail = when (result) {
                        "missing" -> "the element was still not on the page after " +
                            "${AUTOMATION_ELEMENT_TIMEOUT_MS / 1000} s of waiting"
                        "error" -> "the page rejected the action"
                        else -> result
                    }
                    onAutomationPlaybackFinished(
                        playback.runId,
                        "Stopped at step ${stepIndex + 1}: $detail."
                    )
                    return@LaunchedEffect
                }

                // Give click handlers time to start a document navigation. If they do, continue only
                // after the new document has finished, not against the old page.
                val finishesBeforeClick = pageFinishCount
                val loadsBeforeClick = pageLoadCount
                if (step.action == AutomationAction.CLICK) {
                    // Poll for the navigation instead of sampling once after a fixed 350 ms sleep.
                    // On a slow server the response has not arrived yet at 350 ms, so the old check
                    // concluded "this click does not navigate" and immediately ran the next step
                    // against a page that was about to be replaced — which is how one slow response
                    // turned into a cascade of "element was not found" failures on every later step.
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surfaceColor)
            .testTag("webview_container_${tab.id}")
    ) {
        key(webViewGeneration) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(webViewBackgroundColor)
                        defaultUserAgent = settings.userAgentString

                        settings.javaScriptEnabled = isJavaScriptEnabled
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                        if (tab.isIncognito) {
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        } else {
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        }

                        if (tab.isDesktopSite) settings.userAgentString = DESKTOP_USER_AGENT

                        setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                            onFindMatchesChanged(activeMatchOrdinal, numberOfMatches)
                        }

                        addJavascriptInterface(
                            AutomationJavascriptBridge { serializedStep ->
                                if (automationRecordingEnabled.get()) {
                                    parseAutomationStep(serializedStep)?.let { step ->
                                        automationStepCallback.get().invoke(tab.id, step)
                                    }
                                }
                            },
                            AUTOMATION_BRIDGE_NAME
                        )

                        // The inspector's bridge is exposed to pages only when this build carries the
                        // inspector at all (never in release builds). It is removed in onDispose. The
                        // page hooks are what actually decide whether anything is ever pushed.
                        if (inspector.uiState.value.available) {
                            addJavascriptInterface(
                                InspectorJsBridge { payload ->
                                    inspector.dispatch(InspectorMessage.PageRecords(tab.id, payload))
                                },
                                InspectorScripts.BRIDGE_NAME
                            )
                        }

                        webViewClient = object : NetworkInspectorWebViewClient(tab.id, inspector) {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val targetUri = request?.url ?: return false
                                val scheme = targetUri.scheme ?: return false

                                if (scheme == "http" || scheme == "https") return false

                                return try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, targetUri))
                                    true
                                } catch (_: Exception) {
                                    true
                                }
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                pageIsLoading = true
                                pageLoadCount += 1
                                url?.let { onPageStarted(tab.id, it) }
                                view?.let { onNavigationStateChanged(tab.id, it.canGoBack(), it.canGoForward()) }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                pageIsLoading = false
                                pageFinishCount += 1
                                val finalUrl = url ?: tab.url
                                onPageFinished(tab.id, finalUrl, view?.title)
                                if (automationRecordingEnabled.get()) {
                                    view?.evaluateJavascript(AutomationRecorderScripts.START, null)
                                }
                                view?.let { onNavigationStateChanged(tab.id, it.canGoBack(), it.canGoForward()) }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    pageIsLoading = false
                                    pageFinishCount += 1
                                    onProgressChanged(tab.id, 100)
                                    // WebView only hands over a code and a description here, but that
                                    // is the difference between "no internet", "DNS failed" and "the
                                    // server timed out" — exactly what a blank screen cannot say.
                                    val description = error?.description?.toString()
                                        ?: "an unknown network error"
                                    onPageEvent(
                                        tab.id,
                                        "The page could not be loaded: $description" +
                                            (error?.errorCode?.let { " (errorCode=$it)" } ?: "") + "."
                                    )
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                // super records it for the inspector; this override only adds the
                                // user-visible reason. Loading state is left alone on purpose:
                                // WebView still renders the error body and calls onPageFinished, and
                                // finishing the page twice would confuse automation step tracking.
                                super.onReceivedHttpError(view, request, errorResponse)
                                if (request?.isForMainFrame == true && errorResponse != null) {
                                    val reason = errorResponse.reasonPhrase?.takeIf { it.isNotBlank() }
                                    onPageEvent(
                                        tab.id,
                                        "The server refused this page with HTTP ${errorResponse.statusCode}" +
                                            (reason?.let { " ($it)" } ?: "") +
                                            ". The site rejected the request, so nothing was rendered."
                                    )
                                }
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?
                            ) {
                                // super records the cause and cancels the load, which is the safe
                                // default and is never overridden here.
                                super.onReceivedSslError(view, handler, error)
                                pageIsLoading = false
                                onProgressChanged(tab.id, 100)
                                onPageEvent(
                                    tab.id,
                                    "The secure connection was rejected and the page was not loaded. " +
                                        "This app does not bypass certificate errors."
                                )
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                // Let the inspector record that every in-flight request of this tab is
                                // now unobservable before the view is torn down.
                                super.onRenderProcessGone(view, detail)
                                automationPlaybackReference.get()?.let { playback ->
                                    if (playback.tabId == tab.id) {
                                        onAutomationPlaybackFinished(
                                            playback.runId,
                                            "The page renderer restarted; run the automation again."
                                        )
                                    }
                                }
                                // Without this the tab silently goes blank and reloads, and there is
                                // no way to tell a page that crashed from one Android killed to
                                // reclaim memory — which matters, because the second one repeats on
                                // heavy sites and no amount of reloading will fix it.
                                val killedForMemory = detail != null &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                    !detail.didCrash()
                                onPageEvent(
                                    tab.id,
                                    if (killedForMemory) {
                                        "The page was closed by Android to free memory, and is " +
                                            "reloading. A heavy site can do this repeatedly on a " +
                                            "low-memory device — close other tabs to help it."
                                    } else {
                                        "The page stopped unexpectedly and is reloading."
                                    }
                                )
                                view?.let {
                                    (it.parent as? ViewGroup)?.removeView(it)
                                    it.removeJavascriptInterface(AUTOMATION_BRIDGE_NAME)
                                    it.removeJavascriptInterface(InspectorScripts.BRIDGE_NAME)
                                    it.destroy()
                                }
                                webViewInstance = null
                                webViewGeneration += 1
                                pageIsLoading = false
                                return true
                            }
                        }

                        webChromeClient = object : NetworkInspectorWebChromeClient(tab.id, inspector) {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                onProgressChanged(tab.id, newProgress)
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                super.onReceivedTitle(view, title)
                                if (!title.isNullOrBlank()) {
                                    onPageFinished(tab.id, view?.url ?: tab.url, title)
                                }
                            }
                        }

                        // The factory is the sole initial-load path. Navigation changes after
                        // this view exists are handled by the trigger effect above.
                        if (!tab.isNewTabPage) loadUrl(tab.url)
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    webViewInstance = webView
                    webView.setBackgroundColor(webViewBackgroundColor)
                }
            )
        }
    }

    DisposableEffect(tab.id) {
        onDispose {
            webViewInstance?.let { webView ->
                webView.stopLoading()
                webView.removeJavascriptInterface(AUTOMATION_BRIDGE_NAME)
                webView.removeJavascriptInterface(InspectorScripts.BRIDGE_NAME)
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
            inspector.dispatch(InspectorMessage.WebViewDestroyed(tab.id))
            webViewInstance = null
        }
    }
}

class AutomationJavascriptBridge(
    private val onStep: (String) -> Unit
) {
    @JavascriptInterface
    fun recordStep(serializedStep: String) {
        if (serializedStep.length <= MAX_AUTOMATION_SELECTOR_LENGTH + MAX_AUTOMATION_VALUE_LENGTH + 256) {
            onStep(serializedStep)
        }
    }
}

private fun parseAutomationStep(json: String): AutomationStep? = runCatching {
    val step = JSONObject(json)
    val action = AutomationAction.fromWireValue(step.optString("action")) ?: return null
    val selector = step.optString("selector").take(MAX_AUTOMATION_SELECTOR_LENGTH)
    if (selector.isBlank()) return null
    AutomationStep(
        action = action,
        selector = selector,
        value = step.optString("value").take(MAX_AUTOMATION_VALUE_LENGTH)
    )
}.getOrNull()

private suspend fun evaluateAutomationStep(webView: WebView, step: AutomationStep): String {
    val selector = JSONObject.quote(step.selector)
    val value = JSONObject.quote(step.value)
    val script = """
        (function() {
          try {
            var element = document.querySelector($selector);
            if (!element) return 'missing';
            element.scrollIntoView(true);
            if (${JSONObject.quote(step.action.wireValue)} === 'click') {
              element.click();
            } else {
              element.focus();
              if ('value' in element) {
                var prototype = Object.getPrototypeOf(element);
                var descriptor = prototype && Object.getOwnPropertyDescriptor(prototype, 'value');
                if (descriptor && descriptor.set) descriptor.set.call(element, $value);
                else element.value = $value;
              } else if (element.isContentEditable) {
                element.textContent = $value;
              }
              element.dispatchEvent(new Event('input', { bubbles: true }));
              element.dispatchEvent(new Event('change', { bubbles: true }));
            }
            return 'done';
          } catch (error) {
            return 'error';
          }
        })();
    """.trimIndent()

    val rawResult = suspendCancellableCoroutine<String?> { continuation ->
        webView.evaluateJavascript(script) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
    }
    return runCatching { JSONTokener(rawResult ?: "null").nextValue() as? String }
        .getOrNull() ?: "error"
}

private fun samePageUrl(first: String?, second: String?): Boolean {
    if (first.isNullOrBlank() || second.isNullOrBlank()) return false
    fun withoutFragment(url: String): String = url.substringBefore('#').trimEnd('/')
    return withoutFragment(first) == withoutFragment(second)
}

private object AutomationRecorderScripts {
    val START = """
        (function() {
          window.__browserAutomationRecording = true;
          if (window.__browserAutomationRecorderInstalled) return;
          window.__browserAutomationRecorderInstalled = true;

          function escapeCss(value) {
            if (window.CSS && window.CSS.escape) return window.CSS.escape(value);
            return String(value).replace(/([^a-zA-Z0-9_-])/g, function(character) { return '\\' + character; });
          }
          function unique(selector) {
            try { return document.querySelectorAll(selector).length === 1; }
            catch (_) { return false; }
          }
          function selectorFor(element) {
            if (!element || !element.tagName) return '';
            if (element.id) {
              var idSelector = '#' + escapeCss(element.id);
              if (unique(idSelector)) return idSelector;
            }
            var tag = element.tagName.toLowerCase();
            ['data-testid', 'name', 'aria-label'].some(function(attribute) {
              var value = element.getAttribute(attribute);
              if (!value) return false;
              var selector = tag + '[' + attribute + '="' + value.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"]';
              if (unique(selector)) { element.__browserAutomationSelector = selector; return true; }
              return false;
            });
            if (element.__browserAutomationSelector) {
              var result = element.__browserAutomationSelector;
              delete element.__browserAutomationSelector;
              return result;
            }
            var parts = [];
            var current = element;
            while (current && current.nodeType === 1 && current !== document.documentElement) {
              var part = current.tagName.toLowerCase();
              var parent = current.parentElement;
              if (parent) {
                var siblings = Array.prototype.filter.call(parent.children, function(child) {
                  return child.tagName === current.tagName;
                });
                if (siblings.length > 1) part += ':nth-of-type(' + (siblings.indexOf(current) + 1) + ')';
              }
              parts.unshift(part);
              current = parent;
            }
            return parts.join(' > ');
          }
          function send(action, element, value) {
            if (!window.__browserAutomationRecording || !window.BrowserAutomation) return;
            var selector = selectorFor(element);
            if (!selector) return;
            try {
              window.BrowserAutomation.recordStep(JSON.stringify({
                action: action,
                selector: selector,
                value: value == null ? '' : String(value).slice(0, 2000)
              }));
            } catch (_) {}
          }
          function isSensitive(element) {
            var type = (element.type || '').toLowerCase();
            var hints = [element.name, element.id, element.autocomplete, element.getAttribute('aria-label')]
              .join(' ').toLowerCase();
            return type === 'password' || type === 'file' || type === 'hidden' ||
              /password|passcode|one-time|otp|credit.?card|card.?number|cvv|cvc|ssn|social.?security/.test(hints);
          }
          function isTextInput(element) {
            if (element.isContentEditable) return true;
            var tag = element.tagName && element.tagName.toLowerCase();
            if (tag === 'textarea' || tag === 'select') return true;
            if (tag !== 'input') return false;
            return !/^(button|submit|reset|checkbox|radio|file|hidden|image|password)$/i.test(element.type || 'text');
          }
          var pendingInputs = new WeakMap();
          var pendingInputElements = new Set();
          function flushPendingInputs() {
            pendingInputElements.forEach(function(element) {
              clearTimeout(pendingInputs.get(element));
              if (document.documentElement.contains(element) && !isSensitive(element)) {
                var value = element.isContentEditable ? element.textContent : element.value;
                send('input', element, value || '');
              }
            });
            pendingInputElements.clear();
          }
          document.addEventListener('click', function(event) {
            flushPendingInputs();
            var element = event.target && event.target.closest ? event.target.closest(
              'a,button,input[type=button],input[type=submit],input[type=checkbox],input[type=radio],[role=button],[onclick]'
            ) : event.target;
            if (element && !isSensitive(element)) send('click', element, '');
          }, true);
          document.addEventListener('input', function(event) {
            var element = event.target;
            if (!element || !isTextInput(element) || isSensitive(element)) return;
            clearTimeout(pendingInputs.get(element));
            pendingInputElements.add(element);
            pendingInputs.set(element, setTimeout(function() {
              var value = element.isContentEditable ? element.textContent : element.value;
              send('input', element, value || '');
              pendingInputElements.delete(element);
            }, 400));
          }, true);
          document.addEventListener('change', function(event) {
            var element = event.target;
            if (!element || !isTextInput(element) || isSensitive(element)) return;
            clearTimeout(pendingInputs.get(element));
            pendingInputElements.delete(element);
            var value = element.isContentEditable ? element.textContent : element.value;
            send('input', element, value || '');
          }, true);
        })();
    """.trimIndent()

    val STOP = "window.__browserAutomationRecording = false;"
}
