package com.ayra.ai.app.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.agent.DefaultAgentCore
import com.ayra.ai.app.ai.agent.DefaultAgentToolRegistry
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.AiUsageTracker
import com.ayra.ai.app.automation.ProposedAction
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentCoreAndToolRegistryTest {

    @Test
    fun `tool registry builds valid OpenAI tools JSON schema and enforces SSRF and ActionGate`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = DefaultAgentToolRegistry(context)

        val tools = registry.listTools()
        assertTrue(tools.size >= 20)

        val schemaArr = registry.toOpenAiToolsJsonArray(localOnlyMode = false)
        assertEquals(tools.size, schemaArr.length())

        val ssrf = registry.getTool("web_fetch")!!.execute(JSONObject().put("url", "http://169.254.169.254/latest/meta-data/"))
        assertFalse(ssrf.success)

        val cal = registry.getTool("calendar_create")!!.execute(JSONObject().put("title", "Team Sync").put("offset_hours", 2))
        assertTrue(cal.requiresConfirmationAction is ProposedAction.CalendarCreate)

        val email = registry.getTool("email_draft")!!.execute(JSONObject().put("to", "dev@example.com").put("subject", "Update").put("body", "Done"))
        assertTrue(email.requiresConfirmationAction is ProposedAction.ComposeEmail)

        val math = registry.getTool("code_run")!!.execute(JSONObject().put("expression", "(12 + 8) * 3"))
        assertTrue(math.success)
        assertTrue(math.output.contains("60"))
    }

    @Test
    fun `agent core parses tool invocation JSON and multi-step activation triggers`() {
        val parsed = DefaultAgentCore.parseToolInvocationFromText(
            """```json
            {"tool":"web_search","args":{"query":"Android Compose 2026","max_results":3}}
            ```"""
        )
        assertNotNull(parsed)
        assertEquals("web_search", parsed!!.toolName)
        assertEquals("Android Compose 2026", parsed.args.getString("query"))

        assertTrue(DefaultAgentCore.shouldActivateMultiStepAgent("/agent search kotlin coroutines and save pdf", "auto"))
        assertTrue(DefaultAgentCore.shouldActivateMultiStepAgent("Explain how Kotlin coroutines work", "agent"))
        assertFalse(DefaultAgentCore.shouldActivateMultiStepAgent("Explain how Kotlin coroutines work", "auto"))
    }

    @Test
    fun `usage tracker enforces daily quota soft warning at 80 percent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AiUsageTracker.clearAll(context)
        AiUsageTracker.setDailyLimit(context, 10)
        repeat(8) {
            AiUsageTracker.recordCall(context, AiProviderType.GEMINI, true, 120L, estimatedTokens = 100)
        }
        val snap = AiUsageTracker.getDailySnapshot(context)
        assertEquals(8, snap.requestsToday)
        assertEquals(800L, snap.estimatedTokensToday)
        assertTrue(snap.isNearLimit)
        assertNotNull(snap.warningBannerText)

        repeat(2) {
            AiUsageTracker.recordCall(context, AiProviderType.GEMINI, true, 100L, estimatedTokens = 100)
        }
        assertTrue(AiUsageTracker.getDailySnapshot(context).isOverSoftLimit)
        AiUsageTracker.clearAll(context)
    }
}
