package com.example.automation

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ui.BrowserViewModel
import java.io.IOException
import java.security.SecureRandom

/**
 * Owns the localhost automation server: whether it runs, its port, and the access token.
 *
 * The server is off by default. When enabled it listens on 127.0.0.1 only.
 */
class AutomationService internal constructor(
    context: Context,
    viewModel: BrowserViewModel,
    bridge: AutomationBridge,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val api = AutomationApi(viewModel, bridge) { token }
    private var server: AutomationServer? = null

    var isRunning by mutableStateOf(false)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    val isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)

    val port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    /** Sent by clients as the X-Automation-Token header. Created on first use. */
    val token: String
        get() = prefs.getString(KEY_TOKEN, null) ?: newToken().also {
            prefs.edit().putString(KEY_TOKEN, it).apply()
        }

    fun startIfEnabled() {
        if (isEnabled) start()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) start() else stop()
    }

    /** Replaces the token. Existing clients must use the new one. */
    fun regenerateToken() {
        prefs.edit().putString(KEY_TOKEN, newToken()).apply()
    }

    @Synchronized
    fun start() {
        if (server != null) return
        val candidate = AutomationServer(port, api::handle)
        try {
            candidate.start()
            server = candidate
            lastError = null
            isRunning = true
        } catch (error: IOException) {
            lastError = "Could not listen on 127.0.0.1:$port (${error.message ?: "unknown error"})"
            isRunning = false
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        isRunning = false
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    private companion object {
        const val PREFS_NAME = "automation"
        const val KEY_ENABLED = "enabled"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "token"
        const val DEFAULT_PORT = 8765
        const val TOKEN_BYTES = 24
    }
}
