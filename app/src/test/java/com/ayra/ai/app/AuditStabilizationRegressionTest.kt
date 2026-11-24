package com.ayra.ai.app

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.AyraIntentClassifier
import com.ayra.ai.app.ai.AyraOrchestrator
import com.ayra.ai.app.ai.AyraToolIntent
import com.ayra.ai.app.ai.agents.AgentDomain
import com.ayra.ai.app.ai.agents.AyraAgentOrchestrator
import com.ayra.ai.app.ai.providers.AiModelChannel
import com.ayra.ai.app.ai.providers.AiModelRegistry
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ChatTurn
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelCategory
import com.ayra.ai.app.ai.providers.ModelCatalog
import com.ayra.ai.app.ai.providers.ModelHealthStatus
import com.ayra.ai.app.ai.providers.ProviderAnthropicAndFreeTransport
import com.ayra.ai.app.ai.providers.ProviderError
import com.ayra.ai.app.ai.providers.ProviderGeminiTransport
import com.ayra.ai.app.ai.providers.ProviderOpenAiTransport
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderTierStatus
import com.ayra.ai.app.ai.providers.RegistryModelEntry
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.ChatRepository
import java.time.LocalDate
import com.ayra.ai.app.security.SecureKeyStore
import com.ayra.ai.app.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditStabilizationRegressionTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SecureKeyStore.clearAllSecrets(context)
        AyraPreferences.clearAllPreferences(context)
        ModelCatalog.clearRuntimeHealth()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        ModelCatalog.clearRuntimeHealth()
        SecureKeyStore.clearAllSecrets(context)
        AyraPreferences.clearAllPreferences(context)
    }

    @Test
    fun anthropicSkipsThinkingBlocksDetectsHttp400InvalidModelAndStreamInlineError() = runBlocking {
        val overrides = mapOf(AiProviderType.ANTHROPIC to server.url("/anthropic/").toString())
        val client = OkHttpClient()

        // 1. Extended thinking block + multi-text block concatenation
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "msg_1",
                  "type": "message",
                  "content": [
                    {"type": "thinking", "thinking": "Internal hidden chain of thought"},
                    {"type": "redacted_thinking", "data": "abc"},
                    {"type": "text", "text": "Part 1. "},
                    {"type": "text", "text": "Final answer."}
                  ]
                }
                """.trimIndent()
            )
        )
        val out = ProviderAnthropicAndFreeTransport.callAnthropic(
            httpClient = client,
            baseUrlOverrides = overrides,
            turns = listOf(ChatTurn("user", "Explain")),
            model = "claude-sonnet-4-6",
            apiKey = "sk-ant-test"
        )
        assertEquals("Part 1. Final answer.", out)

        // 2. HTTP 400 invalid model -> ModelNotFound(httpStatus = 400)
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"type":"error","error":{"type":"invalid_request_error","message":"model: invalid model identifier"}}"""
            )
        )
        var err400: Throwable? = null
        try {
            ProviderAnthropicAndFreeTransport.callAnthropic(
                httpClient = client,
                baseUrlOverrides = overrides,
                turns = listOf(ChatTurn("user", "Hi")),
                model = "claude-bad-id",
                apiKey = "sk-ant-test"
            )
        } catch (t: Throwable) {
            err400 = t
        }
        assertTrue(err400 is ProviderError.ModelNotFound)
        assertEquals(400, (err400 as ProviderError.ModelNotFound).httpStatus)

        // 3. SSE streaming skips thinking_delta and throws on SSE inline rate_limit_error
        val sseBody = listOf(
            """data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"secret"}}""",
            """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Visible "}}""",
            """data: {"type":"error","error":{"type":"rate_limit_error","message":"Too many tokens"}}"""
        ).joinToString("\n\n", postfix = "\n\n")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val chunks = mutableListOf<String>()
        var streamErr: Throwable? = null
        try {
            flow {
                ProviderAnthropicAndFreeTransport.streamAnthropic(
                    httpClient = client,
                    baseUrlOverrides = overrides,
                    turns = listOf(ChatTurn("user", "Stream test")),
                    model = "claude-sonnet-4-6",
                    apiKey = "sk-ant-test",
                    collector = this
                )
            }.collect { chunks.add(it) }
        } catch (t: Throwable) {
            streamErr = t
        }
        assertEquals(listOf("Visible "), chunks)
        assertTrue("Expected ProviderError.RateLimited, got $streamErr", streamErr is ProviderError.RateLimited)
    }

    @Test
    fun geminiAndOpenAiDetectHttp200InlineErrorsAndSafetyBlocks() {
        val client = OkHttpClient()

        // 1. Gemini HTTP 200 promptFeedback blockReason=SAFETY -> ContentBlocked
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"promptFeedback":{"blockReason":"SAFETY"}}"""
            )
        )
        var safetyErr: Throwable? = null
        try {
            ProviderGeminiTransport.callGemini(
                httpClient = client,
                baseUrlOverride = server.url("/gemini/").toString(),
                turns = listOf(ChatTurn("user", "Unsafe test")),
                model = "gemini-3.6-flash",
                apiKey = "AIza-test",
                withSearchGrounding = false
            )
        } catch (t: Throwable) {
            safetyErr = t
        }
        assertTrue("Expected ContentBlocked, got $safetyErr", safetyErr is ProviderError.ContentBlocked)

        // 2. Gemini HTTP 200 inline error -> Unauthorized / typed error
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"error":{"code":403,"status":"PERMISSION_DENIED","message":"API key invalid"}}"""
            )
        )
        var gemInlineErr: Throwable? = null
        try {
            ProviderGeminiTransport.callGemini(
                httpClient = client,
                baseUrlOverride = server.url("/gemini/").toString(),
                turns = listOf(ChatTurn("user", "Hi")),
                model = "gemini-3.6-flash",
                apiKey = "AIza-test",
                withSearchGrounding = false
            )
        } catch (t: Throwable) {
            gemInlineErr = t
        }
        assertTrue("Expected Unauthorized, got $gemInlineErr", gemInlineErr is ProviderError.Unauthorized)

        // 3. OpenAI-compatible HTTP 200 inline error -> RateLimited
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"error":{"code":429,"type":"rate_limit_exceeded","message":"Upstream quota hit"}}"""
            )
        )
        var oaErr: Throwable? = null
        try {
            ProviderOpenAiTransport.callOpenAiCompatible(
                client = client,
                context = context,
                provider = AiProviderType.GROQ,
                baseUrlOverrides = mapOf(AiProviderType.GROQ to server.url("/groq/").toString()),
                hasCustomClient = true,
                turns = listOf(ChatTurn("user", "Hi")),
                model = ExternalAiProviders.GROQ_LLAMA_MODEL,
                apiKey = "gsk-test"
            )
        } catch (t: Throwable) {
            oaErr = t
        }
        assertTrue("Expected RateLimited, got $oaErr", oaErr is ProviderError.RateLimited)
    }

    @Test
    fun aiModelRegistryPingMapsGranularHealthStates401_404_410_429() = runBlocking {
        AyraPreferences.setGroqKey(context, "gsk-probe-test")
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, true)

        val ext = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.GROQ to server.url("/groq/").toString()),
            customHttpClient = OkHttpClient()
        )
        val entry = RegistryModelEntry(
            id = "test_groq_probe",
            provider = AiProviderType.GROQ,
            modelId = ExternalAiProviders.GROQ_QWEN_MODEL,
            displayName = "Test Groq Qwen",
            category = ModelCategory.REASONING,
            endpoint = server.url("/groq/chat/completions").toString(),
            authRequirement = "Bearer",
            tierStatus = ProviderTierStatus.FREE_TIER_API,
            rateLimitNote = "Test",
            contextWindow = "128k",
            supportsStreaming = true,
            supportsVision = false,
            supportsToolCalling = true,
            modelVersion = "test"
        )

        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Bad key"}}"""))
        val r401 = AiModelRegistry.pingEntry(context, entry, externalProvidersOverride = ext)
        assertEquals(ModelHealthStatus.AUTH_FAILED, r401.healthStatus)

        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"Not found"}}"""))
        val r404 = AiModelRegistry.pingEntry(context, entry, externalProvidersOverride = ext)
        assertEquals(ModelHealthStatus.INVALID_MODEL, r404.healthStatus)

        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":{"message":"Gone"}}"""))
        val r410 = AiModelRegistry.pingEntry(context, entry, externalProvidersOverride = ext)
        assertEquals(ModelHealthStatus.RETIRED, r410.healthStatus)

        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limit"}}"""))
        val r429 = AiModelRegistry.pingEntry(context, entry, externalProvidersOverride = ext)
        assertEquals(ModelHealthStatus.DEGRADED, r429.healthStatus)
    }

    @Test
    fun repositoryAndViewModelDeleteMessageAndHandleMissingRegeneratePrompt() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val dbName = "test_delete_regen_${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, ChatDatabase::class.java, dbName)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val now = System.currentTimeMillis()
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO `chat_sessions`
                        (`id`, `title`, `personaMode`, `modelTier`, `isPinned`, `createdAt`, `updatedAt`, `messageCount`, `lastMessagePreview`)
                        VALUES (1, 'Ayra Main Chat', 'AI_ASSISTANT', 'auto', 0, $now, $now, 0, '')
                        """.trimIndent()
                    )
                }
            })
            .allowMainThreadQueries()
            .build()

        var vm: ChatViewModel? = null
        try {
            val repo = ChatRepository(context, db.chatDao(), db.chatSessionDao())
            val session = repo.ensureActiveSession()
            val userMsgId = repo.appendMessage(session.id, ChatMessage(role = "user", content = "Hello Ayra", timestamp = 100L))
            val assistantMsgId = repo.appendMessage(session.id, ChatMessage(role = "assistant", content = "Old assistant reply", timestamp = 200L))

            val activeVm = ChatViewModel(repo)
            vm = activeVm
            advanceUntilIdle()

            // Verify deleteMessage updates Room messages + session preview cleanly
            activeVm.deleteMessage(assistantMsgId)
            val deleteDeadline = System.currentTimeMillis() + 5000L
            var remaining = repo.observeMessages(session.id).first()
            var updatedSession = db.chatSessionDao().getSessionById(session.id)
            while ((remaining.size != 1 || updatedSession?.messageCount != 1) && System.currentTimeMillis() < deleteDeadline) {
                Thread.sleep(20)
                advanceUntilIdle()
                remaining = repo.observeMessages(session.id).first()
                updatedSession = db.chatSessionDao().getSessionById(session.id)
            }
            assertEquals(1, remaining.size)
            assertEquals(userMsgId, remaining.single().id)
            assertNotNull(updatedSession)
            assertEquals(1, updatedSession?.messageCount)
            assertEquals("Hello Ayra", updatedSession?.lastMessagePreview)

            // Verify regenerateResponse reports missing prompt safely when no preceding user turn exists
            val orphanAssistant = ChatMessage(id = 999L, sessionId = session.id, role = "assistant", content = "Orphan reply", timestamp = 300L)
            var missingPromptTriggered = false
            activeVm.regenerateResponse(
                assistantMessage = orphanAssistant,
                orchestrator = AyraOrchestrator(context),
                persona = "AI_ASSISTANT",
                tier = "auto",
                onMissingPrompt = { missingPromptTriggered = true }
            )
            val deadline = System.currentTimeMillis() + 3000L
            while (!missingPromptTriggered && System.currentTimeMillis() < deadline) {
                Thread.sleep(15)
                advanceUntilIdle()
            }
            assertTrue(missingPromptTriggered)
            assertFalse(activeVm.isGenerating.value)
        } finally {
            vm?.releaseForTests()
            Dispatchers.resetMain()
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun systemPromptEmbedsRealCurrentDateAndFreshWikiTriggers() {
        val today = LocalDate.now()
        val sysPrompt = AyraAgentOrchestrator.buildSystemPrompt(
            domain = AgentDomain.GENERAL_ASSISTANT,
            isBengali = false,
            isGfMode = false
        )
        assertTrue(sysPrompt.contains("Current Date: $today"))
        assertTrue(sysPrompt.contains(today.year.toString()))

        assertTrue(ProviderPool.isTimeSensitiveQuery("latest 2026 AI model"))
        assertTrue(ProviderPool.isTimeSensitiveQuery("আজকের সোনার দাম কত"))
        assertTrue(ProviderPool.isTimeSensitiveQuery("notun khobor ki"))
        assertFalse(ProviderPool.isTimeSensitiveQuery("Explain what you know about Kotlin coroutines"))

        assertTrue(AyraAgentOrchestrator.needsLiveWikiGrounding("latest AI release version", AgentDomain.CODING_AGENT))
        assertTrue(AyraAgentOrchestrator.needsLiveWikiGrounding("notun khobor ki", AgentDomain.GENERAL_ASSISTANT))
    }

    @Test
    fun intentClassifierSupportsCreateAndRenderVideoPromptsWhileProtectingQuestions() {
        assertEquals(AyraToolIntent.VIDEO, AyraIntentClassifier.classifyIntent("create a cinematic video of space"))
        assertEquals(AyraToolIntent.VIDEO, AyraIntentClassifier.classifyIntent("render a short video about oceans"))
        assertEquals(AyraToolIntent.VIDEO, AyraIntentClassifier.classifyIntent("একটি সুন্দর ভিডিও তৈরি করো"))
        assertEquals(AyraToolIntent.IMAGE, AyraIntentClassifier.classifyIntent("make a photo of emerald mountains"))
        assertEquals(AyraToolIntent.CHAT, AyraIntentClassifier.classifyIntent("What is video streaming?"))
        assertEquals(AyraToolIntent.CHAT, AyraIntentClassifier.classifyIntent("video game industry size, make it brief"))
    }

    @Test
    fun pollinationsFreePoolForwardsMultiTurnHistoryToBackend() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"Continued reply honoring prior context."}}]}""")
        )

        val ext = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.POLLINATIONS_FREE to server.url("/free/").toString()),
            customHttpClient = OkHttpClient()
        )
        val channel = AiModelChannel(
            id = "pollinations_free_openai",
            provider = AiProviderType.POLLINATIONS_FREE,
            modelId = "openai",
            displayName = "Free Pool",
            specialties = setOf("general"),
            keyResolver = { "" }
        )
        val turns = listOf(
            ChatTurn("system", "You are Ayra AI."),
            ChatTurn("user", "My favorite framework is Jetpack Compose."),
            ChatTurn("assistant", "Got it, Jetpack Compose is awesome."),
            ChatTurn("user", "What did I just say?")
        )

        val result = ext.callChannel(channel, turns, withSearchGrounding = false)
        assertEquals("Continued reply honoring prior context.", result)

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("Got it, Jetpack Compose is awesome."))
        assertTrue(body.contains("What did I just say?"))
    }

    @Test
    fun timeSensitiveQueryBypassesStaleExternalCache() = runBlocking {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, true)
        AyraPreferences.setGroqKey(context, "gsk-fresh-test")

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"First fresh update"}}]}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"Second even fresher update"}}]}""")
        )

        val ext = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.GROQ to server.url("/groq/").toString()),
            customHttpClient = OkHttpClient()
        )

        val first = ext.generateChatResponse(
            prompt = "latest AI news today",
            systemPrompt = "Be concise",
            preferredProvider = AiProviderType.GROQ,
            preferredModelId = ExternalAiProviders.GROQ_LLAMA_MODEL
        )
        val second = ext.generateChatResponse(
            prompt = "latest AI news today",
            systemPrompt = "Be concise",
            preferredProvider = AiProviderType.GROQ,
            preferredModelId = ExternalAiProviders.GROQ_LLAMA_MODEL
        )

        assertEquals("First fresh update", first.text)
        assertEquals("Second even fresher update", second.text)
        assertEquals(2, server.requestCount)
    }
}
