package com.ayra.ai.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.AiComparisonEngine
import com.ayra.ai.app.ai.providers.AiModelRegistry
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelHealthStatus
import com.ayra.ai.app.ai.studios.ComfyUiClient
import com.ayra.ai.app.ai.studios.ComfyUiResult
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.CyberTerminalEngine
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CyberTerminalAndRegistryTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        AyraPreferences.setGroqKey(context, "")
        AyraPreferences.setHuggingFaceKey(context, "")
        AyraPreferences.setGitHubModelsKey(context, "")
        AyraPreferences.setComfyUiUrl(context, "")
        AyraPreferences.setCustomEndpointUrl(context, "")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun cyberTerminalPerformsRealDiskIoLiveInterfacesAndCveLookup() {
        val writeOut = CyberTerminalEngine.executeCommand("write audit_note.txt zero_trust_ok", context)
        assertFalse(writeOut.isError)
        assertTrue(writeOut.output.contains("Wrote"))

        val catOut = CyberTerminalEngine.executeCommand("cat audit_note.txt", context)
        assertEquals("zero_trust_ok", catOut.output)

        val lsOut = CyberTerminalEngine.executeCommand("ls", context)
        assertTrue("ls must list real written file", lsOut.output.contains("audit_note.txt"))

        val traversalOut = CyberTerminalEngine.executeCommand("cat ../../etc/passwd", context)
        assertTrue("Path traversal must be blocked", traversalOut.isError || traversalOut.output.contains("escapes sandbox"))

        val rmOut = CyberTerminalEngine.executeCommand("rm audit_note.txt", context)
        assertFalse(rmOut.isError)
        val catAfterRm = CyberTerminalEngine.executeCommand("cat audit_note.txt", context)
        assertTrue(catAfterRm.output.contains("No such file"))

        val ifconfigOut = CyberTerminalEngine.executeCommand("ifconfig", context)
        assertFalse("Must never show hardcoded fake IP 192.168.1.145", ifconfigOut.output.contains("192.168.1.145"))

        val netstatOut = CyberTerminalEngine.executeCommand("netstat", context)
        assertTrue(netstatOut.output.contains("unprivileged") && netstatOut.output.contains("Active Local Network Bindings"))

        // Mock live CVE REST API
        val cveJson = JSONObject().apply {
            put("id", "CVE-2024-3094")
            put("cvss", 10.0)
            put("Published", "2024-03-29T17:15:00")
            put("summary", "Malicious code discovered in upstream xz/liblzma tarballs.")
            put("references", JSONArray().put("https://nvd.nist.gov/vuln/detail/CVE-2024-3094"))
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(cveJson.toString()))

        val cveOut = CyberTerminalEngine.executeCommand(
            rawInput = "cve CVE-2024-3094",
            context = context,
            httpClientOverride = OkHttpClient(),
            cveApiBaseUrl = server.url("/api/cve/").toString()
        )
        assertFalse(cveOut.isError)
        assertTrue(cveOut.output.contains("CVE-2024-3094"))
        assertTrue(cveOut.output.contains("10.0"))
        assertTrue(cveOut.output.contains("xz/liblzma"))

        // Verify HTTP 404 yields honest lookup failure
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val missingCve = CyberTerminalEngine.executeCommand(
            rawInput = "cve CVE-2099-9999",
            context = context,
            httpClientOverride = OkHttpClient(),
            cveApiBaseUrl = server.url("/api/cve/").toString()
        )
        assertTrue(missingCve.output.contains("HTTP 404") || missingCve.output.contains("not found"))
    }

    @Test
    fun modelRegistryDetectsUnconfiguredKeysAndProbesLiveComfyUi() = runBlocking {
        val catalog = AiModelRegistry.buildInitialCatalog(context)
        val groqEntry = catalog.first { it.provider == AiProviderType.GROQ }
        assertEquals(ModelHealthStatus.UNCONFIGURED, groqEntry.healthStatus)

        val groqPing = AiModelRegistry.pingEntry(context, groqEntry)
        assertEquals(ModelHealthStatus.UNCONFIGURED, groqPing.healthStatus)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"system":{"os":"linux"}}"""))
        AyraPreferences.setComfyUiUrl(context, server.url("/").toString().trimEnd('/'))
        val updatedCatalog = AiModelRegistry.buildInitialCatalog(context)
        val comfyEntry = updatedCatalog.first { it.id == "self_hosted_comfyui" }
        val comfyPing = AiModelRegistry.pingEntry(context, comfyEntry)
        assertEquals(ModelHealthStatus.OPERATIONAL, comfyPing.healthStatus)
        assertTrue((comfyPing.latencyMs ?: 0L) >= 1L)
    }

    @Test
    fun comparisonArenaExecutesConfiguredChannelsInParallelAndHandlesPartialFailure() = runBlocking {
        AyraPreferences.setGroqKey(context, "test-groq-token")
        AyraPreferences.setHuggingFaceKey(context, "test-hf-token")
        AyraPreferences.setFreeAiPoolEnabled(context, false)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("groq") -> MockResponse().setResponseCode(200).setBody(
                        JSONObject().put(
                            "choices",
                            JSONArray().put(
                                JSONObject().put("message", JSONObject().put("content", "Groq answer: Kotlin coroutines use structured concurrency."))
                            )
                        ).toString()
                    )
                    path.contains("hf") -> MockResponse().setResponseCode(503).setBody("""{"error":"upstream overloaded"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val customProviders = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(
                AiProviderType.GROQ to server.url("/groq/").toString(),
                AiProviderType.HUGGINGFACE to server.url("/hf/").toString()
            ),
            customHttpClient = OkHttpClient()
        )
        val arena = AiComparisonEngine(
            context = context,
            externalProviders = customProviders,
            firebaseEngine = null
        )

        val report = arena.runComparisonArena(
            prompt = "What is structured concurrency in Kotlin?",
            runDebateVerification = true
        )

        assertTrue("At least 2 candidates should be evaluated", report.results.size >= 2)
        val groqRes = report.results.first { it.providerName.contains("Groq", ignoreCase = true) }
        val hfRes = report.results.first { it.providerName.contains("Hugging Face", ignoreCase = true) }

        assertTrue(groqRes.success)
        assertTrue(groqRes.responseText.contains("structured concurrency"))
        assertTrue(groqRes.latencyMs >= 1L)

        assertFalse("HTTP 503 channel must report failure honestly", hfRes.success)
        assertTrue((hfRes.errorMessage ?: "").isNotBlank())
        assertTrue(report.synthesisSummary.isNotBlank())
    }

    @Test
    fun comfyUiClientSubmitsPromptPollsHistoryAndDownloadsImage(): Unit = runBlocking {
        val fakePngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        )

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/prompt" -> MockResponse().setResponseCode(200).setBody("""{"prompt_id":"job_777"}""")
                    path == "/history/job_777" -> {
                        val hist = JSONObject().put(
                            "job_777",
                            JSONObject().put(
                                "outputs",
                                JSONObject().put(
                                    "9",
                                    JSONObject().put(
                                        "images",
                                        JSONArray().put(
                                            JSONObject()
                                                .put("filename", "ayra_00001.png")
                                                .put("subfolder", "")
                                                .put("type", "output")
                                        )
                                    )
                                )
                            )
                        )
                        MockResponse().setResponseCode(200).setBody(hist.toString())
                    }
                    path.startsWith("/view") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(okio.Buffer().write(fakePngBytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = ComfyUiClient(OkHttpClient())
        val outDir = File(context.filesDir, "comfy_test_out_${System.nanoTime()}").apply { mkdirs() }
        val result = client.generateImage(
            baseUrl = server.url("/").toString(),
            prompt = "cyberpunk neon lotus",
            outputDir = outDir
        )

        assertTrue("Expected ImageSuccess but got $result", result is ComfyUiResult.ImageSuccess)
        val success = result as ComfyUiResult.ImageSuccess
        assertEquals("job_777", success.promptId)
        assertTrue(success.file.exists())
        assertEquals(fakePngBytes.size.toLong(), success.file.length())

        outDir.deleteRecursively()
        Unit
    }

    @Test
    fun cyberTerminalEnforcesAuthorizationGateRateLimitAndAuditLog() {
        CyberTerminalEngine.resetRateLimiterForTesting()
        com.ayra.ai.app.security.SecurityAuditLogger.clearAll(context)

        // Unconfirmed non-loopback probe must be blocked & audited when userAuthorizedTarget = false
        val blocked = CyberTerminalEngine.executeCommand(
            rawInput = "curl -I https://example.com",
            context = context,
            userAuthorizedTarget = false
        )
        assertTrue(blocked.isError)
        assertTrue(blocked.output.contains("authorization confirmation required", ignoreCase = true))

        // Read-only / local sandbox commands don't require target probe confirmation
        assertFalse(CyberTerminalEngine.requiresAuthorizationConfirmation("owasp top10"))
        assertFalse(CyberTerminalEngine.requiresAuthorizationConfirmation("ping localhost"))
        assertTrue(CyberTerminalEngine.requiresAuthorizationConfirmation("curl -I https://example.com"))

        // Rate limiter trips cleanly after MAX_PROBES_PER_MINUTE
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val okProbe = CyberTerminalEngine.executeCommand(
            rawInput = "headers ${server.url("/test").toString()}",
            context = context,
            httpClientOverride = OkHttpClient(),
            userAuthorizedTarget = true,
            enforceRateLimit = true
        )
        assertFalse(okProbe.isError)

        repeat(CyberTerminalEngine.MAX_PROBES_PER_MINUTE - 1) {
            assertTrue(CyberTerminalEngine.checkAndRecordProbeRateLimit())
        }
        val throttled = CyberTerminalEngine.executeCommand(
            rawInput = "headers https://example.com",
            context = context,
            userAuthorizedTarget = true,
            enforceRateLimit = true
        )
        assertTrue(throttled.isError)
        assertTrue(throttled.output.contains("Rate limit exceeded", ignoreCase = true))
        assertTrue(com.ayra.ai.app.security.SecurityAuditLogger.events.value.isNotEmpty())
        CyberTerminalEngine.resetRateLimiterForTesting()
    }
}
