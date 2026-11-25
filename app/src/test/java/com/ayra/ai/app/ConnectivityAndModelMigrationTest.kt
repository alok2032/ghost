package com.ayra.ai.app

import android.content.Context
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.FirebaseSelfTestOutcome
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ChatTurn
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelCatalog
import com.ayra.ai.app.ai.providers.ProviderAnthropicAndFreeTransport
import com.ayra.ai.app.ai.providers.ProviderCircuitBreaker
import com.ayra.ai.app.ai.providers.ProviderError
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.AyraError
import com.ayra.ai.app.ai.providers.AyraStreamEvent
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.BlockedByPolicyException
import com.ayra.ai.app.security.NetworkConnectivityManager
import com.ayra.ai.app.security.NetworkSecurityHelper
import com.ayra.ai.app.security.NetworkTransportType
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectivityAndModelMigrationTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        AyraPreferences.clearAllPreferences(context)
        server = MockWebServer()
        server.start()
        NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
            isConnected = true,
            hasInternet = true,
            isValidated = true,
            isCaptivePortal = false,
            transport = NetworkTransportType.WIFI
        )
    }

    @After
    fun tearDown() {
        NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
            isConnected = true,
            hasInternet = true,
            isValidated = true,
            isCaptivePortal = false,
            transport = NetworkTransportType.WIFI
        )
        AyraPreferences.clearAllPreferences(context)
        server.shutdown()
    }

    @Test
    fun canonicalModelIdsDelegateToModelCatalogSingleSourceOfTruth() {
        assertEquals("gemini-3.6-flash", ModelCatalog.GEMINI_FLASH_MODEL)
        assertEquals("gemini-3.5-flash", ModelCatalog.GEMINI_FLASH_35_MODEL)
        assertEquals("gemini-3.5-flash-lite", ModelCatalog.GEMINI_FAST_LITE_MODEL)
        assertEquals("gemini-3.1-pro-preview", ModelCatalog.GEMINI_PRO_MODEL)
        assertEquals("gemini-3.1-flash-image", ModelCatalog.GEMINI_IMAGE_MODEL)
        assertEquals("qwen/qwen3.8-27b", ModelCatalog.GROQ_QWEN_MODEL)

        assertEquals(ModelCatalog.GEMINI_FLASH_MODEL, FirebaseAiEngine.MODEL_FLASH)
        assertEquals(ModelCatalog.GEMINI_FLASH_35_MODEL, FirebaseAiEngine.MODEL_FLASH_35)
        assertEquals(ModelCatalog.GEMINI_FAST_LITE_MODEL, FirebaseAiEngine.MODEL_FAST_LITE)
        assertEquals(ModelCatalog.GEMINI_PRO_MODEL, FirebaseAiEngine.MODEL_PRO_REASONING)
        assertEquals(ModelCatalog.GEMINI_IMAGE_MODEL, FirebaseAiEngine.MODEL_IMAGE)
        assertEquals(ModelCatalog.GROQ_QWEN_MODEL, ExternalAiProviders.GROQ_QWEN_MODEL)
    }

    @Test
    fun http404ModelNotFoundDoesNotTripProviderCircuitBreaker() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model missing"}}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model missing"}}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model missing"}}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model missing"}}"""))

        val providers = ExternalAiProviders(
            context = null,
            baseUrlOverrides = mapOf(AiProviderType.GROQ to server.url("/openai/v1/").toString()),
            customHttpClient = OkHttpClient(),
            channelKeyOverrides = mapOf("groq_qwen3_8_27b" to "gsk_test_404")
        )
        val pool = ProviderPool(context = null, externalProviders = providers)
        var capturedError: AyraError? = null
        pool.streamChat(
            ChatRequest(
                prompt = "Hello",
                mode = ProviderPoolMode.FAST,
                preferredProvider = AiProviderType.GROQ,
                preferredModelId = ModelCatalog.GROQ_QWEN_MODEL
            )
        ).collect { ev ->
            if (ev is AyraStreamEvent.Error) capturedError = ev.error
        }

        assertTrue(capturedError is AyraError.ProviderDown)
        assertTrue(
            "HTTP 404 ModelNotFound must NEVER open channel or provider circuit breaker",
            providers.circuitBreaker.canExecuteChannel("groq_qwen3_8_27b")
        )
        assertTrue(providers.circuitBreaker.canExecute(AiProviderType.GROQ))
    }

    @Test
    fun blockedByPolicyAndValidatedDnsErrorDoNotCollapseToNoNetwork() = runBlocking {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, true)
        AyraPreferences.setGroqKey(context, "gsk_policy_test")

        val policyFailingClient = OkHttpClient.Builder()
            .addInterceptor {
                throw BlockedByPolicyException("FAKE_IP_VPN", "Blocked Fake-IP 198.18.0.2")
            }
            .build()
        val policyProviders = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.GROQ to "https://api.groq.com/openai/v1/"),
            customHttpClient = policyFailingClient
        )
        val policyPool = ProviderPool(context = context, externalProviders = policyProviders)
        var policyError: AyraError? = null
        policyPool.streamChat(
            ChatRequest(
                prompt = "Hi",
                mode = ProviderPoolMode.FAST,
                preferredProvider = AiProviderType.GROQ
            )
        ).collect { ev ->
            if (ev is AyraStreamEvent.Error) policyError = ev.error
        }
        assertTrue("Expected SecurityPolicyBlocked, got $policyError", policyError is AyraError.SecurityPolicyBlocked)
        assertFalse(policyProviders.circuitBreaker.canExecuteChannel("groq_qwen3_8_27b").not())

        // While OS connectivity is validated online, UnknownHostException must surface DnsError rather than NoNetwork
        val dnsFailingClient = OkHttpClient.Builder()
            .addInterceptor {
                throw UnknownHostException("Unable to resolve host \"api.groq.com\": No address associated with hostname")
            }
            .build()
        val dnsProviders = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.GROQ to "https://api.groq.com/openai/v1/"),
            customHttpClient = dnsFailingClient
        )
        val dnsPool = ProviderPool(context = context, externalProviders = dnsProviders)
        var dnsError: AyraError? = null
        dnsPool.streamChat(
            ChatRequest(
                prompt = "Hi",
                mode = ProviderPoolMode.FAST,
                preferredProvider = AiProviderType.GROQ
            )
        ).collect { ev ->
            if (ev is AyraStreamEvent.Error) dnsError = ev.error
        }
        assertTrue("Expected DnsError when OS internet is validated, got $dnsError", dnsError is AyraError.DnsError)
    }

    @Test
    fun pollinationsUsesV1ChatCompletionsBearerAuthAndTypedUnauthorized() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Missing API key"}"""))
        val overrides = mapOf(AiProviderType.POLLINATIONS_FREE to server.url("/v1/chat/completions").toString())
        val unauthErr = runCatching {
            ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                httpClient = OkHttpClient(),
                baseUrlOverrides = overrides,
                prompt = "Hello",
                systemPrompt = "Concise",
                preferredModel = ModelCatalog.POLLINATIONS_OPENAI_MODEL,
                conversationTurns = listOf(ChatTurn("user", "Hello")),
                apiKey = ""
            )
        }.exceptionOrNull()
        assertTrue("Expected ProviderError.Unauthorized on 401, got $unauthErr", unauthErr is ProviderError.Unauthorized)
        assertTrue(unauthErr?.message.orEmpty().contains("enter.pollinations.ai"))

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"Pollinations OK"}}]}"""
            )
        )
        val okReply = ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
            httpClient = OkHttpClient(),
            baseUrlOverrides = overrides,
            prompt = "Hello",
            systemPrompt = "Concise",
            preferredModel = ModelCatalog.POLLINATIONS_OPENAI_MODEL,
            conversationTurns = listOf(ChatTurn("user", "Hello")),
            apiKey = "pk_test_123"
        )
        assertEquals("Pollinations OK", okReply)

        server.takeRequest()
        val authReq = server.takeRequest()
        assertTrue(authReq.path.orEmpty().startsWith("/v1/chat/completions"))
        assertEquals("Bearer pk_test_123", authReq.getHeader("Authorization"))
    }

    @Test
    fun validatePublicHttpsUrlAsyncAndStartupSelfTestOfflineWorkCleanly() = runBlocking {
        val valid = NetworkSecurityHelper.validatePublicHttpsUrlAsync("https://api.example.com/v1") {
            arrayOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte())))
        }
        assertTrue(valid.allowed)

        NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
            isConnected = false,
            hasInternet = false,
            isValidated = false,
            isCaptivePortal = false,
            transport = NetworkTransportType.NONE
        )
        val selfTest = FirebaseAiEngine(context).runStartupSelfTest(context)
        assertEquals(FirebaseSelfTestOutcome.OFFLINE, selfTest.outcome)
    }

    @Test
    fun isForbiddenInternalHostLiteralUsesParsedInetAddressWithoutDomainPrefixFalsePositives() {
        // Legitimate hostnames starting with "fc"/"fd"/"ff" must NOT be falsely blocked
        assertFalse(NetworkSecurityHelper.isForbiddenInternalHostLiteral("fcm.googleapis.com"))
        assertFalse(NetworkSecurityHelper.isForbiddenInternalHostLiteral("fd-example.com"))
        assertFalse(NetworkSecurityHelper.isForbiddenInternalHostLiteral("ffmpeg.org"))
        assertFalse(NetworkSecurityHelper.isForbiddenInternalHostLiteral("fc-cloud.org"))
        assertFalse(NetworkSecurityHelper.isForbiddenInternalHostLiteral("2001:4860:4860::8888"))

        // Internal, ULA, link-local (including zone ID), multicast, documentation, IPv4-mapped & Fake-IP must be blocked
        assertTrue(NetworkSecurityHelper.isForbiddenInternalHostLiteral("[fd00::1]"))
        assertTrue(NetworkSecurityHelper.isForbiddenInternalHostLiteral("[fc00::1]"))
        assertTrue(NetworkSecurityHelper.isForbiddenInternalHostLiteral("[fe80::1%eth0]"))
        assertTrue(NetworkSecurityHelper.isForbiddenInternalHostLiteral("[fec0::1]"))
        assertTrue(NetworkSecurityHelper.isForbiddenInternalHostLiteral("[ff02::1]"))
        assertTrue(NetworkSecurityHelper.isForbiddenInternalHostLiteral("[2001:db8::1]"))
        assertTrue(NetworkSecurityHelper.isForbiddenInternalHostLiteral("[::ffff:127.0.0.1]"))
        assertTrue(NetworkSecurityHelper.isForbiddenInternalHostLiteral("198.18.0.1"))
        assertTrue(NetworkSecurityHelper.isForbiddenInternalHostLiteral("169.254.169.254"))
        assertTrue(NetworkSecurityHelper.isForbiddenInternalHostLiteral("192.168.1.1"))
        assertTrue(NetworkSecurityHelper.isForbiddenInternalHostLiteral("malformed:colon:host"))
    }

    @Test
    fun validateLocalAiEndpointUrlAsyncEnforcesLoopbackAndLanPolicyOffMainThread() = runBlocking {
        assertTrue(NetworkSecurityHelper.validateLocalAiEndpointUrlAsync(context, "http://127.0.0.1:11434").allowed)
        assertFalse(NetworkSecurityHelper.validateLocalAiEndpointUrlAsync(context, "https://192.168.1.40:11434").allowed)
        AyraPreferences.setAllowLanEndpointsEnabled(context, true)
        assertTrue(NetworkSecurityHelper.validateLocalAiEndpointUrlAsync(context, "https://192.168.1.40:11434").allowed)
    }

    @Test
    fun circuitBreakerHalfOpenAllowsSingleProbeAndReopensOnFailure() {
        val breaker = ProviderCircuitBreaker(failureThreshold = 2, cooldownPeriodMs = 20L)
        breaker.recordFailure(AiProviderType.GROQ)
        breaker.recordFailure(AiProviderType.GROQ)
        assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState(AiProviderType.GROQ))
        assertFalse(breaker.canExecute(AiProviderType.GROQ))

        Thread.sleep(35L)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val granted = AtomicInteger(0)

        repeat(threads) {
            pool.execute {
                ready.countDown()
                go.await()
                if (breaker.canExecute(AiProviderType.GROQ)) {
                    granted.incrementAndGet()
                }
                done.countDown()
            }
        }
        ready.await()
        go.countDown()
        done.await()
        pool.shutdownNow()

        assertEquals("Only a single HALF_OPEN probe may be admitted", 1, granted.get())
        assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, breaker.getState(AiProviderType.GROQ))

        breaker.recordFailure(AiProviderType.GROQ)
        assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState(AiProviderType.GROQ))
    }

    @Test
    fun geminiFlashFallbackChainAvoidsBlazeOnlyProModel() {
        val chain = FirebaseAiEngine(context).modelFallbackChain(FirebaseAiEngine.MODEL_FLASH)
        assertEquals(
            listOf(
                FirebaseAiEngine.MODEL_FLASH,
                FirebaseAiEngine.MODEL_FAST_LITE,
                FirebaseAiEngine.MODEL_FLASH_35
            ),
            chain
        )
        assertFalse(chain.contains(FirebaseAiEngine.MODEL_PRO_REASONING))
    }
}
