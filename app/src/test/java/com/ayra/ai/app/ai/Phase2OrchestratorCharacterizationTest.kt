package com.ayra.ai.app.ai

import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.automation.ProposedAction
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Characterization test pinning existing AyraOrchestrator & AyraIntentClassifier
 * behavior before Phase 2 AgentCore / Memory / ViewModel upgrades.
 */
@RunWith(RobolectricTestRunner::class)
class Phase2OrchestratorCharacterizationTest {

    @Test
    fun `orchestrator preserves crisis interception and action gate routing`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val orchestrator = AyraOrchestrator(context)

        val crisisEvents = orchestrator.handle("I feel like ending my life tonight").toList()
        assertTrue(crisisEvents.any { it is AyraEvent.Complete && (it.response.modelUsed ?: "").contains("Safety Guardian") })

        val callEvents = orchestrator.handle("call +15551234567").toList()
        assertTrue(callEvents.any { it is AyraEvent.ActionRequired && it.action is ProposedAction.DirectCall })

        assertEquals(AyraToolIntent.TERMINAL, AyraIntentClassifier.classifyIntent("/terminal ping 127.0.0.1"))
        assertEquals(AyraToolIntent.WIKI, AyraIntentClassifier.classifyIntent("/wiki quantum mechanics"))
        assertEquals(AyraToolIntent.DEEP_RESEARCH, AyraIntentClassifier.classifyIntent("deep research on solid state batteries"))
        assertEquals(AyraToolIntent.CHAT, AyraIntentClassifier.classifyIntent("Explain what a REST API call is"))
    }
}
