package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.devtools.DevToolsRuntimeProvider
import com.example.devtools.InspectorRuntime
import com.example.ui.BrowserScreen
import com.example.ui.BrowserViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: BrowserViewModel by viewModels()

    /**
     * The developer Network Inspector.
     *
     * In a release build this is always [com.example.devtools.NullInspectorRuntime]: nothing is
     * captured, no menu entry appears and no bridge is exposed to pages. Debug builds get the live
     * controller from `DebugBrowserApplication` (see `app/src/debug/`).
     */
    private val inspector: InspectorRuntime get() = DevToolsRuntimeProvider.get()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = inspector
        setContent {
            MyApplicationTheme {
                BrowserScreen(viewModel = viewModel, inspector = runtime)
            }
        }
    }

    override fun onDestroy() {
        // Raw capture is deliberately session-scoped: leaving the browser drops the unmasked values
        // that "raw capture" kept in memory, while the persisted switches stay as the user left them.
        if (isFinishing && !isChangingConfigurations) {
            inspector.endSession()
        }
        super.onDestroy()
    }
}
