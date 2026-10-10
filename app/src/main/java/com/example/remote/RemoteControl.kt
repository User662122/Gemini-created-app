package com.example.remote

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ui.BrowserViewModel
import java.security.SecureRandom

/** Status of the localhost control server, observed by the UI. */
data class RemoteServerStatus(
    val running: Boolean = false,
    val port: Int? = null,
    val error: String? = null,
)

/** Settings, status and the browser instance that the localhost control server drives. */
object RemoteControl {

    const val DEFAULT_PORT = 9876
    private const val PREFS = "remote_control"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PORT = "port"
    private const val KEY_TOKEN = "token"

    /** Written on the main thread only. */
    var status by mutableStateOf(RemoteServerStatus())
        internal set

    /** The browser currently on screen; read and written on the main thread. */
    internal var browser: BrowserViewModel? = null
        private set

    @Volatile private var cachedToken: String? = null

    fun attachBrowser(viewModel: BrowserViewModel) {
        browser = viewModel
    }

    fun detachBrowser(viewModel: BrowserViewModel) {
        if (browser === viewModel) browser = null
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun port(context: Context): Int = prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun token(context: Context): String {
        cachedToken?.let { return it }
        val prefs = prefs(context)
        val token = prefs.getString(KEY_TOKEN, null) ?: newToken().also {
            prefs.edit().putString(KEY_TOKEN, it).apply()
        }
        cachedToken = token
        return token
    }

    fun regenerateToken(context: Context): String {
        val token = newToken()
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
        cachedToken = token
        return token
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) startService(context) else stopService(context)
    }

    /** Returns false if [port] is not a usable unprivileged port. */
    fun setPort(context: Context, port: Int): Boolean {
        if (port !in 1024..65535) return false
        prefs(context).edit().putInt(KEY_PORT, port).apply()
        if (isEnabled(context)) startService(context) // the service restarts on the new port
        return true
    }

    /** Starts the server again after app launch if the user left it switched on. */
    fun restoreIfEnabled(context: Context) {
        if (isEnabled(context)) startService(context)
    }

    private fun startService(context: Context) {
        val intent = Intent(context, RemoteControlService::class.java)
        context.startForegroundService(intent)
    }

    private fun stopService(context: Context) {
        context.stopService(Intent(context, RemoteControlService::class.java))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun newToken(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
