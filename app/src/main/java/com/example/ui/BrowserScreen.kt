package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AutomationsDialog
import com.example.ui.components.BookmarksDialog
import com.example.ui.components.ChromeOmniboxSheet
import com.example.ui.components.ChromeOverflowMenu
import com.example.ui.components.ChromeTopBar
import com.example.ui.components.DownloadsDialog
import com.example.ui.components.EnginePromptDialogs
import com.example.ui.components.FindInPageBar
import com.example.ui.components.HistoryDialog
import com.example.ui.components.NewTabPage
import com.example.ui.components.SettingsDialog
import com.example.ui.components.TabSwitcherSheet
import com.example.ui.components.WebViewContainer
import com.example.ui.engine.BrowserDataCleaner
import com.example.ui.engine.BrowserEngineKind
import com.example.ui.engine.gecko.GeckoBrowserSurface
import com.example.ui.engine.gecko.GeckoEngine
import com.example.ui.engine.gecko.GeckoPromptRequest
import com.example.ui.engine.gecko.GeckoRuntimeManager
import com.example.ui.engine.gecko.PromptAnswer
import com.example.ui.theme.ChromeDarkBg
import com.example.ui.theme.IncognitoBg
import com.example.devtools.ConsoleLevel
import com.example.devtools.FailureObservation
import com.example.devtools.InspectorMessage
import com.example.devtools.InspectorRuntime
import com.example.devtools.ui.NetworkInspectorScreen

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    inspector: InspectorRuntime,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val inspectorState by inspector.uiState.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val searchSuggestions by viewModel.searchSuggestions.collectAsStateWithLifecycle()

    var showMenu by remember { mutableStateOf(false) }
    var findTriggerNext by remember { mutableLongStateOf(0L) }
    var findTriggerPrev by remember { mutableLongStateOf(0L) }
    var isNetworkInspectorOpen by remember { mutableStateOf(false) }
    val statusSnackbarHostState = remember { SnackbarHostState() }

    val engineKind by viewModel.engineKind.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val bridgeError by GeckoRuntimeManager.bridgeError.collectAsStateWithLifecycle()

    // The embedded Gecko engine. Constructed eagerly but inert until `start()` is called, so
    // choosing the system engine costs nothing and the bridge/prompt plumbing always has a home.
    val geckoEngine = remember {
        GeckoEngine(
            context = context.applicationContext,
            registry = viewModel.downloadRegistry,
            onDownloadStatus = { viewModel.onDownloadStatus(it) },
            onExternalApp = { uri -> openExternally(context, Uri.parse(uri)) },
            onRecordedStep = { tabId, step -> viewModel.onAutomationStepRecorded(tabId, step) },
            onPageError = { tabId, message, source, line ->
                inspector.dispatch(
                    InspectorMessage.Console(
                        tabId = tabId,
                        level = ConsoleLevel.ERROR,
                        message = message,
                        source = source,
                        lineNumber = line,
                    )
                )
            },
            onPageStarted = { tabId, url ->
                viewModel.onPageStarted(tabId, url)
                inspector.dispatch(InspectorMessage.DocumentStarted(tabId))
            },
            onUrlChanged = { tabId, url -> viewModel.onLocationChanged(tabId, url) },
            onPageFinished = { tabId, url, title ->
                viewModel.onPageFinished(tabId, url, title)
                inspector.dispatch(InspectorMessage.DocumentFinished(tabId, url, title))
            },
            onProgressChanged = { tabId, progress -> viewModel.onProgressChanged(tabId, progress) },
            onPageEvent = { tabId, message -> viewModel.onPageEvent(tabId, message) },
            onNavigationStateChanged = { tabId, canGoBack, canGoForward ->
                viewModel.onNavigationStateChanged(tabId, canGoBack, canGoForward)
            },
            onNewTab = { url -> viewModel.openTabForPopup(url) },
            onCloseTab = { tabId -> viewModel.closeTabById(tabId) },
            // Gecko's own report of why a load failed is strictly more specific than WebView's, so
            // the developer inspector keeps recording failures under this engine too.
            onLoadFailure = { tabId, url, description, errorCode ->
                inspector.observer.onRequestFailed(
                    FailureObservation(
                        tabId = tabId,
                        url = url ?: uiState.currentTab?.url.orEmpty(),
                        description = description,
                        errorCode = errorCode,
                        isForMainFrame = true,
                        method = "GET",
                        observedAtMillis = System.currentTimeMillis(),
                    )
                )
            },
            // The inspector's capture channel under this engine: the bridge extension reports every
            // request and the cookie store to it. Tab URLs attribute requests to tabs.
            inspector = inspector,
            tabUrls = {
                val state = viewModel.uiState.value
                (state.regularTabs + state.incognitoTabs).associate { it.id to it.url }
            },
        )
    }

    val geckoPromptRequests by geckoEngine.promptHost.requests.collectAsStateWithLifecycle()

    var pendingFileRequest by remember { mutableStateOf<GeckoPromptRequest.FileChooser?>(null) }
    var pendingPermissionRequest by remember { mutableStateOf<GeckoPromptRequest.AndroidPermissions?>(null) }

    // File uploads. SAF needs no storage permission, and GeckoView accepts the content URIs the
    // picker returns — the documented path for <input type="file"> in an embedded Gecko.
    val pickSingleFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val request = pendingFileRequest
        pendingFileRequest = null
        if (request != null) {
            geckoEngine.promptHost.resolve(
                request.id,
                if (uri == null) PromptAnswer.Dismiss else PromptAnswer.Files(listOf(uri))
            )
        }
    }

    val pickMultipleFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val request = pendingFileRequest
        pendingFileRequest = null
        if (request != null) {
            geckoEngine.promptHost.resolve(
                request.id,
                if (uris.isEmpty()) PromptAnswer.Dismiss else PromptAnswer.Files(uris)
            )
        }
    }

    // Android runtime permissions a page asked for (camera, microphone, location). The system prompt
    // is the only place these can be granted; the engine is told the outcome either way.
    val requestAndroidPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request != null) {
            val granted = results.isNotEmpty() && results.values.all { it }
            geckoEngine.promptHost.resolve(
                request.id,
                if (granted) PromptAnswer.Allow else PromptAnswer.Deny
            )
        }
    }

    // The inspector's publishing ticker only runs while its screens are on screen, so browsing with
    // the inspector closed costs nothing extra.
    LaunchedEffect(isNetworkInspectorOpen) {
        inspector.setScreensVisible(isNetworkInspectorOpen)
    }

    // Tab scoping and cookie queries follow the visible tab.
    val activeTabId = uiState.currentTab?.id
    LaunchedEffect(activeTabId) {
        if (activeTabId != null) inspector.setCurrentTab(activeTabId)
    }

    LaunchedEffect(Unit) {
        inspector.attachCookieScopeProvider(viewModel.cookieScopeProvider)
    }

    // The embedded engine is started once the screen exists, and keeps a session for exactly the
    // tabs that are open. Starting it here rather than in the ViewModel keeps the engine's lifecycle
    // tied to what is on screen.
    ManageTabSessions(viewModel = viewModel, engine = geckoEngine, engineKind = engineKind)

    LaunchedEffect(uiState.automationStatus, uiState.isRecordingAutomation, uiState.automationPlayback) {
        val status = uiState.automationStatus
        if (!status.isNullOrBlank() && !uiState.isRecordingAutomation && uiState.automationPlayback == null) {
            statusSnackbarHostState.showSnackbar(status)
        }
    }

    // Keyed on the token, not the message: a failure that repeats with identical wording (a renderer
    // killed twice) must still be announced twice, and a later state change must not cut short a
    // diagnostic the user has not read yet. Long duration because these explain a blank screen.
    //
    // The action is the honest escape hatch for a page the server has refused this client: hand the
    // very same URL to a real browser instead of pretending this view can change the server's mind.
    LaunchedEffect(uiState.pageStatusToken) {
        val message = uiState.pageStatus
        if (uiState.pageStatusToken > 0L && !message.isNullOrBlank()) {
            val result = statusSnackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Open externally",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                val url = uiState.currentTab?.url
                if (!url.isNullOrBlank() && url != "chrome://newtab") {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            }
        }
    }

    val currentTab = uiState.currentTab
    val isIncognito = uiState.isIncognitoViewActive
    val isBookmarked = bookmarks.any { it.url == currentTab?.url }

    // Chrome Back navigation handler
    BackHandler(
        enabled = uiState.isOmniboxEditing ||
                uiState.isFindInPageOpen ||
                uiState.isTabSwitcherOpen ||
                uiState.isBookmarksOpen ||
                uiState.isHistoryOpen ||
                uiState.isSettingsOpen ||
                uiState.isAutomationsOpen ||
                isNetworkInspectorOpen ||
                (currentTab?.canGoBack == true)
    ) {
        when {
            // The inspector sits above every other surface, so it consumes back first: the detail
            // sheet closes before the inspector itself.
            isNetworkInspectorOpen && inspectorState.openEntry != null -> inspector.closeEntry()
            isNetworkInspectorOpen -> isNetworkInspectorOpen = false
            uiState.isOmniboxEditing -> viewModel.setOmniboxEditing(false)
            uiState.isFindInPageOpen -> viewModel.setFindInPageVisible(false)
            uiState.isBookmarksOpen -> viewModel.setBookmarksVisible(false)
            uiState.isHistoryOpen -> viewModel.setHistoryVisible(false)
            uiState.isSettingsOpen -> viewModel.setSettingsVisible(false)
            uiState.isAutomationsOpen -> viewModel.setAutomationsVisible(false)
            uiState.isTabSwitcherOpen -> viewModel.setTabSwitcherVisible(false)
            currentTab?.canGoBack == true -> viewModel.navigateBack()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("browser_main_scaffold"),
        snackbarHost = { SnackbarHost(statusSnackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        val screenBg = if (isIncognito) IncognitoBg else MaterialTheme.colorScheme.background

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(screenBg)
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Top Address Bar
                ChromeTopBar(
                    tab = currentTab,
                    tabsCount = uiState.totalTabsCount,
                    isIncognito = isIncognito,
                    onOmniboxClick = { viewModel.setOmniboxEditing(true) },
                    onTabSwitcherClick = { viewModel.setTabSwitcherVisible(true) },
                    onMenuClick = { showMenu = true },
                    onReloadClick = { viewModel.reloadCurrentTab() }
                )

                // Chrome 3-dots popup menu
                Box {
                    ChromeOverflowMenu(
                        expanded = showMenu,
                        tab = currentTab,
                        isBookmarked = isBookmarked,
                        onDismissRequest = { showMenu = false },
                        onNewTab = { viewModel.openNewTab(isIncognito = false) },
                        onNewIncognitoTab = { viewModel.openNewTab(isIncognito = true) },
                        onHistory = { viewModel.setHistoryVisible(true) },
                        onBookmarks = { viewModel.setBookmarksVisible(true) },
                        onShare = {
                            val urlToShare = currentTab?.url ?: ""
                            if (urlToShare.isNotBlank() && currentTab?.isNewTabPage == false) {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, urlToShare)
                                    putExtra(Intent.EXTRA_SUBJECT, currentTab?.title ?: "")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share via"))
                            }
                        },
                        onFindInPage = { viewModel.setFindInPageVisible(true) },
                        onDownloads = { viewModel.setDownloadsVisible(true) },
                        onToggleDesktopSite = { viewModel.toggleDesktopSite() },
                        onSettings = { viewModel.setSettingsVisible(true) },
                        onReload = { viewModel.reloadCurrentTab() },
                        onBack = { viewModel.navigateBack() },
                        onForward = { viewModel.navigateForward() },
                        onToggleBookmark = { viewModel.toggleBookmarkCurrentTab() },
                        isRecordingAutomation = uiState.isRecordingAutomation,
                        onAutomations = { viewModel.setAutomationsVisible(true) },
                        onNetworkInspector = if (inspectorState.available) {
                            { isNetworkInspectorOpen = true }
                        } else {
                            null
                        }
                    )
                }

                // Find in page bar
                if (uiState.isFindInPageOpen) {
                    FindInPageBar(
                        query = uiState.findQuery,
                        matchIndex = uiState.findMatchIndex,
                        matchTotal = uiState.findMatchTotal,
                        onQueryChange = { viewModel.setFindQuery(it) },
                        onFindNext = { findTriggerNext = System.currentTimeMillis() },
                        onFindPrevious = { findTriggerPrev = System.currentTimeMillis() },
                        onClose = { viewModel.setFindInPageVisible(false) }
                    )
                }

                // Content Area: New Tab Page or WebView Container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (currentTab == null || currentTab.isNewTabPage) {
                        NewTabPage(
                            isIncognito = isIncognito,
                            bookmarks = bookmarks,
                            onSearchClick = { viewModel.setOmniboxEditing(true) },
                            onNavigateUrl = { viewModel.navigateTo(it) }
                        )
                    } else {
                        key(currentTab.id) {
                            if (engineKind == BrowserEngineKind.GECKO) {
                                GeckoBrowserSurface(
                                    engine = geckoEngine,
                                    tab = currentTab,
                                    isJavaScriptEnabled = uiState.isJavaScriptEnabled,
                                    reloadTrigger = uiState.reloadTrigger,
                                    navigateBackTrigger = uiState.navigateBackTrigger,
                                    navigateForwardTrigger = uiState.navigateForwardTrigger,
                                    findQuery = if (uiState.isFindInPageOpen) uiState.findQuery else "",
                                    findTriggerNext = findTriggerNext,
                                    findTriggerPrev = findTriggerPrev,
                                    isRecordingAutomation = uiState.isRecordingAutomation &&
                                        currentTab.id == uiState.recordingAutomationTabId,
                                    automationPlayback = uiState.automationPlayback,
                                    inspector = inspector,
                                    onAutomationPlaybackFinished = { runId, result ->
                                        viewModel.onAutomationPlaybackFinished(runId, result)
                                    },
                                    onPageEvent = { id, message -> viewModel.onPageEvent(id, message) },
                                    onFindMatchesChanged = { idx, total ->
                                        viewModel.updateFindMatches(idx, total)
                                    }
                                )
                            } else {
                                WebViewContainer(
                                    tab = currentTab,
                                    isJavaScriptEnabled = uiState.isJavaScriptEnabled,
                                    reloadTrigger = uiState.reloadTrigger,
                                    navigateBackTrigger = uiState.navigateBackTrigger,
                                    navigateForwardTrigger = uiState.navigateForwardTrigger,
                                    findQuery = if (uiState.isFindInPageOpen) uiState.findQuery else "",
                                    findTriggerNext = findTriggerNext,
                                    findTriggerPrev = findTriggerPrev,
                                    isRecordingAutomation = uiState.isRecordingAutomation &&
                                        currentTab.id == uiState.recordingAutomationTabId,
                                    automationPlayback = uiState.automationPlayback,
                                    inspector = inspector,
                                    inspectorScriptToken = inspectorState.scriptToken,
                                    onAutomationStepRecorded = { tabId, step ->
                                        viewModel.onAutomationStepRecorded(tabId, step)
                                    },
                                    onAutomationPlaybackFinished = { runId, result ->
                                        viewModel.onAutomationPlaybackFinished(runId, result)
                                    },
                                    onPageStarted = { id, url -> viewModel.onPageStarted(id, url) },
                                    onPageFinished = { id, url, title -> viewModel.onPageFinished(id, url, title) },
                                    onProgressChanged = { id, progress -> viewModel.onProgressChanged(id, progress) },
                                    onPageEvent = { id, message -> viewModel.onPageEvent(id, message) },
                                    onNavigationStateChanged = { id, canBack, canForward ->
                                        viewModel.onNavigationStateChanged(id, canBack, canForward)
                                    },
                                    onFindMatchesChanged = { idx, total ->
                                        viewModel.updateFindMatches(idx, total)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Overlay Sheets & Dialogs

            // 1. Omnibox search / input sheet
            if (uiState.isOmniboxEditing) {
                ChromeOmniboxSheet(
                    initialInput = uiState.omniboxInput,
                    suggestions = searchSuggestions,
                    isIncognito = isIncognito,
                    onQueryChanged = { viewModel.onOmniboxInputChanged(it) },
                    onNavigate = { viewModel.navigateTo(it) },
                    onDismiss = { viewModel.setOmniboxEditing(false) }
                )
            }

            // 2. Tab Switcher Grid sheet
            if (uiState.isTabSwitcherOpen) {
                TabSwitcherSheet(
                    regularTabs = uiState.regularTabs,
                    activeRegularTabId = uiState.activeRegularTabId,
                    incognitoTabs = uiState.incognitoTabs,
                    activeIncognitoTabId = uiState.activeIncognitoTabId,
                    isIncognitoView = uiState.isIncognitoViewActive,
                    onTabSelected = { tabId, incognito -> viewModel.selectTab(tabId, incognito) },
                    onTabClosed = { tabId, incognito -> viewModel.closeTab(tabId, incognito) },
                    onCloseAllTabs = { incognito -> viewModel.closeAllTabs(incognito) },
                    onNewTab = { incognito -> viewModel.openNewTab(isIncognito = incognito) },
                    onSwitchIncognitoView = { incognito -> viewModel.switchIncognitoView(incognito) },
                    onDone = { viewModel.setTabSwitcherVisible(false) }
                )
            }

            // 3. Bookmarks screen
            if (uiState.isBookmarksOpen) {
                BookmarksDialog(
                    bookmarks = bookmarks,
                    onOpenBookmark = { viewModel.navigateTo(it) },
                    onAddBookmark = { title, url -> viewModel.addBookmark(title, url) },
                    onDeleteBookmark = { viewModel.deleteBookmark(it) },
                    onDismiss = { viewModel.setBookmarksVisible(false) }
                )
            }

            // 4. History screen
            if (uiState.isHistoryOpen) {
                HistoryDialog(
                    historyItems = history,
                    onOpenUrl = { viewModel.navigateTo(it) },
                    onDeleteItem = { viewModel.deleteHistoryItem(it) },
                    onClearAll = { viewModel.clearBrowsingData() },
                    onDismiss = { viewModel.setHistoryVisible(false) }
                )
            }

            // 5. Settings screen
            if (uiState.isSettingsOpen) {
                SettingsDialog(
                    selectedEngine = uiState.selectedSearchEngine,
                    isJavaScriptEnabled = uiState.isJavaScriptEnabled,
                    isDesktopDefault = uiState.isDesktopSiteDefault,
                    engineKind = engineKind,
                    onSelectEngine = { viewModel.setSearchEngine(it) },
                    onToggleJavaScript = { viewModel.setJavaScriptEnabled(it) },
                    onToggleDesktopDefault = { viewModel.setDesktopSiteDefault(it) },
                    onSelectBrowserEngine = { viewModel.setEngineKind(it) },
                    onClearData = {
                        // Clearing data must clear the engine's store too, not just this app's
                        // history table: cookies and site storage lived on regardless before.
                        viewModel.clearBrowsingData()
                        BrowserDataCleaner.clear(context, engineKind) { message ->
                            viewModel.onDownloadStatus(message)
                        }
                    },
                    onDismiss = { viewModel.setSettingsVisible(false) }
                )
            }

            // 6. Developer Network Inspector (debug builds only; never reachable in release)
            if (isNetworkInspectorOpen && inspectorState.available) {
                NetworkInspectorScreen(
                    state = inspectorState,
                    onClose = { isNetworkInspectorOpen = false },
                    onFilterChange = { inspector.updateFilter(it) },
                    onOpenEntry = { inspector.openEntry(it) },
                    onCloseEntry = { inspector.closeEntry() },
                    onClearEntries = { inspector.clearEntries() },
                    onClearConsole = { inspector.clearConsole() },
                    onClearAll = { inspector.clearAll() },
                    onRefreshCookies = { inspector.refreshCookies() },
                    onSettingChange = { setting, value -> inspector.setSetting(setting, value) },
                    onEndSession = { inspector.endSession() },
                    onBuildExport = { format -> inspector.buildExport(format) },
                )
            }

            if (uiState.isAutomationsOpen) {
                AutomationsDialog(
                    automations = uiState.automations,
                    currentUrl = currentTab?.takeUnless { it.isNewTabPage }?.url,
                    isIncognito = isIncognito,
                    isJavaScriptEnabled = uiState.isJavaScriptEnabled,
                    isRecording = uiState.isRecordingAutomation,
                    recordingStepCount = uiState.recordingAutomationSteps.size,
                    runningAutomationName = uiState.automationPlayback?.automation?.name,
                    statusMessage = uiState.automationStatus,
                    onStartRecording = { viewModel.startAutomationRecording() },
                    onSaveRecording = { viewModel.saveAutomationRecording(it) },
                    onCancelRecording = { viewModel.cancelAutomationRecording() },
                    onRunAutomation = { viewModel.runAutomation(it) },
                    onDeleteAutomation = { viewModel.deleteAutomation(it) },
                    onStopPlayback = { viewModel.stopAutomationPlayback() },
                    onDismiss = { viewModel.setAutomationsVisible(false) }
                )
            }

            if (uiState.isDownloadsOpen) {
                DownloadsDialog(
                    downloads = downloads,
                    onOpen = { entry ->
                        entry.contentUri?.let { uri -> openExternally(context, Uri.parse(uri)) }
                    },
                    onRemove = { viewModel.removeDownload(it.id) },
                    onClearAll = { viewModel.clearDownloads() },
                    onDismiss = { viewModel.setDownloadsVisible(false) }
                )
            }

            // Page-raised dialogs: alert/confirm/prompt, HTTP authentication, permission requests,
            // select/date pickers. The WebView engine showed these itself; an embedded engine does
            // not, so the app has to.
            EnginePromptDialogs(
                requests = geckoPromptRequests,
                onResolve = { id, answer -> geckoEngine.promptHost.resolve(id, answer) }
            )

            // Keeping the two requests that need activity launchers out of the composable above.
            LaunchedEffect(geckoPromptRequests) {
                geckoPromptRequests.forEach { request ->
                    when (request) {
                        is GeckoPromptRequest.FileChooser ->
                            if (pendingFileRequest?.id != request.id) {
                                pendingFileRequest = request
                                if (request.allowsMultiple) {
                                    pickMultipleFiles.launch(arrayOf("*/*"))
                                } else {
                                    pickSingleFile.launch(
                                        request.mimeTypes.firstOrNull()?.toMimeType() ?: "*/*"
                                    )
                                }
                            }

                        is GeckoPromptRequest.AndroidPermissions ->
                            if (pendingPermissionRequest?.id != request.id) {
                                pendingPermissionRequest = request
                                requestAndroidPermissions.launch(request.permissions.toTypedArray())
                            }

                        else -> Unit
                    }
                }
            }

            // Downloads and page errors have no dialog of their own; they surface as snackbars, the
            // same way page status already does.
            LaunchedEffect(uiState.downloadStatusToken) {
                uiState.downloadStatus?.let { statusSnackbarHostState.showSnackbar(it) }
            }

            // The page bridge is a bundled extension. If it cannot be installed, the parts of the app
            // that need to talk to pages (automation recording and playback, uncaught-error reports)
            // are unavailable — which the user is told, rather than left to discover by silence.
            LaunchedEffect(bridgeError) {
                bridgeError?.let { reason ->
                    statusSnackbarHostState.showSnackbar(
                        "The page bridge did not load ($reason), so automation recording and " +
                            "page-error reports are unavailable."
                    )
                }
            }
        }
    }
}

/**
 * The Gecko session must outlive the composition of a tab's surface, and must be told which tabs are
 * still open, so the engine can release the ones that are gone.
 */
@Composable
private fun ManageTabSessions(
    viewModel: BrowserViewModel,
    engine: GeckoEngine,
    engineKind: BrowserEngineKind,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val javascriptEnabled = uiState.isJavaScriptEnabled
    val liveTabIds = remember(uiState.regularTabs, uiState.incognitoTabs) {
        (uiState.regularTabs.map { it.id } + uiState.incognitoTabs.map { it.id }).toSet()
    }
    // Starting the engine starts a browser process. Choosing the system engine must not pay for that,
    // so everything here is gated on Gecko actually being the engine in use.
    val isGeckoActive = engineKind == BrowserEngineKind.GECKO

    LaunchedEffect(isGeckoActive) {
        if (!isGeckoActive) return@LaunchedEffect
        engine.start()
        GeckoRuntimeManager.ensureBridgeExtension(context)
    }

    // The extension installs asynchronously, and a session can only be given its message delegate
    // once it exists, so every open session is re-registered when the extension arrives.
    val bridgeExtension by GeckoRuntimeManager.bridgeExtension.collectAsStateWithLifecycle()
    LaunchedEffect(bridgeExtension, isGeckoActive) {
        if (isGeckoActive && bridgeExtension != null) engine.attachBridgeToOpenSessions()
    }

    // JavaScript is a session setting, so it must be pushed into sessions that already exist.
    LaunchedEffect(javascriptEnabled, isGeckoActive) {
        if (isGeckoActive) engine.sessions.setJavaScriptEnabled(javascriptEnabled)
    }

    DisposableEffect(liveTabIds) {
        engine.keepSessionsFor(liveTabIds)
        onDispose { }
    }

    // Tab state outlives the screen; the engine's sessions do not. Closing them on the way out is
    // what keeps native sessions from piling up across activity recreations.
    DisposableEffect(engine) {
        onDispose { engine.close() }
    }
}

private fun openExternally(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }
}

/**
 * The MIME type for a file picker's accept value.
 *
 * A value with a slash in it is already a MIME type; a bare extension such as `.pdf` is not, and an
 * unrecognised one falls back to accepting every type rather than to a picker that opens nothing.
 */
private fun String.toMimeType(): String {
    if (contains("/")) return this
    val byExtension = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(trim().removePrefix("."))
    return byExtension ?: "*/*"
}

