package com.example.ui.engine

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.example.ui.engine.gecko.GeckoRuntimeManager

/** Which engine renders pages. */
enum class BrowserEngineKind(val storageValue: String, val displayName: String) {
    /**
     * Mozilla's Gecko engine with SpiderMonkey, embedded through GeckoView: Gecko renders and lays
     * out, SpiderMonkey runs JavaScript, and Gecko's own network and storage stacks handle cookies,
     * localStorage, the HTTP cache and HTTPS. This is the default.
     */
    GECKO("gecko", "Gecko (embedded)"),

    /**
     * The previous engine: `android.webkit.WebView`, which is also Chromium (Blink + V8) but is owned
     * by the operating system rather than by this app. Kept as a fallback while the replacement
     * proves itself, and selected from Settings → Browser engine.
     */
    WEBVIEW("webview", "Android WebView (system)");

    companion object {
        fun fromStorage(value: String?): BrowserEngineKind =
            entries.firstOrNull { it.storageValue == value } ?: GECKO
    }
}

/**
 * Clears what the engines keep, not just what this app keeps.
 *
 * "Clear browsing data" used to empty the history table and nothing else: cookies, site storage and
 * the cache all survived it. Each engine now gets asked properly.
 */
object BrowserDataCleaner {

    fun clear(
        context: Context,
        kind: BrowserEngineKind,
        onFinished: (String) -> Unit = {},
    ) {
        when (kind) {
            BrowserEngineKind.GECKO -> GeckoRuntimeManager.clearBrowsingData(context) { result ->
                onFinished(
                    result.fold(
                        onSuccess = { "Browsing data cleared: cookies, site storage, cache and permissions." },
                        onFailure = { error ->
                            "History was cleared, but the browser engine refused to clear its own " +
                                "data: ${error.message ?: "unknown reason"}."
                        }
                    )
                )
            }

            BrowserEngineKind.WEBVIEW -> {
                // The system engine keeps cookies and DOM storage in its own process; these calls
                // reach it. Its HTTP cache belongs to the WebView implementation itself and has no
                // API that clears it without an attached WebView.
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                onFinished("Browsing data cleared: cookies and site storage.")
            }
        }
    }
}
