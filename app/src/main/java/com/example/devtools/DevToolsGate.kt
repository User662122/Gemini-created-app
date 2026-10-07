package com.example.devtools

import android.content.Context
import android.content.pm.ApplicationInfo
import com.example.BuildConfig

/**
 * The hard gate that keeps the Network Inspector out of production builds.
 *
 * Four independent conditions must all be true for the inspector to exist at runtime:
 *
 *  1. `BuildConfig.DEBUG` — false in every release build, and folded away by R8, so the capture code
 *     becomes unreachable and is stripped from the release APK.
 *  2. `BuildConfig.NETWORK_INSPECTOR_ENABLED` — a per-build-type flag declared in
 *     `app/build.gradle.kts` (`true` only for the debug build type). Setting it to `false` disables
 *     the inspector even for debug builds.
 *  3. `ApplicationInfo.FLAG_DEBUGGABLE` — false for a release certificate, so a debug-flagged build
 *     that somehow reached a device still refuses to capture when it is not debuggable.
 *  4. The debug `Application` subclass in `app/src/debug/`, which is the only code that installs the
 *     live inspector factory. Production code paths always resolve to [NullInspectorRuntime].
 *
 * Consequently: in a release build nothing is captured, no bridge is exposed to pages, no UI entry
 * point appears, and there is no setting that could turn any of it on.
 */
object DevToolsGate {

    /** True when the inspector may run in this process. */
    fun isInspectorAvailable(context: Context?): Boolean {
        if (!BuildConfig.DEBUG) return false
        if (!BuildConfig.NETWORK_INSPECTOR_ENABLED) return false
        if (context == null) return true
        return isDebuggable(context)
    }

    /** True when the installed package is debuggable (release-signed builds are not). */
    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Short explanation of why the inspector is unavailable, shown in developer builds only. */
    fun unavailableReason(): String = when {
        !BuildConfig.NETWORK_INSPECTOR_ENABLED ->
            "This build was compiled with NETWORK_INSPECTOR_ENABLED=false."
        !BuildConfig.DEBUG ->
            "Network Inspector is only compiled into debug builds."
        else ->
            "The installed package is not debuggable (FLAG_DEBUGGABLE is not set)."
    }
}
