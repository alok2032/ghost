package com.ayra.ai.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.providers.AiModelRegistry
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.AyraStreamEvent
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.SecureKeyStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteBGatewayPoolTest {

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
        server.shutdown()
        SecureKeyStore.clearAllSecrets(context)
        AyraPreferences.clearAllPreferences(context)
    }

    @Test
    fun streamChatUsesRouteBGatewayAndFallsBackToByokOnHttp503() = runBlocking {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, true)
        AyraPreferences.setByokPreferred(context, false)
        AyraPreferences.setFreeAiPoolEnabled(context, false)

        // 1. Primary Route B Gateway succeeds
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"content":"Route B Gateway answer verified."}""")
        )

        val extProviders = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.GROQ to server.url("/groq/").toString()),
            customHttpClient = OkHttpClient()
        )
        val pool = ProviderPool(context = context, externalProviders = extProviders).apply {
            routeBTestOverride = ProviderPool.RouteBTestConfig(
                baseUrl = server.url("/").toString(),
                bearerToken = "test-firebase-id-token",
                httpClient = OkHttpClient()
            )
        }

        val events = pool.streamChat(ChatRequest(prompt = "Explain Route B gateway routing")).toList()
        val complete = events.filterIsInstance<AyraStreamEvent.Complete>().single()
        assertEquals("Route B Gateway answer verified.", complete.response.text)
        assertTrue(complete.response.modelUsed.contains("Route B Gateway"))
        val req1 = server.takeRequest()
        assertEquals("/api/v1/ai/chat", req1.path)
        assertEquals("Bearer test-firebase-id-token", req1.getHeader("Authorization"))

        // 2. When Route B Gateway returns 503, ProviderPool seamlessly falls back to configured Groq BYOK
        AyraPreferences.setGroqKey(context, "gsk-route-b-fallback-test")
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"gateway maintenance"}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"Fallback Groq BYOK answer"}}]}""")
        )

        val fallbackEvents = pool.streamChat(ChatRequest(prompt = "Second question requiring fallback")).toList()
        val fallbackComplete = fallbackEvents.filterIsInstance<AyraStreamEvent.Complete>().single()
        assertEquals("Fallback Groq BYOK answer", fallbackComplete.response.text)
        assertEquals(AiProviderType.GROQ, fallbackComplete.response.provider)
    }

    @Test
    fun syncBackendModelCatalogParsesAndMergesGatewayModels() = runBlocking {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, true)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "models": [
                        {
                          "id": "ayra-gateway-custom-v1",
                          "provider": "gemini",
                          "displayName": "Ayra Gateway Custom V1",
                          "capabilities": ["CHAT", "CODE", "REASONING"],
                          "verificationStatus": "LIVE_VERIFIED",
                          "availableViaGateway": true,
                          "contextTokens": 128000
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val mergedCount = AiModelRegistry.syncBackendModelCatalog(
            context = context,
            backendBaseUrl = server.url("/").toString(),
            httpClient = OkHttpClient(),
            bearerTokenOverride = "catalog-id-token",
            allowUnvalidatedUrlForTests = true
        )
        assertEquals(1, mergedCount)
        val syncedEntry = AiModelRegistry.entries.value.firstOrNull { it.modelId == "ayra-gateway-custom-v1" }
        assertTrue("Expected synced gateway entry in AiModelRegistry", syncedEntry != null)
        assertEquals("Ayra Gateway Custom V1 (Gateway)", syncedEntry?.displayName)
        assertTrue(AyraPreferences.getRemoteModelCatalogJson(context).contains("ayra-gateway-custom-v1"))
    }
}
