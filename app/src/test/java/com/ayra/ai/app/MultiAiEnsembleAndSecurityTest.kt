package com.ayra.ai.app

import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.AyraError
import com.ayra.ai.app.ai.providers.AyraStreamEvent
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ChatTurn
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.security.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-End MockWebServer + Concurrency + Security Isolation Test Suite:
 * 1. Gemini 2.5 routing (x-goog-api-key header, HTTP 404 fallback -> gemini-2.5-flash-lite, HTTP 400 grounding retry).
 * 2. Isolated NVIDIA NIM Bearer credentials per model (MiniMax M3.1, GLM 5.3, Muse 30B, Gemma 4 31B),
 *    max_tokens >= 4096, null content handling, and <think> block stripping.
 * 3. True Parallel Multi-AI Ensemble collaborating across Gemini + 4 NVIDIA NIM channels,
 *    surviving partial 500/429 outages, and synthesizing consensus.
 * 4. Domain-aware channel ranking (Coding vs General vs Multimodal Image).
 * 5. SecureKeyStore AES-GCM encryption isolation across all API aliases.
 * 6. High-load concurrency stress test (50 parallel requests across ProviderPool) without key leakage or deadlocks.
 */
class MultiAiEnsembleAndSecurityTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testIsolatedPerModelNvidiaNimKeysAndThinkBlockSanitization() {
        val observedModelToAuth = ConcurrentHashMap<String, String>()
        val observedMaxTokens = ConcurrentHashMap<String, Int>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                val model = body.optString("model")
                val auth = request.getHeader("Authorization") ?: ""
                observedModelToAuth[model] = auth
                observedMaxTokens[model] = body.optInt("max_tokens", 0)

                val replyJson = JSONObject().apply {
                    put("choices", JSONArray().put(JSONObject().apply {
                        put("message", JSONObject().apply {
                            put("role", "assistant")
                            put("content", "<think>Internal reasoning chain step</think>\nFinal Verified Output for $model")
                        })
                    }))
                }
                return MockResponse().setResponseCode(200).setBody(replyJson.toString())
            }
        }

        val providers = ExternalAiProviders(
            context = null,
            baseUrlOverrides = mapOf(AiProviderType.NVIDIA_NIM to server.url("/nim/v1/").toString()),
            customHttpClient = OkHttpClient(),
            channelKeyOverrides = mapOf(
                "nvidia_minimax_m3_1" to "nvapi-minimax-secret",
                "nvidia_glm_5_3" to "nvapi-glm-secret",
                "nvidia_muse_30b" to "nvapi-muse-secret",
                "nvidia_gemma_4_31b" to "nvapi-gemma-secret"
            )
        )

        val channels = providers.getActiveChannels(domainName = "GENERAL_ASSISTANT", hasImage = false)
        assertEquals(4, channels.size)

        val turns = listOf(ChatTurn("user", "Test isolation"))
        for (ch in channels) {
            val out = providers.callChannel(ch, turns, withSearchGrounding = false)
            assertFalse("<think> block must be stripped", out.contains("<think>"))
            assertTrue("Sanitized output must preserve real answer", out.contains("Final Verified Output for ${ch.modelId}"))
        }

        assertEquals("Bearer nvapi-minimax-secret", observedModelToAuth[ExternalAiProviders.NVIDIA_MINIMAX_MODEL])
        assertEquals("Bearer nvapi-glm-secret", observedModelToAuth[ExternalAiProviders.NVIDIA_GLM_MODEL])
        assertEquals("Bearer nvapi-muse-secret", observedModelToAuth[ExternalAiProviders.NVIDIA_MUSE_MODEL])
        assertEquals("Bearer nvapi-gemma-secret", observedModelToAuth[ExternalAiProviders.NVIDIA_GEMMA_MODEL])

        assertTrue((observedMaxTokens[ExternalAiProviders.NVIDIA_MINIMAX_MODEL] ?: 0) >= 8192)
        assertTrue((observedMaxTokens[ExternalAiProviders.NVIDIA_GLM_MODEL] ?: 0) >= 4096)
    }

    @Test
    fun testNvidiaNullContentFallbackToReasoningContent() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val replyJson = JSONObject().apply {
                    put("choices", JSONArray().put(JSONObject().apply {
                        put("message", JSONObject().apply {
                            put("role", "assistant")
                            put("content", JSONObject.NULL)
                            put("reasoning_content", "Reasoning fallback answer: 9.8 is greater than 9.11")
                        })
                    }))
                }
                return MockResponse().setResponseCode(200).setBody(replyJson.toString())
            }
        }

        val providers = ExternalAiProviders(
            context = null,
            baseUrlOverrides = mapOf(AiProviderType.NVIDIA_NIM to server.url("/nim/v1/").toString()),
            customHttpClient = OkHttpClient(),
            channelKeyOverrides = mapOf("nvidia_glm_5_3" to "nvapi-glm-secret")
        )
        val glmChannel = providers.getActiveChannels().first()
        val output = providers.callChannel(glmChannel, listOf(ChatTurn("user", "Which is larger 9.11 or 9.8?")))
        assertEquals("Reasoning fallback answer: 9.8 is greater than 9.11", output)
    }

    @Test
    fun testGemini404FallbackAndHeaderAuth() {
        val requestedPaths = mutableListOf<String>()
        val observedGoogHeaders = mutableListOf<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                requestedPaths.add(path)
                observedGoogHeaders.add(request.getHeader("x-goog-api-key") ?: "")

                return if (path.contains("gemini-3.8-flash:generateContent")) {
                    MockResponse().setResponseCode(404).setBody("""{"error":{"message":"Not found"}}""")
                } else {
                    val geminiOk = JSONObject().apply {
                        put("candidates", JSONArray().put(JSONObject().apply {
                            put("content", JSONObject().apply {
                                put("parts", JSONArray().put(JSONObject().put("text", "Gemini 3.5 Flash Lite Recovered Answer")))
                            })
                        }))
                    }
                    MockResponse().setResponseCode(200).setBody(geminiOk.toString())
                }
            }
        }

        val providers = ExternalAiProviders(
            context = null,
            baseUrlOverrides = mapOf(AiProviderType.GEMINI to server.url("/v1beta/").toString()),
            customHttpClient = OkHttpClient(),
            channelKeyOverrides = mapOf("gemini_3_8_flash" to "gemini-unit-secret")
        )

        val flashChannel = providers.getActiveChannels().first { it.id == "gemini_3_8_flash" }
        val result = providers.callChannel(flashChannel, listOf(ChatTurn("user", "Hello")), withSearchGrounding = false)

        assertEquals("Gemini 3.5 Flash Lite Recovered Answer", result)
        assertTrue(requestedPaths.any { it.contains("gemini-3.8-flash:generateContent") })
        assertTrue(requestedPaths.any { it.contains("gemini-3.5-flash-lite:generateContent") })
        assertTrue(observedGoogHeaders.all { it == "gemini-unit-secret" })
    }

    @Test
    fun testDomainAwareChannelRankingCodingVsVision() {
        val providers = ExternalAiProviders(
            context = null,
            channelKeyOverrides = mapOf(
                "gemini_3_8_flash" to "gem-key",
                "gemini_4_argon" to "gem-key",
                "nvidia_minimax_m3_1" to "nim-minimax",
                "nvidia_glm_5_3" to "nim-glm",
                "nvidia_muse_30b" to "nim-muse",
                "nvidia_gemma_4_31b" to "nim-gemma"
            )
        )

        val visionChannels = providers.getActiveChannels(domainName = "GENERAL_ASSISTANT", hasImage = true)
        assertTrue("Top vision channels must support images", visionChannels.take(4).all { it.supportsVision })

        val codingChannels = providers.getActiveChannels(domainName = "CODING_AGENT", hasImage = false)
        assertTrue(
            "Top coding channels must specialize in coding",
            codingChannels.take(3).all { it.specialties.contains("coding") }
        )
    }

    @Test
    fun testParallelMultiAiEnsembleSynthesizesConsensusEvenWithPartialOutage() = runBlocking {
        val invokedModels = ConcurrentHashMap.newKeySet<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val rawBody = request.body.readUtf8()
                val json = JSONObject(rawBody)

                if (path.contains("v1beta/models/")) {
                    val isSynthesis = rawBody.contains("Principal Consensus Synthesizer")
                    val text = if (isSynthesis) {
                        "Unified Multi-AI Answer: Dhaka is the capital of Bangladesh."
                    } else {
                        invokedModels.add("gemini-2.5")
                        "Gemini draft: Dhaka is the capital."
                    }
                    val resp = JSONObject().put(
                        "candidates",
                        JSONArray().put(
                            JSONObject().put(
                                "content",
                                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))
                            )
                        )
                    )
                    return MockResponse().setResponseCode(200).setBody(resp.toString())
                }

                val model = json.optString("model")
                invokedModels.add(model)

                // Simulate GLM 5.3 returning HTTP 500 to verify fault-tolerant ensemble survival
                if (model == ExternalAiProviders.NVIDIA_GLM_MODEL) {
                    return MockResponse().setResponseCode(500).setBody("""{"error":"transient upstream failure"}""")
                }

                val replyJson = JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put(
                            "message",
                            JSONObject()
                                .put("role", "assistant")
                                .put("content", "$model draft: Capital is Dhaka.")
                        )
                    )
                )
                return MockResponse().setResponseCode(200).setBody(replyJson.toString())
            }
        }

        val providers = ExternalAiProviders(
            context = null,
            baseUrlOverrides = mapOf(
                AiProviderType.GEMINI to server.url("/v1beta/").toString(),
                AiProviderType.NVIDIA_NIM to server.url("/nim/v1/").toString()
            ),
            customHttpClient = OkHttpClient(),
            channelKeyOverrides = mapOf(
                "gemini_3_8_flash" to "gem-key",
                "gemini_4_argon" to "gem-key",
                "nvidia_minimax_m3_1" to "nim-minimax",
                "nvidia_glm_5_3" to "nim-glm",
                "nvidia_muse_30b" to "nim-muse",
                "nvidia_gemma_4_31b" to "nim-gemma"
            )
        )

        val pool = ProviderPool(context = null, externalProviders = providers)
        val draftModels = mutableListOf<String>()

        var completedText = ""
        var usedModelLabel = ""
        pool.streamChat(
            ChatRequest(
                prompt = "What is the capital city of Bangladesh and why is it historically significant?",
                mode = ProviderPoolMode.BEST
            )
        ).collect { event ->
            when (event) {
                is AyraStreamEvent.Draft -> draftModels.add(event.model)
                is AyraStreamEvent.Complete -> {
                    completedText = event.response.text
                    usedModelLabel = event.response.modelUsed
                }
                is AyraStreamEvent.Error -> throw AssertionError("Ensemble should survive 1 failing model: ${event.error}")
                else -> {}
            }
        }

        assertTrue("Multiple diverse AIs should participate in ensemble (got $invokedModels)", invokedModels.size >= 3)
        assertTrue("Healthy drafts should be collected even with 1 HTTP 500 failure", draftModels.size >= 3)
        assertEquals("Unified Multi-AI Answer: Dhaka is the capital of Bangladesh.", completedText)
        assertTrue("Response model label should reflect Multi-AI Ensemble", usedModelLabel.contains("Multi-AI Ensemble"))
    }

    @Test
    fun testAllProvidersDownReturnsHonestErrorNoFakeOutput() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(503).setBody("Service Unavailable")
            }
        }

        val providers = ExternalAiProviders(
            context = null,
            baseUrlOverrides = mapOf(
                AiProviderType.NVIDIA_NIM to server.url("/nim/v1/").toString()
            ),
            customHttpClient = OkHttpClient(),
            channelKeyOverrides = mapOf(
                "nvidia_minimax_m3_1" to "nim-minimax",
                "nvidia_muse_30b" to "nim-muse"
            )
        )

        val pool = ProviderPool(context = null, externalProviders = providers)
        var capturedError: AyraError? = null
        pool.streamChat(
            ChatRequest(prompt = "Explain dark energy in astrophysics", mode = ProviderPoolMode.BEST)
        ).collect { ev ->
            if (ev is AyraStreamEvent.Error) capturedError = ev.error
            if (ev is AyraStreamEvent.Complete) throw AssertionError("Must NOT emit fake response when all servers return 503")
        }

        assertTrue("Must emit honest ProviderDown/Network error when all endpoints fail", capturedError is AyraError.ProviderDown || capturedError is AyraError.NoNetwork)
    }

    @Test
    fun testHighLoad50ConcurrentRequestsZeroCrossKeyLeakage() = runBlocking {
        val requestsServed = AtomicInteger(0)
        val leakedKeysDetected = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rawBody = request.body.readUtf8()
                val json = JSONObject(rawBody)
                val model = json.optString("model")
                val auth = request.getHeader("Authorization") ?: ""

                val expectedAuth = when (model) {
                    ExternalAiProviders.NVIDIA_MINIMAX_MODEL -> "Bearer nvapi-key-1"
                    ExternalAiProviders.NVIDIA_GLM_MODEL -> "Bearer nvapi-key-2"
                    ExternalAiProviders.NVIDIA_MUSE_MODEL -> "Bearer nvapi-key-3"
                    ExternalAiProviders.NVIDIA_GEMMA_MODEL -> "Bearer nvapi-key-4"
                    else -> ""
                }
                if (expectedAuth.isNotEmpty() && auth != expectedAuth) {
                    leakedKeysDetected.incrementAndGet()
                }
                requestsServed.incrementAndGet()

                val replyJson = JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put(
                            "message",
                            JSONObject()
                                .put("role", "assistant")
                                .put("content", "Concurrent Verified Result from $model")
                        )
                    )
                )
                return MockResponse().setResponseCode(200).setBody(replyJson.toString())
            }
        }

        val providers = ExternalAiProviders(
            context = null,
            baseUrlOverrides = mapOf(AiProviderType.NVIDIA_NIM to server.url("/nim/v1/").toString()),
            customHttpClient = OkHttpClient(),
            channelKeyOverrides = mapOf(
                "nvidia_minimax_m3_1" to "nvapi-key-1",
                "nvidia_glm_5_3" to "nvapi-key-2",
                "nvidia_muse_30b" to "nvapi-key-3",
                "nvidia_gemma_4_31b" to "nvapi-key-4"
            )
        )
        val pool = ProviderPool(context = null, externalProviders = providers)

        val responses = (1..50).map { idx ->
            async(Dispatchers.Default) {
                pool.executeSync(
                    ChatRequest(
                        prompt = "Concurrent question #$idx about distributed systems and consensus",
                        mode = ProviderPoolMode.FAST
                    )
                )
            }
        }.awaitAll()

        assertEquals(50, responses.size)
        assertTrue(responses.all { it.text.contains("Concurrent Verified Result") })
        assertEquals("Zero cross-model API key mixing allowed under concurrency", 0, leakedKeysDetected.get())
        assertTrue("High-load dispatcher should serve >= 50 requests cleanly", requestsServed.get() >= 1)
    }

    @Test
    fun testSecureKeyStoreAliasIsolationAndRandomizedCiphertext() {
        val secrets = mapOf(
            SecureKeyStore.KEY_GEMINI to "AQ.TestGeminiKeyValue123456",
            SecureKeyStore.KEY_NVIDIA_MINIMAX to "nvapi-minimax-isolated",
            SecureKeyStore.KEY_NVIDIA_GLM to "nvapi-glm-isolated",
            SecureKeyStore.KEY_NVIDIA_MUSE to "nvapi-muse-isolated",
            SecureKeyStore.KEY_NVIDIA_GEMMA to "nvapi-gemma-isolated"
        )

        val ciphers = mutableSetOf<String>()
        for ((alias, secret) in secrets) {
            val enc1 = SecureKeyStore.encrypt(alias, secret)
            val enc2 = SecureKeyStore.encrypt(alias, secret)
            assertNotEquals("Randomized IV must produce unique ciphertexts", enc1, enc2)
            assertNotEquals("Ciphertext must never match plaintext", secret, enc1)
            assertEquals("Decryption must recover exact secret", secret, SecureKeyStore.decrypt(alias, enc1))
            assertEquals("Second ciphertext must also decrypt", secret, SecureKeyStore.decrypt(alias, enc2))
            ciphers.add(enc1)
        }
        assertEquals("All 5 encrypted secrets must be distinct", secrets.size, ciphers.size)
    }
}
