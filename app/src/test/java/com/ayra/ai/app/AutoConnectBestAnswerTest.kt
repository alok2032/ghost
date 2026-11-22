package com.ayra.ai.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.agents.AgentDomain
import com.ayra.ai.app.ai.providers.AiModelChannel
import com.ayra.ai.app.ai.providers.AiModelRegistry
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.AiRoutingMode
import com.ayra.ai.app.ai.providers.AyraStreamEvent
import com.ayra.ai.app.ai.providers.BestAnswerSelector
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelTier
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.SecureKeyStore
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoConnectBestAnswerTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SecureKeyStore.clearAllSecrets(context)
        AyraPreferences.clearAllPreferences(context)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        SecureKeyStore.clearAllSecrets(context)
        AyraPreferences.clearAllPreferences(context)
    }

    @Test
    fun freshInstallFailsClosedUntilExplicitOptInAndLocalOnlyBlocksExternal() {
        // Fresh preferences (Phase 12 fail-closed defaults): external pool & sync disabled until opted in
        assertFalse("Fresh install must fail-closed on external consent", AyraPreferences.hasExternalAiConsent(context))
        assertFalse("Free AI Pool must remain disabled until user opts in", AyraPreferences.isFreeAiPoolEnabled(context))
        assertFalse("Free AI Pool consent must default to false", AyraPreferences.hasFreePoolConsent(context))
        assertFalse("First-run onboarding must not be skipped silently", AyraPreferences.isOnboardingCompleted(context))
        assertFalse("Cloud sync must fail-closed out of the box", AyraPreferences.isCloudSyncEnabled(context))

        val ext = ExternalAiProviders(context)
        assertFalse("App must not send traffic externally before opt-in", ext.hasAnyConfiguredProvider())
        assertTrue("Active channels must be empty before external consent", ext.getActiveChannels().isEmpty())

        // Explicit user opt-in activates built-in Free AI Pool channels cleanly
        AyraPreferences.setExternalAiConsent(context, true)
        AyraPreferences.setFreeAiPoolEnabled(context, true)
        assertTrue("Opted-in Free AI Pool must enable channels", AyraPreferences.isFreeAiPoolEnabled(context))
        assertTrue("Opted-in Free AI Pool consent must be true", AyraPreferences.hasFreePoolConsent(context))
        assertTrue("Opted-in app must have active channels", ext.hasAnyConfiguredProvider())

        val channels = ext.getActiveChannels()
        val channelIds = channels.map { it.id }.toSet()
        assertTrue(channelIds.contains("pollinations_free_openai"))
        assertTrue(channelIds.contains("pollinations_free_openai_large"))
        assertTrue(channelIds.contains("pollinations_free_qwen_coder"))
        assertTrue(channelIds.contains("pollinations_free_deepseek"))

        val registryIds = AiModelRegistry.buildInitialCatalog(context).map { it.id }.toSet()
        assertTrue(registryIds.contains("pollinations_free_qwen_coder"))
        assertTrue(registryIds.contains("pollinations_free_deepseek"))
    }

    @Test
    fun localOnlyModeAndRevokedConsentFailClosedImmediately() {
        AyraPreferences.setExternalAiConsent(context, true)
        AyraPreferences.setFreeAiPoolEnabled(context, true)

        val ext = ExternalAiProviders(context)
        assertTrue(ext.hasAnyConfiguredProvider())

        AyraPreferences.setLocalOnlyAiModeEnabled(context, true)
        assertFalse("Local-Only Privacy Mode must disable Free AI Pool", AyraPreferences.isFreeAiPoolEnabled(context))
        assertFalse("Local-Only mode with no Ollama must have zero external providers", ext.hasAnyConfiguredProvider())

        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        assertTrue(ext.hasAnyConfiguredProvider())

        AyraPreferences.setExternalAiConsent(context, false)
        assertFalse("Revoked external consent must block Free AI Pool", AyraPreferences.isFreeAiPoolEnabled(context))
        assertFalse(ext.hasAnyConfiguredProvider())
    }

    @Test
    fun questionAwareAutoDirectivesActivateSecurityAndMathEnginesInAutoMode() = runBlocking {
        val secDirective = ModelTier.AUTO.buildQuestionAwareAutoDirectives(
            prompt = "Audit this Android app for OWASP SQLi and CVE-2025 vulnerabilities",
            domainName = AgentDomain.CYBER_DEFENSE_AGENT.name,
            context = context
        )
        assertNotNull(secDirective)
        assertTrue(secDirective!!.contains("DEFENSIVE", ignoreCase = true) || secDirective.contains("CVE", ignoreCase = true))

        val mathDirective = ModelTier.AUTO.buildQuestionAwareAutoDirectives(
            prompt = "Prove the time complexity algorithm of Dijkstra with Fibonacci heap",
            domainName = AgentDomain.MATHEMATICS_AGENT.name,
            context = context
        )
        assertNotNull(mathDirective)
        assertTrue(mathDirective!!.contains("DEEPSEEK-R1 AUTO VERIFICATION"))
    }

    @Test
    fun bestAnswerSelectorPrefersAccurateMatchedDraftOverRefusalAndBloat() {
        val codingChannel = AiModelChannel(
            id = "qwen_coder",
            provider = AiProviderType.GROQ,
            modelId = "qwen3-32b",
            displayName = "Qwen Coder",
            specialties = setOf("coding", "math"),
            supportsVision = false,
            isReasoning = true,
            keyResolver = { "" }
        )
        val genericChannel = AiModelChannel(
            id = "generic_chat",
            provider = AiProviderType.OPENAI,
            modelId = "gpt-4o-mini",
            displayName = "Generic Chat",
            specialties = setOf("general"),
            supportsVision = false,
            keyResolver = { "" }
        )
        val brokenChannel = AiModelChannel(
            id = "broken_channel",
            provider = AiProviderType.DEEPSEEK,
            modelId = "deepseek-chat",
            displayName = "Broken Model",
            specialties = setOf("coding"),
            supportsVision = false,
            keyResolver = { "" }
        )

        val goodBengaliCodeDraft = """
            বাংলায় Kotlin coroutine দিয়ে নিরাপদ সমাধান:
            ```kotlin
            suspend fun fetchItems(): List<String> = withContext(Dispatchers.IO) {
                listOf("alpha", "beta")
            }
            ```
            এটি মেইন থ্রেড ব্লক না করে ব্যাকগ্রাউন্ডে কাজ করে।
        """.trimIndent()

        val bloatedRefusalDraft = "As an AI language model, I cannot run live apps. " +
            "Please provide an API key first to continue with coding tasks in general cases. ".repeat(8)

        val emptyHtmlError = "<html><body>502 Bad Gateway Cloudflare</body></html>"

        val ranked = BestAnswerSelector.rankDrafts(
            prompt = "বাংলায় Kotlin coroutine কোড দাও",
            domain = AgentDomain.CODING_AGENT,
            drafts = listOf(
                genericChannel to bloatedRefusalDraft,
                brokenChannel to emptyHtmlError,
                codingChannel to goodBengaliCodeDraft
            )
        )

        assertEquals("qwen_coder", ranked.first().channel.id)
        assertTrue(ranked.first().text.contains("withContext(Dispatchers.IO)"))
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun providerPoolEnsembleFallsBackToBestScoredDraftInsteadOfLongestGarbage() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val rawBody = request.body.readUtf8()
                if (rawBody.contains("Principal Consensus Synthesizer")) {
                    // Simulate synthesizer transient failure so BestAnswerSelector fallback runs
                    return MockResponse().setResponseCode(503).setBody("""{"error":"synth overloaded"}""")
                }
                val reply = when {
                    path.startsWith("/groq/") ->
                        "As an AI language model, I cannot verify anything. Please provide an API key first to continue. ".repeat(6)
                    path.startsWith("/cerebras/") -> "Cannot."
                    else -> """
                        বাংলায় বাইনারি সার্চ (Binary Search) কোড:
                        ```kotlin
                        fun binarySearch(arr: IntArray, target: Int): Int {
                            var lo = 0; var hi = arr.lastIndex
                            while (lo <= hi) {
                                val mid = lo + (hi - lo) / 2
                                when {
                                    arr[mid] == target -> return mid
                                    arr[mid] < target -> lo = mid + 1
                                    else -> hi = mid - 1
                                }
                            }
                            return -1
                        }
                        ```
                        সময় জটিলতা O(log n)।
                    """.trimIndent()
                }
                val body = JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put("message", JSONObject().put("role", "assistant").put("content", reply))
                    )
                )
                return MockResponse().setResponseCode(200).setBody(body.toString())
            }
        }

        // Disable Pollinations free pool in this MockWebServer test so only mocked channels execute
        AyraPreferences.setFreeAiPoolEnabled(context, false)
        val ext = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(
                AiProviderType.GROQ to server.url("/groq/").toString(),
                AiProviderType.CEREBRAS to server.url("/cerebras/").toString(),
                AiProviderType.SAMBANOVA to server.url("/sambanova/").toString()
            ),
            customHttpClient = OkHttpClient(),
            channelKeyOverrides = mapOf(
                AiProviderType.GROQ.id to "test-groq",
                AiProviderType.CEREBRAS.id to "test-cerebras",
                AiProviderType.SAMBANOVA.id to "test-sambanova"
            )
        )

        val pool = ProviderPool(context = context, externalProviders = ext)
        var finalText = ""
        var finalModel = ""

        pool.streamChat(
            ChatRequest(
                prompt = "বাংলায় binary search Kotlin কোড লিখে দাও",
                mode = ProviderPoolMode.AUTO
            )
        ).collect { ev ->
            if (ev is AyraStreamEvent.Complete) {
                finalText = ev.response.text
                finalModel = ev.response.modelUsed
            }
        }

        assertTrue("BestAnswerSelector should choose clean runnable Kotlin code", finalText.contains("fun binarySearch"))
        assertTrue("Model label should reflect Best Answer selection", finalModel.contains("Best Answer"))
    }

    @Test
    fun routingModeRoundTripsAllModesWithoutCollapsing() {
        AiRoutingMode.entries.forEach { mode ->
            assertEquals(mode, AiRoutingMode.fromId(mode.id))
            AyraPreferences.setRoutingMode(context, mode.id)
            assertEquals(mode.id, AyraPreferences.getRoutingMode(context))
        }
        assertEquals(AiRoutingMode.AUTO, AiRoutingMode.fromId("balanced"))
        assertEquals(AiRoutingMode.BEST_QUALITY, AiRoutingMode.fromId("best_quality"))
        assertEquals(AiRoutingMode.BEST_QUALITY, AiRoutingMode.fromId("gemini_pro"))
        assertEquals(AiRoutingMode.FAST, AiRoutingMode.fromId("gemini_flash"))
        assertEquals(AiRoutingMode.PRIVACY_LOCAL, AiRoutingMode.fromId("privacy_local"))
        assertEquals(AiRoutingMode.REASONING, AiRoutingMode.fromId("deepseek_r1"))
    }

    @Test
    fun allAiProvidersAndLocalEndpointsWireProperlyWithFailClosedConsent() {
        // 1. Saving API keys must NEVER silently enable external consent
        AyraPreferences.setCustomGeminiKey(context, "gem-key")
        AyraPreferences.setOpenAiKey(context, "oai-key")
        AyraPreferences.setAnthropicKey(context, "ant-key")
        AyraPreferences.setDeepSeekKey(context, "ds-key")
        AyraPreferences.setGroqKey(context, "groq-key")
        AyraPreferences.setCerebrasKey(context, "cer-key")
        AyraPreferences.setSambaNovaKey(context, "smb-key")
        AyraPreferences.setHuggingFaceKey(context, "hf-key")
        AyraPreferences.setXAiKey(context, "xai-key")
        AyraPreferences.setMistralKey(context, "mis-key")
        AyraPreferences.setPerplexityKey(context, "pplx-key")
        AyraPreferences.setCustomOpenRouterKey(context, "or-key")
        AyraPreferences.setNvidiaMinimaxKey(context, "nv-minimax")
        AyraPreferences.setNvidiaGlmKey(context, "nv-glm")
        AyraPreferences.setNvidiaMuseKey(context, "nv-muse")
        AyraPreferences.setNvidiaGemmaKey(context, "nv-gemma")
        AyraPreferences.setTavilySearchKey(context, "tvly-key")
        AyraPreferences.setElevenLabsKey(context, "el-key")
        AyraPreferences.setComfyUiUrl(context, "http://10.0.2.2:8188")
        AyraPreferences.setBackendGatewayUrl(context, "https://1.1.1.1")

        assertFalse("Saving keys must never silently grant external AI consent", AyraPreferences.hasExternalAiConsent(context))

        val ext = ExternalAiProviders(context)
        assertFalse("External providers must remain blocked until user opts in", ext.isProviderConfigured(AiProviderType.OPENAI))

        // 2. Local Ollama / Proxy URL + optional Bearer key works without external cloud consent
        AyraPreferences.setOllamaHost(context, "http://10.0.2.2:11434")
        AyraPreferences.setCustomEndpointKey(context, "proxy-secret-token")
        assertTrue(ext.isProviderConfigured(AiProviderType.LOCAL_OLLAMA))
        assertEquals("proxy-secret-token", ext.getProviderKey(AiProviderType.LOCAL_OLLAMA))
        assertTrue(
            "Local Ollama channel must activate when explicit Ollama host is configured",
            ext.getActiveChannels().any { it.provider == AiProviderType.LOCAL_OLLAMA }
        )

        // 3. Explicit external consent activates all 14+ cloud LLM engines & NIM per-model keys
        AyraPreferences.setExternalAiConsent(context, true)
        assertEquals("nv-minimax", ext.getKeyForProviderAndModel(AiProviderType.NVIDIA_NIM, ExternalAiProviders.NVIDIA_MINIMAX_MODEL))
        assertEquals("nv-glm", ext.getKeyForProviderAndModel(AiProviderType.NVIDIA_NIM, ExternalAiProviders.NVIDIA_GLM_MODEL))
        assertEquals("nv-muse", ext.getKeyForProviderAndModel(AiProviderType.NVIDIA_NIM, ExternalAiProviders.NVIDIA_MUSE_MODEL))
        assertEquals("nv-gemma", ext.getKeyForProviderAndModel(AiProviderType.NVIDIA_NIM, ExternalAiProviders.NVIDIA_GEMMA_MODEL))

        val activeIds = ext.getActiveChannels().map { it.id }.toSet()
        assertTrue(activeIds.contains("gemini_3_8_flash"))
        assertTrue(activeIds.contains("nvidia_minimax_m3_1"))
        assertTrue(activeIds.contains("nvidia_glm_5_3"))
        assertTrue(activeIds.contains("nvidia_muse_30b"))
        assertTrue(activeIds.contains("nvidia_gemma_4_31b"))
        assertTrue(activeIds.contains("groq_qwen_3_8"))
        assertTrue(activeIds.contains("cerebras_gpt_oss"))
        assertTrue(activeIds.contains("sambanova_deepseek"))
        assertTrue(activeIds.contains("huggingface_qwen_72b"))
        assertTrue(activeIds.contains("openai_gpt_6_2_sol"))
        assertTrue(activeIds.contains("anthropic_claude_5_5"))
        assertTrue(activeIds.contains("deepseek_v4_pro"))
        assertTrue(activeIds.contains("perplexity_sonar"))
        assertTrue(activeIds.contains("mistral_large"))
        assertTrue(activeIds.contains("xai_grok"))
        assertTrue(activeIds.contains("openrouter_free"))
        assertTrue(activeIds.contains("local_ollama"))
    }
}
