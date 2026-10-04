package com.example.data

import android.content.Context
import com.example.data.model.AutomationAction
import com.example.data.model.AutomationStep
import com.example.data.model.BrowserAutomation
import org.json.JSONArray
import org.json.JSONObject

/** Small local-only store for saved browser automations. */
class BrowserAutomationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun getAll(): List<BrowserAutomation> {
        val json = preferences.getString(AUTOMATIONS_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            val automations = mutableListOf<BrowserAutomation>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val rawSteps = item.optJSONArray("steps") ?: JSONArray()
                val steps = mutableListOf<AutomationStep>()
                for (stepIndex in 0 until rawSteps.length()) {
                    val rawStep = rawSteps.optJSONObject(stepIndex) ?: continue
                    val action = AutomationAction.fromWireValue(rawStep.optString("action")) ?: continue
                    val selector = rawStep.optString("selector").take(MAX_SELECTOR_LENGTH)
                    if (selector.isBlank()) continue
                    steps += AutomationStep(
                        action = action,
                        selector = selector,
                        value = rawStep.optString("value").take(MAX_VALUE_LENGTH),
                        delayBeforeMs = rawStep.optLong("delayBeforeMs", DEFAULT_STEP_DELAY_MS)
                            .coerceIn(0L, MAX_STEP_DELAY_MS)
                    )
                }
                val startUrl = item.optString("startUrl")
                val name = item.optString("name")
                if (startUrl.startsWith("http://") || startUrl.startsWith("https://")) {
                    automations += BrowserAutomation(
                        id = item.optString("id").ifBlank { "automation-$index" },
                        name = name.ifBlank { "Automation ${index + 1}" },
                        startUrl = startUrl,
                        steps = steps,
                        createdAt = item.optLong("createdAt", 0L)
                    )
                }
            }
            automations
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(automation: BrowserAutomation) {
        val automations = getAll().filterNot { it.id == automation.id } + automation
        preferences.edit().putString(AUTOMATIONS_KEY, encode(automations).toString()).apply()
    }

    @Synchronized
    fun delete(id: String) {
        val remaining = getAll().filterNot { it.id == id }
        preferences.edit().putString(AUTOMATIONS_KEY, encode(remaining).toString()).apply()
    }

    private fun encode(automations: List<BrowserAutomation>): JSONArray = JSONArray().apply {
        automations.forEach { automation ->
            put(
                JSONObject().apply {
                    put("id", automation.id)
                    put("name", automation.name)
                    put("startUrl", automation.startUrl)
                    put("createdAt", automation.createdAt)
                    put("steps", JSONArray().apply {
                        automation.steps.forEach { step ->
                            put(
                                JSONObject().apply {
                                    put("action", step.action.wireValue)
                                    put("selector", step.selector)
                                    put("value", step.value)
                                    put("delayBeforeMs", step.delayBeforeMs)
                                }
                            )
                        }
                    })
                }
            )
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "browser_automations"
        private const val AUTOMATIONS_KEY = "items"
        private const val MAX_SELECTOR_LENGTH = 1_000
        private const val MAX_VALUE_LENGTH = 2_000
        private const val DEFAULT_STEP_DELAY_MS = 350L
        private const val MAX_STEP_DELAY_MS = 5_000L
    }
}
