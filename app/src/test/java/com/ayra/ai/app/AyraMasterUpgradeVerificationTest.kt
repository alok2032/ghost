package com.ayra.ai.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.AutonomousKnowledgeEngine
import com.ayra.ai.app.ai.AyraIntentClassifier
import com.ayra.ai.app.ai.AyraToolIntent
import com.ayra.ai.app.ai.PromptQualityAnalyzer
import com.ayra.ai.app.ai.agents.AgentDomain
import com.ayra.ai.app.ai.agents.AyraAgentOrchestrator
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.CustomAssistantEntity
import com.ayra.ai.app.data.ProjectEntity
import com.ayra.ai.app.data.PromptTemplateEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
@Config(sdk = [34])
class AyraMasterUpgradeVerificationTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AyraPreferences.clearAllPreferences(context)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        AyraPreferences.clearAllPreferences(context)
    }

    @Test
    fun migration6To7PreservesExistingDataAndSupportsWorkspaceCrudAndVersioning() = runBlocking {
        val dbName = "test_migration_6_7_${System.nanoTime()}.db"
        try {
            val helper = object : SQLiteOpenHelper(context, dbName, null, 6) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `chat_sessions` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `title` TEXT NOT NULL,
                            `personaMode` TEXT NOT NULL,
                            `modelTier` TEXT NOT NULL,
                            `isPinned` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            `messageCount` INTEGER NOT NULL,
                            `lastMessagePreview` TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `chat_messages` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `sessionId` INTEGER NOT NULL DEFAULT 1,
                            `role` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `attachedImagePath` TEXT,
                            `craftType` TEXT,
                            `craftPayload` TEXT,
                            `actionCommand` TEXT,
                            FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId` ON `chat_messages` (`sessionId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_timestamp` ON `chat_messages` (`timestamp`)")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `scheduled_tasks` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `title` TEXT NOT NULL,
                            `targetApp` TEXT NOT NULL,
                            `recipient` TEXT NOT NULL,
                            `messageText` TEXT NOT NULL,
                            `scheduledEpochMillis` INTEGER NOT NULL,
                            `formattedTime` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `taskType` TEXT NOT NULL,
                            `phoneNumber` TEXT NOT NULL,
                            `speakOnCall` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `installed_github_plugins` (
                            `repoFullName` TEXT NOT NULL,
                            `repoUrl` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `owner` TEXT NOT NULL,
                            `description` TEXT NOT NULL,
                            `defaultBranch` TEXT NOT NULL,
                            `stars` INTEGER NOT NULL,
                            `primaryLanguage` TEXT NOT NULL,
                            `pluginType` TEXT NOT NULL,
                            `topicsJson` TEXT NOT NULL,
                            `capabilitiesSummary` TEXT NOT NULL,
                            `skillPromptContext` TEXT NOT NULL,
                            `openApiSpecJson` TEXT NOT NULL,
                            `commandsJson` TEXT NOT NULL,
                            `fileTreeJson` TEXT NOT NULL,
                            `isEnabled` INTEGER NOT NULL,
                            `installedAt` INTEGER NOT NULL,
                            `lastSyncedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`repoFullName`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `knowledge_documents` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `title` TEXT NOT NULL,
                            `mimeType` TEXT NOT NULL,
                            `charCount` INTEGER NOT NULL,
                            `chunkCount` INTEGER NOT NULL,
                            `sourceUri` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `document_chunks` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `documentId` INTEGER NOT NULL,
                            `documentTitle` TEXT NOT NULL,
                            `chunkIndex` INTEGER NOT NULL,
                            `content` TEXT NOT NULL,
                            `tokenEstimate` INTEGER NOT NULL,
                            FOREIGN KEY(`documentId`) REFERENCES `knowledge_documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_chunks_documentId` ON `document_chunks` (`documentId`)")
                }

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }

            val v6 = helper.writableDatabase
            v6.execSQL(
                "INSERT INTO `chat_sessions` (`id`, `title`, `personaMode`, `modelTier`, `isPinned`, `createdAt`, `updatedAt`, `messageCount`, `lastMessagePreview`) " +
                    "VALUES (1, 'Legacy Session', 'AI_ASSISTANT', 'auto', 0, 1000, 1000, 1, 'Hello v6')"
            )
            v6.execSQL(
                "INSERT INTO `chat_messages` (`id`, `sessionId`, `role`, `content`, `timestamp`) " +
                    "VALUES (1, 1, 'user', 'Hello v6', 1000)"
            )
            v6.execSQL(
                "INSERT INTO `knowledge_documents` (`id`, `title`, `mimeType`, `charCount`, `chunkCount`, `sourceUri`, `createdAt`) " +
                    "VALUES (1, 'v6_notes.txt', 'text/plain', 50, 1, 'file://notes', 1000)"
            )
            v6.close()
            helper.close()

            val upgraded = Room.databaseBuilder(context, ChatDatabase::class.java, dbName)
                .addMigrations(ChatDatabase.MIGRATION_4_5, ChatDatabase.MIGRATION_5_6, ChatDatabase.MIGRATION_6_7, ChatDatabase.MIGRATION_7_8, ChatDatabase.MIGRATION_8_9)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys=ON;")
                    }
                })
                .allowMainThreadQueries()
                .build()

            // Verify v6 rows survived intact
            val legacySession = upgraded.chatSessionDao().getSessionById(1L)
            assertNotNull(legacySession)
            assertEquals("Legacy Session", legacySession?.title)
            val legacyDocs = upgraded.knowledgeDocumentDao().observeAllDocuments().first()
            assertEquals(1, legacyDocs.size)
            assertEquals("v6_notes.txt", legacyDocs[0].title)

            // Verify Project & CustomAssistant CRUD
            val dao = upgraded.ayraWorkspaceDao()
            val projectId = dao.insertProject(
                ProjectEntity(
                    name = "Kotlin Architecture",
                    description = "Room + Compose project",
                    customInstructions = "Always prefer immutable state and coroutines",
                    defaultModelTier = "best",
                    memoryNotes = "Target Android 14"
                )
            )
            val assistantId = dao.insertAssistant(
                CustomAssistantEntity(
                    name = "Security Auditor",
                    description = "Audits Android & API security",
                    iconEmoji = "🛡️",
                    systemPrompt = "Perform strict threat modeling and OWASP MASVS checks.",
                    personaStyle = "CRITICAL",
                    defaultModelTier = "best",
                    thinkingMode = "reasoning"
                )
            )
            assertEquals("Kotlin Architecture", dao.getProjectById(projectId)?.name)
            assertEquals("Security Auditor", dao.getAssistantById(assistantId)?.name)

            // Verify PromptTemplate + PromptVersion lifecycle & cascade deletion
            val v1Prompt = PromptQualityAnalyzer.compileStructuredPrompt(
                role = "Senior Systems Architect",
                objective = "Design {{service_name}} API",
                context = "High-throughput Kotlin microservice",
                inputData = "{{spec}}",
                requirements = "Include retry policies and schema",
                constraints = "Never hardcode secrets; must handle timeouts",
                outputFormat = "Markdown sections and JSON schema",
                validationCriteria = "Verify idempotency"
            )
            val tplId = dao.savePromptWithVersion(
                PromptTemplateEntity(
                    title = "API Blueprint",
                    category = "Coding",
                    compiledPrompt = v1Prompt,
                    tagsCsv = "kotlin,api"
                ),
                changeNote = "Initial draft"
            )
            val savedV1 = dao.getPromptTemplateById(tplId)!!
            assertEquals(1, savedV1.versionNumber)

            dao.savePromptWithVersion(
                savedV1.copy(compiledPrompt = "$v1Prompt\n\n### Constraints\nUse circuit breakers."),
                changeNote = "Added resilience"
            )
            val versionsAfterUpdate = dao.getPromptVersionsOnce(tplId)
            assertEquals(2, versionsAfterUpdate.size)
            assertEquals(2, versionsAfterUpdate[0].versionNumber)
            assertEquals(1, versionsAfterUpdate[1].versionNumber)

            dao.deletePromptTemplateById(tplId)
            assertTrue("Versions should cascade delete when template is deleted", dao.getPromptVersionsOnce(tplId).isEmpty())

            upgraded.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun promptQualityAnalyzerDetectsVariablesAndScoresStructureAccurately() {
        val weak = PromptQualityAnalyzer.analyze("write code")
        assertTrue("Weak prompt should score below 50 (was ${weak.overallScore})", weak.overallScore < 50)
        assertTrue(weak.suggestions.isNotEmpty())

        val compiled = PromptQualityAnalyzer.compileStructuredPrompt(
            role = "Act as a Principal Android Security Engineer",
            objective = "Audit {{target_module}} for cryptographic flaws and leaked credentials",
            context = "Production Jetpack Compose app targeting API 35 for enterprise banking",
            inputData = "Kotlin repository files for {{target_module}}",
            requirements = "Check Keystore usage, TLS pinning, and Room SQLCipher configuration step-by-step",
            constraints = "Do not fabricate CVE numbers; must verify every finding against supplied code; avoid generic advice",
            outputFormat = "Markdown table with Severity, File, Evidence, and Kotlin fix in code blocks",
            validationCriteria = "Verify zero false positives and check edge cases for biometric lock"
        )
        val strong = PromptQualityAnalyzer.analyze(compiled)
        assertTrue("Structured prompt should score >= 85 (was ${strong.overallScore})", strong.overallScore >= 85)
        assertTrue(strong.variablesDetected.contains("target_module"))
    }

    @Test
    fun wikiIntentClassifierAndStructuredMultiLayerWikiHandleLivePayloadsAndCitations() = runBlocking {
        assertEquals(AyraToolIntent.WIKI, AyraIntentClassifier.classifyIntent("/wiki quantum computing"))
        assertEquals(AyraToolIntent.WIKI, AyraIntentClassifier.classifyIntent("/deepwiki CRISPR-Cas9"))
        assertEquals(AyraToolIntent.WIKI, AyraIntentClassifier.classifyIntent("/darkwiki Tor Onionoo relays"))
        assertEquals(AyraToolIntent.WIKI, AyraIntentClassifier.classifyIntent("wiki: Black hole"))
        assertEquals(AyraToolIntent.WIKI, AyraIntentClassifier.classifyIntent("উইকি: বাংলা সাহিত্য"))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/wiki/") -> MockResponse().setResponseCode(200).setBody(
                        """{"query":{"pages":{"7":{"title":"CVE-2024-3094","extract":"XZ Utils backdoor CVE-2024-3094 was discovered in compression tooling used across Linux distributions before release."}}}}"""
                    )
                    path.startsWith("/wikidata/") -> MockResponse().setResponseCode(200).setBody(
                        """{"search":[{"id":"Q125255","label":"XZ Utils","description":"free lossless data compression software"}]}"""
                    )
                    path.startsWith("/ddg/") -> MockResponse().setResponseCode(200).setBody(
                        """{"Heading":"Supply chain attack","AbstractText":"A software supply chain attack compromises build or distribution channels.","AbstractURL":"https://en.wikipedia.org/wiki/Supply_chain_attack"}"""
                    )
                    path.startsWith("/openalex/") -> MockResponse().setResponseCode(200).setBody(
                        """{"results":[{"title":"Analyzing Onion Overlay Security","publication_year":2024,"doi":"10.1145/example","abstract_inverted_index":{"Tor":[0],"relay":[1],"telemetry":[2],"analysis.":[3]}}]}"""
                    )
                    path.startsWith("/pubmed/esearch.fcgi") -> MockResponse().setResponseCode(200).setBody(
                        """{"esearchresult":{"idlist":["38123456"]}}"""
                    )
                    path.startsWith("/pubmed/esummary.fcgi") -> MockResponse().setResponseCode(200).setBody(
                        """{"result":{"38123456":{"title":"Clinical Genomic Sequencing Study","pubdate":"2024 Jan","source":"Nature Medicine"}}}"""
                    )
                    path.startsWith("/cve/cve/") -> MockResponse().setResponseCode(200).setBody(
                        """{"summary":"Malicious code in liblzma build artifacts in XZ Utils 5.6.0.","cvss":10.0}"""
                    )
                    path.startsWith("/onionoo/") -> MockResponse().setResponseCode(200).setBody(
                        """{"relays_published":"2026-11-16 12:00:00","relays":[{"n":"AyraGuardRelay","r":true}]}"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val result = AutonomousKnowledgeEngine.queryMultiLayerWikiStructured(
            prompt = "CVE-2024-3094 vaccine clinical onion analysis",
            isBengali = false,
            httpClient = OkHttpClient(),
            wikipediaBaseUrl = server.url("/wiki/").toString(),
            ddgBaseUrl = server.url("/ddg/").toString(),
            openAlexBaseUrl = server.url("/openalex/").toString(),
            arxivBaseUrl = server.url("/arxiv/").toString(),
            wikidataBaseUrl = server.url("/wikidata/").toString(),
            pubmedBaseUrl = server.url("/pubmed/").toString(),
            cveBaseUrl = server.url("/cve/").toString(),
            onionooBaseUrl = server.url("/onionoo/").toString(),
            forceAllLayers = true
        )

        assertTrue(result.isFromLiveNetwork)
        assertTrue(result.surfaceWiki.contains("XZ Utils backdoor"))
        assertTrue(result.wikidataEntities.contains("Q125255"))
        assertTrue(result.deepAcademic.contains("Tor relay telemetry analysis."))
        assertTrue(result.pubmedArticles.contains("Clinical Genomic Sequencing Study"))
        assertTrue(result.cveIntel.contains("CVSS Score:** 10.0"))
        assertTrue(result.liveTorNetwork.contains("AyraGuardRelay"))
        assertTrue(result.citations.any { it.url.contains("nvd.nist.gov/vuln/detail/CVE-2024-3094") })
        assertTrue(result.citations.any { it.url.contains("wikidata.org/wiki/Q125255") })
        assertTrue(result.citations.any { it.url.contains("pubmed.ncbi.nlm.nih.gov/38123456") })
        assertTrue(result.toFormattedMarkdown().contains("[VERIFIED LIVE SOURCES]"))
    }

    @Test
    fun preferencesAndOrchestratorSupportThemesPersonalizationAndThinkingModes() {
        AyraPreferences.setThemeMode(context, "high_contrast")
        assertEquals("high_contrast", AyraPreferences.themeModeFlow.value)

        AyraPreferences.setAccentPreset(context, "purple")
        assertEquals("purple", AyraPreferences.getAccentPreset(context))

        AyraPreferences.setCustomAccentHex(context, "not-a-hex")
        assertEquals("#00F0FF", AyraPreferences.getCustomAccentHex(context))
        AyraPreferences.setCustomAccentHex(context, "#FF5500")
        assertEquals("#FF5500", AyraPreferences.getCustomAccentHex(context))

        AyraPreferences.setFontScale(context, 2.5f)
        assertEquals(1.35f, AyraPreferences.getFontScale(context), 0.001f)

        AyraPreferences.setThinkingMode(context, "reasoning")
        assertEquals("reasoning", AyraPreferences.getThinkingMode(context))

        AyraPreferences.saveStructuredInstructions(
            context,
            AyraPreferences.StructuredInstructions(
                rolePrompt = "Principal Kotlin Engineer",
                aboutUser = "Building Ayra AI",
                howToRespond = "Cite edge cases cleanly",
                preferredOutputFormat = "Structured Markdown",
                interests = "Android, Cryptography",
                restrictions = "No filler prose"
            )
        )
        val combined = AyraPreferences.buildCombinedPersonalizationBlock(context)
        assertTrue(combined.contains("Principal Kotlin Engineer"))
        assertTrue(combined.contains("No filler prose"))
        assertTrue(combined.contains("Response Calibration:"))

        val sysPrompt = AyraAgentOrchestrator.buildSystemPrompt(
            domain = AgentDomain.CODING_AGENT,
            isBengali = false,
            isGfMode = false,
            userCustomInstructions = combined,
            rememberedFacts = setOf("User prefers Jetpack Compose"),
            personaStyle = "TECHNICAL",
            thinkingMode = "reasoning"
        )
        assertTrue(sysPrompt.contains("Principal Engineer Mode"))
        assertTrue(sysPrompt.contains("CHAIN-OF-THOUGHT REASONING"))
        assertTrue(sysPrompt.contains("User prefers Jetpack Compose"))
        assertFalse(sysPrompt.contains("girlfriend"))
    }
}
