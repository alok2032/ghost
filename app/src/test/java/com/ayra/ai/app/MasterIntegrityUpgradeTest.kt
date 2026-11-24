package com.ayra.ai.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.AiMediaPromptSynthesizer
import com.ayra.ai.app.ai.AyraIntentClassifier
import com.ayra.ai.app.ai.AyraToolIntent
import com.ayra.ai.app.ai.DataAnalysisEngine
import com.ayra.ai.app.ai.UnifiedAiEngine
import com.ayra.ai.app.ai.agents.AgentDomain
import com.ayra.ai.app.ai.agents.AyraAgentOrchestrator
import com.ayra.ai.app.ai.code.GeneratedProjectFile
import com.ayra.ai.app.ai.code.ProjectWorkspaceManager
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.AyraError
import com.ayra.ai.app.ai.providers.AyraStreamEvent
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelCatalog
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.openmodel.OpenModelEngine
import com.ayra.ai.app.ai.rag.DocumentRagEngine
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MasterIntegrityUpgradeTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AyraPreferences.clearAllPreferences(context)
        server = MockWebServer()
        server.start()
        ProviderPool(context).clearCache()
        UnifiedAiEngine.clearCache()
    }

    @After
    fun tearDown() {
        ProviderPool(context).clearCache()
        UnifiedAiEngine.clearCache()
        AyraPreferences.clearAllPreferences(context)
        runCatching { server.shutdown() }
    }

    @Test
    fun testOpenModelTestConnectionRejects404AndAccepts200Or401() = runBlocking {
        val openModel = OpenModelEngine(context)

        // All 3 probe paths (/api/tags, /v1/models, /) return 404 -> must throw
        repeat(3) { server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found")) }
        val baseUrl = server.url("/").toString().trimEnd('/')
        var failed404 = false
        try {
            openModel.testConnection(baseUrl)
        } catch (_: Exception) {
            failed404 = true
        }
        assertTrue("404 across all probes must throw", failed404)

        // /api/tags returns 200 -> succeeds cleanly
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))
        val latency200 = openModel.testConnection(baseUrl)
        assertTrue(latency200 >= 1L)

        // /api/tags 404, /v1/models returns 401 auth challenge -> accepted
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        val latency401 = openModel.testConnection(baseUrl)
        assertTrue(latency401 >= 1L)
    }

    @Test
    fun testIntentClassifierRecallAndSafety() {
        assertEquals(AyraToolIntent.IMAGE, AyraIntentClassifier.classifyIntent("generate image of a cyberpunk skyline"))
        assertEquals(AyraToolIntent.IMAGE, AyraIntentClassifier.classifyIntent("একটি সুন্দর সূর্যাস্তের ছবি বানাও"))
        assertEquals(AyraToolIntent.SONG, AyraIntentClassifier.classifyIntent("create a song about monsoon rain"))
        assertEquals(AyraToolIntent.SONG, AyraIntentClassifier.classifyIntent("বৃষ্টি নিয়ে একটা রোমান্টিক গান তৈরি করো"))

        // Informational questions still go to CHAT
        assertEquals(AyraToolIntent.CHAT, AyraIntentClassifier.classifyIntent("How do you generate images with diffusion models?"))
        assertEquals(AyraToolIntent.CHAT, AyraIntentClassifier.classifyIntent("Tell me about the history of music in Bengal and make it short"))
    }

    @Test
    fun testAgentOrchestratorAvoidsSubstringFalsePositives() {
        assertFalse(AyraAgentOrchestrator.classifyIntent("What is Wi-Fi 7?") == AgentDomain.AUTOMATION_AGENT)
        assertFalse(AyraAgentOrchestrator.classifyIntent("Explain Bluetooth LE protocols") == AgentDomain.AUTOMATION_AGENT)
        assertFalse(AyraAgentOrchestrator.classifyIntent("Tell me about the apple company") == AgentDomain.AUTOMATION_AGENT)
        assertFalse(AyraAgentOrchestrator.classifyIntent("Wish you a happy birthday!") == AgentDomain.CODING_AGENT)

        // Explicit commands still route properly
        assertEquals(AgentDomain.AUTOMATION_AGENT, AyraAgentOrchestrator.classifyIntent("Turn on wifi"))
        assertEquals(AgentDomain.AUTOMATION_AGENT, AyraAgentOrchestrator.classifyIntent("Open WhatsApp"))
    }

    @Test
    fun testDataAnalysisHandlesQuotedMultilineCsvAndMissingSentinels() {
        val csv = """
            name,score,notes
            "Alice","92.5","Line one
            line two"
            "Bob","N/A","Absent"
            "Carol","null","Pending"
            "Dave","85.0","OK"
        """.trimIndent()

        val summary = DataAnalysisEngine.analyzeTabularText(csv, "grades.csv")
        assertEquals(4, summary.rowCount)
        assertEquals(3, summary.columnCount)

        val scoreCol = summary.columns.first { it.name == "score" }
        assertTrue("score column with N/A and null sentinels must still be numeric", scoreCol.isNumeric)
        assertEquals(2, scoreCol.nonEmptyCount)
        assertEquals(2, scoreCol.missingCount)
        assertEquals(85.0, scoreCol.min ?: 0.0, 0.01)
        assertEquals(92.5, scoreCol.max ?: 0.0, 0.01)
    }

    @Test
    fun testDocumentRagMarksLowOverlapFallbackClearly() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val engine = DocumentRagEngine(db.knowledgeDocumentDao())
            engine.ingestDocument(
                title = "notes.txt",
                mimeType = "text/plain",
                rawText = "Photosynthesis converts solar photons into glucose using chlorophyll in plant chloroplasts."
            )
            val hits = engine.searchRelevantChunks("quantum chromodynamics quarks gluons", topK = 1)
            assertEquals(1, hits.size)
            assertFalse("Zero-overlap fallback hit must set isExactMatch = false", hits[0].isExactMatch)

            val (cited, _) = engine.buildCitedContext("quantum chromodynamics quarks gluons", topK = 1)
            assertTrue(cited.contains("Low lexical overlap fallback"))
        } finally {
            db.close()
        }
    }

    @Test
    fun testProjectWorkspaceManagerPreventsSiblingPrefixTraversalAndSupportsCrud() {
        val base = context.filesDir
        val projDir = ProjectWorkspaceManager.writeProjectToDiskAndZip(
            context = context,
            projectName = "safeproj",
            files = listOf(
                GeneratedProjectFile("Main.kt", "fun main() {}"),
                GeneratedProjectFile("../safeproj_evil/secret.txt", "pwned")
            )
        ).first
        assertNotNull(projDir)
        val siblingEscape = File(base, "ayra_projects/safeproj_evil/secret.txt")
        assertFalse("Sibling prefix traversal file must NOT be written", siblingEscape.exists())

        assertTrue(ProjectWorkspaceManager.createProjectFile(projDir!!, "README.md", "# Test"))
        assertTrue(File(projDir, "README.md").exists())
        assertTrue(ProjectWorkspaceManager.deleteProject(context, projDir.name))
        assertFalse(projDir.exists())
    }

    @Test
    fun testMediaSynthesizerRespectsConsentAspectRatioAndStoryboardHonesty() {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, true)

        val urlWide = AiMediaPromptSynthesizer.buildImageUrl("neon skyline", context = context, aspectRatio = "16:9")
        assertTrue(urlWide.contains("width=1024") && urlWide.contains("height=576"))

        val json = AiMediaPromptSynthesizer.buildVideoPayload("rocket launch", context = context, aspectRatio = "16:9")
        assertTrue(json.optBoolean("isStoryboardOnly", false))
        assertEquals(2, json.optInt("frameCount", 0))
        assertFalse(json.has("duration"))

        // When consent is revoked, previewUrl and url are redacted/empty
        AyraPreferences.setExternalAiConsent(context, false)
        assertEquals("", AiMediaPromptSynthesizer.buildImageUrl("neon skyline", context = context))
        val blockedVideo = AiMediaPromptSynthesizer.buildVideoPayload("rocket launch", context = context, aspectRatio = "16:9")
        assertEquals("", blockedVideo.optString("previewUrl"))
        AyraPreferences.setExternalAiConsent(context, true)
    }

    @Test
    fun testUnifiedAiEngineHonestOfflineSongAndBlockedImage() = runBlocking {
        // Ensure offline / local-only mode with no external consent or free pool
        AyraPreferences.setLocalOnlyAiModeEnabled(context, true)
        AyraPreferences.setFreeAiPoolEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, false)
        AyraPreferences.setCustomGeminiKey(context, "")
        AyraPreferences.setCustomOpenRouterKey(context, "")
        AyraPreferences.setGroqKey(context, "")

        val engine = UnifiedAiEngine(context)

        val songResp = engine.generateUnifiedResponse("একটি বৃষ্টির গান লেখো")
        assertNull("Offline song synthesis must not emit fake SONG craft card", songResp.craftType)
        assertTrue(songResp.modelUsed?.contains("Offline", ignoreCase = true) == true)

        val imgResp = engine.generateUnifiedResponse("generate image of an owl")
        assertNull("Blocked external image pool must not emit fake/unconsented IMAGE url", imgResp.craftType)

        // Restore preferences cleanly
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setFreeAiPoolEnabled(context, true)
        AyraPreferences.setExternalAiConsent(context, true)
    }

    @Test
    fun testProviderPoolPropagatesCancellationCleanly() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"late response"}}]}""")
        )
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, true)
        val ext = com.ayra.ai.app.ai.providers.ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(
                com.ayra.ai.app.ai.providers.AiProviderType.OPENAI to server.url("/").toString()
            ),
            customHttpClient = okhttp3.OkHttpClient(),
            channelKeyOverrides = mapOf(
                com.ayra.ai.app.ai.providers.AiProviderType.OPENAI.id to "sk-cancel-test"
            )
        )
        val pool = ProviderPool(context, ext)
        val deferred = async {
            pool.executeSync(
                ChatRequest(
                    prompt = "Hello",
                    preferredProvider = com.ayra.ai.app.ai.providers.AiProviderType.OPENAI,
                    systemPromptOverride = "be brief"
                )
            )
        }
        delay(50)
        deferred.cancel(CancellationException("cancelled by test"))
        var threwCancellation = false
        try {
            deferred.await()
        } catch (_: CancellationException) {
            threwCancellation = true
        }
        assertTrue("Coroutines must propagate CancellationException", threwCancellation)
    }

    @Test
    fun testComfyUiClientConfinesTraversalFilenameInsideOutputDir() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"prompt_id":"job_123"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "job_123": {
                    "outputs": {
                      "9": {
                        "images": [
                          {"filename": "../../escaped_evil.png", "subfolder": "", "type": "output"}
                        ]
                      }
                    }
                  }
                }
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("PNG_BINARY_BYTES"))

        val rootTmp = File(context.cacheDir, "comfy_test_${System.nanoTime()}").apply { mkdirs() }
        val outputDir = File(rootTmp, "outputs").apply { mkdirs() }
        try {
            val client = com.ayra.ai.app.ai.studios.ComfyUiClient(
                httpClient = okhttp3.OkHttpClient(),
                context = context
            )
            val result = client.generateImage(
                baseUrl = server.url("/").toString().trimEnd('/'),
                prompt = "aurora skyline",
                outputDir = outputDir
            )
            assertTrue("Expected ImageSuccess, got $result", result is com.ayra.ai.app.ai.studios.ComfyUiResult.ImageSuccess)
            val downloaded = (result as com.ayra.ai.app.ai.studios.ComfyUiResult.ImageSuccess).file
            assertTrue(
                "Downloaded file must be confined inside outputDir",
                downloaded.canonicalPath.startsWith("${outputDir.canonicalPath}${File.separator}")
            )
            assertFalse("Traversal file in parent dir must not exist", File(rootTmp, "escaped_evil.png").exists())
        } finally {
            rootTmp.deleteRecursively()
        }
    }

    @Test
    fun testLocalOnlyPrivacyModeBlocksCveWikiLookupAndDynamicModelDiscovery() = runBlocking {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, true)
        AyraPreferences.setExternalAiConsent(context, false)
        try {
            val secEngine = com.ayra.ai.app.ai.securityresearch.SecurityResearchEngine(context)
            val grounding = secEngine.buildSecurityGrounding("Analyze CVE-2024-12345 and OWASP top 10")
            assertFalse("Local-Only mode must skip external CVE lookup", grounding.contains("[LIVE CVE INTEL"))
            assertTrue("Local offline OWASP handbook still grounds cleanly", grounding.contains("[OWASP SECURITY REFERENCE]"))

            val externalProviders = com.ayra.ai.app.ai.providers.ExternalAiProviders(
                context = context,
                customHttpClient = null
            )
            val models = externalProviders.discoverDynamicModels(com.ayra.ai.app.ai.providers.AiProviderType.OPENAI)
            assertTrue(models.isNotEmpty())
            assertEquals(0, server.requestCount)
        } finally {
            AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
            AyraPreferences.setExternalAiConsent(context, true)
        }
    }

    @Test
    fun testProviderPoolRespectsRoutingModePrivacyLocalAndFast() = runBlocking {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, true)
        AyraPreferences.setFreeAiPoolEnabled(context, false)
        AyraPreferences.setAllowLanEndpointsEnabled(context, true)
        AyraPreferences.setCustomEndpointUrl(context, server.url("/v1/").toString())

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"Local Ollama routing verified"}}]}""")
        )
        // Second response in case streaming hits SSE or fallback
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"Groq fast routing verified"}}]}""")
        )

        val ext = com.ayra.ai.app.ai.providers.ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(
                com.ayra.ai.app.ai.providers.AiProviderType.LOCAL_OLLAMA to server.url("/local/").toString(),
                com.ayra.ai.app.ai.providers.AiProviderType.OPENAI to server.url("/openai/").toString(),
                com.ayra.ai.app.ai.providers.AiProviderType.GROQ to server.url("/groq/").toString()
            ),
            customHttpClient = okhttp3.OkHttpClient(),
            channelKeyOverrides = mapOf(
                "local_ollama" to "local",
                com.ayra.ai.app.ai.providers.AiProviderType.OPENAI.id to "sk-test",
                com.ayra.ai.app.ai.providers.AiProviderType.GROQ.id to "gsk-test"
            )
        )
        val pool = ProviderPool(context, ext)

        val localResp = pool.executeSync(
            ChatRequest(
                prompt = "Summarize architecture principles in depth",
                routingMode = com.ayra.ai.app.ai.providers.AiRoutingMode.PRIVACY_LOCAL
            )
        )
        assertEquals(com.ayra.ai.app.ai.providers.AiProviderType.LOCAL_OLLAMA, localResp.provider)
        assertTrue(localResp.text.contains("Local Ollama routing verified"))

        val fastResp = pool.executeSync(
            ChatRequest(
                prompt = "Explain binary search vs linear search with complexity analysis",
                routingMode = com.ayra.ai.app.ai.providers.AiRoutingMode.FAST
            )
        )
        assertEquals(com.ayra.ai.app.ai.providers.AiProviderType.GROQ, fastResp.provider)
        assertTrue(fastResp.text.contains("Groq fast routing verified"))
    }

    @Test
    fun testChatRepositoryEnsureActiveSessionIsRaceFreeUnderConcurrency() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = com.ayra.ai.app.data.ChatRepository(context, db.chatDao(), db.chatSessionDao())
            val first = async { repo.ensureActiveSession() }
            val second = async { repo.ensureActiveSession() }
            val s1 = first.await()
            val s2 = second.await()
            assertEquals(s1.id, s2.id)
            assertEquals(1, db.chatSessionDao().getAllSessionsOnce().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun testFirebaseAiEngineSelectModelForTaskHonorsExplicitTiersAndSafeDefault() {
        val fb = FirebaseAiEngine(context)
        assertEquals(FirebaseAiEngine.MODEL_PRO_REASONING, fb.selectModelForTask("gemini_pro", "Prove Fermat", preferReasoning = true))
        assertEquals(FirebaseAiEngine.MODEL_PRO_REASONING, fb.selectModelForTask("reasoning", "Design distributed lock", preferReasoning = true))
        assertEquals(FirebaseAiEngine.MODEL_PRO_REASONING, fb.selectModelForTask("quality", "Audit crypto", preferReasoning = false))
        assertEquals(FirebaseAiEngine.MODEL_FAST_LITE, fb.selectModelForTask("fast", "hi", preferReasoning = false))
        assertEquals(FirebaseAiEngine.MODEL_FLASH, fb.selectModelForTask("auto", "hi", preferReasoning = false))
    }

    @Test
    fun testStreamChatResponseFallsBackOn404AndRecordsSuccess() = runBlocking {
        ModelCatalog.clearRuntimeHealth()
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, true)
        AyraPreferences.setGroqKey(context, "gsk-stream-404-test")

        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model not found"}}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Streamed \"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Qwen OK\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )

        val ext = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.GROQ to server.url("/groq/").toString()),
            customHttpClient = okhttp3.OkHttpClient()
        )
        val chunks = ext.streamChatResponse(
            prompt = "Hello stream",
            systemPrompt = "Be concise",
            preferredProvider = AiProviderType.GROQ,
            preferredModelId = ExternalAiProviders.GROQ_LLAMA_MODEL
        ).toList()

        val fullText = chunks.joinToString("")
        assertTrue("Expected failover notice in stream: $fullText", fullText.contains("Model Notice:"))
        assertTrue("Expected streamed fallback tokens: $fullText", fullText.contains("Streamed Qwen OK"))
        assertTrue(ModelCatalog.isModelTemporarilyUnavailable(AiProviderType.GROQ, ExternalAiProviders.GROQ_LLAMA_MODEL))
        ModelCatalog.clearRuntimeHealth()
    }

    @Test
    fun testLocalOllamaHostActivationAndApiTagsDiscovery() = runBlocking {
        ExternalAiProviders.clearDynamicModelCache(AiProviderType.LOCAL_OLLAMA)
        AyraPreferences.setLocalOnlyAiModeEnabled(context, true)
        AyraPreferences.setAllowLanEndpointsEnabled(context, true)
        val ollamaBase = server.url("/").toString().trimEnd('/')
        AyraPreferences.setOllamaHost(context, ollamaBase)

        val ext = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.LOCAL_OLLAMA to "$ollamaBase/v1/"),
            customHttpClient = okhttp3.OkHttpClient()
        )
        val channels = ext.getActiveChannels()
        assertTrue("Expected LOCAL_OLLAMA channel when explicit Ollama host is configured", channels.any { it.provider == AiProviderType.LOCAL_OLLAMA })

        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"models":[{"name":"qwen2.5:14b"},{"name":"llama3.3"}]}""")
        )
        val discovered = ext.discoverDynamicModels(AiProviderType.LOCAL_OLLAMA)
        assertTrue(discovered.any { it.id == "qwen2.5:14b" && it.provider == AiProviderType.LOCAL_OLLAMA })
        assertTrue(discovered.any { it.id == "llama3.3" && it.provider == AiProviderType.LOCAL_OLLAMA })
        ExternalAiProviders.clearDynamicModelCache(AiProviderType.LOCAL_OLLAMA)
    }

    @Test
    fun testGetActiveChannelsFiltersWhenConsentFalseAndProviderPoolEmitsConsentRequiredError() = runBlocking {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, false)
        AyraPreferences.setFreeAiPoolEnabled(context, false)
        AyraPreferences.setGroqKey(context, "gsk-saved-but-no-consent")

        val ext = ExternalAiProviders(context)
        assertTrue(
            "Saved key without consent must not appear in getActiveChannels()",
            ext.getActiveChannels().isEmpty()
        )
        assertTrue(
            "Saved key without consent must be detected by getSavedKeyProvidersIgnoringConsent()",
            ext.getSavedKeyProvidersIgnoringConsent().contains(AiProviderType.GROQ)
        )

        val events = ProviderPool(context, ext).streamChat(ChatRequest(prompt = "Hello")).toList()
        val err = events.filterIsInstance<AyraStreamEvent.Error>().firstOrNull()?.error
        assertTrue("Expected consent-specific ProviderDown error, got $err", err is AyraError.ProviderDown)
        assertTrue(
            "Expected consent guidance in error message: ${err?.userFriendlyMessage}",
            err?.userFriendlyMessage?.contains("Consent", ignoreCase = true) == true
        )
    }
}
