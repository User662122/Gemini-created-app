package com.example.ui

import android.app.Application
import android.net.Uri
import android.webkit.URLUtil
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BrowserDatabase
import com.example.data.BrowserRepository
import com.example.data.model.Bookmark
import com.example.data.model.BrowserTab
import com.example.data.model.HistoryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

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
    val navigateForwardTrigger: Long = 0L
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

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>>
    val history: StateFlow<List<HistoryItem>>

    // Combined query suggestions (matching bookmarks, history, and search engine)
    private val _searchSuggestions = MutableStateFlow<List<SuggestionItem>>(emptyList())
    val searchSuggestions: StateFlow<List<SuggestionItem>> = _searchSuggestions.asStateFlow()

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
                activeRegularTabId = firstTab.id
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
            navigationTrigger = if (url == "chrome://newtab") 0L else System.currentTimeMillis()
        )

        _uiState.update { state ->
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
        _uiState.update { state ->
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
        _uiState.update { state ->
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
        _uiState.update { state ->
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
        _uiState.update { state ->
            state.copy(isIncognitoViewActive = isIncognito)
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
        val navTrigger = System.currentTimeMillis()

        _uiState.update { state ->
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

    fun reloadCurrentTab() {
        _uiState.update { it.copy(reloadTrigger = System.currentTimeMillis()) }
    }

    fun navigateBack() {
        _uiState.update { it.copy(navigateBackTrigger = System.currentTimeMillis()) }
    }

    fun navigateForward() {
        _uiState.update { it.copy(navigateForwardTrigger = System.currentTimeMillis()) }
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
