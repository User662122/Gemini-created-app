package com.example.devtools

import android.os.Handler
import android.os.HandlerThread
import android.webkit.CookieManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Supplies the URLs whose cookie scopes should be queried for a tab: the current page first, then
 * anything else the app knows about that tab.
 */
interface CookieScopeProvider {
    fun cookieQueryUrls(tabId: String): List<String>
}

/**
 * Reads cookie information back out of `android.webkit.CookieManager`.
 *
 * What this can show, honestly:
 *
 *  * `CookieManager.getCookie(url)` returns the `Cookie` header string for a URL — that is, the
 *    `name=value` pairs the browser would send. It contains **no attributes**, which is why Domain,
 *    Path, Secure, HttpOnly and Expiry read "not available" for those rows. Android does not expose
 *    `getCookie(url, includeAttributes)` to third-party apps.
 *  * Rows that came from a `Set-Cookie` header (captured by the inspector when the app intercepts a
 *    response, or from the app's own HTTP client) *do* carry real Domain/Path/Secure/HttpOnly/Expiry
 *    values, because a `Set-Cookie` header is the server's own complete declaration.
 *  * Values are always masked; the inspector never stores a cookie value.
 *
 * `CookieManager` calls go through a dedicated `HandlerThread`, never the UI thread, and are bounded
 * by a timeout so a stuck browser process can never freeze the app.
 */
class CookieInspector(
    private val cookieManager: () -> CookieManager?,
    private val store: NetworkLogStore,
) {

    /** Installed by the app (the browser ViewModel) so the current page's cookies are always listed. */
    var scopeProvider: CookieScopeProvider? = null

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    /** Collects every cookie scope the app can see for one tab. Safe to call from a background thread. */
    fun collect(tabId: String): List<CookieRecord> {
        val manager = cookieManager() ?: return store.cookieObservations()
        val observations = store.cookieObservations()

        val urls = LinkedHashSet<String>()
        scopeProvider?.cookieQueryUrls(tabId)?.let { urls.addAll(it) }
        urls.addAll(store.originsForTab(tabId, InspectorLimits.MAX_COOKIE_HOSTS))
        urls.addAll(observations.mapNotNull { it.observedForUrl.takeIf { url -> url.isNotBlank() } })

        val records = LinkedHashMap<String, CookieRecord>()
        var queried = 0
        for (url in urls) {
            if (queried >= InspectorLimits.MAX_COOKIE_HOSTS) break
            val scheme = UrlParts.scheme(url)
            if (scheme != "http" && scheme != "https") continue
            val host = UrlParts.host(url) ?: continue
            queried++

            val cookieHeader = readCookieHeader(manager, url) ?: continue
            for ((name, value) in SetCookieParser.parseCookieHeader(cookieHeader)) {
                val recordId = "cookie-header:$host:$name"
                val observed = matchObservation(observations, host, name)
                records[recordId] = mergeCookieManagerAndObserved(
                    id = recordId,
                    host = host,
                    url = url,
                    name = name,
                    maskedValue = Redaction.maskCookieValue(value),
                    observed = observed,
                )
            }
        }

        for (observed in observations) {
            if (!records.containsKey(observed.id)) {
                records[observed.id] = observed
            }
        }

        return records.values
            .sortedWith(compareBy({ it.name.lowercase() }, { it.domain.value ?: "" }))
            .take(InspectorLimits.MAX_COOKIE_RECORDS)
    }

    /** Records a cookie the app itself wrote, so its scope (derived from the URL passed) is listed. */
    fun recordAppSetCookie(url: String, cookieText: String) {
        val host = UrlParts.host(url) ?: return
        val pairs = SetCookieParser.parseCookieHeader(cookieText)
        for ((index, pair) in pairs.withIndex()) {
            if (index > 32) break
            store.addCookieObservation(
                CookieRecord(
                    id = "app-set:$host:${pair.first}",
                    name = pair.first,
                    value = InspectorValue.known(
                        Redaction.maskCookieValue(pair.second),
                        EvidenceSource.APP_HTTP_CLIENT,
                        InspectorExplanations.COOKIE_MASKED,
                    ),
                    domain = InspectorValue.derived(
                        host,
                        "Derived from the URL this app passed to CookieManager.setCookie; no Domain " +
                            "attribute is stored for cookies the app sets.",
                    ),
                    path = InspectorValue.derived(
                        UrlParts.defaultCookiePath(url),
                        "Default path for the URL this app passed to CookieManager.setCookie.",
                    ),
                    secure = InspectorValue.derived(
                        UrlParts.isSecure(url),
                        "Derived from the scheme of the URL this app passed to CookieManager.setCookie.",
                    ),
                    httpOnly = InspectorValue.unknown(
                        "Not tracked: CookieManager.setCookie does not report an HttpOnly flag back to the app.",
                    ),
                    expiration = InspectorValue.unknown(
                        "Not tracked: a cookie set through setCookie(url, value) is a session cookie " +
                            "unless the app adds its own expiry attribute.",
                    ),
                    observedForUrl = url,
                    sources = setOf(EvidenceSource.APP_HTTP_CLIENT),
                    notes = listOf("Written by this app, not observed from page traffic."),
                )
            )
        }
    }

    fun close() {
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
    }

    private fun mergeCookieManagerAndObserved(
        id: String,
        host: String,
        url: String,
        name: String,
        maskedValue: String,
        observed: CookieRecord?,
    ): CookieRecord {
        fun <T> unreadable(field: String): InspectorValue<T> = InspectorValue.unknown(
            "Not available: the cookie was returned for $host, but Android exposes only the Cookie " +
                "header string through CookieManager, so its $field is not readable. Rows that came " +
                "from a Set-Cookie header show the real value."
        )
        return CookieRecord(
            id = id,
            name = name,
            value = InspectorValue.known(maskedValue, EvidenceSource.COOKIE_MANAGER, InspectorExplanations.COOKIE_MASKED),
            domain = observed?.domain?.takeIf { it.isKnown } ?: unreadable("Domain"),
            path = observed?.path?.takeIf { it.isKnown } ?: unreadable("Path"),
            secure = observed?.secure?.takeIf { it.isKnown } ?: unreadable("Secure flag"),
            httpOnly = observed?.httpOnly?.takeIf { it.isKnown } ?: unreadable("HttpOnly flag"),
            expiration = observed?.expiration?.takeIf { it.isKnown } ?: unreadable("expiry"),
            observedForUrl = url,
            sources = if (observed == null) {
                setOf(EvidenceSource.COOKIE_MANAGER)
            } else {
                setOf(EvidenceSource.COOKIE_MANAGER) + observed.sources
            },
            notes = observed?.notes.orEmpty(),
        )
    }

    /** Finds the Set-Cookie row (if any) that describes the same cookie for this host. */
    private fun matchObservation(
        observations: List<CookieRecord>,
        host: String,
        name: String,
    ): CookieRecord? = observations.firstOrNull { observed ->
        if (observed.name != name) return@firstOrNull false
        val domain = observed.domain.value ?: return@firstOrNull false
        val normalized = domain.trimStart('.').lowercase()
        host.equals(normalized, ignoreCase = true) || host.endsWith(".$normalized")
    }

    /**
     * Reads the cookie header on a dedicated looper thread with a hard timeout, so neither the UI
     * thread nor a WebView thread can block on the browser process.
     */
    private fun readCookieHeader(manager: CookieManager, url: String): String? {
        val handler = ensureHandler() ?: return null
        val latch = CountDownLatch(1)
        var result: String? = null
        val posted = handler.post {
            try {
                result = manager.getCookie(url)
            } catch (_: Throwable) {
                // A failing cookie read must never break the inspector or the page.
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return null
        return if (latch.await(COOKIE_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) result else null
    }

    private fun ensureHandler(): Handler? {
        workerHandler?.let { return it }
        synchronized(this) {
            workerHandler?.let { return it }
            val thread = HandlerThread(THREAD_NAME)
            thread.start()
            workerThread = thread
            val handler = Handler(thread.looper)
            workerHandler = handler
            return handler
        }
    }

    private companion object {
        const val THREAD_NAME = "network-inspector-cookies"
        const val COOKIE_QUERY_TIMEOUT_MS = 1_000L
    }
}
