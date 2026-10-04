package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.BrowserTab

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

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
    onPageStarted: (String, String) -> Unit,
    onPageFinished: (String, String, String?) -> Unit,
    onProgressChanged: (String, Int) -> Unit,
    onNavigationStateChanged: (String, Boolean, Boolean) -> Unit,
    onFindMatchesChanged: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var webViewInstance by remember(tab.id) { mutableStateOf<WebView?>(null) }
    var defaultUserAgent by remember(tab.id) { mutableStateOf<String?>(null) }
    var currentDesktopMode by remember(tab.id) { mutableStateOf(tab.isDesktopSite) }

    // React to user navigation trigger (Omnibox enter, shortcut tap, bookmark tap)
    LaunchedEffect(tab.navigationTrigger) {
        if (tab.navigationTrigger > 0L && !tab.isNewTabPage) {
            webViewInstance?.loadUrl(tab.url)
        }
    }

    // React to explicit user reload action
    LaunchedEffect(reloadTrigger) {
        if (reloadTrigger > 0L) {
            webViewInstance?.reload()
        }
    }

    // React to navigation back trigger
    LaunchedEffect(navigateBackTrigger) {
        if (navigateBackTrigger > 0L) {
            webViewInstance?.let { wv ->
                if (wv.canGoBack()) {
                    wv.goBack()
                }
            }
        }
    }

    // React to navigation forward trigger
    LaunchedEffect(navigateForwardTrigger) {
        if (navigateForwardTrigger > 0L) {
            webViewInstance?.let { wv ->
                if (wv.canGoForward()) {
                    wv.goForward()
                }
            }
        }
    }

    // React to desktop site toggle cleanly without infinite loops
    LaunchedEffect(tab.isDesktopSite) {
        if (currentDesktopMode != tab.isDesktopSite) {
            currentDesktopMode = tab.isDesktopSite
            webViewInstance?.let { wv ->
                val targetUA = if (tab.isDesktopSite) DESKTOP_USER_AGENT else defaultUserAgent
                wv.settings.userAgentString = targetUA
                wv.reload()
            }
        }
    }

    // React to JavaScript setting change
    LaunchedEffect(isJavaScriptEnabled) {
        webViewInstance?.settings?.javaScriptEnabled = isJavaScriptEnabled
    }

    // React to find query change
    LaunchedEffect(findQuery) {
        webViewInstance?.let { wv ->
            if (findQuery.isNotBlank()) {
                wv.findAllAsync(findQuery)
            } else {
                wv.clearMatches()
                onFindMatchesChanged(0, 0)
            }
        }
    }

    // React to find next trigger
    LaunchedEffect(findTriggerNext) {
        if (findTriggerNext > 0L) {
            webViewInstance?.findNext(true)
        }
    }

    // React to find prev trigger
    LaunchedEffect(findTriggerPrev) {
        if (findTriggerPrev > 0L) {
            webViewInstance?.findNext(false)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("webview_container_${tab.id}")
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

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

                    if (tab.isDesktopSite) {
                        settings.userAgentString = DESKTOP_USER_AGENT
                    }

                    setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                        onFindMatchesChanged(activeMatchOrdinal, numberOfMatches)
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val targetUri = request?.url ?: return false
                            val scheme = targetUri.scheme ?: return false

                            if (scheme == "http" || scheme == "https") {
                                return false // Let WebView handle normal web requests
                            }

                            // Handle external schemes (mailto:, tel:, market:)
                            return try {
                                val intent = Intent(Intent.ACTION_VIEW, targetUri)
                                ctx.startActivity(intent)
                                true
                            } catch (e: Exception) {
                                true
                            }
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            url?.let {
                                onPageStarted(tab.id, it)
                            }
                            view?.let {
                                onNavigationStateChanged(tab.id, it.canGoBack(), it.canGoForward())
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val finalUrl = url ?: tab.url
                            onPageFinished(tab.id, finalUrl, view?.title)
                            view?.let {
                                onNavigationStateChanged(tab.id, it.canGoBack(), it.canGoForward())
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                onProgressChanged(tab.id, 100)
                            }
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?
                        ): Boolean {
                            view?.let {
                                (it.parent as? ViewGroup)?.removeView(it)
                                it.destroy()
                            }
                            webViewInstance = null
                            return true
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
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

                    if (!tab.isNewTabPage) {
                        loadUrl(tab.url)
                    }

                    webViewInstance = this
                }
            },
            update = { webView ->
                // Maintain instance reference only; zero reloads or loads inside update lambda
                webViewInstance = webView
            }
        )
    }

    DisposableEffect(tab.id) {
        onDispose {
            webViewInstance?.stopLoading()
            webViewInstance = null
        }
    }
}
