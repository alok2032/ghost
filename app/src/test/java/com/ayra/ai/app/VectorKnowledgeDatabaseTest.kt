package com.ayra.ai.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.AutonomousKnowledgeEngine
import com.ayra.ai.app.ai.rag.CuratedDarkDeepKnowledgeCorpus
import com.ayra.ai.app.ai.rag.LocalVectorEmbedder
import com.ayra.ai.app.ai.rag.VectorKnowledgeRepository
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
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
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VectorKnowledgeDatabaseTest {

    private lateinit var context: Context
    private lateinit var dbFileName: String
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFileName = "test_ayra_vector_db_${System.nanoTime()}.db"
        context.deleteDatabase(dbFileName)
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        context.deleteDatabase(dbFileName)
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
    }

    private fun openDiskDb(name: String = dbFileName): ChatDatabase {
        return Room.databaseBuilder(context, ChatDatabase::class.java, name)
            .addMigrations(
                ChatDatabase.MIGRATION_4_5,
                ChatDatabase.MIGRATION_5_6,
                ChatDatabase.MIGRATION_6_7,
                ChatDatabase.MIGRATION_7_8,
                ChatDatabase.MIGRATION_8_9,
                ChatDatabase.MIGRATION_9_10
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys=ON;")
                }
            })
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun roomMigration7To8CreatesVectorTablesPreservesWorkspaceAndCascadesDeletes() = runBlocking {
        val helper = object : SQLiteOpenHelper(context, dbFileName, null, 7) {
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
                        `lastMessagePreview` TEXT NOT NULL,
                        `projectId` INTEGER DEFAULT NULL,
                        `assistantId` INTEGER DEFAULT NULL
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
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `projects` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `customInstructions` TEXT NOT NULL,
                        `defaultModelTier` TEXT NOT NULL,
                        `memoryNotes` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `custom_assistants` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `iconEmoji` TEXT NOT NULL,
                        `systemPrompt` TEXT NOT NULL,
                        `personaStyle` TEXT NOT NULL,
                        `defaultModelTier` TEXT NOT NULL,
                        `thinkingMode` TEXT NOT NULL,
                        `allowedToolsJson` TEXT NOT NULL,
                        `preferredLanguage` TEXT NOT NULL,
                        `isBuiltInPreset` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `prompt_templates` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `roleText` TEXT NOT NULL,
                        `objectiveText` TEXT NOT NULL,
                        `contextText` TEXT NOT NULL,
                        `inputText` TEXT NOT NULL,
                        `requirementsText` TEXT NOT NULL,
                        `constraintsText` TEXT NOT NULL,
                        `outputFormatText` TEXT NOT NULL,
                        `validationCriteriaText` TEXT NOT NULL,
                        `compiledPrompt` TEXT NOT NULL,
                        `tagsCsv` TEXT NOT NULL,
                        `versionNumber` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `prompt_versions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `templateId` INTEGER NOT NULL,
                        `versionNumber` INTEGER NOT NULL,
                        `compiledPrompt` TEXT NOT NULL,
                        `changeNote` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`templateId`) REFERENCES `prompt_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_prompt_versions_templateId` ON `prompt_versions` (`templateId`)")

                // Seed v7 rows that MUST survive migration to v8
                db.execSQL(
                    """
                    INSERT INTO `chat_sessions` (`id`, `title`, `personaMode`, `modelTier`, `isPinned`, `createdAt`, `updatedAt`, `messageCount`, `lastMessagePreview`, `projectId`, `assistantId`)
                    VALUES (1, 'v7 Pre-Upgrade Session', 'CYBER_EXPERT', 'auto', 1, 1000, 2000, 0, '', 7, 3)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `projects` (`id`, `name`, `description`, `customInstructions`, `defaultModelTier`, `memoryNotes`, `createdAt`, `updatedAt`)
                    VALUES (7, 'Zero-Trust Audit', 'Audit workspace', 'Always verify sources', 'pro', 'SQLCipher enabled', 1000, 2000)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `custom_assistants` (`id`, `name`, `description`, `iconEmoji`, `systemPrompt`, `personaStyle`, `defaultModelTier`, `thinkingMode`, `allowedToolsJson`, `preferredLanguage`, `isBuiltInPreset`, `createdAt`, `updatedAt`)
                    VALUES (3, 'NetSec Analyst', 'Defensive security', 'shield', 'Verify CVEs carefully', 'DIRECT', 'auto', 'deep_analysis', '["wiki"]', 'en', 0, 1000, 2000)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `prompt_templates` (`id`, `title`, `category`, `roleText`, `objectiveText`, `contextText`, `inputText`, `requirementsText`, `constraintsText`, `outputFormatText`, `validationCriteriaText`, `compiledPrompt`, `tagsCsv`, `versionNumber`, `createdAt`, `updatedAt`)
                    VALUES (9, 'Threat Model', 'Security', 'Architect', 'Analyze attack surface', '', '', '', '', '', '', 'Prompt body v7', 'security', 1, 1000, 2000)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `prompt_versions` (`id`, `templateId`, `versionNumber`, `compiledPrompt`, `changeNote`, `createdAt`)
                    VALUES (12, 9, 1, 'Prompt body v7', 'Initial v7', 1000)
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.close()
        helper.close()

        val db = openDiskDb()
        // Verify all v7 tables & rows survived migration
        val sessions = db.chatSessionDao().getAllSessions().first()
        assertTrue(sessions.any { it.title == "v7 Pre-Upgrade Session" && it.projectId == 7L && it.assistantId == 3L })
        val projects = db.ayraWorkspaceDao().observeProjects().first()
        assertTrue(projects.any { it.id == 7L && it.name == "Zero-Trust Audit" })
        val assistants = db.ayraWorkspaceDao().observeAssistants().first()
        assertTrue(assistants.any { it.id == 3L && it.name == "NetSec Analyst" })
        val templates = db.ayraWorkspaceDao().observePromptTemplates().first()
        assertTrue(templates.any { it.id == 9L && it.title == "Threat Model" })

        // Verify new vector tables + foreign key cascade delete
        val repo = VectorKnowledgeRepository(db.vectorKnowledgeDao(), context)
        val insertedId = repo.indexTextSource(
            sourceKey = "test:cascade:entry",
            title = "Ephemeral Onion Telemetry",
            layer = "DARK_WEB",
            providerName = "Test Feed",
            sourceUrl = "https://spec.torproject.org/",
            originType = "LIVE_CACHED",
            fullText = "Ephemeral Curve25519 circuit keys are rotated cleanly per Tor relay hop and deleted on shutdown. ".repeat(6)
        )
        assertTrue("Inserted vector source id should be > 0", insertedId > 0L)
        assertNotNull(db.vectorKnowledgeDao().getSourceByKey("test:cascade:entry"))
        assertTrue(db.vectorKnowledgeDao().countChunksOnce() > 0)

        db.vectorKnowledgeDao().deleteSourceByKey("test:cascade:entry")
        assertEquals(0, db.vectorKnowledgeDao().countSourcesOnce())
        assertEquals(0, db.vectorKnowledgeDao().countChunksOnce())
        db.close()
    }

    @Test
    fun curatedCorpusSeedsIdempotentlyAndPersistsFloat32VectorBlobsAcrossColdRestart() = runBlocking {
        val db1 = openDiskDb()
        val repo1 = VectorKnowledgeRepository(db1.vectorKnowledgeDao(), context)

        val seededFirst = repo1.ensureSeeded()
        assertEquals(CuratedDarkDeepKnowledgeCorpus.entries.size, seededFirst)
        // Calling ensureSeeded again must be idempotent (0 newly seeded)
        val seededSecond = repo1.ensureSeeded()
        assertEquals(0, seededSecond)

        val sourcesCount = db1.vectorKnowledgeDao().countSourcesOnce()
        val chunksCount = db1.vectorKnowledgeDao().countChunksOnce()
        assertEquals(CuratedDarkDeepKnowledgeCorpus.entries.size, sourcesCount)
        assertTrue("Multiple vector chunks expected for curated corpus", chunksCount >= sourcesCount)
        db1.close()

        // Cold restart: reopen SQLite file and inspect persisted BLOB vectors
        val db2 = openDiskDb()
        val persistedChunks = db2.vectorKnowledgeDao().getAllChunksOnce()
        assertEquals(chunksCount, persistedChunks.size)

        for (chunk in persistedChunks) {
            assertEquals(LocalVectorEmbedder.DIMENSIONS, chunk.embeddingDims)
            assertEquals(LocalVectorEmbedder.DIMENSIONS * 4, chunk.embeddingBlob.size)
            val floats = LocalVectorEmbedder.fromByteArray(chunk.embeddingBlob)
            assertEquals(LocalVectorEmbedder.DIMENSIONS, floats.size)
            val norm = sqrt(floats.fold(0.0) { acc, v -> acc + v * v })
            assertTrue("Stored vector BLOB must be L2-normalized (norm=$norm)", abs(norm - 1.0) < 1e-3)
        }

        val layerCounts = db2.vectorKnowledgeDao().observeLayerChunkCounts().first()
        assertTrue(layerCounts.any { it.layer == "DARK_WEB" && it.chunkCount > 0 })
        assertTrue(layerCounts.any { it.layer == "DEEP_WEB" && it.chunkCount > 0 })
        db2.close()
    }

    @Test
    fun offlineHybridSearchRanksEnglishAndBengaliDarkDeepQueriesAccurately() = runBlocking {
        val db = openDiskDb()
        val repo = VectorKnowledgeRepository(db.vectorKnowledgeDao(), context)
        repo.ensureSeeded()

        val torHits = repo.search("Curve25519 onion guard exit relay", layerFilter = "DARK_WEB", topK = 3)
        assertTrue("Tor query should return offline Dark Web hits", torHits.isNotEmpty())
        assertEquals("curated:dark_web:tor_onion_v3", torHits.first().sourceKey)
        assertTrue(torHits.first().cosineSimilarity in 0.0..1.0)
        assertTrue(torHits.first().hybridScore > 0.25)

        val i2pHits = repo.search("I2P unidirectional garlic tunnel NetDB", layerFilter = "DARK_WEB", topK = 3)
        assertTrue("I2P query should return hits", i2pHits.isNotEmpty())
        assertEquals("curated:dark_web:i2p_garlic_routing", i2pHits.first().sourceKey)

        val pqcHits = repo.search("lattice post quantum cryptography FIPS 203 ML-KEM", layerFilter = "DEEP_WEB", topK = 3)
        assertTrue("PQC query should return Deep Web hits", pqcHits.isNotEmpty())
        assertEquals("curated:deep_web:post_quantum_zero_trust", pqcHits.first().sourceKey)

        val transformerHits = repo.search("Vaswani self-attention RAG architecture", layerFilter = "DEEP_WEB", topK = 3)
        assertTrue("Transformer query should return Deep Web hits", transformerHits.isNotEmpty())
        assertEquals("curated:deep_web:transformer_attention_rag", transformerHits.first().sourceKey)

        // Cross-lingual Bengali -> English semantic matching completely offline
        val bengaliHits = repo.search("ডার্ক ওয়েব অনিয়ন রাউটিং টর", topK = 4)
        assertTrue("Bengali Dark Web query should match English curated Dark Web corpus", bengaliHits.isNotEmpty())
        assertTrue(
            "Top Bengali hits should include Tor or Hidden Wiki Dark Web sources",
            bengaliHits.take(2).any { it.layer == "DARK_WEB" && (it.sourceKey.contains("tor") || it.sourceKey.contains("hidden_wiki")) }
        )

        val (citedContext, ragHits) = repo.buildCitedVectorContext("What is The Hidden Wiki and Ahmia OpSec?", topK = 3, minScore = 0.20)
        assertTrue(ragHits.isNotEmpty())
        assertTrue(citedContext.contains("[VectorDB:DARK_WEB"))
        assertTrue(citedContext.contains("<untrusted_external_data"))
        db.close()
    }

    @Test
    fun liveMockWebServerFetchAutomaticallyCachesIntoRoomVectorDbAndServesOfflineAfterShutdown() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/wiki/") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "query": {
                            "pages": {
                              "101": {
                                "title": "Quantum-Safe Onion Routing",
                                "extract": "Quantum-Safe Onion Routing pairs hybrid ML-KEM lattice key exchange with Curve25519 relays for forward security across distributed directory authorities."
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/openalex/") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "results": [
                            {
                              "title": "High-Throughput Hybrid Lattice Onion Telemetry 2026",
                              "publication_year": 2026,
                              "doi": "https://doi.org/10.5555/hybrid-lattice-2026",
                              "abstract_inverted_index": {
                                "Benchmarking": [0],
                                "ZephryxLattice99": [1],
                                "post-quantum": [2],
                                "circuits": [3],
                                "reduces": [4],
                                "handshake": [5],
                                "latency.": [6]
                              }
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/arxiv/") -> MockResponse().setResponseCode(200).setBody(
                        """
                        <feed>
                          <entry>
                            <title>Secure Transformer Reasoning Over Live Vulnerability Feeds</title>
                            <summary>Evaluates dense Room SQLite vector embeddings for cyber advisory retrieval offline.</summary>
                          </entry>
                        </feed>
                        """.trimIndent()
                    )
                    path.startsWith("/cve/") -> MockResponse().setResponseCode(200).setBody(
                        """
                        [
                          {
                            "id": "CVE-2026-99991",
                            "summary": "Critical ZephryxRelay buffer validation flaw in mock telemetry daemon patched in v4.2."
                          }
                        ]
                        """.trimIndent()
                    )
                    path.startsWith("/onionoo/") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "relays_published": "2026-11-16 12:00:00",
                          "relays": [
                            {"n": "LiveVaultTestRelay", "r": true}
                          ],
                          "bridges": []
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val db = openDiskDb()
        val repo = VectorKnowledgeRepository(db.vectorKnowledgeDao(), context)
        val okClient = OkHttpClient()

        // 1) Online lookup automatically caches retrieved sources into Room Vector DB
        val onlineLookup = AutonomousKnowledgeEngine.queryMultiLayerWikiWithVectorCache(
            context = context,
            prompt = "CVE-2026-99991 ZephryxLattice99 LiveVaultTestRelay",
            isBengali = false,
            offlineOnlyOverride = false,
            forceAllLayers = true,
            httpClient = okClient,
            vectorRepoOverride = repo,
            wikipediaBaseUrl = server.url("/wiki/").toString(),
            ddgBaseUrl = server.url("/ddg/").toString(),
            openAlexBaseUrl = server.url("/openalex/").toString(),
            arxivBaseUrl = server.url("/arxiv/").toString(),
            wikidataBaseUrl = server.url("/wikidata/").toString(),
            pubmedBaseUrl = server.url("/pubmed/").toString(),
            cveBaseUrl = server.url("/cve/").toString(),
            onionooBaseUrl = server.url("/onionoo/").toString()
        )
        assertFalse(onlineLookup.usedOfflineVectorCache)
        assertTrue(onlineLookup.wikiResult.isFromLiveNetwork)
        assertTrue("Expected live fetch to index new vector sources", onlineLookup.cachedNewSourcesCount >= 2)

        // Also run syncLiveCuratedFeeds against MockWebServer
        val syncReport = repo.syncLiveCuratedFeeds(
            httpClient = okClient,
            cveBaseUrl = server.url("/cve/").toString(),
            onionooBaseUrl = server.url("/onionoo/").toString(),
            openAlexBaseUrl = server.url("/openalex/").toString(),
            arxivBaseUrl = server.url("/arxiv/").toString()
        )
        assertEquals(4, syncReport.syncedSources)
        assertTrue(syncReport.failedFeeds.isEmpty())

        // 2) Shut down MockWebServer completely to guarantee ZERO network access
        server.shutdown()

        // 3) Query offline via VectorKnowledgeRepository + AutonomousKnowledgeEngine
        val cveHits = repo.search("CVE-2026-99991 ZephryxRelay", topK = 3, minScore = 0.15)
        assertTrue("CVE cached from MockWebServer must be retrieved offline", cveHits.isNotEmpty())
        assertTrue(cveHits.any { it.originType == "LIVE_CACHED" && it.excerpt.contains("ZephryxRelay") })

        val relayHits = repo.search("LiveVaultTestRelay Tor Onionoo consensus", layerFilter = "DARK_WEB", topK = 3, minScore = 0.15)
        assertTrue("Onionoo relay cached live must be retrieved offline", relayHits.isNotEmpty())
        assertTrue(relayHits.any { it.originType == "LIVE_CACHED" && it.excerpt.contains("LiveVaultTestRelay") })

        val openAlexOfflineLookup = AutonomousKnowledgeEngine.queryMultiLayerWikiWithVectorCache(
            context = context,
            prompt = "ZephryxLattice99 post-quantum circuits",
            isBengali = false,
            offlineOnlyOverride = true,
            httpClient = okClient,
            vectorRepoOverride = repo
        )
        assertTrue(openAlexOfflineLookup.usedOfflineVectorCache)
        assertFalse(openAlexOfflineLookup.wikiResult.isFromLiveNetwork)
        assertTrue(openAlexOfflineLookup.wikiResult.deepAcademic.contains("ZephryxLattice99"))
        assertTrue(openAlexOfflineLookup.wikiResult.citations.isNotEmpty())
        db.close()
    }

    @Test
    fun localOnlyAiModeBlocksOutboundSyncAndServesOfflineVectorVault() = runBlocking {
        val db = openDiskDb()
        val repo = VectorKnowledgeRepository(db.vectorKnowledgeDao(), context)
        AyraPreferences.setLocalOnlyAiModeEnabled(context, true)

        val beforeRequestCount = server.requestCount
        val blockedReport = repo.syncLiveCuratedFeeds(httpClient = OkHttpClient())
        assertTrue("Local-Only AI Mode must block outbound sync", blockedReport.blockedByLocalOnlyMode)
        assertEquals(0, blockedReport.syncedSources)
        assertTrue(blockedReport.totalChunksInDb > 0)
        assertEquals(beforeRequestCount, server.requestCount)

        val offlineResult = AutonomousKnowledgeEngine.queryMultiLayerWikiWithVectorCache(
            context = context,
            prompt = "Tor v3 onion Ed25519 Curve25519",
            isBengali = false,
            httpClient = OkHttpClient(),
            vectorRepoOverride = repo
        )
        assertTrue(offlineResult.usedOfflineVectorCache)
        assertFalse(offlineResult.wikiResult.isFromLiveNetwork)
        assertTrue(offlineResult.wikiResult.darkWebTech.contains("Curve25519"))
        db.close()
    }
}
