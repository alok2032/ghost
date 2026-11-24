package com.ayra.ai.app

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.AiMediaPromptSynthesizer
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.UnifiedAiEngine
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelCatalog
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.UrlValidationResult
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
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
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ZeroSetupAndMediaIntegrityTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AyraPreferences.clearAllPreferences(context)
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:547647504815:android:testapp")
                    .setApiKey("test-firebase-client-placeholder-key")
                    .setProjectId("test-ayra-project")
                    .build()
            )
        }
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        AyraPreferences.clearAllPreferences(context)
    }

    @Test
    fun builtInFirebaseAiIsAvailableWithoutThirdPartyConsentWhileThirdPartyStaysGated() {
        // Fresh preferences: zero-setup activates built-in + keyless multi-AI out of the box
        assertTrue(AyraPreferences.hasExternalAiConsent(context))
        assertFalse(AyraPreferences.isLocalOnlyAiModeEnabled(context))

        // Built-in Firebase AI Logic works out of the box when FirebaseApp is initialized
        assertTrue(
            "FirebaseAiEngine.isAvailable must return true on fresh launch",
            FirebaseAiEngine.isAvailable(context)
        )

        // Revoking third-party consent gates third-party channels while Firebase AI stays active
        AyraPreferences.setExternalAiConsent(context, false)
        val ext = ExternalAiProviders(context)
        assertTrue(
            "Third-party external channels must stay empty when consent is disabled",
            ext.getActiveChannels().isEmpty()
        )
        assertTrue(FirebaseAiEngine.isAvailable(context))

        // Local-Only Privacy Mode still disables built-in Firebase AI Logic
        AyraPreferences.setLocalOnlyAiModeEnabled(context, true)
        assertFalse(
            "Local-Only Privacy Mode must disable Firebase AI Logic",
            FirebaseAiEngine.isAvailable(context)
        )
    }

    @Test
    fun downloadAndVerifyImageAcceptsRealPngAndRejectsHtmlOrCorruptBytes() {
        val generatedDir = File(context.filesDir, "generated_images")
        generatedDir.deleteRecursively()

        val validPngBytes = ByteArrayOutputStream().use { out ->
            val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }

        val allowTestLoopback = { _: String ->
            UrlValidationResult(allowed = true)
        }
        val testClient = OkHttpClient()

        // 1. Valid raster PNG succeeds and saves into filesDir/generated_images
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(validPngBytes))
        )
        val okRes = AiMediaPromptSynthesizer.downloadAndVerifyImageToLocalFile(
            context = context,
            imageUrl = server.url("/ok.png").toString(),
            httpClient = testClient,
            urlValidator = allowTestLoopback
        )
        assertTrue("Valid PNG must succeed: ${okRes.exceptionOrNull()?.message}", okRes.isSuccess)
        val savedFile = okRes.getOrThrow()
        assertTrue(savedFile.exists())
        assertTrue(savedFile.parentFile?.name == "generated_images")

        // 2. text/html error page is rejected and never left on disk
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>502 Bad Gateway</body></html>")
        )
        val htmlRes = AiMediaPromptSynthesizer.downloadAndVerifyImageToLocalFile(
            context = context,
            imageUrl = server.url("/error.html").toString(),
            httpClient = testClient,
            urlValidator = allowTestLoopback
        )
        assertTrue("HTML response masquerading as image must fail", htmlRes.isFailure)

        // 3. Corrupt non-image bytes claiming image/png are rejected and deleted
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody("not-an-image-payload-bytes")
        )
        val corruptRes = AiMediaPromptSynthesizer.downloadAndVerifyImageToLocalFile(
            context = context,
            imageUrl = server.url("/corrupt.png").toString(),
            httpClient = testClient,
            urlValidator = allowTestLoopback
        )
        assertTrue("Corrupt bytes failing BitmapFactory decode must fail", corruptRes.isFailure)
        val leftoverFiles = generatedDir.listFiles()?.toList().orEmpty()
        assertEquals("Only the 1 valid PNG should remain on disk", 1, leftoverFiles.size)
    }

    @Test
    fun videoPromptWithoutRenderedMp4EmitsStoryboardCraftType() = runBlocking {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, true)
        val engine = UnifiedAiEngine(context)
        val res = engine.generateUnifiedResponse(
            prompt = "Create a cinematic video of deep space nebula",
            personaMode = "PROFESSIONAL",
            modelTier = "auto"
        )
        assertEquals("STORYBOARD", res.craftType)
        assertNotNull(res.craftPayload)
    }

    @Test
    fun modelCatalogAndExecutorsStayAlignedWithCentralizedConstants() {
        val imageEntry = ModelCatalog.findEntry(AiProviderType.GEMINI, FirebaseAiEngine.MODEL_IMAGE)
        assertNotNull("Image model entry must exist in ModelCatalog for FirebaseAiEngine.MODEL_IMAGE", imageEntry)
        assertEquals("gemini-3.1-flash-image", imageEntry?.id)
        val removedTts = ModelCatalog.findEntry(AiProviderType.GEMINI, "gemini-3.1-flash-tts-preview")
        assertEquals(null, removedTts)
    }
}
