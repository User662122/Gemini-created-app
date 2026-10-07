package com.example.devtools

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * One switch in the Network Inspector settings screen.
 *
 * [requiresReinjection] marks the switches whose value the injected page script reads at install time,
 * so changing them re-installs the hooks in every open tab.
 */
enum class InspectorSetting(
    val key: String,
    val title: String,
    val description: String,
    val defaultValue: Boolean,
    /** Session-only switches are never written to disk. */
    val sessionOnly: Boolean = false,
    val requiresReinjection: Boolean = false,
) {
    ENABLED(
        key = "enabled",
        title = "Network Inspector",
        description = "Record network activity, cookies and console output for the pages in this " +
            "browser. Available only in debug builds.",
        defaultValue = true,
    ),
    RAW_CAPTURE(
        key = "raw_capture",
        title = "Raw capture (reveal masked values)",
        description = InspectorExplanations.RAW_CAPTURE_WARNING,
        defaultValue = false,
        sessionOnly = true,
    ),
    REQUEST_BODIES(
        key = "request_bodies",
        title = "Capture request bodies",
        description = "Store previews of the bodies the page passed to fetch(), XMLHttpRequest and " +
            "sendBeacon, after scrubbing obvious secrets. Off by default because POST bodies often " +
            "contain personal data.",
        defaultValue = false,
        requiresReinjection = true,
    ),
    RESPONSE_BODIES(
        key = "response_bodies",
        title = "Capture response body previews",
        description = "Store a size-capped preview of text responses the page read through fetch() or " +
            "XMLHttpRequest. Bodies of resources the app does not fetch itself stay unavailable.",
        defaultValue = false,
        requiresReinjection = true,
    ),
    CONSOLE(
        key = "console",
        title = "JavaScript console",
        description = "Record console.log/warn/error messages and uncaught JavaScript errors.",
        defaultValue = true,
        requiresReinjection = true,
    ),
    CONSOLE_VERBOSE(
        key = "console_verbose",
        title = "Include verbose console output",
        description = "Also record console.debug and console.info, which many libraries emit " +
            "constantly.",
        defaultValue = false,
        requiresReinjection = true,
    ),
}

/**
 * Persists the inspector switches and publishes them as one immutable [CapturePolicy].
 *
 * The WebView side reads `policy()` once per callback, which is an `AtomicReference` read — no
 * SharedPreferences access on the browser threads, ever. Changing a switch publishes a new policy
 * object atomically, so a callback can never observe a half-updated configuration.
 */
class InspectorSettingsStore(
    context: Context,
    private val available: Boolean = DevToolsGate.isInspectorAvailable(context),
) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val currentPolicy = AtomicReference(if (available) readPolicy() else CapturePolicy.DISABLED)
    private val currentVersion = AtomicInteger(0)

    /** Raw capture is deliberately never persisted: it dies with the process. */
    @Volatile
    private var rawCaptureInSession: Boolean = false

    /** The policy the WebView side reads. Cheap enough for a hot path. */
    fun policy(): CapturePolicy = currentPolicy.get()

    /** Increases on every settings change; used as a re-injection key by the WebView containers. */
    fun version(): Int = currentVersion.get()

    fun isAvailable(): Boolean = available

    fun get(setting: InspectorSetting): Boolean {
        if (!available) return false
        if (setting == InspectorSetting.RAW_CAPTURE) return rawCaptureInSession
        return preferences.getBoolean(setting.key, setting.defaultValue)
    }

    fun set(setting: InspectorSetting, value: Boolean) {
        if (!available) return
        when {
            setting.sessionOnly -> rawCaptureInSession = value
            setting == InspectorSetting.ENABLED -> {
                preferences.edit().putBoolean(setting.key, value).apply()
                // Switching the inspector off also drops raw capture, as the warning promises.
                if (!value) rawCaptureInSession = false
            }
            else -> preferences.edit().putBoolean(setting.key, value).apply()
        }
        publish()
    }

    /** Builds the install script that matches the current policy. */
    fun installScript(): String = InspectorScripts.build(
        captureRequestBodies = policy().captureRequestBodies,
        captureResponseBodies = policy().captureResponseBodies,
    )

    /** Forgets raw capture at the end of a session. */
    fun endSession() {
        if (!rawCaptureInSession) return
        rawCaptureInSession = false
        publish()
    }

    /** Restores every switch to its default, including the persisted ones. */
    fun resetToDefaults() {
        if (!available) return
        preferences.edit().clear().apply()
        rawCaptureInSession = false
        publish()
    }

    private fun readPolicy(): CapturePolicy {
        if (!available) return CapturePolicy.DISABLED
        return CapturePolicy(
            enabled = preferences.getBoolean(InspectorSetting.ENABLED.key, InspectorSetting.ENABLED.defaultValue),
            rawCapture = rawCaptureInSession,
            captureRequestBodies = preferences.getBoolean(
                InspectorSetting.REQUEST_BODIES.key,
                InspectorSetting.REQUEST_BODIES.defaultValue,
            ),
            captureResponseBodies = preferences.getBoolean(
                InspectorSetting.RESPONSE_BODIES.key,
                InspectorSetting.RESPONSE_BODIES.defaultValue,
            ),
            captureConsole = preferences.getBoolean(
                InspectorSetting.CONSOLE.key,
                InspectorSetting.CONSOLE.defaultValue,
            ),
            captureConsoleVerbose = preferences.getBoolean(
                InspectorSetting.CONSOLE_VERBOSE.key,
                InspectorSetting.CONSOLE_VERBOSE.defaultValue,
            ),
        )
    }

    private fun publish() {
        currentPolicy.set(readPolicy())
        currentVersion.incrementAndGet()
    }

    companion object {
        /** Separate from the browser's other preferences so clearing one does not clear the other. */
        const val PREFERENCES_NAME = "network_inspector_settings"
    }
}
