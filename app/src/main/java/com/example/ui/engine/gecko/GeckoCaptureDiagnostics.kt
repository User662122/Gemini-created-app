package com.example.ui.engine.gecko

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide counters for the Network Inspector's Gecko capture pipeline, so that a failure can
 * be located from an export instead of from a guess.
 *
 * The export prints these under **CAPTURE SOURCE**: each stage of the pipeline (extension
 * installed → delegate registered → settings pulled → listeners attached → records received →
 * cookies received) either has a count or a timestamp, so the first missing one is the broken link.
 * The extension reports its own half (which APIs and permissions it actually has, which listeners
 * it attached, the manifest version actually running) as `capture-status` messages; an outdated
 * installed copy of the extension — the classic "nothing changed after an upgrade" — shows up
 * there as a version mismatch.
 *
 * Everything here is process-wide and lock-free: the counters survive an engine recreation, and a
 * message arriving on a Gecko thread never blocks on anything.
 */
object GeckoCaptureDiagnostics {

    private val settingsPulls = AtomicLong(0)
    private val recordsReceived = AtomicLong(0)
    private val cookieDumps = AtomicLong(0)
    private val cookieChanges = AtomicLong(0)
    private val statusReports = AtomicLong(0)

    @Volatile
    var delegateRegistered: Boolean = false

    @Volatile
    var lastSettingsReplyEnabled: Boolean? = null

    @Volatile
    var lastRecordUrl: String? = null

    @Volatile
    var lastRecordAtMillis: Long = 0L

    /** The newest `capture-status` map from the extension, as "key=value" pairs. */
    @Volatile
    var extensionStatus: List<Pair<String, String>> = emptyList()

    @Volatile
    var lastError: String? = null

    /** `extensions.webextensions.remote` read back from Gecko (value/default/user). */
    @Volatile
    var extensionProcessPref: String? = null

    fun recordSettingsPull(enabled: Boolean?) {
        settingsPulls.incrementAndGet()
        lastSettingsReplyEnabled = enabled
    }

    fun recordNetworkRecord(url: String) {
        recordsReceived.incrementAndGet()
        lastRecordUrl = url.take(200)
        lastRecordAtMillis = System.currentTimeMillis()
    }

    fun recordCookieDump(count: Int) {
        cookieDumps.incrementAndGet()
    }

    fun recordCookieChange(removed: Boolean) {
        cookieChanges.incrementAndGet()
    }

    fun recordStatus(status: List<Pair<String, String>>) {
        statusReports.incrementAndGet()
        extensionStatus = status
    }

    fun recordError(error: String) {
        lastError = error.take(300)
    }

    /** The export's CAPTURE SOURCE section, as ordered label/value pairs. */
    fun snapshot(): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>(16)
        rows += "Bridge extension installed" to (if (GeckoRuntimeManager.bridgeExtension.value != null) "yes" else "no")
        rows += "Installed extension version" to (
            GeckoRuntimeManager.bridgeExtension.value?.metaData?.version
                ?: GeckoRuntimeManager.bridgeError.value
                ?: "unknown"
            )
        rows += "Bundled extension version" to GeckoRuntimeManager.BRIDGE_EXTENSION_VERSION
        rows += "Background-message delegate registered" to (if (delegateRegistered) "yes" else "no")
        extensionProcessPref?.let {
            rows += "extensions.webextensions.remote (read back from Gecko)" to it
        }
        rows += "Capture settings pulls answered" to settingsPulls.get().toString()
        rows += "Last settings reply (capture enabled)" to (lastSettingsReplyEnabled?.toString() ?: "—")
        rows += "Network records received" to recordsReceived.get().toString()
        rows += "Last record" to (
            lastRecordUrl?.let { "$it (${(System.currentTimeMillis() - lastRecordAtMillis) / 1000} s ago)" }
                ?: "—"
        )
        rows += "Cookie dumps received" to cookieDumps.get().toString()
        rows += "Cookie changes received" to cookieChanges.get().toString()
        rows += "Extension status reports" to statusReports.get().toString()
        if (extensionStatus.isNotEmpty()) {
            rows += "— extension's own report —" to ""
            rows += extensionStatus
        }
        lastError?.let { rows += "Last capture error" to it }
        return rows
    }
}
