package com.ayra.ai.app.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.AyraOrchestrator
import com.ayra.ai.app.ai.agent.DefaultAgentToolRegistry
import com.ayra.ai.app.ai.openmodel.OpenModelEngine
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.securityresearch.SecurityResearchEngine
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.ChatRepository
import com.ayra.ai.app.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelOrchestrationTest {

    private lateinit var context: Context
    private lateinit var db: ChatDatabase
    private lateinit var repository: ChatRepository
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        ChatDatabase.closeDatabaseForMaintenance()
        context.getSharedPreferences("ayra_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ChatRepository(context, db.chatDao(), db.chatSessionDao())
        server = MockWebServer()
        server.start()
        ProviderPool(context).clearCache()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        ProviderPool(context).clearCache()
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setCustomEndpointUrl(context, "")
        AyraPreferences.setMemoryEnabled(context, false)
        db.close()
        ChatDatabase.closeDatabaseForMaintenance()
        Dispatchers.resetMain()
    }

    @Test
    fun `launchOrchestratedInference streams SSE tokens via MockWebServer and persists assistant reply`() = runBlocking {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, true)
        AyraPreferences.setAllowLanEndpointsEnabled(context, true)
        AyraPreferences.setFreeAiPoolEnabled(context, false)
        AyraPreferences.setCustomEndpointUrl(context, server.url("/v1/").toString())

        val sseBody = buildString {
            append("data: ").append(
                JSONObject().put("choices", JSONArray().put(
                    JSONObject().put("delta", JSONObject().put("content", "Ayra AI "))
                )).toString()
            ).append("\n\n")
            append("data: ").append(
                JSONObject().put("choices", JSONArray().put(
                    JSONObject().put("delta", JSONObject().put("content", "verified Clean Architecture response."))
                )).toString()
            ).append("\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val vm = ChatViewModel(repository)
        try {
            val sessionId = vm.ensureSessionReady()
            assertTrue(vm.tryStartSending())

            val orchestrator = AyraOrchestrator(context)
            var spokenReply: String? = null
            vm.launchOrchestratedInference(
                orchestrator = orchestrator,
                prompt = "Explain clean architecture in one sentence",
                persona = "AI_ASSISTANT",
                tier = "auto",
                history = emptyList(),
                imageBase64 = null,
                documentText = null,
                documentName = null,
                cloudRepo = null,
                onSpeakResponse = { spokenReply = it }
            )

            var assistantMsg: ChatMessage? = null
            repeat(160) {
                val msgs = repository.getRecentMessages(sessionId, 10)
                assistantMsg = msgs.lastOrNull { it.role == "assistant" }
                if (assistantMsg != null && !vm.isGenerating.value) return@repeat
                delay(50)
            }

            val finalMsgs = repository.getRecentMessages(sessionId, 20)
            assertNotNull(
                "assistantMsg null (reqCount=${server.requestCount}, isGen=${vm.isGenerating.value}, status=${vm.thinkingStatus.value}, msgs=${finalMsgs.map { it.role + ':' + it.content.take(60) }})",
                assistantMsg
            )
            assertTrue(assistantMsg!!.content.contains("Ayra AI verified Clean Architecture response."))
            assertEquals(assistantMsg!!.content, spokenReply)
            assertFalse(vm.isGenerating.value)
            assertTrue(vm.activeCitations.value.isEmpty())
            assertTrue(vm.agentPlanSteps.value.isEmpty())
        } finally {
            vm.releaseForTests()
        }
    }

    @Test
    fun `opt-in memory banner inspects candidate fact and saves only after explicit confirmation`() = runBlocking {
        val vm = ChatViewModel(repository)
        try {
            vm.ensureSessionReady()

            // Memory disabled by default -> no banner candidate emitted
            assertFalse(AyraPreferences.isMemoryEnabled(context))
            vm.inspectCandidateMemory(context, "Remember that I prefer Jetpack Compose")
            assertNull(vm.pendingMemoryCandidate.value)

            // Enable opt-in memory -> candidate surfaced, dismiss skips persistence
            AyraPreferences.setMemoryEnabled(context, true)
            vm.inspectCandidateMemory(context, "Remember that I prefer Jetpack Compose")
            assertNotNull(vm.pendingMemoryCandidate.value)
            vm.dismissPendingMemorySave()
            assertNull(vm.pendingMemoryCandidate.value)
            assertTrue(ChatDatabase.getDatabase(context).longTermMemoryDao().getAllSync().isEmpty())

            // Explicit confirmation persists encrypted record into Room
            vm.inspectCandidateMemory(context, "My name is Alok")
            assertNotNull(vm.pendingMemoryCandidate.value)
            var feedback = ""
            vm.confirmPendingMemorySave(context) { feedback = it }

            var savedCount = 0
            repeat(40) {
                savedCount = ChatDatabase.getDatabase(context).longTermMemoryDao().getAllSync().size
                if (savedCount > 0 && feedback.isNotBlank()) return@repeat
                delay(50)
            }

            assertEquals(1, savedCount)
            assertTrue(feedback.contains("Saved to encrypted memory"))
            assertNull(vm.pendingMemoryCandidate.value)
        } finally {
            vm.releaseForTests()
        }
    }

    @Test
    fun `ayra ai branding invariants hold across agent and specialized engines`() {
        assertTrue(SecurityResearchEngine.SAFE_DEFENSIVE_PROMPT.contains("Ayra Defensive Security Research Engine"))
        assertTrue(OpenModelEngine.SAFE_SYSTEM_PROMPT.contains("Ayra Open-Weights Engine"))
        val registry = DefaultAgentToolRegistry(context)
        assertEquals(22, registry.listTools().size)
        assertNotNull(registry.getTool("mcp_call"))
        val calendarTool = registry.getTool("calendar_create")
        assertNotNull(calendarTool)
        assertFalse(SecurityResearchEngine.SAFE_DEFENSIVE_PROMPT.contains("Ghost"))
        assertFalse(OpenModelEngine.SAFE_SYSTEM_PROMPT.contains("Ghost"))
    }
}
