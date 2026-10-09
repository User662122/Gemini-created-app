package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.webkit.URLUtil
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BrowserAutomationStore
import com.example.data.BrowserDatabase
import com.example.data.BrowserRepository
import com.example.data.DownloadRegistry
import com.example.data.model.AutomationPlayback
import com.example.data.model.AutomationStep
import com.example.data.model.Bookmark
import com.example.data.model.BrowserAutomation
import com.example.data.model.BrowserTab
import com.example.data.model.DownloadEntry
import com.example.data.model.HistoryItem
import com.example.devtools.CookieScopeProvider
import com.example.ui.engine.BrowserEngineKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val MAX_AUTOMATION_STEPS = 200
private const val MAX_AUTOMATION_SELECTOR_LENGTH = 1_000
private const val MAX_AUTOMATION_VALUE_LENGTH = 2_000
private const val MAX_AUTOMATION_NAME_LENGTH = 50
private const val DEFAULT_AUTOMATION_DELAY_MS = 350L
private const val MIN_AUTOMATION_DELAY_MS = 150L
private const val MAX_AUTOMATION_DELAY_MS = 5_000L

enum class SearchEngine(val displayName: String, val searchUrl: String, val homeUrl: String) {
    GOOGLE("Google", "https://www.google.com/search?q=", "https://www.google.com"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=", "https://duckduckgo.com"),
    BING("Bing", "https://www.bing.com/search?q=", "https://www.bing.com"),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q=", "https://www.ecosia.org"),
    YAHOO("Yahoo", "https://search.yahoo.com/search?p=", "https://search.yahoo.com")
}

data class BrowserUiState(
    val regularTabs: List<BrowserTab> = emptyList(),
    val activeRegularTabId: String = "",
    val incognitoTabs: List<BrowserTab> = emptyList(),
    val activeIncognitoTabId: String = "",
    val isIncognitoViewActive: Boolean = false,
    val isTabSwitcherOpen: Boolean = false,
    val isOmniboxEditing: Boolean = false,
    val omniboxInput: String = "",
    val isBookmarksOpen: Boolean = false,
    val isHistoryOpen: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val isFindInPageOpen: Boolean = false,
    val findQuery: String = "",
    val findMatchIndex: Int = 0,
    val findMatchTotal: Int = 0,
    val selectedSearchEngine: SearchEngine = SearchEngine.GOOGLE,
    val isJavaScriptEnabled: Boolean = true,
    val isDesktopSiteDefault: Boolean = false,
    val reloadTrigger: Long = 0L,
    val navigateBackTrigger: Long = 0L,
    val navigateForwardTrigger: Long = 0L,
    val automations: List<BrowserAutomation> = emptyList(),
    val isAutomationsOpen: Boolean = false,
    val isRecordingAutomation: Boolean = false,
    val recordingAutomationTabId: String? = null,
    val recordingAutomationStartUrl: String? = null,
    val recordingAutomationSteps: List<AutomationStep> = emptyList(),
    val automationPlayback: AutomationPlayback? = null,
    val automationStatus: String? = null,
    /**
     * The most recent reason a page did not work, in words a person can act on: a refused
     * certificate, a main-frame transport error, an HTTP status the server rejected the navigation
     * with, a renderer Android killed for memory, or a document that stalled.
     *
     * These all used to present as a blank screen with nothing to explain it.
     */
    val pageStatus: String? = null,
    /**
     * Increments per page event so an identical failure twice in a row is still announced twice —
     * a renderer killed repeatedly is the case that most needs to be visible.
     */
    val pageStatusToken: Long = 0L,
    val isDownloadsOpen: Boolean = false,
    /** The last word about a download, good or bad. */
    val downloadStatus: String? = null,
    val downloadStatusToken: Long = 0L
) {
    val currentTab: BrowserTab?
        get() {
            return if (isIncognitoViewActive) {
                incognitoTabs.find { it.id == activeIncognitoTabId } ?: incognitoTabs.firstOrNull()
            } else {
                regularTabs.find { it.id == activeRegularTabId } ?: regularTabs.firstOrNull()
            }
        }

    val totalTabsCount: Int
        get() = if (isIncognitoViewActive) incognitoTabs.size else regularTabs.size
}

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: BrowserRepository
    private val automationStore = BrowserAutomationStore(application)

    /**
     * Which engine renders pages. Gecko (an embedded Gecko/SpiderMonkey engine) is the default; the
     * system WebView engine stays selectable while the replacement proves itself on real sites.
     * See docs/ENGINE_MIGRATION.md.
     */
    private val enginePreferences =
        application.getSharedPreferences(BrowserEngineKind.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _engineKind = MutableStateFlow(
        BrowserEngineKind.fromStorage(enginePreferences.getString(BrowserEngineKind.KEY_KIND, null))
    )
    val engineKind: StateFlow<BrowserEngineKind> = _engineKind.asStateFlow()

    /** Where downloads are recorded. Owned here so the list survives tab and screen changes. */
    val downloadRegistry = DownloadRegistry(application)
    val downloads: StateFlow<List<DownloadEntry>> = downloadRegistry.downloads
    private var lastAutomationStepAt = 0L
    private var nextAutomationRunId = 0L

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>>
    val history: StateFlow<List<HistoryItem>>

    // Combined query suggestions (matching bookmarks, history, and search engine)
    private val _searchSuggestions = MutableStateFlow<List<SuggestionItem>>(emptyList())
    val searchSuggestions: StateFlow<List<SuggestionItem>> = _searchSuggestions.asStateFlow()

    /**
     * Tells the developer Network Inspector which URLs to ask `CookieManager` about.
     *
     * Only the visible tab's current page is offered here; the inspector adds the origins it has
     * already seen in that tab's traffic, so a page's own cookie scope is always included without the
     * inspector having to guess.
     */
    val cookieScopeProvider: CookieScopeProvider = object : CookieScopeProvider {
        override fun cookieQueryUrls(tabId: String): List<String> {
            val current = uiState.value.currentTab ?: return emptyList()
            if (current.id != tabId || current.isNewTabPage) return emptyList()
            return listOf(current.url)
        }
    }

    init {
        val database = BrowserDatabase.getDatabase(application)
        repository = BrowserRepository(database.bookmarkDao(), database.historyDao())

        bookmarks = repository.allBookmarks.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

        history = repository.allHistory.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

        // Initialize with default first tab
        val firstTab = BrowserTab(
            id = UUID.randomUUID().toString(),
            title = "New Tab",
            url = "chrome://newtab",
            isIncognito = false
        )
        _uiState.update {
            it.copy(
                regularTabs = listOf(firstTab),
                activeRegularTabId = firstTab.id,
                automations = automationStore.getAll()
            )
        }
    }

    fun openNewTab(url: String = "chrome://newtab", isIncognito: Boolean = _uiState.value.isIncognitoViewActive) {
        val newTab = BrowserTab(
            id = UUID.randomUUID().toString(),
            title = if (url == "chrome://newtab") "New Tab" else "Loading...",
            url = url,
            isIncognito = isIncognito,
            isDesktopSite = _uiState.value.isDesktopSiteDefault,
            navigationTrigger = if (url == "chrome://newtab") 0L else 1L
        )

        _uiState.update { previousState ->
            val state = cancelAutomationPlayback(previousState, "Automation stopped because the active tab changed.")
            if (isIncognito) {
                val updated = state.incognitoTabs + newTab
                state.copy(
                    incognitoTabs = updated,
                    activeIncognitoTabId = newTab.id,
                    isIncognitoViewActive = true,
                    isTabSwitcherOpen = false,
                    isOmniboxEditing = false
                )
            } else {
                val updated = state.regularTabs + newTab
                state.copy(
                    regularTabs = updated,
                    activeRegularTabId = newTab.id,
                    isIncognitoViewActive = false,
                    isTabSwitcherOpen = false,
                    isOmniboxEditing = false
                )
            }
        }
    }

    fun closeTab(tabId: String, isIncognito: Boolean) {
        _uiState.update { previousState ->
            val state = if (previousState.automationPlayback?.tabId == tabId) {
                cancelAutomationPlayback(previousState, "Automation stopped because its tab was closed.")
            } else previousState
            if (isIncognito) {
                val updated = state.incognitoTabs.filterNot { it.id == tabId }
                if (updated.isEmpty()) {
                    // Closed all incognito tabs, switch back to regular tabs
                    state.copy(
                        incognitoTabs = emptyList(),
                        activeIncognitoTabId = "",
                        isIncognitoViewActive = false
                    )
                } else {
                    val nextActiveId = if (state.activeIncognitoTabId == tabId) {
                        updated.last().id
                    } else {
                        state.activeIncognitoTabId
                    }
                    state.copy(incognitoTabs = updated, activeIncognitoTabId = nextActiveId)
                }
            } else {
                val updated = state.regularTabs.filterNot { it.id == tabId }
                if (updated.isEmpty()) {
                    // Create one fresh tab if all closed
                    val freshTab = BrowserTab(title = "New Tab", url = "chrome://newtab")
                    state.copy(
                        regularTabs = listOf(freshTab),
                        activeRegularTabId = freshTab.id
                    )
                } else {
                    val nextActiveId = if (state.activeRegularTabId == tabId) {
                        updated.last().id
                    } else {
                        state.activeRegularTabId
                    }
                    state.copy(regularTabs = updated, activeRegularTabId = nextActiveId)
                }
            }
        }
    }

    fun closeAllTabs(isIncognito: Boolean) {
        _uiState.update { previousState ->
            val tabsBeingClosed = if (isIncognito) previousState.incognitoTabs else previousState.regularTabs
            val playbackTabId = previousState.automationPlayback?.tabId
            val closesPlaybackTab = playbackTabId != null && tabsBeingClosed.any { it.id == playbackTabId }
            val state = if (closesPlaybackTab) {
                cancelAutomationPlayback(previousState, "Automation stopped because its tab was closed.")
            } else previousState
            if (isIncognito) {
                state.copy(
                    incognitoTabs = emptyList(),
                    activeIncognitoTabId = "",
                    isIncognitoViewActive = false,
                    isTabSwitcherOpen = false
                )
            } else {
                val fresh = BrowserTab(title = "New Tab", url = "chrome://newtab")
                state.copy(
                    regularTabs = listOf(fresh),
                    activeRegularTabId = fresh.id,
                    isTabSwitcherOpen = false
                )
            }
        }
    }

    fun selectTab(tabId: String, isIncognito: Boolean) {
        _uiState.update { previousState ->
            val state = cancelAutomationPlayback(previousState, "Automation stopped because the active tab changed.")
            if (isIncognito) {
                state.copy(
                    activeIncognitoTabId = tabId,
                    isIncognitoViewActive = true,
                    isTabSwitcherOpen = false
                )
            } else {
                state.copy(
                    activeRegularTabId = tabId,
                    isIncognitoViewActive = false,
                    isTabSwitcherOpen = false
                )
            }
        }
    }

    fun switchIncognitoView(isIncognito: Boolean) {
        _uiState.update { previousState ->
            cancelAutomationPlayback(previousState, "Automation stopped because the active tab changed.")
                .copy(isIncognitoViewActive = isIncognito)
        }
    }

    fun setTabSwitcherVisible(visible: Boolean) {
        _uiState.update { it.copy(isTabSwitcherOpen = visible, isOmniboxEditing = false) }
    }

    fun setOmniboxEditing(editing: Boolean, initialText: String? = null) {
        val currentTab = _uiState.value.currentTab
        val text = initialText ?: if (currentTab?.isNewTabPage == true) "" else (currentTab?.url ?: "")
        _uiState.update {
            it.copy(
                isOmniboxEditing = editing,
                omniboxInput = if (editing) text else it.omniboxInput
            )
        }
        if (editing) {
            updateSuggestions(text)
        }
    }

    fun onOmniboxInputChanged(input: String) {
        _uiState.update { it.copy(omniboxInput = input) }
        updateSuggestions(input)
    }

    private fun updateSuggestions(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _searchSuggestions.value = emptyList()
            return
        }

        val suggestions = mutableListOf<SuggestionItem>()

        // Check if query looks like a search
        suggestions.add(
            SuggestionItem.Search(
                query = trimmed,
                engine = _uiState.value.selectedSearchEngine
            )
        )

        // Direct URL match
        if (trimmed.contains(".") && !trimmed.contains(" ")) {
            val url = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
            suggestions.add(SuggestionItem.DirectUrl(url = url, display = trimmed))
        }

        // Matching bookmarks
        bookmarks.value.filter {
            it.title.contains(trimmed, ignoreCase = true) || it.url.contains(trimmed, ignoreCase = true)
        }.take(3).forEach {
            suggestions.add(SuggestionItem.BookmarkItem(it))
        }

        // Matching history
        history.value.filter {
            it.title.contains(trimmed, ignoreCase = true) || it.url.contains(trimmed, ignoreCase = true)
        }.distinctBy { it.url }.take(3).forEach {
            suggestions.add(SuggestionItem.HistoryItemSuggestion(it))
        }

        _searchSuggestions.value = suggestions
    }

    fun navigateTo(input: String) {
        val targetUrl = resolveUrl(input.trim())
        val current = _uiState.value.currentTab ?: return
        if (!current.isNewTabPage && sameNavigationUrl(current.url, targetUrl)) {
            _uiState.update {
                cancelAutomationPlayback(it, "Automation stopped because navigation changed.")
                    .copy(isOmniboxEditing = false, isTabSwitcherOpen = false)
            }
            return
        }
        val navTrigger = current.navigationTrigger + 1L

        _uiState.update { previousState ->
            val state = cancelAutomationPlayback(previousState, "Automation stopped because navigation changed.")
            val updateTab: (BrowserTab) -> BrowserTab = { tab ->
                if (tab.id == current.id) {
                    tab.copy(
                        url = targetUrl,
                        title = targetUrl,
                        isLoading = true,
                        progress = 10,
                        navigationTrigger = navTrigger
                    )
                } else tab
            }

            if (state.isIncognitoViewActive) {
                state.copy(
                    incognitoTabs = state.incognitoTabs.map(updateTab),
                    isOmniboxEditing = false,
                    isTabSwitcherOpen = false
                )
            } else {
                state.copy(
                    regularTabs = state.regularTabs.map(updateTab),
                    isOmniboxEditing = false,
                    isTabSwitcherOpen = false
                )
            }
        }
    }

    private fun resolveUrl(input: String): String {
        if (input.isBlank() || input == "chrome://newtab") return "chrome://newtab"
        val trimmed = input.trim()

        if (URLUtil.isValidUrl(trimmed)) {
            return trimmed
        }
        if (trimmed.startsWith("localhost:") || trimmed.startsWith("127.0.0.1")) {
            return "http://$trimmed"
        }
        val domainPattern = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z]{2,})+(/.*)?$")
        if (domainPattern.matches(trimmed)) {
            return "https://$trimmed"
        }

        // Fallback to chosen search engine
        val encoded = Uri.encode(trimmed)
        return "${_uiState.value.selectedSearchEngine.searchUrl}$encoded"
    }

    fun onPageStarted(tabId: String, url: String) {
        updateTabState(tabId) {
            it.copy(url = url, isLoading = true, progress = 15)
        }
    }

    fun onPageFinished(tabId: String, url: String, title: String?) {
        val finalTitle = if (!title.isNullOrBlank()) title else url
        updateTabState(tabId) {
            it.copy(
                url = url,
                title = finalTitle,
                isLoading = false,
                progress = 100
            )
        }

        val tab = _uiState.value.currentTab
        if (tab?.id == tabId && !tab.isIncognito) {
            viewModelScope.launch {
                repository.addHistory(title = finalTitle, url = url, isIncognito = false)
            }
        }
    }

    fun onProgressChanged(tabId: String, progress: Int) {
        updateTabState(tabId) {
            it.copy(
                progress = progress,
                isLoading = progress < 100
            )
        }
    }

    fun onNavigationStateChanged(tabId: String, canGoBack: Boolean, canGoForward: Boolean) {
        updateTabState(tabId) {
            it.copy(canGoBack = canGoBack, canGoForward = canGoForward)
        }
    }

    /**
     * Announces why a page did not work, instead of leaving the user with a blank screen.
     *
     * Called from WebView callbacks on the UI thread. The message is deliberately kept even when the
     * page then recovers (a renderer restart reloads by itself): the reload happens *because* of the
     * failure, and hiding the failure is what made the peak-load behaviour impossible to understand.
     */
    fun onPageEvent(tabId: String, message: String) {
        if (message.isBlank()) return
        _uiState.update { state ->
            val current = state.currentTab
            // Only the tab on screen owns a WebView, but ignore anything else defensively so a
            // background tab can never raise a snackbar about a page nobody is looking at.
            if (current != null && current.id != tabId) return@update state
            state.copy(
                pageStatus = message,
                pageStatusToken = state.pageStatusToken + 1L
            )
        }
    }

    fun setAutomationsVisible(visible: Boolean) {
        _uiState.update { it.copy(isAutomationsOpen = visible) }
    }

    @Synchronized
    fun startAutomationRecording() {
        val tab = _uiState.value.currentTab
        when {
            tab == null || tab.isNewTabPage -> {
                _uiState.update { it.copy(automationStatus = "Open a website before recording an automation.") }
            }
            tab.isIncognito -> {
                _uiState.update { it.copy(automationStatus = "Recording is unavailable in incognito tabs.") }
            }
            !_uiState.value.isJavaScriptEnabled -> {
                _uiState.update { it.copy(automationStatus = "Enable JavaScript before recording website actions.") }
            }
            !URLUtil.isHttpUrl(tab.url) && !URLUtil.isHttpsUrl(tab.url) -> {
                _uiState.update { it.copy(automationStatus = "Only web pages can be recorded.") }
            }
            else -> {
                lastAutomationStepAt = 0L
                _uiState.update {
                    it.copy(
                        isAutomationsOpen = false,
                        isRecordingAutomation = true,
                        recordingAutomationTabId = tab.id,
                        recordingAutomationStartUrl = tab.url,
                        recordingAutomationSteps = emptyList(),
                        automationStatus = "Recording on ${tab.displayHost}."
                    )
                }
            }
        }
    }

    @Synchronized
    fun onAutomationStepRecorded(tabId: String, step: AutomationStep) {
        val currentState = _uiState.value
        if (!currentState.isRecordingAutomation || currentState.recordingAutomationTabId != tabId ||
            currentState.recordingAutomationSteps.size >= MAX_AUTOMATION_STEPS
        ) return

        val now = System.currentTimeMillis()
        val safeStep = step.copy(
            selector = step.selector.take(MAX_AUTOMATION_SELECTOR_LENGTH),
            value = step.value.take(MAX_AUTOMATION_VALUE_LENGTH),
            delayBeforeMs = if (lastAutomationStepAt == 0L) DEFAULT_AUTOMATION_DELAY_MS
            else (now - lastAutomationStepAt).coerceIn(MIN_AUTOMATION_DELAY_MS, MAX_AUTOMATION_DELAY_MS)
        )
        lastAutomationStepAt = now
        _uiState.update { state ->
            if (!state.isRecordingAutomation || state.recordingAutomationTabId != tabId ||
                state.recordingAutomationSteps.size >= MAX_AUTOMATION_STEPS
            ) {
                state
            } else {
                state.copy(recordingAutomationSteps = state.recordingAutomationSteps + safeStep)
            }
        }
    }

    @Synchronized
    fun saveAutomationRecording(name: String) {
        val state = _uiState.value
        val startUrl = state.recordingAutomationStartUrl
        if (startUrl.isNullOrBlank() || state.recordingAutomationSteps.isEmpty()) {
            _uiState.update {
                it.copy(automationStatus = "No actions were recorded. Interact with the page before saving.")
            }
            return
        }

        val automation = BrowserAutomation(
            name = name.trim().take(MAX_AUTOMATION_NAME_LENGTH).ifBlank {
                "Automation ${state.automations.size + 1}"
            },
            startUrl = startUrl,
            steps = state.recordingAutomationSteps.toList()
        )
        automationStore.save(automation)
        lastAutomationStepAt = 0L
        _uiState.update {
            it.copy(
                automations = automationStore.getAll(),
                isRecordingAutomation = false,
                recordingAutomationTabId = null,
                recordingAutomationStartUrl = null,
                recordingAutomationSteps = emptyList(),
                automationStatus = "Saved \"${automation.name}\"."
            )
        }
    }

    @Synchronized
    fun cancelAutomationRecording() {
        lastAutomationStepAt = 0L
        _uiState.update {
            it.copy(
                isRecordingAutomation = false,
                recordingAutomationTabId = null,
                recordingAutomationStartUrl = null,
                recordingAutomationSteps = emptyList(),
                automationStatus = "Recording discarded."
            )
        }
    }

    fun deleteAutomation(automation: BrowserAutomation) {
        automationStore.delete(automation.id)
        _uiState.update { it.copy(automations = automationStore.getAll()) }
    }

    fun runAutomation(automation: BrowserAutomation) {
        if (!_uiState.value.isJavaScriptEnabled) {
            _uiState.update { it.copy(automationStatus = "Enable JavaScript in Settings to run automations.") }
            return
        }
        if (automation.steps.isEmpty()) {
            _uiState.update { it.copy(automationStatus = "This automation has no recorded steps.") }
            return
        }

        val runId = ++nextAutomationRunId
        _uiState.update { state ->
            val current = state.currentTab ?: return@update state
            val startTab = if (current.isNewTabPage) {
                current.copy(
                    url = automation.startUrl,
                    title = "Loading...",
                    isLoading = true,
                    progress = 10,
                    navigationTrigger = current.navigationTrigger + 1L
                )
            } else current
            val updatedState = if (state.isIncognitoViewActive) {
                state.copy(
                    incognitoTabs = state.incognitoTabs.map { if (it.id == current.id) startTab else it }
                )
            } else {
                state.copy(
                    regularTabs = state.regularTabs.map { if (it.id == current.id) startTab else it }
                )
            }
            updatedState.copy(
                isAutomationsOpen = false,
                automationPlayback = AutomationPlayback(runId, current.id, automation),
                automationStatus = "Running \"${automation.name}\"..."
            )
        }
    }

    fun onAutomationPlaybackFinished(runId: Long, result: String) {
        _uiState.update { state ->
            if (state.automationPlayback?.runId != runId) state
            else state.copy(automationPlayback = null, automationStatus = result)
        }
    }

    fun stopAutomationPlayback() {
        _uiState.update {
            if (it.automationPlayback == null) it
            else it.copy(automationPlayback = null, automationStatus = "Automation stopped.")
        }
    }

    fun reloadCurrentTab() {
        _uiState.update { it.copy(reloadTrigger = it.reloadTrigger + 1L) }
    }

    fun navigateBack() {
        _uiState.update { it.copy(navigateBackTrigger = it.navigateBackTrigger + 1L) }
    }

    fun navigateForward() {
        _uiState.update { it.copy(navigateForwardTrigger = it.navigateForwardTrigger + 1L) }
    }

    fun toggleDesktopSite() {
        val current = _uiState.value.currentTab ?: return
        val newDesktopState = !current.isDesktopSite
        updateTabState(current.id) {
            it.copy(isDesktopSite = newDesktopState)
        }
    }

    fun toggleBookmarkCurrentTab() {
        val tab = _uiState.value.currentTab ?: return
        if (tab.isNewTabPage) return

        viewModelScope.launch {
            val isAlreadyBookmarked = bookmarks.value.any { it.url == tab.url }
            if (isAlreadyBookmarked) {
                repository.deleteBookmarkByUrl(tab.url)
            } else {
                repository.addBookmark(title = tab.title, url = tab.url)
            }
        }
    }

    fun addBookmark(title: String, url: String) {
        viewModelScope.launch {
            repository.addBookmark(title, url)
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            repository.deleteBookmark(bookmark)
        }
    }

    fun deleteHistoryItem(item: HistoryItem) {
        viewModelScope.launch {
            repository.deleteHistoryItem(item)
        }
    }

    fun clearBrowsingData() {
        viewModelScope.launch {
            repository.clearAllHistory()
        }
    }

    fun setEngineKind(kind: BrowserEngineKind) {
        enginePreferences.edit().putString(BrowserEngineKind.KEY_KIND, kind.storageValue).apply()
        _engineKind.value = kind
    }

    /**
     * A URL change that is not a new document: a redirect, a fragment, a history entry pushed by the
     * page. The tab's address and loading state are updated without inventing a second history visit.
     */
    fun onLocationChanged(tabId: String, url: String) {
        updateTabState(tabId) { it.copy(url = url) }
    }

    /**
     * Opens a tab for a page that asked for a new window (`target="_blank"`, `window.open`) and
     * returns its id, so the engine can hand the new session straight to it.
     */
    fun openTabForPopup(url: String): String {
        openNewTab(url = url, isIncognito = _uiState.value.isIncognitoViewActive)
        return _uiState.value.currentTab?.id ?: ""
    }

    /** A page's own `window.close()`. */
    fun closeTabById(tabId: String) {
        val state = _uiState.value
        val isIncognito = state.incognitoTabs.any { it.id == tabId }
        if (!isIncognito && state.regularTabs.none { it.id == tabId }) return
        closeTab(tabId, isIncognito)
    }

    fun setDownloadsVisible(visible: Boolean) {
        _uiState.update { it.copy(isDownloadsOpen = visible) }
    }

    fun onDownloadStatus(message: String) {
        if (message.isBlank()) return
        _uiState.update {
            it.copy(downloadStatus = message, downloadStatusToken = it.downloadStatusToken + 1L)
        }
    }

    fun removeDownload(id: String) {
        downloadRegistry.remove(id)
    }

    fun clearDownloads() {
        downloadRegistry.clear()
    }

    fun setBookmarksVisible(visible: Boolean) {
        _uiState.update { it.copy(isBookmarksOpen = visible) }
    }

    fun setHistoryVisible(visible: Boolean) {
        _uiState.update { it.copy(isHistoryOpen = visible) }
    }

    fun setSettingsVisible(visible: Boolean) {
        _uiState.update { it.copy(isSettingsOpen = visible) }
    }

    fun setFindInPageVisible(visible: Boolean) {
        _uiState.update {
            it.copy(
                isFindInPageOpen = visible,
                findQuery = if (visible) it.findQuery else "",
                findMatchIndex = 0,
                findMatchTotal = 0
            )
        }
    }

    fun setFindQuery(query: String) {
        _uiState.update { it.copy(findQuery = query) }
    }

    fun updateFindMatches(currentIndex: Int, total: Int) {
        _uiState.update { it.copy(findMatchIndex = currentIndex, findMatchTotal = total) }
    }

    fun setSearchEngine(engine: SearchEngine) {
        _uiState.update { it.copy(selectedSearchEngine = engine) }
    }

    fun setJavaScriptEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isJavaScriptEnabled = enabled) }
        reloadCurrentTab()
    }

    fun setDesktopSiteDefault(enabled: Boolean) {
        _uiState.update { it.copy(isDesktopSiteDefault = enabled) }
    }

    private fun cancelAutomationPlayback(state: BrowserUiState, reason: String): BrowserUiState =
        if (state.automationPlayback == null) state
        else state.copy(automationPlayback = null, automationStatus = reason)

    private fun sameNavigationUrl(first: String, second: String): Boolean =
        first.substringBefore('#').trimEnd('/') == second.substringBefore('#').trimEnd('/')

    private fun updateTabState(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        _uiState.update { state ->
            if (state.isIncognitoViewActive) {
                state.copy(incognitoTabs = state.incognitoTabs.map { if (it.id == tabId) transform(it) else it })
            } else {
                state.copy(regularTabs = state.regularTabs.map { if (it.id == tabId) transform(it) else it })
            }
        }
    }
}

sealed class SuggestionItem {
    data class Search(val query: String, val engine: SearchEngine) : SuggestionItem()
    data class DirectUrl(val url: String, val display: String) : SuggestionItem()
    data class BookmarkItem(val bookmark: Bookmark) : SuggestionItem()
    data class HistoryItemSuggestion(val historyItem: HistoryItem) : SuggestionItem()
}
