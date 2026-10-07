package com.example.devtools

/**
 * Snapshot of the inspector's capture switches, read **once per callback** by the observer.
 *
 * Being a value type matters for speed: the WebView callbacks never touch SharedPreferences, they
 * read this immutable record (published under `@Volatile`), so an incoming request costs one
 * reference read rather than an IPC-backed preference lookup.
 */
data class CapturePolicy(
    val enabled: Boolean,
    /** Keep original values so individual masked fields can be revealed in the UI. */
    val rawCapture: Boolean,
    /** Capture request bodies reported by the injected `fetch`/`XMLHttpRequest` hooks. */
    val captureRequestBodies: Boolean,
    /** Capture response-body previews reported by the injected hooks. */
    val captureResponseBodies: Boolean,
    /** Capture `console.*` messages and JavaScript errors. */
    val captureConsole: Boolean,
    /** Also capture `console.debug`/`console.info` ("verbose"). */
    val captureConsoleVerbose: Boolean,
) {
    companion object {
        /** Nothing is captured: used by release builds and whenever the inspector is switched off. */
        val DISABLED = CapturePolicy(
            enabled = false,
            rawCapture = false,
            captureRequestBodies = false,
            captureResponseBodies = false,
            captureConsole = false,
            captureConsoleVerbose = false,
        )
    }
}
