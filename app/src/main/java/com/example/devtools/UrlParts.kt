package com.example.devtools

/**
 * Tiny, dependency-free URL reader.
 *
 * This exists instead of `android.net.Uri` so the parsing logic stays pure Kotlin: it can be unit
 * tested on the JVM without Robolectric, and it never throws on the malformed URLs that real pages
 * produce.
 */
object UrlParts {

    /** Lower-case scheme, or null when the string is not absolute. */
    fun scheme(url: String): String? {
        val separator = url.indexOf(':')
        if (separator <= 0) return null
        val candidate = url.substring(0, separator)
        if (!candidate.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        return candidate.lowercase()
    }

    /** Host without userinfo/port, lower-cased, or null when there is no authority. */
    fun host(url: String): String? {
        val scheme = scheme(url) ?: return null
        if (scheme == "file") return null
        val afterScheme = url.substring(scheme.length + 1)
        if (!afterScheme.startsWith("//")) return null
        val authority = afterScheme.substring(2)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        if (authority.isEmpty()) return null
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) {
            // IPv6 literal such as [::1]:8080
            val closing = hostPort.indexOf(']')
            if (closing < 0) hostPort else hostPort.substring(0, closing + 1)
        } else {
            hostPort.substringBefore(':')
        }
        return host.ifBlank { null }?.lowercase()
    }

    /** Path portion, defaulting to "/". Query and fragment are removed. */
    fun path(url: String): String {
        val scheme = scheme(url)
        val afterScheme = if (scheme != null) url.substring(scheme.length + 1) else url
        val searchStart = afterScheme.indexOfFirst { it == '?' || it == '#' }
        val withoutQuery = if (searchStart >= 0) afterScheme.substring(0, searchStart) else afterScheme
        val slash = withoutQuery.indexOf('/')
        if (slash < 0) return "/"
        val path = withoutQuery.substring(slash)
        return path.ifBlank { "/" }
    }

    /**
     * Default cookie path for a URL, as defined by RFC 6265 section 5.1.4: the request path up to,
     * but not including, the right-most "/". Used when a `Set-Cookie` header sends no `Path` attribute.
     */
    fun defaultCookiePath(url: String): String {
        val requestPath = path(url)
        val lastSlash = requestPath.lastIndexOf('/')
        return if (lastSlash <= 0) "/" else requestPath.substring(0, lastSlash)
    }

    /** Query string without the leading "?", or null when absent/empty. */
    fun query(url: String): String? {
        val start = url.indexOf('?')
        if (start < 0) return null
        val end = url.indexOf('#', start)
        val query = if (end >= 0) url.substring(start + 1, end) else url.substring(start + 1)
        return query.ifBlank { null }
    }

    /** Lower-case file extension of the last path segment, without the dot. */
    fun fileExtension(url: String): String? {
        val path = path(url)
        val lastSegment = path.substringAfterLast('/')
        val dot = lastSegment.lastIndexOf('.')
        if (dot <= 0 || dot == lastSegment.length - 1) return null
        val extension = lastSegment.substring(dot + 1).lowercase()
        if (extension.length > 8) return null
        if (!extension.all { it.isLetterOrDigit() }) return null
        return extension
    }

    fun isSecure(url: String): Boolean {
        val scheme = scheme(url) ?: return false
        return scheme == "https" || scheme == "wss"
    }

    /** `https://host` — used to group requests per site and to query the cookie store. */
    fun origin(url: String): String? {
        val scheme = scheme(url) ?: return null
        val host = host(url) ?: return null
        return "$scheme://$host"
    }

    /** `scheme://host:port/path` without query/fragment, for correlation keys. */
    fun withoutQuery(url: String): String {
        val hash = url.indexOf('#')
        val withoutFragment = if (hash >= 0) url.substring(0, hash) else url
        val question = withoutFragment.indexOf('?')
        return if (question >= 0) withoutFragment.substring(0, question) else withoutFragment
    }

    /**
     * Correlation key used to match a WebView-observed request with the same request reported by the
     * page's JavaScript hooks. Query strings are included because two requests that differ only by
     * query are different requests.
     */
    fun correlationKey(url: String, method: String): String =
        withoutQuery(url).lowercase() + "|" + method.uppercase() + "|" + (query(url) ?: "")

    /** Best-effort percent decoding for display; never throws. */
    fun decode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)

    /** Query parameters in order, with names percent-decoded. */
    fun queryParameters(url: String): List<Pair<String, String>> {
        val query = query(url) ?: return emptyList()
        val result = ArrayList<Pair<String, String>>(4)
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val equals = part.indexOf('=')
            if (equals < 0) {
                result += decode(part) to ""
            } else {
                result += decode(part.substring(0, equals)) to decode(part.substring(equals + 1))
            }
        }
        return result
    }
}
