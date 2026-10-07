package com.example.devtools

/**
 * The single place where the inspector decides that something is too sensitive to show.
 *
 * Rules, in the spirit of Android's `android.util.Log` guidance ("never log credentials"):
 *
 *  * Masking happens **at capture time**, on the background thread, before anything is stored. A
 *    masked value therefore never sits in the inspector's buffer, never reaches the UI, and never
 *    appears in an exported report.
 *  * Header values whose *name* looks sensitive (see [isSensitiveHeaderName]) are masked. Their raw
 *    value is kept only while raw capture is on, which is what powers the per-field "Reveal" action.
 *  * Cookie values are masked with [maskCookieValue] — the value is never stored in either mode,
 *    because the Cookie Inspector needs only name/domain/path/flags/expiry.
 *  * Request bodies are off unless the user enables them, and are scrubbed with [scrubText] before
 *    they are stored.
 *  * Console messages are always scrubbed, in both modes, because page code routinely logs tokens.
 *
 * Masking is intentionally conservative: over-masking costs a little convenience in a debug tool,
 * under-masking can leak a live session token into a screenshot or a bug report.
 */
object Redaction {

    /** Replacement text for a masked value. */
    const val MASK = "••••••"

    /**
     * Header names that are always treated as sensitive (lower-case, exact match).
     */
    val EXACT_SENSITIVE_HEADERS: Set<String> = setOf(
        "authorization",
        "proxy-authorization",
        "www-authenticate",
        "proxy-authenticate",
        "authentication",
        "cookie",
        "cookie2",
        "set-cookie",
        "set-cookie2",
        "x-api-key",
        "api-key",
        "apikey",
        "x-auth-token",
        "x-authorization",
        "x-access-token",
        "x-session-token",
        "x-csrf-token",
        "x-xsrf-token",
        "x-amz-security-token",
        "x-goog-api-key",
        "x-firebase-appcheck",
        "x-functions-key",
        "x-ms-client-secret",
        "sec-websocket-key",
    )

    /**
     * Substrings that make a header name sensitive even when it is not in
     * [EXACT_SENSITIVE_HEADERS] (for example `X-Company-Auth-Token`).
     */
    private val SENSITIVE_HEADER_FRAGMENTS: List<String> = listOf(
        "token", "secret", "password", "passwd", "credential", "cookie", "session", "auth",
        "apikey", "api-key", "private-key", "signature",
    )

    /** Header values that may be printed in full even though the name matched a fragment. */
    private val PUBLIC_TOKEN_LIKE_HEADERS: Set<String> = setOf(
        "sec-websocket-key",
    )

    /**
     * Tokens inside a query-parameter name that make its *value* sensitive
     * (for example `?access_token=…`, `?api_key=…`, `?SAMLResponse=…`).
     */
    private val SENSITIVE_PARAM_TOKENS: Set<String> = setOf(
        "token", "tokens", "secret", "password", "passwd", "pwd", "pass", "passcode",
        "auth", "authorization", "authenticated", "access", "refresh", "bearer", "session",
        "sessionid", "sid", "samlresponse", "assertion", "credential", "credentials",
        "apikey", "key", "keys", "jwt", "signature", "sig", "otp", "pin", "private",
        "csrf", "xsrf", "sso",
    )

    /** `true` when a header value must be masked. */
    fun isSensitiveHeaderName(name: String): Boolean {
        val lowered = name.lowercase()
        if (lowered in PUBLIC_TOKEN_LIKE_HEADERS) return false
        if (lowered in EXACT_SENSITIVE_HEADERS) return true
        return SENSITIVE_HEADER_FRAGMENTS.any { lowered.contains(it) }
    }

    /**
     * Turns one header into a storable [HttpField].
     *
     * @param captureRawValues when true (raw capture enabled) the original value is also stored, so
     *        the detail screen can reveal this single field. When false the value is unrecoverable.
     */
    fun headerField(
        name: String,
        value: String,
        captureRawValues: Boolean,
        extraNote: String? = null,
    ): HttpField {
        val truncated = truncate(value, InspectorLimits.MAX_HEADER_VALUE_CHARS)
        val wasTruncated = truncated.length != value.length
        val notes = ArrayList<String>(2)
        extraNote?.let { notes += it }
        if (wasTruncated) {
            notes += "Value truncated to ${InspectorLimits.MAX_HEADER_VALUE_CHARS} characters " +
                "(original ${value.length})."
        }

        if (isSensitiveHeaderName(name)) {
            notes += "Name matches the sensitive-field policy, so the value is masked."
            return HttpField(
                name = name,
                display = "$MASK (${value.length} chars)",
                raw = if (captureRawValues) truncated else null,
                redaction = RedactionKind.MASKED,
                note = notes.joinToString(" "),
            )
        }

        return HttpField(
            name = name,
            display = truncated,
            raw = truncated,
            redaction = if (wasTruncated) RedactionKind.TRUNCATED else RedactionKind.NONE,
            note = notes.takeIf { it.isNotEmpty() }?.joinToString(" "),
        )
    }

    /**
     * Masks a cookie value. Cookie values are **never** stored: the Cookie Inspector only needs the
     * name, scope, flags and expiry, and `CookieManager` remains the source of truth if the app ever
     * needs the real value.
     */
    fun maskCookieValue(value: String?): String =
        if (value.isNullOrEmpty()) "" else "$MASK (${value.length} chars)"

    /**
     * Masks the values of sensitive query parameters so a URL can be rendered/copied safely.
     *
     * Only parameter *values* are replaced; the path and the non-sensitive parameters stay readable
     * so the URL remains useful for debugging. Pass `maskSensitiveParams = false` (raw mode) to see
     * the URL exactly as observed.
     */
    fun displayUrl(
        url: String,
        maskSensitiveParams: Boolean,
        maxChars: Int = InspectorLimits.MAX_URL_CHARS,
    ): String {
        if (!maskSensitiveParams) return truncate(url, maxChars)

        val questionMark = url.indexOf('?')
        if (questionMark < 0) return truncate(url, maxChars)

        val hash = url.indexOf('#', questionMark)
        val queryEnd = if (hash >= 0) hash else url.length
        val prefix = url.substring(0, questionMark)
        val query = url.substring(questionMark + 1, queryEnd)
        val suffix = if (hash >= 0) url.substring(hash) else ""
        if (query.isEmpty()) return truncate(url, maxChars)

        val maskedQuery = query.split('&').joinToString("&") { part ->
            val equals = part.indexOf('=')
            if (equals <= 0) {
                part
            } else {
                val name = UrlParts.decode(part.substring(0, equals))
                if (isSensitiveParamName(name)) {
                    part.substring(0, equals + 1) + MASK
                } else {
                    part
                }
            }
        }
        return truncate(prefix + "?" + maskedQuery + suffix, maxChars)
    }

    /** `true` when a query parameter's value must be masked. */
    fun isSensitiveParamName(name: String): Boolean {
        val raw = name.lowercase()
        if (raw in SENSITIVE_PARAM_TOKENS) return true
        // Split compound names (access_token, X-Amz-Signature, api-key …) into word tokens so that
        // "inside" does not match "sid" while "session_id" does match "session".
        val tokens = raw.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return tokens.any { it in SENSITIVE_PARAM_TOKENS }
    }

    /**
     * Scrubs secrets out of free text (console messages and request-body previews).
     *
     * @return the scrubbed text plus whether anything was replaced, so the UI can show a
     *         "masked by policy" badge instead of silently altering what the page logged.
     */
    fun scrubText(text: String, maxChars: Int = Int.MAX_VALUE): ScrubResult {
        var result = truncate(text, maxChars)
        var didMask = result.length != text.length

        // 1. Authorization-style schemes: "Bearer eyJ…", "Basic dXNlcjpwYXNz".
        result = AUTH_SCHEME_PATTERN.replace(result) { match ->
            match.groupValues[1] + " " + MASK
        }

        // 2. Credentials embedded in a URL: https://user:password@host/…
        result = URL_CREDENTIALS_PATTERN.replace(result) { match ->
            match.groupValues[1] + MASK + "@"
        }

        // 3. key=value / "key": "value" pairs whose key name looks sensitive.
        result = KEY_VALUE_PATTERN.replace(result) { match ->
            val prefix = match.groupValues[1]
            if (match.value.endsWith(MASK)) match.value else prefix + "=" + MASK
        }

        // 4. Bare JWTs (three base64url segments starting with the standard "eyJ" JSON header).
        result = JWT_PATTERN.replace(result) { _ -> MASK }

        // 5. Very long opaque runs, almost always session ids / signatures.
        result = LONG_OPAQUE_PATTERN.replace(result) { _ -> MASK }

        if (result != truncate(text, maxChars) || didMask) didMask = true
        return ScrubResult(text = result, masked = didMask)
    }

    /** Truncates without splitting surrogate pairs. */
    fun truncate(value: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (value.length <= maxChars) return value
        var end = maxChars
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) end -= 1
        return value.substring(0, end)
    }

    private val AUTH_SCHEME_PATTERN =
        Regex("""(?i)\b(bearer|basic|digest|negotiate|aws4-hmac-sha256)\s+[A-Za-z0-9._~+/=\-]{6,}""")

    private val URL_CREDENTIALS_PATTERN =
        Regex("""(?i)([a-z][a-z0-9+.\-]*://[^\s:@/]*):[^\s@/]+@""")

    private val KEY_VALUE_PATTERN = Regex(
        """(?i)\b(password|passwd|pwd|passcode|secret|client[_-]?secret|api[_-]?key|apikey|""" +
            """access[_-]?token|refresh[_-]?token|auth[_-]?token|id[_-]?token|session[_-]?id|""" +
            """sessionid|csrf[_-]?token|xsrf[_-]?token|otp|cvv|cvc|pin)["']?\s*[:=]\s*["']?""" +
            """([^\s"',;&}\]]{3,})"""
    )

    private val JWT_PATTERN = Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]*""")

    private val LONG_OPAQUE_PATTERN = Regex("""\b[A-Za-z0-9_\-]{64,}\b""")
}

/** Result of [Redaction.scrubText]. */
data class ScrubResult(val text: String, val masked: Boolean)
