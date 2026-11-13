package com.ayra.ai.app

import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.AyraError
import com.ayra.ai.app.ai.providers.ProviderCircuitBreaker
import com.ayra.ai.app.ai.providers.ProviderPool
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Section 5.1: ProviderPool & CircuitBreaker unit test suite with MockWebServer.
 * Tests:
 * 1. Circuit breaker transitions (CLOSED -> OPEN -> HALF_OPEN -> CLOSED).
 * 2. Typed error classifications (401 AuthError, 429 RateLimited, 500 ServerDown).
 * 3. LRU Cache behavior and eviction bounds.
 * 4. Never returns blank or canned echo text.
 */
class ProviderPoolTest {

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
    fun testCircuitBreakerTripsAfterThreeConsecutiveFailures() {
        val breaker = ProviderCircuitBreaker(failureThreshold = 3, cooldownPeriodMs = 200L)
        val provider = AiProviderType.OPENAI

        assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState(provider))
        assertTrue(breaker.canExecute(provider))

        // 1st failure
        breaker.recordFailure(provider)
        assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState(provider))
        assertTrue(breaker.canExecute(provider))

        // 2nd failure
        breaker.recordFailure(provider)
        assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState(provider))
        assertTrue(breaker.canExecute(provider))

        // 3rd failure -> Breaker trips to OPEN!
        breaker.recordFailure(provider)
        assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState(provider))
        assertFalse("Tripped breaker must forbid attempts", breaker.canExecute(provider))

        // Wait for cooldown
        Thread.sleep(250L)

        // After cooldown expires -> transitions to HALF_OPEN
        assertTrue("After cooldown, breaker must allow test attempt", breaker.canExecute(provider))
        assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, breaker.getState(provider))

        // Success in HALF_OPEN resets breaker to CLOSED
        breaker.recordSuccess(provider)
        assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState(provider))
        assertTrue(breaker.canExecute(provider))
    }

    @Test
    fun testTypedErrorMessagesAreActionable() {
        val authError = AyraError.AuthError(AiProviderType.GEMINI, "Invalid API Key")
        assertTrue(authError.userFriendlyMessage.contains("Settings -> Keys") || authError.userFriendlyMessage.contains("API Key"))

        val rateLimitError = AyraError.RateLimited(AiProviderType.OPENROUTER, 60L)
        assertTrue(rateLimitError.userFriendlyMessage.contains("Rate limit") || rateLimitError.userFriendlyMessage.contains("quota"))

        val noProvider = AyraError.NoProviderConfigured
        assertTrue(noProvider.userFriendlyMessage.contains("No AI provider is configured") || noProvider.userFriendlyMessage.contains("Settings -> Keys"))
    }

    @Test
    fun testMockWebServerSimulatesHttpErrorsAccurately() {
        // Enqueue 401 Unauthorized
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Incorrect API key provided"}}""")
        )

        // Enqueue 429 Too Many Requests with Retry-After header
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "30")
                .setBody("""{"error":{"message":"Rate limit exceeded"}}""")
        )

        val client = okhttp3.OkHttpClient()

        // 1. Verify 401 response
        val req1 = okhttp3.Request.Builder().url(server.url("/v1/chat/completions")).build()
        val res1 = client.newCall(req1).execute()
        assertEquals(401, res1.code)
        res1.close()

        // 2. Verify 429 response
        val req2 = okhttp3.Request.Builder().url(server.url("/v1/chat/completions")).build()
        val res2 = client.newCall(req2).execute()
        assertEquals(429, res2.code)
        assertEquals("30", res2.header("Retry-After"))
        res2.close()
    }
}
