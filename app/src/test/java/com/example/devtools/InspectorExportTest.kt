package com.example.devtools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The export is what leaves the app, so it is tested as data: the JSON is parsed back and checked
 * field by field, and both formats are checked for the promise that matters — a value that was masked
 * at capture time can never reappear in a file.
 *
 * Robolectric because the JSON side uses `org.json`, which the plain JVM runtime stubs out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InspectorExportTest {

    private fun observer(reveal: Boolean) = LiveNetworkObserver {
        CapturePolicy(
            enabled = true,
            revealSensitiveValues = reveal,
            captureRequestBodies = true,
            captureResponseBodies = true,
            captureConsole = true,
            captureConsoleVerbose = true,
        )
    }

    private fun capture(observer: LiveNetworkObserver) {
        observer.onRequestStarted(
            RequestObservation(
                tabId = "tab",
                url = "https://example.com/api/items?access_token=supersecret&page=2",
                method = "POST",
                observedAtMillis = 1_756_000_000_000L,
                timeSource = EvidenceSource.APP_CLOCK,
                isForMainFrame = false,
                isRedirect = false,
                hasGesture = false,
                headers = mapOf("Authorization" to "Bearer abcdefghijklmnop"),
                headersReport = HeadersReport.WEBVIEW_SUBSET,
                initiator = Initiator.FETCH,
                observedVia = EvidenceSource.WEBVIEW_CALLBACK,
            )
        )
        observer.onResponseReceived(
            ResponseObservation(
                tabId = "tab",
                // Same URL and method as the request above: this is what the correlation keys match on.
                url = "https://example.com/api/items?access_token=supersecret&page=2",
                method = "POST",
                statusCode = 201,
                reasonPhrase = "Created",
                statusSource = EvidenceSource.WEBVIEW_CALLBACK,
                headers = mapOf("Content-Type" to "application/json"),
                headersReport = HeadersReport.WEBVIEW_SUBSET,
                contentType = "application/json",
                observedAtMillis = 1_756_000_000_100L,
                timeSource = EvidenceSource.APP_CLOCK,
                durationMillis = 100L,
                isForMainFrame = false,
                observedVia = EvidenceSource.WEBVIEW_CALLBACK,
            )
        )
        observer.onConsoleMessage("tab", ConsoleLevel.LOG, "token=abcdef123456", "app.js", 12, null)
    }

    private fun settings(reveal: Boolean): Map<InspectorSetting, Boolean> =
        InspectorSetting.values().associateWith { setting ->
            if (setting == InspectorSetting.FULL_CAPTURE) reveal else setting.defaultValue
        }

    private fun render(
        observer: LiveNetworkObserver,
        format: ExportFormat,
        reveal: Boolean,
    ): String = InspectorExport.render(
        format = format,
        exportedAtMillis = 1_756_000_000_000L,
        settings = settings(reveal),
        snapshot = observer.store.snapshot(1_756_000_000_000L),
        droppedConsoleRows = 0,
        cookies = observer.store.cookieObservations(),
        droppedByRateLimit = 0L,
        droppedByPageScript = 0L,
    )

    @Test
    fun `the text export contains the whole session`() {
        val observer = observer(reveal = true)
        capture(observer)

        val text = render(observer, ExportFormat.TEXT, reveal = true)

        assertTrue(text.contains("Network Inspector export"))
        assertTrue(text.contains("Capture mode:   FULL CAPTURE"))
        assertTrue(text.contains("https://example.com/api/items"))
        assertTrue(text.contains("Status:        201"))
        assertTrue(text.contains("CONSOLE"))
        assertTrue(text.contains("token=abcdef123456"))
        assertTrue(text.contains("COOKIES"))
        assertTrue(text.contains("WHAT WEBVIEW LETS THIS APP OBSERVE"))
        // Every setting is listed, so a reader knows what the file does and does not contain.
        InspectorSetting.values().forEach { setting -> assertTrue(text.contains(setting.title)) }
    }

    @Test
    fun `a full-capture text export carries the warning and the real values`() {
        val observer = observer(reveal = true)
        capture(observer)

        val text = render(observer, ExportFormat.TEXT, reveal = true)

        assertTrue(text.contains(InspectorExplanations.FULL_CAPTURE_WARNING))
        assertTrue(text.contains("Bearer abcdefghijklmnop"))
        assertTrue(text.contains("access_token=supersecret"))
    }

    @Test
    fun `a masked export cannot leak what was never stored`() {
        val observer = observer(reveal = false)
        capture(observer)

        val text = render(observer, ExportFormat.TEXT, reveal = false)
        val json = render(observer, ExportFormat.JSON, reveal = false)

        listOf(text, json).forEach { body ->
            assertFalse(body.contains("Bearer abcdefghijklmnop"))
            assertFalse(body.contains("supersecret"))
            assertFalse(body.contains("abcdef123456"))
        }
        assertTrue(text.contains("masked (full capture was off)"))
    }

    @Test
    fun `the json export round-trips into a structured document`() {
        val observer = observer(reveal = true)
        capture(observer)

        val root = JSONObject(render(observer, ExportFormat.JSON, reveal = true))

        assertEquals("full", root.getString("captureMode"))
        assertTrue(root.getBoolean("unmaskedValuesIncluded"))
        assertTrue("the buffer revision is part of the document", root.has("revision"))
        assertEquals(0, root.getJSONObject("dropped").getInt("entries"))
        assertEquals(1, root.getJSONArray("entries").length())
        assertEquals(1, root.getJSONArray("console").length())
        assertTrue(root.getJSONArray("capabilities").length() > 5)
        assertTrue(root.getJSONObject("settings").getBoolean(InspectorSetting.FULL_CAPTURE.key))

        val entry = root.getJSONArray("entries").getJSONObject(0)
        assertEquals("RECEIVED", entry.getString("state"))
        assertEquals("POST", entry.getJSONObject("request").getString("method"))
        assertEquals("201", entry.getJSONObject("response").getJSONObject("statusCode").get("value").toString())
        assertEquals(
            "WEBVIEW_CALLBACK",
            entry.getJSONObject("response").getJSONObject("statusCode").getString("source"),
        )

        val headers = entry.getJSONObject("request").getJSONArray("headers")
        val auth = (0 until headers.length())
            .map { headers.getJSONObject(it) }
            .single { it.getString("name") == "Authorization" }
        assertEquals("REVEALED", auth.getString("redaction"))
    }

    @Test
    fun `an empty buffer still produces a valid file`() {
        val observer = observer(reveal = true)

        val text = render(observer, ExportFormat.TEXT, reveal = true)
        val json = JSONObject(render(observer, ExportFormat.JSON, reveal = true))

        assertTrue(text.contains("(nothing captured)"))
        assertEquals(0, json.getJSONArray("entries").length())
    }

    @Test
    fun `file names are unique per second and carry the right extension`() {
        val text = InspectorExport.fileName(ExportFormat.TEXT, 1_756_000_000_000L)
        val json = InspectorExport.fileName(ExportFormat.JSON, 1_756_000_000_000L)
        val later = InspectorExport.fileName(ExportFormat.TEXT, 1_756_000_001_000L)

        assertTrue(text.startsWith("network-inspector-"))
        assertTrue(text.endsWith(".txt"))
        assertTrue(json.endsWith(".json"))
        assertFalse(text == later)
        assertEquals(ExportFormat.TEXT.mimeType, "text/plain")
        assertEquals(ExportFormat.JSON.mimeType, "application/json")
    }

    @Test
    fun `containsUnmaskedValues tracks the capture switch`() {
        assertTrue(InspectorExport.containsUnmaskedValues(settings(reveal = true)))
        assertFalse(InspectorExport.containsUnmaskedValues(settings(reveal = false)))
    }
}
