package com.example.ui.engine.gecko

import android.content.Context
import androidx.annotation.MainThread
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension

/**
 * The single [GeckoRuntime] every tab shares.
 *
 * In GeckoView the runtime is the browser process: one instance serves all sessions (tabs), owns the
 * profile directory, the cookie jar, the HTTP cache and the network stack. Creating a second one
 * would mean a second profile, so this is a process-wide singleton, created on the main thread and
 * never destroyed — which is also what `GeckoRuntime.getDefault` is for.
 *
 * Settings here are deliberate and small:
 *
 *  * JavaScript, web fonts and the network/storage stack are on, i.e. the behaviour the app had.
 *  * Debug-only console output and **remote debugging**. The latter is the honest replacement for
 *    the part of the in-app Network Inspector that GeckoView does not expose to embedders: with a
 *    debug build you can `adb forward tcp:6000 localfilesystem:/data/data/<pkg>/firefox-debugger-socket`
 *    and attach desktop Firefox DevTools for the real Network/Console/Storage panels. It is gated on
 *    `BuildConfig.DEBUG` so a release build exposes nothing.
 *  * Login autofill is off: this app has no password manager, so Gecko should not offer to save
 *    credentials it could never fill back in.
 */
object GeckoRuntimeManager {

    /**
     * The bridge extension bundled in `app/src/main/assets/browserbridge/`.
     *
     * GeckoView has no `addJavascriptInterface`: the supported way for a page and the app to talk is
     * a built-in WebExtension using native messaging. See [GeckoBridge] for what travels over it.
     */
    const val BRIDGE_EXTENSION_ID = "browserbridge@example.com"

    /** The `nativeApp` identifier used by `browser.runtime.connectNative` / `sendNativeMessage`. */
    const val BRIDGE_NATIVE_APP = "browserbridge"

    private const val BRIDGE_EXTENSION_ASSETS = "resource://android/assets/browserbridge/"

    @Volatile
    private var runtime: GeckoRuntime? = null

    private val _bridgeExtension = MutableStateFlow<WebExtension?>(null)

    /** The installed bridge extension, or null until [ensureBridgeExtension] has finished. */
    val bridgeExtension: StateFlow<WebExtension?> = _bridgeExtension.asStateFlow()

    private val _bridgeError = MutableStateFlow<String?>(null)

    /** Why the bridge extension could not be installed, if it could not be. Never fatal. */
    val bridgeError: StateFlow<String?> = _bridgeError.asStateFlow()

    @MainThread
    fun get(context: Context): GeckoRuntime {
        runtime?.let { return it }
        return synchronized(this) {
            runtime ?: GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .consoleOutput(BuildConfig.DEBUG)
                    .remoteDebuggingEnabled(BuildConfig.DEBUG)
                    .javaScriptEnabled(true)
                    .webFontsEnabled(true)
                    .loginAutofillEnabled(false)
                    .build()
            ).also { runtime = it }
        }
    }

    /**
     * Installs (or re-uses) the bridge extension. Safe to call repeatedly: `ensureBuiltIn` only
     * installs when the extension is not installed yet, and the extension outlives the process.
     */
    @MainThread
    fun ensureBridgeExtension(context: Context) {
        if (_bridgeExtension.value != null) return
        val geckoRuntime = get(context)
        geckoRuntime.webExtensionController
            .ensureBuiltIn(BRIDGE_EXTENSION_ASSETS, BRIDGE_EXTENSION_ID)
            .accept(
                { extension -> _bridgeExtension.value = extension },
                { error -> _bridgeError.value = error?.message ?: "the bridge extension did not load" }
            )
    }

    /**
     * Clears Gecko's own browsing data: cookies, site storage (localStorage/IndexedDB), the HTTP
     * cache, auth sessions and stored permission decisions. Room's history table is cleared by the
     * ViewModel; this is the half the app previously could not do at all.
     */
    @MainThread
    fun clearBrowsingData(context: Context, onFinished: (Result<Unit>) -> Unit = {}) {
        get(context).storageController
            .clearData(StorageController.ClearFlags.ALL)
            .accept(
                { onFinished(Result.success(Unit)) },
                { error -> onFinished(Result.failure(error ?: IllegalStateException("clearData failed"))) }
            )
    }
}
