package com.ayra.ai.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.AiMediaPromptSynthesizer
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.Model3dMeshCatalog
import com.ayra.ai.app.ai.ecc.EnterpriseCognitiveEngine
import com.ayra.ai.app.ai.higgsfield.OpenHiggsfieldEngine
import com.ayra.ai.app.ai.omniroute.OmniRouteEngine
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.AiRoutingMode
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.studios.ComfyUiClient
import com.ayra.ai.app.ai.studios.ComfyUiResult
import com.ayra.ai.app.data.AyraPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiMasterAuditAndUpgradeTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testFirebaseModelFallbackChainCascadesCorrectly() {
        val fb = FirebaseAiEngine(context)
        val argonChain = fb.modelFallbackChain(FirebaseAiEngine.MODEL_PRO_REASONING)
        assertEquals(
            listOf(
                FirebaseAiEngine.MODEL_PRO_REASONING,
                FirebaseAiEngine.MODEL_FLASH,
                FirebaseAiEngine.MODEL_FAST_LITE
            ),
            argonChain
        )

        val flashChain = fb.modelFallbackChain(FirebaseAiEngine.MODEL_FLASH)
        assertEquals(
            listOf(FirebaseAiEngine.MODEL_FLASH, FirebaseAiEngine.MODEL_FAST_LITE),
            flashChain
        )

        val liteChain = fb.modelFallbackChain(FirebaseAiEngine.MODEL_FAST_LITE)
        assertEquals(listOf(FirebaseAiEngine.MODEL_FAST_LITE, FirebaseAiEngine.MODEL_FLASH), liteChain)
    }

    @Test
    fun testOptimalRoutingSelectsGroqCerebrasSambaNovaHuggingFace() {
        val providers = ExternalAiProviders(context)

        AyraPreferences.setGroqKey(context, "gsk_test_routing_key")
        val fastRoute = providers.getOptimalModelForRoutingMode(AiRoutingMode.FAST)
        assertEquals(AiProviderType.GROQ, fastRoute.first)

        AyraPreferences.setGroqKey(context, "")
        AyraPreferences.setCerebrasKey(context, "csk_test_routing_key")
        val cerebrasFast = providers.getOptimalModelForRoutingMode(AiRoutingMode.FAST)
        assertEquals(AiProviderType.CEREBRAS, cerebrasFast.first)
        AyraPreferences.setCerebrasKey(context, "")

        AyraPreferences.setSambaNovaKey(context, "sambanova_test_key")
        val bestRoute = providers.getOptimalModelForRoutingMode(AiRoutingMode.BEST_QUALITY)
        assertEquals(AiProviderType.SAMBANOVA, bestRoute.first)
        AyraPreferences.setSambaNovaKey(context, "")

        AyraPreferences.setHuggingFaceKey(context, "hf_test_routing_key")
        val openWeightsRoute = providers.getOptimalModelForRoutingMode(AiRoutingMode.OPEN_WEIGHTS)
        assertEquals(AiProviderType.HUGGINGFACE, openWeightsRoute.first)
        AyraPreferences.setHuggingFaceKey(context, "")
    }

    @Test
    fun testProviderPoolIsTrivialGreetingDoesNotBypassTechnicalQueries() {
        val pool = ProviderPool(context)

        assertTrue(pool.isTrivialGreeting("hi"))
        assertTrue(pool.isTrivialGreeting("Hello Ayra!"))
        assertTrue(pool.isTrivialGreeting("হ্যালো"))
        assertTrue(pool.isTrivialGreeting("kemon acho"))

        assertFalse(pool.isTrivialGreeting("solve 2+2"))
        assertFalse(pool.isTrivialGreeting("kotlin bug"))
        assertFalse(pool.isTrivialGreeting("how?"))
        assertFalse(pool.isTrivialGreeting("ki koro?"))
        assertFalse(pool.isTrivialGreeting("write java"))
    }

    @Test
    fun testEnterpriseCognitiveEngineExtractsAcronymsProperNounsAndDomain() {
        val ecc = EnterpriseCognitiveEngine(context)
        val ctx = ecc.extractCognitiveContext(
            "Analyze CVE-2024-3094 in \"OpenSSH Backdoor\" using JWT and Neural Transformer"
        )
        assertEquals("Cybersecurity", ctx.domain)
        assertTrue(ctx.detectedEntities.any { it.contains("CVE-2024-3094") })
        assertTrue(ctx.detectedEntities.any { it.contains("JWT") })
        assertTrue(ctx.detectedEntities.any { it.contains("OpenSSH Backdoor") })
    }

    @Test
    fun testOmniRouteDetermineRouteHandlesAllSpecialistTiers() {
        val omni = OmniRouteEngine(context)
        assertEquals("DEEPSEEK_HARNESS", omni.determineRoute("normal query", "deepseek_r1").selectedEngine)
        assertEquals("LLAMA_3_3", omni.determineRoute("normal query", "llama_3_3").selectedEngine)
        assertEquals("OPEN_WEIGHTS", omni.determineRoute("normal query", "open_weights").selectedEngine)
        assertEquals("OPEN_WEIGHTS", omni.determineRoute("normal query", "local").selectedEngine)
        assertEquals("SECURITY_RESEARCH", omni.determineRoute("normal query", "security_research").selectedEngine)
        assertEquals("ECC", omni.determineRoute("normal query", "ecc").selectedEngine)
        assertEquals("HIGGSFIELD", omni.determineRoute("normal query", "higgsfield").selectedEngine)
        assertEquals("SUPERPOWERS", omni.determineRoute("normal query", "superpowers").selectedEngine)
    }

    @Test
    fun testComfyUiUploadAndImg2ImgWorkflow() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"name":"uploaded_ref.png"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"prompt_id":"job_123"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"job_123":{"outputs":{"8":{"images":[{"filename":"edited_01.png","subfolder":"","type":"output"}]}}}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("PNG_BINARY_BYTES"))

        val client = ComfyUiClient(OkHttpClient())
        val outDir = File(context.cacheDir, "comfy_i2i_test").apply { mkdirs() }
        val result = client.generateImageToImage(
            baseUrl = server.url("/").toString().trimEnd('/'),
            prompt = "Cyberpunk neon lighting",
            referenceImageBytes = byteArrayOf(1, 2, 3, 4),
            outputDir = outDir
        )

        assertTrue(result is ComfyUiResult.ImageSuccess)
        val success = result as ComfyUiResult.ImageSuccess
        assertEquals("job_123", success.promptId)
        assertTrue(success.file.exists())
        assertEquals("PNG_BINARY_BYTES", success.file.readText())
    }

    @Test
    fun testAiMediaPromptSynthesizerExpandsViaOpenAiEndpoint() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"<think>planning lens</think>\nPhotorealistic bengal tiger emerging from mist, 85mm f/1.4, golden hour backlight"}}]}"""
            )
        )
        val expanded = AiMediaPromptSynthesizer.expandViaOpenAiEndpoint(
            subject = "royal bengal tiger",
            endpointUrl = server.url("/v1/chat/completions").toString(),
            apiKey = "test-key",
            modelId = "llama-3.3-70b-versatile",
            type = "IMAGE",
            clientOverride = OkHttpClient()
        )
        assertTrue(expanded.contains("Photorealistic bengal tiger"))
        assertFalse(expanded.contains("<think>"))
    }

    @Test
    fun testModel3dMeshCatalogParsesValidObjAndRejectsJunk() {
        val validObj = """
            v 0.0 1.0 0.0
            v -1.0 -1.0 1.0
            v 1.0 -1.0 1.0
            v 0.0 -1.0 -1.0
            f 1 2 3
            f 1 3 4
            f 1 4 2
            f 2 4 3
        """.trimIndent()

        val parsed = Model3dMeshCatalog.parseWavefrontObj(validObj, "CUSTOM_AI")
        assertNotNull(parsed)
        assertEquals(4, parsed!!.vertexCount)
        assertEquals(4, parsed.faceCount)
        assertTrue(parsed.edgeCount >= 6)

        val roundTrip = Model3dMeshCatalog.buildWavefrontObj(parsed, "Tetrahedron")
        assertTrue(roundTrip.contains("o Tetrahedron"))
        assertTrue(roundTrip.contains("f 1 2 3"))

        assertNull(Model3dMeshCatalog.parseWavefrontObj("not a 3d model at all"))
    }

    @Test
    fun testOpenHiggsfieldAiDirectorFallsBackCleanlyOffline() = runBlocking {
        val engine = OpenHiggsfieldEngine(context)
        val payload = engine.synthesizeVideoCraftWithAi("Aerial drone night neon skyline", "16:9")
        assertTrue(payload.cameraMotion.contains("Aerial", ignoreCase = true))
        assertTrue(payload.lighting.contains("Neon", ignoreCase = true))
        assertTrue(payload.jsonPayload.contains("\"aspectRatio\":\"16:9\""))
    }

    @Test
    fun testFirebaseSelectModelUsesFreeFlashByDefaultAndFastLiteForFast() {
        val fb = FirebaseAiEngine(context)
        assertEquals(FirebaseAiEngine.MODEL_FLASH, fb.selectModelForTask("auto", "solve math research algorithm", preferReasoning = true))
        assertEquals(FirebaseAiEngine.MODEL_FLASH, fb.selectModelForTask("best", "prove theorem", preferReasoning = true))
        assertEquals(FirebaseAiEngine.MODEL_FAST_LITE, fb.selectModelForTask("fast", "hi", preferReasoning = false))
        assertEquals(FirebaseAiEngine.MODEL_PRO_REASONING, fb.selectModelForTask("gemini_pro", "complex architecture", preferReasoning = true))
    }

    @Test
    fun testMainActivityLaunchesDirectlyWithoutForcedGoogleSignIn() {
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        assertFalse("MainActivity must not finish or block without Google login", activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
