package com.example.data.model

import java.util.UUID

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Tab",
    val url: String = "chrome://newtab",
    val isIncognito: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isDesktopSite: Boolean = false,
    val faviconUrl: String? = null,
    val navigationTrigger: Long = 0L
) {
    val isNewTabPage: Boolean
        get() = url.isBlank() || url == "chrome://newtab" || url == "about:blank"

    val displayHost: String
        get() {
            if (isNewTabPage) return "Search or type URL"
            return try {
                val uri = android.net.Uri.parse(url)
                val host = uri.host
                if (!host.isNullOrBlank()) host.removePrefix("www.") else url
            } catch (e: Exception) {
                url
            }
        }
}
