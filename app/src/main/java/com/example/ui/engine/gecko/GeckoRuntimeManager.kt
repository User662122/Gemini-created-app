package com.example.ui.engine.gecko

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    /**
     * The `nativeApp` identifier for the bridge extension's *background script* channel (network
     * capture, cookie store). Kept separate from [BRIDGE_NATIVE_APP] — the per-tab content-script
     * ports — so background messages can never be delivered to a session's delegate or vice versa.
     */
    const val BRIDGE_NET_NATIVE_APP = "browserbridge-net"

    /**
     * The version in `assets/browserbridge/manifest.json`. `ensureBuiltIn` reinstalls a built-in
     * extension only when the version differs, so this is what makes an upgraded APK actually pick
     * up a changed extension — and it is checked again below, because "the profile already has an
     * older copy" is exactly how an upgrade silently changes nothing.
     */
    const val BRIDGE_EXTENSION_VERSION = "1.1"

    private const val BRIDGE_EXTENSION_ASSETS = "resource://android/assets/browserbridge/"

    private const val TAG = "GeckoCapture"

    @Volatile
    private var runtime: GeckoRuntime? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Guards the one forced reinstall of a stale extension copy (see [ensureBridgeExtension]). */
    @Volatile
    private var forcedReinstallAttempted = false

    /**
     * The delegate that receives messages from the extension's background script (see
     * [GeckoNetworkCapture]). Registered on the [WebExtension] itself, which is a process-wide,
     * UI-thread call; replacing it replaces the previous one.
     */
    @Volatile
    private var extensionMessageDelegate: WebExtension.MessageDelegate? = null

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
     * reinstalls when the version differs, and the extension outlives the process.
     *
     * The installed copy's version is checked against [BRIDGE_EXTENSION_VERSION] anyway: an
     * in-place APK upgrade keeps the profile, and if `ensureBuiltIn` ever reports the old copy
     * instead of updating it, the extension's new abilities (the background script's capture)
     * would silently not exist while the old content script keeps working — which looks exactly
     * like "nothing changed". A mismatch forces one uninstall + reinstall.
     */
    @MainThread
    fun ensureBridgeExtension(context: Context) {
        if (_bridgeExtension.value != null) return
        val geckoRuntime = get(context)
        geckoRuntime.webExtensionController
            .ensureBuiltIn(BRIDGE_EXTENSION_ASSETS, BRIDGE_EXTENSION_ID)
            .accept(
                { extension ->
                    if (extension == null) {
                        _bridgeError.value = "the bridge extension did not load"
                        return@accept
                    }
                    val installedVersion = extension.metaData?.version
                    if (installedVersion != null &&
                        installedVersion != BRIDGE_EXTENSION_VERSION &&
                        !forcedReinstallAttempted
                    ) {
                        // The profile still has an older copy and ensureBuiltIn kept it. Force the
                        // update once; the next call installs the bundled version.
                        forcedReinstallAttempted = true
                        _bridgeError.value = null
                        Log.w(
                            TAG,
                            "bridge extension is version $installedVersion but the APK bundles " +
                                "$BRIDGE_EXTENSION_VERSION; forcing a reinstall"
                        )
                        geckoRuntime.webExtensionController.uninstall(extension).accept(
                            { ensureBridgeExtension(context) },
                            { error ->
                                _bridgeError.value =
                                    "reinstalling the bridge extension failed: ${error?.message}"
                            }
                        )
                        return@accept
                    }
                    _bridgeExtension.value = extension
                    registerExtensionMessageDelegate(extension)
                    Log.i(TAG, "bridge extension installed (version ${installedVersion ?: "?"})")
                },
                { error -> _bridgeError.value = error?.message ?: "the bridge extension did not load" }
            )
    }

    /**
     * Sets the delegate that receives messages from the extension's background script (network
     * capture and the cookie store). Called before or after the extension finishes installing;
     * either way the delegate ends up registered exactly once.
     */
    fun setExtensionMessageDelegate(delegate: WebExtension.MessageDelegate?) {
        extensionMessageDelegate = delegate
        _bridgeExtension.value?.let { registerExtensionMessageDelegate(it) }
    }

    private fun registerExtensionMessageDelegate(extension: WebExtension) {
        val delegate = extensionMessageDelegate ?: return
        // WebExtension.setMessageDelegate is @UiThread, and the install callback may arrive on a
        // Gecko handler thread, so the registration is posted to the main thread.
        mainHandler.post {
            runCatching {
                extension.setMessageDelegate(delegate, BRIDGE_NET_NATIVE_APP)
                Log.i(TAG, "background-message delegate registered for $BRIDGE_NET_NATIVE_APP")
            }.onFailure { error ->
                GeckoCaptureDiagnostics.recordError("setMessageDelegate failed: ${error.message}")
                Log.e(TAG, "registering the background-message delegate failed", error)
            }
        }
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
