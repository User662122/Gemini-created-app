package com.example.devtools

/**
 * Parses one `Set-Cookie` header value.
 *
 * This matters because a `Set-Cookie` header is the *only* place the app can see a cookie's real
 * scope: `CookieManager.getCookie(url)` returns a `name=value` string with no attributes at all (the
 * browser strips them). Values parsed here are never stored — only the attributes and a masked value
 * are kept, because the Cookie Inspector never needs the secret.
 *
 * Limitation, documented rather than papered over: `WebResourceResponse.getResponseHeaders()` is a
 * `Map<String, String>`, so WebView has already merged multiple `Set-Cookie` headers into one comma
 * separated value. That cannot be split reliably (an `Expires` attribute itself contains a comma), so
 * only the first cookie of a merged header is parsed and the caller is told.
 */
object SetCookieParser {

    /** Attribute set of a single cookie, as declared by the server. */
    data class ParsedCookie(
        val name: String,
        val value: String,
        val domain: String?,
        val path: String?,
        val secure: Boolean,
        val httpOnly: Boolean,
        /** Raw `Expires` attribute, or null. Kept as sent; the inspector does not reinterpret dates. */
        val expiresAttribute: String?,
        /** Raw `Max-Age` attribute, or null. */
        val maxAgeAttribute: String?,
        val sameSite: String?,
        /** True when the value plausibly contained more than one cookie. */
        val possiblyMerged: Boolean,
    ) {
        /** Human-readable expiry: whatever the server declared, or "session cookie". */
        val expiryLabel: String
            get() = when {
                expiresAttribute != null && maxAgeAttribute != null ->
                    "Expires=$expiresAttribute, Max-Age=$maxAgeAttribute"
                expiresAttribute != null -> "Expires=$expiresAttribute"
                maxAgeAttribute != null -> "Max-Age=$maxAgeAttribute"
                else -> "session cookie (no Expires or Max-Age attribute)"
            }
    }

    fun parse(headerValue: String): ParsedCookie? {
        val text = headerValue.trim()
        if (text.isEmpty()) return null

        val segments = splitTopLevel(text)
        if (segments.isEmpty()) return null

        val first = segments[0]
        val equals = first.indexOf('=')
        if (equals <= 0) return null
        val name = first.substring(0, equals).trim()
        if (name.isEmpty()) return null
        val value = first.substring(equals + 1).trim()

        var domain: String? = null
        var path: String? = null
        var secure = false
        var httpOnly = false
        var expires: String? = null
        var maxAge: String? = null
        var sameSite: String? = null

        for (index in 1 until segments.size) {
            val segment = segments[index].trim()
            if (segment.isEmpty()) continue
            val separator = segment.indexOf('=')
            val attributeName = (if (separator < 0) segment else segment.substring(0, separator)).trim().lowercase()
            val attributeValue = if (separator < 0) "" else segment.substring(separator + 1).trim()
            when (attributeName) {
                "domain" -> domain = attributeValue.trimStart('.').ifBlank { null }
                "path" -> path = attributeValue.ifBlank { null }
                "secure" -> secure = true
                "httponly" -> httpOnly = true
                "expires" -> expires = attributeValue.ifBlank { null }
                "max-age" -> maxAge = attributeValue.ifBlank { null }
                "samesite" -> sameSite = attributeValue.ifBlank { null }
            }
        }

        // A comma inside the first segment followed by another name=value pair means WebView merged
        // headers. Report it instead of guessing.
        val possiblyMerged = value.contains(", ") && segments.size == 1 && text.contains(", ")

        return ParsedCookie(
            name = name,
            value = value,
            domain = domain,
            path = path,
            secure = secure,
            httpOnly = httpOnly,
            expiresAttribute = expires,
            maxAgeAttribute = maxAge,
            sameSite = sameSite,
            possiblyMerged = possiblyMerged,
        )
    }

    /**
     * Splits on `;` at the top level, honouring double quotes so a value containing `;` is not torn
     * apart.
     */
    private fun splitTopLevel(text: String): List<String> {
        val parts = ArrayList<String>(4)
        val current = StringBuilder(text.length)
        var inQuotes = false
        for (character in text) {
            when {
                character == '"' -> {
                    inQuotes = !inQuotes
                    current.append(character)
                }
                character == ';' && !inQuotes -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(character)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts
    }

    /**
     * Parses the `name=value; name2=value2` string returned by `CookieManager.getCookie(url)`.
     *
     * Only name/value pairs exist there — no attributes, which is exactly why the Cookie Inspector
     * shows Domain/Path/Secure/HttpOnly/Expiry as unavailable for these rows.
     */
    fun parseCookieHeader(header: String): List<Pair<String, String>> {
        if (header.isBlank()) return emptyList()
        val result = ArrayList<Pair<String, String>>(4)
        for (segment in header.split(';')) {
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) continue
            val equals = trimmed.indexOf('=')
            if (equals <= 0) continue
            val name = trimmed.substring(0, equals).trim()
            if (name.isEmpty()) continue
            result.add(name to trimmed.substring(equals + 1))
            if (result.size >= 60) break
        }
        return result
    }
}
