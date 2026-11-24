package com.ayra.ai.app.security

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.ui.components.AyraDiagnosticsSection
import com.ayra.ai.app.ui.theme.AyraTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkConnectivityAndDiagnosticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NetworkConnectivityManager.resetForTests()
        AyraDiagnosticsEngine.clearForTesting()
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, true)
        AyraPreferences.setBackendGatewayUrl(context, "")
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        NetworkConnectivityManager.resetForTests()
        AyraDiagnosticsEngine.clearForTesting()
        AyraPreferences.setBackendGatewayUrl(context, "")
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        runCatching { server.shutdown() }
    }

    @Test
    fun connectivityStatesDistinguishWifiCellularCaptivePortalAndOffline() {
        val wifi = NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
            isConnected = true,
            hasInternet = true,
            isValidated = true,
            isCaptivePortal = false,
            transport = NetworkTransportType.WIFI,
            nowMs = 1_000L
        )
        assertEquals(ConnectionHealthStatus.CONNECTED_VALIDATED, wifi.status)
        assertEquals(NetworkTransportType.WIFI, wifi.transportType)
        assertEquals(1_000L, wifi.lastSuccessfulConnectionMs)
        assertEquals(NetworkFailureCategory.NONE, wifi.lastFailureCategory)

        val cellularUnvalidated = NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
            isConnected = true,
            hasInternet = true,
            isValidated = false,
            isCaptivePortal = false,
            transport = NetworkTransportType.CELLULAR,
            nowMs = 2_000L
        )
        assertEquals(ConnectionHealthStatus.CONNECTED_UNVALIDATED, cellularUnvalidated.status)
        assertEquals(NetworkTransportType.CELLULAR, cellularUnvalidated.transportType)
        assertEquals(NetworkFailureCategory.NO_VALIDATED_INTERNET, cellularUnvalidated.lastFailureCategory)
        assertEquals(1_000L, cellularUnvalidated.lastSuccessfulConnectionMs)

        val captive = NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
            isConnected = true,
            hasInternet = true,
            isValidated = false,
            isCaptivePortal = true,
            transport = NetworkTransportType.WIFI,
            nowMs = 3_000L
        )
        assertEquals(ConnectionHealthStatus.CAPTIVE_PORTAL, captive.status)
        assertEquals(NetworkFailureCategory.CAPTIVE_PORTAL, captive.lastFailureCategory)

        val offline = NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
            isConnected = false,
            hasInternet = false,
            isValidated = false,
            isCaptivePortal = false,
            transport = NetworkTransportType.NONE,
            nowMs = 4_000L
        )
        assertEquals(ConnectionHealthStatus.OFFLINE, offline.status)
        assertEquals(NetworkFailureCategory.NO_NETWORK, offline.lastFailureCategory)
    }

    @Test
    fun disconnectThenValidatedRestoreTriggersBoundedRecoveryOnceWithinCooldown() = runBlocking {
        val listenerCalls = AtomicInteger(0)
        NetworkConnectivityManager.registerRecoveryListener {
            listenerCalls.incrementAndGet()
        }

        // Start offline
        NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
            isConnected = false,
            hasInternet = false,
            isValidated = false,
            isCaptivePortal = false,
            transport = NetworkTransportType.NONE,
            nowMs = 10_000L,
            recoveryDebounceMs = 0L,
            recoveryCooldownMs = 60_000L
        )

        // Restore validated Wi-Fi -> should trigger recovery once
        NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
            isConnected = true,
            hasInternet = true,
            isValidated = true,
            isCaptivePortal = false,
            transport = NetworkTransportType.WIFI,
            nowMs = 20_000L,
            recoveryDebounceMs = 0L,
            recoveryCooldownMs = 60_000L
        )

        // Wait briefly for IO coroutine
        val deadline = System.currentTimeMillis() + 2_000L
        while (listenerCalls.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        assertEquals(1, listenerCalls.get())
        assertEquals(1, NetworkConnectivityManager.recoveryCountFlow.value)

        // Rapid flap inside cooldown window must NOT trigger unbounded loop
        NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
            isConnected = false,
            hasInternet = false,
            isValidated = false,
            isCaptivePortal = false,
            transport = NetworkTransportType.NONE,
            nowMs = 21_000L,
            recoveryDebounceMs = 0L,
            recoveryCooldownMs = 60_000L
        )
        NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
            isConnected = true,
            hasInternet = true,
            isValidated = true,
            isCaptivePortal = false,
            transport = NetworkTransportType.WIFI,
            nowMs = 22_000L,
            recoveryDebounceMs = 0L,
            recoveryCooldownMs = 60_000L
        )
        Thread.sleep(100L)
        assertEquals(1, listenerCalls.get())
    }

    @Test
    fun classifyNetworkFailureMapsDnsTlsCertTimeoutAuthRateLimitOutageAndSsrf() {
        assertEquals(
            NetworkFailureCategory.DNS_FAILURE,
            NetworkSecurityHelper.classifyNetworkFailure(UnknownHostException("Unable to resolve host")).category
        )
        assertEquals(
            NetworkFailureCategory.TLS_HANDSHAKE_FAILURE,
            NetworkSecurityHelper.classifyNetworkFailure(SSLHandshakeException("Handshake failed")).category
        )
        assertEquals(
            NetworkFailureCategory.SSL_CERTIFICATE_FAILURE,
            NetworkSecurityHelper.classifyNetworkFailure(SSLPeerUnverifiedException("Hostname mismatch")).category
        )
        assertEquals(
            NetworkFailureCategory.SSL_CERTIFICATE_FAILURE,
            NetworkSecurityHelper.classifyNetworkFailure(CertificateException("Untrusted cert")).category
        )
        assertEquals(
            NetworkFailureCategory.TIMEOUT,
            NetworkSecurityHelper.classifyNetworkFailure(SocketTimeoutException("Read timed out")).category
        )
        assertEquals(
            NetworkFailureCategory.CONNECTION_REFUSED,
            NetworkSecurityHelper.classifyNetworkFailure(ConnectException("Failed to connect")).category
        )
        assertEquals(
            NetworkFailureCategory.SSRF_BLOCKED,
            NetworkSecurityHelper.classifyNetworkFailure(SecurityException("Blocked SSRF IP")).category
        )
        assertEquals(
            NetworkFailureCategory.AUTH_FAILURE,
            NetworkSecurityHelper.classifyNetworkFailure(httpCode = 401).category
        )
        assertEquals(
            NetworkFailureCategory.AUTH_FAILURE,
            NetworkSecurityHelper.classifyNetworkFailure(httpCode = 403).category
        )
        assertEquals(
            NetworkFailureCategory.RATE_LIMITED,
            NetworkSecurityHelper.classifyNetworkFailure(httpCode = 429).category
        )
        assertEquals(
            NetworkFailureCategory.CLIENT_ERROR,
            NetworkSecurityHelper.classifyNetworkFailure(httpCode = 404).category
        )
        assertEquals(
            NetworkFailureCategory.BACKEND_UNAVAILABLE,
            NetworkSecurityHelper.classifyNetworkFailure(httpCode = 503).category
        )
    }

    @Test
    fun executeWithSafeRetryRetriesTransientErrorsWithBoundedBackoffAndNeverRetriesAuthOrSslOrSsrf() = runTest {
        val sleeps = mutableListOf<Long>()
        var attempts = 0
        val value = NetworkSecurityHelper.executeWithSafeRetry(
            maxAttempts = 3,
            initialDelayMs = 100L,
            maxDelayMs = 300L,
            jitterFactor = 0.0,
            sleeper = { sleeps.add(it) }
        ) { attempt ->
            attempts = attempt
            if (attempt < 3) throw RetryableHttpException(503, "Service Unavailable")
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(3, attempts)
        assertEquals(listOf(100L, 200L), sleeps)

        // Never retry HTTP 401
        var authAttempts = 0
        val authErr = runCatching {
            NetworkSecurityHelper.executeWithSafeRetry(maxAttempts = 3, sleeper = {}) { attempt ->
                authAttempts = attempt
                throw NonRetryableHttpException(401, "Unauthorized")
            }
        }.exceptionOrNull()
        assertTrue(authErr is NonRetryableHttpException)
        assertEquals(1, authAttempts)

        // Never retry HTTP 404
        var notFoundAttempts = 0
        runCatching {
            NetworkSecurityHelper.executeWithSafeRetry(maxAttempts = 3, sleeper = {}) { attempt ->
                notFoundAttempts = attempt
                throw NonRetryableHttpException(404, "Not Found")
            }
        }
        assertEquals(1, notFoundAttempts)

        // Never retry TLS handshake or SSRF SecurityException
        var sslAttempts = 0
        runCatching {
            NetworkSecurityHelper.executeWithSafeRetry(maxAttempts = 3, sleeper = {}) { attempt ->
                sslAttempts = attempt
                throw SSLHandshakeException("Bad cipher")
            }
        }
        assertEquals(1, sslAttempts)

        var ssrfAttempts = 0
        runCatching {
            NetworkSecurityHelper.executeWithSafeRetry(maxAttempts = 3, sleeper = {}) { attempt ->
                ssrfAttempts = attempt
                throw SecurityException("SSRF blocked")
            }
        }
        assertEquals(1, ssrfAttempts)

        // Never catch/retry CancellationException
        var cancelAttempts = 0
        val cancelErr = runCatching {
            NetworkSecurityHelper.executeWithSafeRetry(maxAttempts = 3, sleeper = {}) { attempt ->
                cancelAttempts = attempt
                throw CancellationException("Cancelled")
            }
        }.exceptionOrNull()
        assertTrue(cancelErr is CancellationException)
        assertEquals(1, cancelAttempts)
    }

    @Test
    fun preferIpv4OrderAndNormalizeOpenAiModelsUrlWorkCorrectly() {
        val v6 = InetAddress.getByAddress("example.com", ByteArray(16).apply { this[15] = 1 }) as Inet6Address
        val v4 = InetAddress.getByAddress("example.com", byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)) as Inet4Address
        val ordered = NetworkSecurityHelper.preferIpv4Order(listOf(v6, v4))
        assertTrue(ordered.first() is Inet4Address)
        assertTrue(ordered.last() is Inet6Address)

        assertEquals(
            "http://10.0.2.2:8000/v1/models",
            NetworkSecurityHelper.normalizeOpenAiModelsUrl("http://10.0.2.2:8000/v1")
        )
        assertEquals(
            "http://10.0.2.2:8000/v1/models",
            NetworkSecurityHelper.normalizeOpenAiModelsUrl("http://10.0.2.2:8000/v1/")
        )
        assertEquals(
            "http://10.0.2.2:8000/v1/models",
            NetworkSecurityHelper.normalizeOpenAiModelsUrl("http://10.0.2.2:8000")
        )
        assertEquals(
            "https://proxy.example.com/v1/models",
            NetworkSecurityHelper.normalizeOpenAiModelsUrl("https://proxy.example.com/v1/chat/completions")
        )
    }

    @Test
    fun runAllConnectionTestsReportsHonestPassAndFailsOnDnsOrHttp503() = runBlocking {
        // 1. Success pass against local MockWebServer + configured backend /health
        server.enqueue(MockResponse().setResponseCode(204)) // HTTPS probe
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")) // Backend /health

        AyraPreferences.setBackendGatewayUrl(context, server.url("/").toString().trimEnd('/'))
        NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
            isConnected = true,
            hasInternet = true,
            isValidated = true,
            isCaptivePortal = false,
            transport = NetworkTransportType.WIFI,
            nowMs = 1_000L
        )

        val fakePublicV4 = InetAddress.getByAddress("generativelanguage.googleapis.com", byteArrayOf(142.toByte(), 250.toByte(), 190.toByte(), 10))
        val passReport = NetworkConnectivityManager.runAllConnectionTests(
            context = context,
            customDnsHost = "generativelanguage.googleapis.com",
            httpsProbeUrl = server.url("/generate_204").toString(),
            httpClientOverride = OkHttpClient(),
            dnsResolver = { arrayOf(fakePublicV4) },
            providerProbeOverride = { provider ->
                SubsystemDiagnosticResult(
                    name = provider.displayName,
                    state = DiagnosticCheckState.PASS,
                    latencyMs = 12L,
                    detail = "Verified live response (12ms)"
                )
            }
        )

        assertEquals(DiagnosticCheckState.PASS, passReport.internetCheck?.state)
        assertEquals(DiagnosticCheckState.PASS, passReport.dnsCheck?.state)
        assertEquals(DiagnosticCheckState.PASS, passReport.httpsCheck?.state)
        assertEquals(DiagnosticCheckState.PASS, passReport.backendCheck?.state)
        assertNotNull(passReport.lastSuccessMs)

        // 2. Failure scenario: DNS unknown host + HTTP 503 after retry
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        val failReport = NetworkConnectivityManager.runAllConnectionTests(
            context = context,
            customDnsHost = "unreachable.invalid",
            httpsProbeUrl = server.url("/generate_204").toString(),
            httpClientOverride = OkHttpClient(),
            dnsResolver = { throw UnknownHostException("Simulated DNS failure") },
            providerProbeOverride = { provider ->
                SubsystemDiagnosticResult(
                    name = provider.displayName,
                    state = DiagnosticCheckState.FAIL,
                    latencyMs = 5L,
                    detail = "Auth failed",
                    category = NetworkFailureCategory.AUTH_FAILURE
                )
            }
        )

        assertEquals(DiagnosticCheckState.FAIL, failReport.dnsCheck?.state)
        assertEquals(NetworkFailureCategory.DNS_FAILURE, failReport.dnsCheck?.category)
        assertEquals(DiagnosticCheckState.FAIL, failReport.httpsCheck?.state)
        assertEquals(NetworkFailureCategory.BACKEND_UNAVAILABLE, failReport.httpsCheck?.category)
        assertEquals(DiagnosticCheckState.FAIL, failReport.backendCheck?.state)
        assertEquals(NetworkFailureCategory.AUTH_FAILURE, failReport.backendCheck?.category)
    }

    @Test
    fun ayraDiagnosticsSectionUiDisplaysLiveStatusAndRunsRealTestAllConnectionsFlow() {
        NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
            isConnected = false,
            hasInternet = false,
            isValidated = false,
            isCaptivePortal = false,
            transport = NetworkTransportType.NONE,
            nowMs = 100L
        )

        composeRule.setContent {
            AyraTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    AyraDiagnosticsSection(
                        context = context,
                        refreshOnMount = false,
                        testSuiteRunner = { ctx ->
                            NetworkConnectivityManager.evaluateCapabilitiesSnapshotForTests(
                                isConnected = true,
                                hasInternet = true,
                                isValidated = true,
                                isCaptivePortal = false,
                                transport = NetworkTransportType.WIFI,
                                nowMs = 500L
                            )
                            server.enqueue(MockResponse().setResponseCode(204))
                            val fakePublicV4 = InetAddress.getByAddress(
                                "generativelanguage.googleapis.com",
                                byteArrayOf(142.toByte(), 250.toByte(), 190.toByte(), 10)
                            )
                            NetworkConnectivityManager.runAllConnectionTests(
                                context = ctx,
                                customDnsHost = "generativelanguage.googleapis.com",
                                httpsProbeUrl = server.url("/generate_204").toString(),
                                httpClientOverride = OkHttpClient(),
                                dnsResolver = { arrayOf(fakePublicV4) },
                                providerProbeOverride = { p ->
                                    SubsystemDiagnosticResult(
                                        name = p.displayName,
                                        state = DiagnosticCheckState.PASS,
                                        latencyMs = 15L,
                                        detail = "Verified mock provider"
                                    )
                                }
                            )
                        }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("network_diagnostics_dashboard").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostics_untested_hint").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Offline • No active network").performScrollTo().assertIsDisplayed()

        // Tap Test All Connections and verify real results populate UI cleanly
        composeRule.onNodeWithTag("test_all_connections_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            val report = NetworkConnectivityManager.diagnosticsFlow.value
            report.lastRunMs != null && !report.isRunning
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("diagnostics_results_list").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Wi-Fi • Validated Internet").performScrollTo().assertIsDisplayed()
    }
}
