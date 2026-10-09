package com.example.ui

import java.net.URLEncoder

/** Converts address-bar input into a web URL or a simple web search. */
internal object BrowserAddress {

    private const val SEARCH_URL = "https://www.google.com/search?q="

    fun resolve(input: String): String? {
        val value = input.trim()
        if (value.isEmpty()) return null

        val lowerCase = value.lowercase()
        if (
            lowerCase.startsWith("http://") ||
            lowerCase.startsWith("https://") ||
            lowerCase.startsWith("about:") ||
            lowerCase.startsWith("file://") ||
            lowerCase.startsWith("data:")
        ) {
            return value
        }

        val authority = value.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringBefore(':')
        val looksLikeHost =
            host.equals("localhost", ignoreCase = true) ||
                host.contains('.') ||
                (authority.startsWith("[") && authority.contains(']'))

        if (!value.any(Char::isWhitespace) && looksLikeHost) {
            return "https://$value"
        }

        return SEARCH_URL + URLEncoder.encode(value, "UTF-8")
    }
}
