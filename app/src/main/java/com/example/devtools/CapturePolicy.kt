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
    /**
     * **Full capture**: store and show every value exactly as observed — no header masking, no
     * query-parameter masking, no text scrubbing, real cookie values.
     *
     * This is the "developer DevTools" behaviour and it is the default for debug builds, because a
     * network inspector that hides the very values you opened it to read is not much of an inspector.
     *
     * What it does *not* change: the switch itself, and everything it makes visible, only exists in
     * debug builds (see [DevToolsGate]). Release builds never construct a policy with this or any
     * other switch enabled, and nothing capture-related is reachable there.
     */
    val revealSensitiveValues: Boolean,
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
            revealSensitiveValues = false,
            captureRequestBodies = false,
            captureResponseBodies = false,
            captureConsole = false,
            captureConsoleVerbose = false,
        )
    }
}
