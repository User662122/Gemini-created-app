package com.example.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.Composable
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
import com.example.ui.components.FindInPageBar
import com.example.ui.components.HistoryDialog
import com.example.ui.components.NewTabPage
import com.example.ui.components.SettingsDialog
import com.example.ui.components.TabSwitcherSheet
import com.example.ui.components.WebViewContainer
import com.example.ui.theme.ChromeDarkBg
import com.example.ui.theme.IncognitoBg
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

    LaunchedEffect(uiState.automationStatus, uiState.isRecordingAutomation, uiState.automationPlayback) {
        val status = uiState.automationStatus
        if (!status.isNullOrBlank() && !uiState.isRecordingAutomation && uiState.automationPlayback == null) {
            statusSnackbarHostState.showSnackbar(status)
        }
    }

    // Keyed on the token, not the message: a failure that repeats with identical wording (a renderer
    // killed twice) must still be announced twice, and a later state change must not cut short a
    // diagnostic the user has not read yet. Long duration because these explain a blank screen.
    LaunchedEffect(uiState.pageStatusToken) {
        val message = uiState.pageStatus
        if (uiState.pageStatusToken > 0L && !message.isNullOrBlank()) {
            statusSnackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
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
                    onSelectEngine = { viewModel.setSearchEngine(it) },
                    onToggleJavaScript = { viewModel.setJavaScriptEnabled(it) },
                    onToggleDesktopDefault = { viewModel.setDesktopSiteDefault(it) },
                    onClearData = { viewModel.clearBrowsingData() },
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
        }
    }
}
