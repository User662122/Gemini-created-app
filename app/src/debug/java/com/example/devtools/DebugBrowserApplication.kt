package com.example.devtools

import android.app.Application

/**
 * Debug-only `Application` subclass. This class exists **only** in the debug source set
 * (`app/src/debug/`), is referenced only by `app/src/debug/AndroidManifest.xml`, and is therefore
 * absent from every release build.
 *
 * That is the mechanism behind requirement "the inspector must exist only in developer/debug mode":
 * production code asks [DevToolsRuntimeProvider] for a runtime, and without this class the provider
 * always answers [NullInspectorRuntime]. There is no setting, intent extra or reflection-based path
 * that can turn capture on in a release build.
 */
class DebugBrowserApplication : Application() {

    private val inspectorScope by lazy { DevToolsRuntimeProvider.createScope() }

    override fun onCreate() {
        super.onCreate()
        val runtime = DevToolsRuntimeProvider.install(this, inspectorScope)
        if (runtime === NullInspectorRuntime) {
            // Either NETWORK_INSPECTOR_ENABLED is false for this build type, or the package is not
            // debuggable. Nothing is captured, and the UI simply has no entry point.
            android.util.Log.i(
                TAG,
                "Network Inspector is compiled out of this build: " + DevToolsGate.unavailableReason(),
            )
        }
    }

    private companion object {
        const val TAG = "NetworkInspector"
    }
}
