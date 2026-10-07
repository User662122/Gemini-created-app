package com.example.devtools

import android.os.Looper

/**
 * Thread helpers for the WebView integration.
 *
 * Background information this code relies on (Android WebView documentation, "Android WebView
 * threading"): `WebViewClient.shouldInterceptRequest` is *documented* to be called on a thread other
 * than the UI thread, so heavy work in that callback does not block rendering — but the platform
 * decides, and on some WebView versions and schemes the callback can arrive on the UI thread. Apps
 * cannot force a particular thread through public APIs, so instead of pretending otherwise, this
 * object only answers the question "where am I?" and the inspector is written so that either answer
 * is cheap:
 *
 *  * request/response handling is O(headers) with no I/O and no allocation-heavy work;
 *  * anything that can be expensive (cookie reads through the browser process, ring-buffer copying,
 *    building display strings) happens off the calling thread or on the throttled publishing tick.
 */
object WebViewThreads {

    /** True when the current callback is running on the UI thread. */
    fun isOnMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** True when this scheme's callbacks should be treated as UI-thread work (asset/file loads). */
    fun isLocalScheme(url: String?): Boolean {
        val scheme = url?.let { UrlParts.scheme(it) } ?: return false
        return scheme == "file" || scheme == "content" || scheme == "data" ||
            scheme == "blob" || scheme == "android_res" || scheme == "android_asset"
    }
}
