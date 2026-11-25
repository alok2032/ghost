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
import com.ayra.ai.app.security.AyraKeyManager
import com.ayra.ai.app.security.BackendGatewaySource
import com.ayra.ai.app.security.EncryptedPreferences
import com.ayra.ai.app.security.SecureAiGatewayClient
import com.ayra.ai.app.security.SecureKeyStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RouteBGatewayPoolTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SecureAiGatewayClient.invalidateEndpointCache()
        SecureKeyStore.clearAllSecrets(context)
        AyraPreferences.clearAllPreferences(context)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        SecureAiGatewayClient.invalidateEndpointCache()
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

    @Test
    fun initializeResolvesFromRemoteConfigAndPersistsInEncryptedVaultWithHttpsIntegrity() = runBlocking {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, true)

        val publicIpResolver: (String) -> Array<InetAddress> = { host ->
            arrayOf(InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte())))
        }

        // 1. Unconfigured returns null (no hardcoded fallback)
        val emptyInit = SecureAiGatewayClient.initialize(context, dnsResolver = publicIpResolver)
        assertFalse(emptyInit.initialized)
        assertEquals(BackendGatewaySource.NONE, emptyInit.source)
        assertNull(SecureAiGatewayClient.resolveVaultBackendUrl(context))
        assertNull(SecureAiGatewayClient.getValidatedBackendGatewayUrl(context))

        // 2. Dynamic fetch via secure remote configuration validates HTTPS and persists into SecureKeyStore
        val remoteInit = SecureAiGatewayClient.initialize(
            context = context,
            dnsResolver = publicIpResolver,
            remoteConfigFetcher = {
                """{"backendGatewayUrl":"https://gateway.ayra-ai.example.com/"}"""
            }
        )
        assertTrue("Expected remote config init to succeed: ${remoteInit.failureReason}", remoteInit.initialized)
        assertEquals(BackendGatewaySource.REMOTE_CONFIG_ENDPOINT, remoteInit.source)
        assertEquals("https://gateway.ayra-ai.example.com", remoteInit.endpointUrl)
        assertEquals("https://gateway.ayra-ai.example.com", SecureKeyStore.getSecret(context, SecureKeyStore.KEY_BACKEND_GATEWAY_URL))
        assertEquals("https://gateway.ayra-ai.example.com", SecureAiGatewayClient.getValidatedBackendGatewayUrl(context))

        // 3. Subsequent offline/restart initialization resolves from Encrypted Vault
        SecureAiGatewayClient.invalidateEndpointCache()
        val vaultInit = SecureAiGatewayClient.initialize(context, dnsResolver = publicIpResolver)
        assertTrue(vaultInit.initialized)
        assertEquals(BackendGatewaySource.ENCRYPTED_VAULT, vaultInit.source)
        assertEquals("https://gateway.ayra-ai.example.com", vaultInit.endpointUrl)
    }

    @Test
    fun initializeMigratesLegacySensitivePrefsToSecureKeyStoreAndRejectsUnsafeUrls() = runBlocking {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        val publicIpResolver: (String) -> Array<InetAddress> = { host ->
            arrayOf(InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte())))
        }
        val privateIpResolver: (String) -> Array<InetAddress> = { host ->
            arrayOf(InetAddress.getByAddress(host, byteArrayOf(10, 0, 0, 12)))
        }

        // Seed legacy ayra_sensitive_user_prefs_v2 key and verify automatic migration into SecureKeyStore
        val legacySensitive = EncryptedPreferences(
            context = context,
            prefsFileName = "ayra_sensitive_user_prefs_v2",
            keyAlias = AyraKeyManager.ALIAS_PREFS_KEK
        )
        legacySensitive.putString("sec_backend_gateway_url", "https://migrated-gw.ayra.example.com/")
        val migratedInit = SecureAiGatewayClient.initialize(context, dnsResolver = publicIpResolver)
        assertTrue(migratedInit.initialized)
        assertEquals(BackendGatewaySource.ENCRYPTED_VAULT, migratedInit.source)
        assertEquals("https://migrated-gw.ayra.example.com", migratedInit.endpointUrl)
        assertEquals("", legacySensitive.getString("sec_backend_gateway_url", ""))
        assertEquals("https://migrated-gw.ayra.example.com", SecureKeyStore.getSecret(context, SecureKeyStore.KEY_BACKEND_GATEWAY_URL))

        // Clear and test rejecting insecure / SSRF URLs from remote config (vault must stay untouched)
        AyraPreferences.setBackendGatewayUrl(context, "")
        val httpRejected = SecureAiGatewayClient.initialize(
            context = context,
            dnsResolver = publicIpResolver,
            remoteConfigFetcher = { """{"backendGatewayUrl":"http://insecure-gateway.example.com"}""" }
        )
        assertFalse(httpRejected.initialized)
        assertNull(httpRejected.endpointUrl)
        assertEquals("", SecureKeyStore.getSecret(context, SecureKeyStore.KEY_BACKEND_GATEWAY_URL))

        val ssrfRejected = SecureAiGatewayClient.initialize(
            context = context,
            dnsResolver = privateIpResolver,
            remoteConfigFetcher = { """{"backendGatewayUrl":"https://internal-ssrf.example.com"}""" }
        )
        assertFalse(ssrfRejected.initialized)
        assertEquals(false, ssrfRejected.validationResult?.allowed)
        assertNull(SecureAiGatewayClient.getValidatedBackendGatewayUrl(context))

        // Even if an attacker tampers a vaulted URL whose DNS resolves privately, initialize fails closed
        AyraPreferences.setBackendGatewayUrl(context, "https://tampered-ssrf.example.com")
        val tamperedInit = SecureAiGatewayClient.initialize(context, dnsResolver = privateIpResolver)
        assertFalse(tamperedInit.initialized)
        assertNull(SecureAiGatewayClient.getValidatedBackendGatewayUrl(context))
    }

    @Test
    fun initializeResolvesRemoteCatalogJsonAndMockWebServerEndpoint() = runBlocking {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        val publicIpResolver: (String) -> Array<InetAddress> = { host ->
            arrayOf(InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte())))
        }

        // Cached remote catalog JSON can supply backendGatewayUrl when no standalone remote config endpoint is set
        AyraPreferences.setRemoteModelCatalogJson(
            context,
            """{"backendGatewayUrl":"https://catalog-gateway.ayra.example.com","models":[]}"""
        )
        val catalogInit = SecureAiGatewayClient.initialize(context, dnsResolver = publicIpResolver)
        assertTrue(catalogInit.initialized)
        assertEquals(BackendGatewaySource.REMOTE_CONFIG_CATALOG, catalogInit.source)
        assertEquals("https://catalog-gateway.ayra.example.com", catalogInit.endpointUrl)

        // MockWebServer HTTP endpoint test (with allowUnvalidatedUrlForTests = true for local mock transport)
        AyraPreferences.setBackendGatewayUrl(context, "")
        AyraPreferences.setRemoteModelCatalogJson(context, "")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"backendGatewayUrl":"${server.url("/").toString().trimEnd('/')}"}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val liveMockInit = SecureAiGatewayClient.initialize(
            context = context,
            remoteConfigUrlOverride = server.url("/remote-config.json").toString(),
            httpClient = OkHttpClient(),
            probeHealthOnInit = true,
            allowUnvalidatedUrlForTests = true
        )
        assertTrue("Expected MockWebServer remote config + /health probe to succeed: ${liveMockInit.failureReason}", liveMockInit.initialized)
        assertEquals(BackendGatewaySource.REMOTE_CONFIG_ENDPOINT, liveMockInit.source)
        assertTrue((liveMockInit.healthLatencyMs ?: 0L) >= 1L)
    }
}
