package com.example.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redaction rules are the security boundary of this feature, so they get the bluntest tests
 * possible: real-looking secrets go in, and the assertions fail loudly if any of them survives.
 */
class RedactionTest {

    @Test
    fun `authorization header is masked and its raw value is dropped in safe mode`() {
        val field = Redaction.headerField(
            name = "Authorization",
            value = "Bearer eyJhbGciOiJIUzI1NiJ9.abcdefghij.zzzzzzzzzz",
            captureRawValues = false,
        )

        assertTrue(field.isRedacted)
        assertFalse(field.display.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertNull("raw value must not be stored when raw capture is off", field.raw)
        assertFalse(field.canReveal)
    }

    @Test
    fun `authorization header keeps a revealable raw value when raw capture is on`() {
        val field = Redaction.headerField(
            name = "X-Company-Auth-Token",
            value = "s3cr3t-value",
            captureRawValues = true,
        )

        assertTrue(field.isRedacted)
        assertEquals("s3cr3t-value", field.raw)
        assertTrue(field.canReveal)
    }

    @Test
    fun `ordinary headers are stored unmasked`() {
        val field = Redaction.headerField("Content-Type", "application/json", captureRawValues = false)

        assertEquals(RedactionKind.NONE, field.redaction)
        assertEquals("application/json", field.display)
    }

    @Test
    fun `sensitive query parameters are masked but the rest of the URL stays readable`() {
        val masked = Redaction.displayUrl(
            url = "https://api.example.com/v1/items?page=2&access_token=abcd1234&q=shoes",
            maskSensitiveParams = true,
        )

        assertTrue(masked.contains("page=2"))
        assertTrue(masked.contains("q=shoes"))
        assertFalse(masked.contains("abcd1234"))
        assertTrue(masked.contains("access_token=${Redaction.MASK}"))
        assertTrue(masked.startsWith("https://api.example.com/v1/items?"))
    }

    @Test
    fun `raw mode leaves the URL untouched`() {
        val url = "https://api.example.com/v1/items?access_token=abcd1234"
        assertEquals(url, Redaction.displayUrl(url, maskSensitiveParams = false))
    }

    @Test
    fun `compound parameter names are tokenised so inside does not match sid`() {
        assertFalse(Redaction.isSensitiveParamName("inside"))
        assertFalse(Redaction.isSensitiveParamName("residue"))
        assertTrue(Redaction.isSensitiveParamName("session_id"))
        assertTrue(Redaction.isSensitiveParamName("X-Amz-Signature"))
        assertTrue(Redaction.isSensitiveParamName("api_key"))
    }

    @Test
    fun `scrubbing removes bearer tokens, key value pairs and JWTs from console text`() {
        val text = "auth=Bearer abc1234567890 password=hunter2 token=eyJhbGciOiJIUzI1NiJ9.aaaaaaaa.bbbbbbbb"

        val result = Redaction.scrubText(text)

        assertTrue(result.masked)
        assertFalse(result.text.contains("abc1234567890"))
        assertFalse(result.text.contains("hunter2"))
        assertFalse(result.text.contains("eyJhbGciOiJIUzI1NiJ9.aaaaaaaa.bbbbbbbb"))
    }

    @Test
    fun `scrubbing removes credentials embedded in a URL`() {
        val result = Redaction.scrubText("failed to load https://alice:sup3rs3cret@internal.example.com/x")

        assertFalse(result.text.contains("sup3rs3cret"))
        assertTrue(result.text.contains("alice"))
        assertTrue(result.text.contains("internal.example.com"))
    }

    @Test
    fun `scrubbing leaves ordinary prose alone`() {
        val result = Redaction.scrubText("Loaded 12 items in 340 ms from the catalog endpoint")

        assertFalse(result.masked)
        assertEquals("Loaded 12 items in 340 ms from the catalog endpoint", result.text)
    }

    @Test
    fun `cookie values are summarised, never stored`() {
        val masked = Redaction.maskCookieValue("session=abcdef123456")

        assertFalse(masked.contains("abcdef123456"))
        assertTrue(masked.contains("chars"))
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        val emoji = "a\uD83D\uDE00b"

        assertEquals("a", Redaction.truncate(emoji, 1))
        assertEquals(emoji, Redaction.truncate(emoji, 4))
    }
}
