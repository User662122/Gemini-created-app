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
    val requiresReinjection: Boolean = false,
) {
    ENABLED(
        key = "enabled",
        title = "Network Inspector",
        description = "Record network activity, cookies and console output for the pages in this " +
            "browser. Available only in debug builds.",
        defaultValue = true,
    ),
    FULL_CAPTURE(
        key = "full_capture",
        title = "Reveal sensitive values",
        description = "Store and show everything exactly as observed: Authorization and Cookie " +
            "headers, tokens in URLs, cookie values, request/response bodies and console text are no " +
            "longer masked or scrubbed. On by default in debug builds, and the reason this tool is " +
            "debug-only. Rows captured while this was off keep the values they were stored with.",
        defaultValue = true,
    ),
    REQUEST_BODIES(
        key = "request_bodies",
        title = "Capture request bodies",
        description = "Store previews of the bodies the page passed to fetch(), XMLHttpRequest and " +
            "sendBeacon. With \"Reveal sensitive values\" on they are stored verbatim, so this is the " +
            "setting to switch off if a body may contain personal data you do not want on screen.",
        defaultValue = true,
        requiresReinjection = true,
    ),
    RESPONSE_BODIES(
        key = "response_bodies",
        title = "Capture response body previews",
        description = "Store a size-capped preview of text responses the page read through fetch() or " +
            "XMLHttpRequest. Bodies of resources the app does not fetch itself stay unavailable.",
        defaultValue = true,
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
        description = "Also record console.debug and console.info. On by default so nothing is " +
            "missing; switch it off when a chatty library fills the buffer.",
        defaultValue = true,
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

    /** The policy the WebView side reads. Cheap enough for a hot path. */
    fun policy(): CapturePolicy = currentPolicy.get()

    /** Increases on every settings change; used as a re-injection key by the WebView containers. */
    fun version(): Int = currentVersion.get()

    fun isAvailable(): Boolean = available

    fun get(setting: InspectorSetting): Boolean =
        available && preferences.getBoolean(setting.key, setting.defaultValue)

    fun set(setting: InspectorSetting, value: Boolean) {
        if (!available) return
        preferences.edit().putBoolean(setting.key, value).apply()
        publish()
    }

    /** Builds the install script that matches the current policy. */
    fun installScript(): String = InspectorScripts.build(
        captureRequestBodies = policy().captureRequestBodies,
        captureResponseBodies = policy().captureResponseBodies,
    )

    /** Restores every switch to its default, including the persisted ones. */
    fun resetToDefaults() {
        if (!available) return
        preferences.edit().clear().apply()
        publish()
    }

    private fun readPolicy(): CapturePolicy {
        if (!available) return CapturePolicy.DISABLED
        return CapturePolicy(
            enabled = preferences.getBoolean(InspectorSetting.ENABLED.key, InspectorSetting.ENABLED.defaultValue),
            revealSensitiveValues = preferences.getBoolean(
                InspectorSetting.FULL_CAPTURE.key,
                InspectorSetting.FULL_CAPTURE.defaultValue,
            ),
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
