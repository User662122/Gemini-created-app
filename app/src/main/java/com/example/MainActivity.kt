package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.remote.RemoteControl
import com.example.ui.BrowserScreen
import com.example.ui.BrowserViewModel
import com.example.ui.theme.BrowserTheme

class MainActivity : ComponentActivity() {

    private val viewModel: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Touch the ViewModel so the browser exists before the control server receives requests.
        viewModel.tabs
        RemoteControl.restoreIfEnabled(this)
        setContent {
            BrowserTheme {
                BrowserScreen(viewModel = viewModel)
            }
        }
    }
}
