package com.example.devtools

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * A [WebChromeClient] that forwards console output and page titles to the inspector.
 *
 * `onConsoleMessage` returns whatever `super` returned (false), so the messages still reach logcat
 * exactly as they did before the inspector existed. The inspector observes console output; it never
 * hides, filters or rewrites it.
 */
open class NetworkInspectorWebChromeClient(
    private val tabId: String,
    private val inspector: InspectorRuntime,
) : WebChromeClient() {

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        val handled = super.onConsoleMessage(consoleMessage)
        if (consoleMessage == null || !inspector.enabled) return handled
        try {
            inspector.dispatch(
                InspectorMessage.Console(
                    tabId = tabId,
                    level = consoleLevel(consoleMessage.messageLevel()),
                    message = consoleMessage.message() ?: "",
                    source = consoleMessage.sourceId(),
                    lineNumber = consoleMessage.lineNumber(),
                )
            )
        } catch (_: Throwable) {
        }
        return handled
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        if (!inspector.enabled) return
        try {
            // The title is one of the few places a main-frame HTTP error becomes visible to the app
            // when the response was consumed in shouldInterceptRequest ("HTTP ERROR 404").
            inspector.dispatch(InspectorMessage.DocumentFinished(tabId, view?.url, title))
        } catch (_: Throwable) {
        }
    }

    private fun consoleLevel(level: ConsoleMessage.MessageLevel?): ConsoleLevel = when (level) {
        ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.ERROR
        ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.WARN
        ConsoleMessage.MessageLevel.DEBUG -> ConsoleLevel.VERBOSE
        ConsoleMessage.MessageLevel.TIP -> ConsoleLevel.VERBOSE
        else -> ConsoleLevel.LOG
    }
}
