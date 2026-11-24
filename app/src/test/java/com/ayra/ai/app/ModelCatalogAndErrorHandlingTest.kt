package com.ayra.ai.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.providers.AiModelInfo
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelCapability
import com.ayra.ai.app.ai.providers.ModelCatalog
import com.ayra.ai.app.ai.providers.ModelVerificationStatus
import com.ayra.ai.app.ai.providers.ProviderError
import com.ayra.ai.app.data.AyraPreferences
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelCatalogAndErrorHandlingTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ModelCatalog.clearRuntimeHealth()
        server = MockWebServer()
        server.start()
        AyraPreferences.setGroqKey(context, "gsk_test_catalog_key")
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, true)
    }

    @After
    fun tearDown() {
        ModelCatalog.clearRuntimeHealth()
        AyraPreferences.setGroqKey(context, "")
        runCatching { server.shutdown() }
    }

    @Test
    fun unverifiedPreviewModelsExcludedUntilDiscoveredLive() {
        val nimDefault = ModelCatalog.defaultModelFor(AiProviderType.NVIDIA_NIM)
        assertNotNull(nimDefault)
        assertEquals("meta/llama-3.3-70b-instruct", nimDefault?.id)
        assertFalse(ModelCatalog.isVerifiedOrConfigured(AiProviderType.NVIDIA_NIM, ExternalAiProviders.NVIDIA_MINIMAX_MODEL))

        ModelCatalog.registerLiveDiscoveredModels(
            AiProviderType.NVIDIA_NIM,
            listOf(
                AiModelInfo(
                    id = ExternalAiProviders.NVIDIA_MINIMAX_MODEL,
                    displayName = "MiniMax M3.1 Flash Live",
                    provider = AiProviderType.NVIDIA_NIM,
                    description = "Verified live",
                    contextWindow = "128k",
                    supportsVision = true
                )
            )
        )
        assertTrue(ModelCatalog.isVerifiedOrConfigured(AiProviderType.NVIDIA_NIM, ExternalAiProviders.NVIDIA_MINIMAX_MODEL))
        assertEquals(
            ModelVerificationStatus.VERIFIED_LIVE,
            ModelCatalog.findEntry(AiProviderType.NVIDIA_NIM, ExternalAiProviders.NVIDIA_MINIMAX_MODEL)?.verificationStatus
        )
    }

    @Test
    fun modelNotFound404TriggersCircuitBlockAndFallbackModelSuccess() = runBlocking {
        // First request fails 404 (model not found), fallback succeeds 200
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model not found"}}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"Fallback response from verified Qwen3 32B"}}]}"""
            )
        )

        val providers = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.GROQ to server.url("/groq/").toString()),
            customHttpClient = OkHttpClient()
        )
        val resp = providers.generateChatResponse(
            prompt = "Explain binary search trees",
            systemPrompt = "Be concise",
            preferredProvider = AiProviderType.GROQ,
            preferredModelId = ExternalAiProviders.GROQ_LLAMA_MODEL
        )
        assertTrue(resp.text.contains("Fallback response from verified Qwen3 32B"))
        assertTrue(ModelCatalog.isModelTemporarilyUnavailable(AiProviderType.GROQ, ExternalAiProviders.GROQ_LLAMA_MODEL))
    }

    @Test
    fun http429RetriesWithBackoffAndRecoversCleanly() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limit exceeded"}}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"Recovered after 429 retry"}}]}"""
            )
        )

        val providers = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.GROQ to server.url("/groq/").toString()),
            customHttpClient = OkHttpClient()
        )
        val resp = providers.generateChatResponse(
            prompt = "Write a concise haiku",
            systemPrompt = "Be helpful",
            preferredProvider = AiProviderType.GROQ,
            preferredModelId = ExternalAiProviders.GROQ_LLAMA_MODEL
        )
        assertTrue(resp.text.contains("Recovered after 429 retry"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun http401PropagatesClearUnauthorizedErrorMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API key"}}"""))
        AyraPreferences.setFreeAiPoolEnabled(context, false)

        val providers = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.GROQ to server.url("/groq/").toString()),
            customHttpClient = OkHttpClient()
        )
        var thrownError: Throwable? = null
        try {
            providers.generateChatResponse(
                prompt = "Test unauthorized handling",
                systemPrompt = "Be helpful",
                preferredProvider = AiProviderType.GROQ,
                preferredModelId = ExternalAiProviders.GROQ_LLAMA_MODEL
            )
        } catch (e: Throwable) {
            thrownError = e
        } finally {
            AyraPreferences.setFreeAiPoolEnabled(context, true)
        }
        assertTrue("Expected ProviderError.Unauthorized or unauthorized message, got $thrownError",
            thrownError is ProviderError.Unauthorized || thrownError?.message?.contains("unauthorized", ignoreCase = true) == true
        )
    }

    @Test
    fun remoteCatalogOverridesPersistAndUpdateFallbackChain() {
        val json = """
            {
              "models": [
                {
                  "provider": "openai",
                  "id": "gpt-4.1-mini",
                  "displayName": "GPT-4.1 Mini (Remote)",
                  "contextTokens": 1000000,
                  "fallbackModelId": "gpt-4o-mini",
                  "isDefault": true
                }
              ]
            }
        """.trimIndent()
        val applied = ModelCatalog.applyRemoteCatalogOverridesJson(json, context)
        assertEquals(1, applied)
        val entry = ModelCatalog.findEntry(AiProviderType.OPENAI, "gpt-4.1-mini")
        assertNotNull(entry)
        assertEquals("gpt-4o-mini", ModelCatalog.nextFallbackModel(AiProviderType.OPENAI, "gpt-4.1-mini"))
        assertTrue(entry?.capabilities?.contains(ModelCapability.TEXT) == true)
        assertTrue(AyraPreferences.getRemoteModelCatalogJson(context).contains("gpt-4.1-mini"))
    }

    @Test
    fun anthropic404MapsToModelNotFoundAndMalformedDiscoveryIsSafe() = runBlocking {
        AyraPreferences.setAnthropicKey(context, "sk-ant-test-key")
        AyraPreferences.setFreeAiPoolEnabled(context, false)
        try {
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model not found"}}"""))
            val providers = ExternalAiProviders(
                context = context,
                baseUrlOverrides = mapOf(AiProviderType.ANTHROPIC to server.url("/anthropic/").toString()),
                customHttpClient = OkHttpClient()
            )
            var thrown: Throwable? = null
            try {
                providers.generateChatResponse(
                    prompt = "Hello",
                    systemPrompt = "Be concise",
                    preferredProvider = AiProviderType.ANTHROPIC,
                    preferredModelId = "claude-missing-model"
                )
            } catch (e: Throwable) {
                thrown = e
            }
            assertTrue("Expected ModelNotFound or model unavailable error, got $thrown",
                thrown is ProviderError.ModelNotFound ||
                    ModelCatalog.isModelTemporarilyUnavailable(AiProviderType.ANTHROPIC, "claude-missing-model")
            )

            // Malformed /models response should not throw during live model discovery
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html>temporarily unavailable</html>"))
            val openAiProviders = ExternalAiProviders(
                context = context,
                baseUrlOverrides = mapOf(AiProviderType.OPENAI to server.url("/openai/").toString()),
                customHttpClient = OkHttpClient(),
                channelKeyOverrides = mapOf(AiProviderType.OPENAI.id to "sk-openai-test-key")
            )
            val discovered = openAiProviders.discoverDynamicModels(AiProviderType.OPENAI)
            assertTrue(discovered.none { it.provider == AiProviderType.OPENAI && it.id.contains("<html>") })
        } finally {
            AyraPreferences.setAnthropicKey(context, "")
            AyraPreferences.setFreeAiPoolEnabled(context, true)
        }
    }
}
