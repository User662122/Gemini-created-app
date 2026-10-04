package com.example.data.model

import java.util.UUID

/** A single browser interaction captured by the automation recorder. */
data class AutomationStep(
    val action: AutomationAction,
    val selector: String,
    val value: String = "",
    val delayBeforeMs: Long = 350L
)

enum class AutomationAction(val wireValue: String) {
    CLICK("click"),
    INPUT("input");

    companion object {
        fun fromWireValue(value: String): AutomationAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class BrowserAutomation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startUrl: String,
    val steps: List<AutomationStep>,
    val createdAt: Long = System.currentTimeMillis()
)

/** A one-shot request to run an automation in the currently visible WebView. */
data class AutomationPlayback(
    val runId: Long,
    val tabId: String,
    val automation: BrowserAutomation
)
