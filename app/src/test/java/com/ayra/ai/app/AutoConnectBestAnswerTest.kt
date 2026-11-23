package com.ayra.ai.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.agents.AgentDomain
import com.ayra.ai.app.ai.providers.AiModelChannel
import com.ayra.ai.app.ai.providers.AiModelRegistry
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.AyraStreamEvent
import com.ayra.ai.app.ai.providers.BestAnswerSelector
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelTier
import com.ayra.ai.app.ai.providers.ProviderAnthropicAndFreeTransport
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
    fun bestAnswerSelectorPenalizesLongRefusalAndSelectDiversePicksAcrossProviders() {
        val longRefusal = "As an AI language model, I cannot verify live repository contents right now. " +
            "Please provide an API key first to proceed with detailed code generation. ".repeat(10)
        assertTrue(
            "Long hard refusal (>500 chars) must still be flagged as low value/refusal",
            BestAnswerSelector.isLowValueOrRefusal(longRefusal)
        )

        val channels = listOf(
            AiModelChannel("groq_1", AiProviderType.GROQ, "llama-3.3-70b", "Groq 1", setOf("general"), false, keyResolver = { "k" }),
            AiModelChannel("groq_2", AiProviderType.GROQ, "qwen3-32b", "Groq 2", setOf("coding"), false, keyResolver = { "k" }),
            AiModelChannel("cerebras_1", AiProviderType.CEREBRAS, "llama3.1-8b", "Cerebras 1", setOf("fast"), false, keyResolver = { "k" }),
            AiModelChannel("sambanova_1", AiProviderType.SAMBANOVA, "DeepSeek-R1", "SambaNova 1", setOf("reasoning"), false, keyResolver = { "k" }),
            AiModelChannel("openai_1", AiProviderType.OPENAI, "gpt-4o", "OpenAI 1", setOf("general"), false, keyResolver = { "k" })
        )
        val pool = ProviderPool(context = context)
        val selected = pool.selectDiverseEnsembleChannels(channels, maxCount = 4)
        val selectedProviders = selected.map { it.provider }.toSet()
        assertEquals(4, selected.size)
        assertEquals(
            "Round-robin diversity must pick 1 from each distinct provider before taking secondary models",
            setOf(AiProviderType.GROQ, AiProviderType.CEREBRAS, AiProviderType.SAMBANOVA, AiProviderType.OPENAI),
            selectedProviders
        )
    }

    @Test
    fun pollinationsTransportExtractsOpenAiJsonAndAllowsValidTechnicalProse() {
        val openAiJson = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put("message", JSONObject().put("role", "assistant").put("content", "HTTP error code 502 means Bad Gateway."))
            )
        ).toString()

        val extracted = ProviderAnthropicAndFreeTransport.extractPollinationsText(openAiJson)
        assertEquals("HTTP error code 502 means Bad Gateway.", extracted)
        assertFalse(
            "Valid prose explaining an HTTP status should NOT be misclassified as a gateway error",
            ProviderAnthropicAndFreeTransport.isPollinationsGatewayError(extracted)
        )
        assertTrue(
            "HTML error page must still be rejected as gateway error",
            ProviderAnthropicAndFreeTransport.isPollinationsGatewayError("<!DOCTYPE html><html><body>502 Bad Gateway</body></html>")
        )
    }
}
