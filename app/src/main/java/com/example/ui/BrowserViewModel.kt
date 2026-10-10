package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.example.remote.PageBridge
import com.example.remote.RemoteControl
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
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

    /** Lets the localhost control server run commands inside pages (see com.example.remote). */
    internal val pageBridge = PageBridge(runtime)

    /** The GeckoView currently showing [attachedViewTabId], used for screenshots. */
    private var attachedView: GeckoView? = null
    private var attachedViewTabId: String? = null

    val tabs = mutableStateListOf<BrowserTabState>()

    var activeTabId by mutableStateOf<String?>(null)
        private set

    val activeTab: BrowserTabState?
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull()

    init {
        pageBridge.install()
        RemoteControl.attachBrowser(this)
        openNewTab()
    }

    fun openNewTab(initialUrl: String? = null, activate: Boolean = true): BrowserTabState {
        val previousTab = activeTab
        val tab = createTab()
        tabs.add(tab)
        if (activate || previousTab == null) {
            previousTab?.session?.setActive(false)
            activeTabId = tab.id
            tab.session.setActive(true)
        } else {
            tab.session.setActive(false)
        }

        if (!initialUrl.isNullOrBlank() && initialUrl != "about:blank") {
            load(tab, initialUrl)
        }
        return tab
    }

    /** Finds a tab by id or zero-based index; a null or blank reference means the active tab. */
    fun findTab(reference: String?): BrowserTabState? {
        val ref = reference?.trim().orEmpty()
        if (ref.isEmpty()) return activeTab
        tabs.firstOrNull { it.id == ref }?.let { return it }
        return ref.toIntOrNull()?.let { tabs.getOrNull(it) }
    }

    /** Called by BrowserScreen when a GeckoView starts or stops showing a tab. */
    fun attachView(tabId: String, view: GeckoView?) {
        if (view != null) {
            attachedView = view
            attachedViewTabId = tabId
        } else if (attachedViewTabId == tabId) {
            attachedView = null
            attachedViewTabId = null
        }
    }

    /** The on-screen GeckoView for [tabId], if that tab is the one being displayed. */
    fun viewFor(tabId: String): GeckoView? = attachedView?.takeIf { attachedViewTabId == tabId }

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
        pageBridge.detach(tabId)
        closing.session.setActive(false)
        runCatching { closing.session.close() }
    }

    fun navigate(address: String) {
        val tab = activeTab ?: return
        navigate(tab, address)
    }

    /** Loads [address] (a URL or search terms) in [tab]; returns the resolved URL. */
    fun navigate(tab: BrowserTabState, address: String): String? {
        val url = BrowserAddress.resolve(address) ?: return null
        load(tab, url)
        return url
    }

    fun goBack() {
        activeTab?.let { goBack(it) }
    }

    fun goBack(tab: BrowserTabState): Boolean {
        if (!tab.canGoBack) return false
        tab.session.goBack()
        return true
    }

    fun goForward() {
        activeTab?.let { goForward(it) }
    }

    fun goForward(tab: BrowserTabState): Boolean {
        if (!tab.canGoForward) return false
        tab.session.goForward()
        return true
    }

    fun reload() {
        activeTab?.let { reload(it) }
    }

    fun reload(tab: BrowserTabState): Boolean {
        if (tab.url.isBlank()) return false
        tab.session.reload()
        return true
    }

    fun stopLoading() {
        activeTab?.let { stopLoading(it) }
    }

    fun stopLoading(tab: BrowserTabState) {
        tab.session.stop()
        tab.isLoading = false
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
        pageBridge.attach(tab.id, session)

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
        RemoteControl.detachBrowser(this)
        pageBridge.release()
        attachedView = null
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
