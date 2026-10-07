package com.example.devtools

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide holder for the one [InspectorRuntime] instance.
 *
 * Production builds never install anything: the field keeps its [NullInspectorRuntime] value, so the
 * inspector cannot be reached, enabled or configured. The debug `Application` subclass in
 * `app/src/debug/` is the only code that installs the live runtime, and that subclass is not compiled
 * into release builds at all.
 */
object DevToolsRuntimeProvider {

    @Volatile
    private var runtime: InspectorRuntime = NullInspectorRuntime

    /** Used by the debug `Application` subclass. Ignored when the inspector is not allowed. */
    fun install(context: Context, scope: CoroutineScope): InspectorRuntime {
        val created = if (DevToolsGate.isInspectorAvailable(context)) {
            InspectorController.create(context, scope)
        } else {
            NullInspectorRuntime
        }
        runtime = created
        return created
    }

    /** The runtime for this process; [NullInspectorRuntime] in every production build. */
    fun get(): InspectorRuntime = runtime

    /** Creates the scope the inspector's ticker runs on (main thread, cancelled with the process). */
    fun createScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
