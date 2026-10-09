package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserAddressTest {

    @Test
    fun keepsExplicitWebUrls() {
        assertEquals("https://example.com/path", BrowserAddress.resolve("https://example.com/path"))
        assertEquals("http://example.com", BrowserAddress.resolve("http://example.com"))
    }

    @Test
    fun addsHttpsToHostNames() {
        assertEquals("https://example.com", BrowserAddress.resolve("example.com"))
        assertEquals("https://localhost:8080", BrowserAddress.resolve("localhost:8080"))
    }

    @Test
    fun searchesTermsAndTrimsWhitespace() {
        assertEquals(
            "https://www.google.com/search?q=gecko+browser",
            BrowserAddress.resolve("  gecko browser  "),
        )
    }

    @Test
    fun emptyAddressIsIgnored() {
        assertNull(BrowserAddress.resolve("   "))
    }
}
