package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.BrowserAutomationStore
import com.example.data.model.AutomationAction
import com.example.data.model.AutomationStep
import com.example.data.model.BrowserAutomation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BrowserAutomationStoreTest {

    @Test
    fun `saved automation can be loaded and deleted`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("browser_automations", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val store = BrowserAutomationStore(context)
        val automation = BrowserAutomation(
            id = "test-automation",
            name = "Sign in",
            startUrl = "https://example.com/login",
            steps = listOf(
                AutomationStep(
                    action = AutomationAction.INPUT,
                    selector = "#username",
                    value = "demo",
                    delayBeforeMs = 500L
                ),
                AutomationStep(
                    action = AutomationAction.CLICK,
                    selector = "button[type=submit]",
                    delayBeforeMs = 250L
                )
            ),
            createdAt = 123L
        )

        store.save(automation)

        assertEquals(listOf(automation), store.getAll())
        store.delete(automation.id)
        assertTrue(store.getAll().isEmpty())
    }
}
