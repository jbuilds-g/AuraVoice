package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.GeminiApiClient
import com.example.data.SecurePreferences
import com.example.service.AuraAccessibilityService
import com.example.service.OverlayService
import com.example.service.OverlayState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches AuraVoice`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AuraVoice", appName)
    }

    @Test
    fun `secure preferences stores and retrieves configuration without hardcoded keys`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val securePreferences = SecurePreferences(context)

        // Initial state is blank
        securePreferences.clearApiKey()
        assertEquals("", securePreferences.getApiKey())
        assertFalse(securePreferences.hasValidApiKey())

        // Save key
        securePreferences.setApiKey("test_ai_studio_api_key_aura")
        assertEquals("test_ai_studio_api_key_aura", securePreferences.getApiKey())
        assertTrue(securePreferences.hasValidApiKey())

        securePreferences.setTranscriptionMode("smart")
        assertEquals("smart", securePreferences.getTranscriptionMode())

        securePreferences.setOverlayActive(true)
        assertEquals(true, securePreferences.isOverlayActive())

        // Clear key
        securePreferences.clearApiKey()
        assertEquals("", securePreferences.getApiKey())
        assertFalse(securePreferences.hasValidApiKey())
    }

    @Test
    fun `gemini client throws IllegalStateException when api key is blank`() {
        val client = GeminiApiClient()
        val dummyFile = File("dummy.m4a")

        val exception1 = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                client.transcribeAudio("", dummyFile)
            }
        }
        assertEquals("No API key configured.", exception1.message)

        val exception2 = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                client.testApiKey("   ")
            }
        }
        assertEquals("No API key configured.", exception2.message)
    }

    @Test
    fun `overlay state machine transitions defined`() {
        val states = OverlayState.values()
        assertEquals(5, states.size)
        assertNotNull(OverlayState.valueOf("IDLE"))
        assertNotNull(OverlayState.valueOf("RECORDING"))
        assertNotNull(OverlayState.valueOf("PROCESSING"))
        assertNotNull(OverlayState.valueOf("SUCCESS"))
        assertNotNull(OverlayState.valueOf("ERROR"))
    }

    @Test
    fun `overlay service tracks editable focus state`() {
        OverlayService.updateEditableFocusState(true)
        assertTrue(OverlayService.isEditableFocused.value)

        OverlayService.updateEditableFocusState(false)
        assertFalse(OverlayService.isEditableFocused.value)
    }

    @Test
    fun `accessibility injection results verified`() {
        val appendedResult = AuraAccessibilityService.InjectionResult.Appended(wasAppended = true)
        assertTrue(appendedResult.wasAppended)

        val clipboardResult = AuraAccessibilityService.InjectionResult.CopiedToClipboardFallback
        assertNotNull(clipboardResult)
    }
}
