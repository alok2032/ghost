package com.ayra.ai.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.AutonomousKnowledgeEngine
import com.ayra.ai.app.ai.DataAnalysisEngine
import com.ayra.ai.app.ai.Model3dMeshCatalog
import com.ayra.ai.app.ai.code.GeneratedProjectFile
import com.ayra.ai.app.ai.code.ProjectWorkspaceManager
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.AiUsageTracker
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.studios.ComfyUiClient
import com.ayra.ai.app.ai.studios.ComfyUiResult
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.ChatSession
import com.ayra.ai.app.data.InstalledGitHubPlugin
import com.ayra.ai.app.data.StorageHelper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlokUpgradeVerificationTest {

    private lateinit var context: Context
    private var server: MockWebServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AiUsageTracker.clearAll(context)
    }

    @After
    fun tearDown() {
        runCatching { server?.shutdown() }
        AiUsageTracker.clearAll(context)
    }

    @Test
    fun dataAnalysisEngineProfilesCsvAndQuotedCellsAccurately() {
        val csv = """
            "Department, Name",Headcount,Budget
            Engineering,10,1000
            Research,20,2000
            Design,30,3000
            Design,,4000
        """.trimIndent()

        val summary = DataAnalysisEngine.analyzeTabularText(csv, "org_metrics.csv")
        assertEquals(4, summary.rowCount)
        assertEquals(3, summary.columnCount)

        val dept = summary.columns[0]
        assertFalse(dept.isNumeric)
        assertEquals("Design", dept.topCategories.first().first)
        assertEquals(2, dept.topCategories.first().second)

        val headcount = summary.columns[1]
        assertTrue(headcount.isNumeric)
        assertEquals(1, headcount.missingCount)
        assertEquals(20.0, headcount.mean ?: 0.0, 0.01)

        val budget = summary.columns[2]
        assertTrue(budget.isNumeric)
        assertEquals(1000.0, budget.min ?: 0.0, 0.01)
        assertEquals(4000.0, budget.max ?: 0.0, 0.01)
        assertEquals(2500.0, budget.median ?: 0.0, 0.01)

        val md = DataAnalysisEngine.summarizeTabularText(csv, "org_metrics.csv")
        assertTrue(md.contains("Dataset Statistical Profile"))
        assertTrue(md.contains("Budget"))
    }

    @Test
    fun storageHelperExtractsDocxXlsxPptxAndFlateDecodePdfStreams() {
        // 1. Minimal .docx
        val docxOut = ByteArrayOutputStream()
        ZipOutputStream(docxOut).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?><w:document><w:body><w:p><w:r><w:t>Ayra DOCX Verified</w:t></w:r></w:p></w:body></w:document>"""
                    .toByteArray()
            )
            zos.closeEntry()
        }
        val docxText = StorageHelper.extractDocxText(ByteArrayInputStream(docxOut.toByteArray()))
        assertTrue(docxText.contains("Ayra DOCX Verified"))

        // 2. Minimal .xlsx with sharedStrings
        val xlsxOut = ByteArrayOutputStream()
        ZipOutputStream(xlsxOut).use { zos ->
            zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zos.write("""<?xml version="1.0"?><sst><si><t>Metric</t></si><si><t>Value</t></si><si><t>Alpha</t></si></sst>""".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(
                """<?xml version="1.0"?><worksheet><sheetData>
                    <row><c t="s"><v>0</v></c><c t="s"><v>1</v></c></row>
                    <row><c t="s"><v>2</v></c><c><v>42</v></c></row>
                </sheetData></worksheet>""".trimIndent().toByteArray()
            )
            zos.closeEntry()
        }
        val xlsxText = StorageHelper.extractXlsxText(ByteArrayInputStream(xlsxOut.toByteArray()), "kpi.xlsx")
        assertTrue(xlsxText.contains("Dataset Statistical Profile"))
        assertTrue(xlsxText.contains("Alpha | 42"))

        // 3. Minimal .pptx
        val pptxOut = ByteArrayOutputStream()
        ZipOutputStream(pptxOut).use { zos ->
            zos.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
            zos.write("""<?xml version="1.0"?><p:sld><a:t>Slide Title Here</a:t><a:t>Bullet Fact</a:t></p:sld>""".toByteArray())
            zos.closeEntry()
        }
        val pptxText = StorageHelper.extractPptxText(ByteArrayInputStream(pptxOut.toByteArray()))
        assertTrue(pptxText.contains("Slide 1"))
        assertTrue(pptxText.contains("Slide Title Here"))
        assertTrue(pptxText.contains("Bullet Fact"))

        // 4. FlateDecode compressed PDF text stream
        val contentStream = "BT /F1 12 Tf 72 712 Td (Ayra Compressed PDF Stream Works) Tj ET".toByteArray(Charsets.ISO_8859_1)
        val deflater = Deflater()
        deflater.setInput(contentStream)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            compressed.write(buf, 0, count)
        }
        deflater.end()

        val pdfHeader = "%PDF-1.4\n1 0 obj\n<< /Length ${compressed.size()} /Filter /FlateDecode >>\nstream\n".toByteArray(Charsets.ISO_8859_1)
        val pdfFooter = "\nendstream\nendobj\n%%EOF".toByteArray(Charsets.ISO_8859_1)
        val fullPdfBytes = pdfHeader + compressed.toByteArray() + pdfFooter
        val extractedPdf = StorageHelper.extractPdfTextTokensFromBytes(fullPdfBytes)
        assertTrue("FlateDecode PDF stream should decompress cleanly", extractedPdf.contains("Ayra Compressed PDF Stream Works"))
    }

    @Test
    fun safeBackupExcludesAllApiSecretsAndRestoresPreferencesSessionsAndPlugins() {
        AyraPreferences.setGroqKey(context, "gsk_super_secret_do_not_export_123456")
        AyraPreferences.setCerebrasKey(context, "csk_secret_do_not_export_987654")
        AyraPreferences.setThemeMode(context, "light")
        AyraPreferences.setSearchGroundingEnabled(context, false)

        val sessions = listOf(
            ChatSession(
                id = 7L,
                title = "Architecture Session",
                personaMode = "PROFESSIONAL",
                modelTier = "best",
                isPinned = true,
                createdAt = 1000L,
                updatedAt = 2000L,
                messageCount = 1,
                lastMessagePreview = "Review Kotlin AST"
            )
        )
        val messages = listOf(
            ChatMessage(
                id = 11L,
                sessionId = 7L,
                role = "user",
                content = "Review Kotlin AST",
                timestamp = 1500L
            )
        )
        val plugins = listOf(
            InstalledGitHubPlugin(
                repoFullName = "alok2032/sample-skill",
                repoUrl = "https://github.com/alok2032/sample-skill",
                name = "sample-skill",
                owner = "alok2032",
                description = "Sample plugin",
                defaultBranch = "main",
                stars = 42,
                primaryLanguage = "Kotlin",
                pluginType = "SKILL_PLUGIN",
                topicsJson = "[]",
                capabilitiesSummary = "Tests",
                skillPromptContext = "Use clean Kotlin",
                openApiSpecJson = "[]",
                commandsJson = "[]",
                fileTreeJson = "[]",
                isEnabled = true
            )
        )

        val backupJson = StorageHelper.exportSafeWorkspaceBackupJson(context, sessions, messages, plugins)
        val parsed = JSONObject(backupJson)
        assertFalse("Backup JSON must mark containsSecrets=false", parsed.getBoolean("containsSecrets"))
        assertFalse("Backup must NEVER leak Groq key", backupJson.contains("gsk_super_secret_do_not_export_123456"))
        assertFalse("Backup must NEVER leak Cerebras key", backupJson.contains("csk_secret_do_not_export_987654"))

        // Mutate preferences before import to confirm restore reapplies them
        AyraPreferences.setThemeMode(context, "dark")
        AyraPreferences.setSearchGroundingEnabled(context, true)

        val restored = StorageHelper.importSafeWorkspaceBackupJson(context, backupJson)
        assertEquals("light", AyraPreferences.getThemeMode(context))
        assertFalse(AyraPreferences.isSearchGroundingEnabled(context))
        assertEquals(1, restored.sessions.size)
        assertEquals("Architecture Session", restored.sessions.first().title)
        assertEquals(1, restored.messages.size)
        assertEquals("Review Kotlin AST", restored.messages.first().content)
        assertEquals(1, restored.plugins.size)
        assertEquals("alok2032/sample-skill", restored.plugins.first().repoFullName)
    }

    @Test
    fun model3dCatalogProducesAccurateCountsAndValidWavefrontObj() {
        val kinds = listOf("CAR", "SPACESHIP", "ROBOT", "DIAMOND", "SPHERE", "CYBER_CORE")
        kinds.forEach { kind ->
            val mesh = Model3dMeshCatalog.getMesh(kind)
            assertTrue(mesh.vertexCount > 0)
            assertTrue(mesh.edgeCount > 0)
            assertTrue(mesh.faceCount > 0)

            val obj = Model3dMeshCatalog.buildWavefrontObj(kind, "Ayra $kind")
            val vLines = obj.lines().count { it.startsWith("v ") }
            val fLines = obj.lines().count { it.startsWith("f ") }
            assertEquals("Vertex lines in .OBJ must match mesh count", mesh.vertexCount, vLines)
            assertEquals("Face lines in .OBJ must match mesh count", mesh.faceCount, fLines)
        }
    }

    @Test
    fun autonomousKnowledgeEngineBlocksSsrfAndExtractsCleanHtml() {
        val blockedLoopback = AutonomousKnowledgeEngine.fetchReadableWebpage("https://127.0.0.1/admin")
        assertTrue(blockedLoopback.isFailure)
        val blockedLan = AutonomousKnowledgeEngine.fetchReadableWebpage("https://10.0.0.5/secret")
        assertTrue(blockedLan.isFailure)
        val blockedPlainHttp = AutonomousKnowledgeEngine.fetchReadableWebpage("http://example.com/")
        assertTrue(blockedPlainHttp.isFailure)

        val mockServer = MockWebServer()
        server = mockServer
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    <html>
                      <head><title>Ayra Research Article &amp; Notes</title></head>
                      <body>
                        <script>alert('xss');</script>
                        <style>.hidden{display:none;}</style>
                        <h1>Executive Overview</h1>
                        <p>Neural orchestrators combine routing with local tools.</p>
                      </body>
                    </html>
                    """.trimIndent()
                )
        )
        mockServer.start()

        val result = AutonomousKnowledgeEngine.fetchReadableWebpageInternal(
            url = mockServer.url("/article").toString(),
            httpClient = OkHttpClient()
        )
        assertTrue(result.isSuccess)
        val (title, body) = result.getOrThrow()
        assertEquals("Ayra Research Article & Notes", title)
        assertTrue(body.contains("Executive Overview"))
        assertTrue(body.contains("Neural orchestrators combine routing with local tools."))
        assertFalse(body.contains("alert('xss')"))
    }

    @Test
    fun comfyUiClientGeneratesVideoMp4ViaHistoryAndDownload(): Unit = runBlocking {
        val mockServer = MockWebServer()
        server = mockServer
        val fakeMp4Bytes = byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x6D, 0x70, 0x34, 0x32)
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/prompt") -> MockResponse().setResponseCode(200)
                        .setBody("""{"prompt_id":"job_wan_99"}""")
                    path.startsWith("/history/job_wan_99") -> MockResponse().setResponseCode(200)
                        .setBody(
                            """{
                              "job_wan_99": {
                                "outputs": {
                                  "9": {
                                    "gifs": [
                                      {"filename": "wan_clip.mp4", "subfolder": "", "type": "output"}
                                    ]
                                  }
                                }
                              }
                            }""".trimIndent()
                        )
                    path.startsWith("/view") -> MockResponse().setResponseCode(200)
                        .setBody(Buffer().write(fakeMp4Bytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        mockServer.start()

        val outDir = File(context.cacheDir, "test_comfy_videos_${System.nanoTime()}")
        val client = ComfyUiClient()
        val workflow = client.buildVideoGenerationWorkflow("Neon skyline timelapse", width = 832, height = 480)
        assertEquals("VHS_VideoCombine", workflow.getJSONObject("9").getString("class_type"))

        val result = client.generateVideo(
            baseUrl = mockServer.url("/").toString(),
            prompt = "Neon skyline timelapse",
            outputDir = outDir
        )
        assertTrue("Expected ComfyUiResult.VideoSuccess but got $result", result is ComfyUiResult.VideoSuccess)
        val success = result as ComfyUiResult.VideoSuccess
        assertTrue(success.file.exists())
        assertEquals(fakeMp4Bytes.size.toLong(), success.file.length())
        outDir.deleteRecursively()
    }

    @Test
    fun projectWorkspaceManagerListsLoadsEditsAndRezipsSavedProjects() {
        val files = listOf(
            GeneratedProjectFile("Main.kt", "fun main() { println(\"v1\") }"),
            GeneratedProjectFile("README.md", "# Project V1")
        )
        val (rootDir, initialZip) = ProjectWorkspaceManager.writeProjectToDiskAndZip(context, "test_edit_proj", files)
        assertTrue(initialZip.exists())

        val listed = ProjectWorkspaceManager.listSavedProjects(context)
        assertTrue(listed.any { it.rootDir.absolutePath == rootDir.absolutePath && it.fileCount == 2 })

        val loaded = ProjectWorkspaceManager.loadProjectFiles(rootDir)
        assertEquals(2, loaded.size)
        assertTrue(ProjectWorkspaceManager.saveEditedProjectFile(rootDir, "Main.kt", "fun main() { println(\"v2\") }"))

        val updatedZip = ProjectWorkspaceManager.createProjectZip(context, "test_edit_proj", rootDir)
        assertTrue(updatedZip.exists())

        var mainContentAfterRezip = ""
        ZipInputStream(updatedZip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "Main.kt") {
                    mainContentAfterRezip = zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        assertTrue(mainContentAfterRezip.contains("println(\"v2\")"))

        rootDir.deleteRecursively()
        initialZip.delete()
        updatedZip.delete()
    }

    @Test
    fun externalAiProvidersWhisperMultipartAndUsageTelemetryWork(): Unit = runBlocking {
        val mockServer = MockWebServer()
        server = mockServer
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hello Ayra Whisper test"}"""))
        mockServer.start()

        val base = mockServer.url("/openai/v1/").toString()
        val tempAudio = File(context.cacheDir, "sample_${System.nanoTime()}.wav").apply {
            writeBytes(ByteArray(128) { 1 })
        }

        AyraPreferences.setGroqKey(context, "gsk_test_whisper_key_123456")
        val providers = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.GROQ to base)
        )
        assertTrue(providers.getBaseUrlForProvider(AiProviderType.CEREBRAS).contains("api.cerebras.ai"))
        assertTrue(providers.getBaseUrlForProvider(AiProviderType.SAMBANOVA).contains("api.sambanova.ai"))

        val transcript = providers.transcribeAudioOpenAiCompatible(tempAudio, "Test audio")
        assertEquals("Hello Ayra Whisper test", transcript)

        val recordedReq = mockServer.takeRequest()
        assertEquals("POST", recordedReq.method)
        assertTrue(recordedReq.path.orEmpty().endsWith("audio/transcriptions"))

        AiUsageTracker.recordCall(context, AiProviderType.CEREBRAS, true, 120L)
        AiUsageTracker.recordCall(context, AiProviderType.SAMBANOVA, false, 450L)
        val metrics = AiUsageTracker.getAllMetrics(context)
        val groqMetric = metrics.first { it.providerId == AiProviderType.GROQ.id }
        assertEquals(1, groqMetric.successCount)
        val cerebrasMetric = metrics.first { it.providerId == AiProviderType.CEREBRAS.id }
        assertEquals(1, cerebrasMetric.totalRequests)
        assertEquals(100, cerebrasMetric.successRatePercent)
        assertEquals(120L, cerebrasMetric.avgLatencyMs)

        tempAudio.delete()
    }
}
