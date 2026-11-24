package com.ayra.ai.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.code.ProjectWorkspaceManager
import com.ayra.ai.app.ai.plugins.GitHubPluginEngine
import com.ayra.ai.app.ai.rag.DocumentRagEngine
import com.ayra.ai.app.audio.AyraAudioSynthesizer
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.StorageHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.io.FileInputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DocumentRagAndExportTest {

    private lateinit var context: Context
    private lateinit var dbFileName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFileName = "test_ayra_rag_${System.nanoTime()}.db"
        context.deleteDatabase(dbFileName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbFileName)
    }

    @Test
    fun roomMigration5To6CreatesRagTablesAndPreservesExistingData() = runBlocking {
        // Seed a minimal v5 database first
        val helper = object : SQLiteOpenHelper(context, dbFileName, null, 5) {
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
                    INSERT INTO `chat_sessions` (`id`, `title`, `personaMode`, `modelTier`, `isPinned`, `createdAt`, `updatedAt`, `messageCount`, `lastMessagePreview`)
                    VALUES (1, 'Legacy Session', 'GF_COMPANION', 'auto', 0, 1000, 1000, 0, '')
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
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.close()

        val db = Room.databaseBuilder(context, ChatDatabase::class.java, dbFileName)
            .addMigrations(
                ChatDatabase.MIGRATION_4_5,
                ChatDatabase.MIGRATION_5_6,
                ChatDatabase.MIGRATION_6_7,
                ChatDatabase.MIGRATION_7_8,
                ChatDatabase.MIGRATION_8_9,
                ChatDatabase.MIGRATION_9_10
            )
            .allowMainThreadQueries()
            .build()

        val sessions = db.chatSessionDao().getAllSessions().first()
        assertTrue("Existing session should survive v5->v6 migration", sessions.any { it.title == "Legacy Session" })

        val rag = DocumentRagEngine(db.knowledgeDocumentDao())
        val longBody = (1..20).joinToString("\n\n") { idx ->
            if (idx == 12) {
                "Section $idx: Ayra uses AES-256-GCM inside Android Keystore for EncryptedPreferences token isolation and zero hardcoded secrets."
            } else {
                "Section $idx: General architecture notes about Jetpack Compose layouts, coroutine pools, and edge-to-edge UI guidelines for mobile devices."
            }
        }
        val docId = rag.ingestDocument(
            title = "Ayra_Security_Spec.txt",
            mimeType = "text/plain",
            rawText = longBody
        )
        assertTrue("Inserted document ID should be > 0", docId > 0L)

        val (citedContext, hits) = rag.buildCitedContext("How does Ayra isolate API keys with Keystore?", topK = 3)
        assertTrue("BM25 retrieval should return at least one hit", hits.isNotEmpty())
        assertTrue(
            "Top hit should contain AES-256-GCM Keystore section",
            hits.first().excerpt.contains("AES-256-GCM")
        )
        assertTrue(
            "Formatted RAG context must contain explicit citation tag",
            citedContext.contains("[Doc: Ayra_Security_Spec.txt §")
        )

        rag.deleteDocument(docId)
        val afterDelete = rag.searchRelevantChunks("Keystore")
        assertTrue("Cascade delete must remove document chunks", afterDelete.isEmpty())
        db.close()
    }

    @Test
    fun audioSynthesizerExportsValidPcmWavFile() {
        val wavFile = AyraAudioSynthesizer.exportSynthToWav(
            context = context,
            title = "Midnight Synth",
            chordsText = "Am - F - C - G",
            bpm = 120
        )
        assertTrue("Exported WAV file must exist", wavFile.exists())
        assertTrue("Exported WAV file should contain PCM audio bytes (> 44KB)", wavFile.length() > 44_000L)

        val header = ByteArray(44)
        FileInputStream(wavFile).use { fis ->
            val read = fis.read(header)
            assertEquals(44, read)
        }
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        wavFile.delete()
    }

    @Test
    fun storageHelperExportsValidMarkdownAndWordDocXml() {
        val mdFile = StorageHelper.exportMarkdownDocument(
            context = context,
            title = "Quantum Research",
            content = "## Key Findings\nQuantum error correction improves logical qubit stability."
        )
        assertTrue(mdFile.exists())
        assertTrue(
            "Markdown export must be encrypted at rest in AYRAVLT vault",
            com.ayra.ai.app.security.EncryptedVaultStorage.isVaultFile(mdFile)
        )
        val rawDiskMd = mdFile.readText(Charsets.UTF_8)
        assertFalse(
            "Raw disk file must not leak plaintext",
            rawDiskMd.contains("Quantum error correction")
        )
        val mdText = com.ayra.ai.app.security.EncryptedVaultStorage
            .decryptVaultFileBytes(context, mdFile)
            .toString(Charsets.UTF_8)
        assertTrue(mdText.startsWith("# Quantum Research"))
        assertTrue(mdText.contains("Quantum error correction"))

        val docFile = StorageHelper.exportWordDocDocument(
            context = context,
            title = "Quarterly <Audit> & Notes",
            content = "# Executive Summary\nAll checks verified cleanly."
        )
        assertTrue(docFile.exists())
        assertTrue(
            "Word export must be encrypted at rest in AYRAVLT vault",
            com.ayra.ai.app.security.EncryptedVaultStorage.isVaultFile(docFile)
        )
        val rawDiskDoc = docFile.readText(Charsets.UTF_8)
        assertFalse(
            "Raw disk file must not leak plaintext XML",
            rawDiskDoc.contains("<w:wordDocument")
        )
        val xmlText = com.ayra.ai.app.security.EncryptedVaultStorage
            .decryptVaultFileBytes(context, docFile)
            .toString(Charsets.UTF_8)
        assertTrue(xmlText.contains("<w:wordDocument"))
        assertTrue("XML entities must be escaped", xmlText.contains("Quarterly &lt;Audit&gt; &amp; Notes"))
        assertTrue(xmlText.contains("Executive Summary"))

        mdFile.delete()
        docFile.delete()
    }

    @Test
    fun projectWorkspaceManagerParsesFilesWritesSafelyAndBuildsReadableZip() {
        val aiOutput = """
            === FILE: src/Main.kt ===
            ```kotlin
            fun main() = println("Hello Ayra")
            ```
            === FILE: ../../escape_attempt.txt ===
            should be sanitized inside root
            === FILE: README.md ===
            # Demo App
        """.trimIndent()

        val parsed = ProjectWorkspaceManager.parseProjectFilesFromAiResponse(aiOutput, "demo_app")
        assertEquals(3, parsed.size)
        assertFalse("Path traversal segments must be stripped", parsed[1].relativePath.contains(".."))

        val (rootDir, zipFile) = ProjectWorkspaceManager.writeProjectToDiskAndZip(
            context = context,
            projectName = "demo_app",
            files = parsed
        )
        assertTrue(rootDir.exists())
        assertTrue(File(rootDir, "src/Main.kt").exists())
        assertTrue(zipFile.exists() && zipFile.length() > 100L)

        val entries = mutableListOf<String>()
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries.add(entry.name)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        assertTrue("ZIP must include src/Main.kt", entries.contains("src/Main.kt"))
        assertTrue("ZIP must include README.md", entries.contains("README.md"))

        rootDir.deleteRecursively()
        zipFile.delete()
    }

    @Test
    fun gitHubPluginCompatibilityEvaluatorClassifiesReposAccurately() {
        val engine = GitHubPluginEngine(context)

        val openApiReport = engine.evaluateAndroidCompatibility(
            treePaths = listOf("README.md", ".well-known/ai-plugin.json", "openapi.json"),
            fetchedFiles = emptyMap(),
            language = "JSON",
            description = "Weather lookup REST plugin"
        )
        assertEquals("DIRECT_IN_APP_API", openApiReport.compatibilityLevel)

        val comfyReport = engine.evaluateAndroidCompatibility(
            treePaths = listOf("main.py", "requirements.txt", "comfy/model_management.py"),
            fetchedFiles = mapOf("requirements.txt" to "torch\ntorchvision\ncomfyui"),
            language = "Python",
            description = "ComfyUI node workflow engine requiring CUDA GPU"
        )
        assertEquals("REMOTE_SERVER_BRIDGE", comfyReport.compatibilityLevel)
        assertTrue(comfyReport.runtimeRequirements.any { it.contains("Python") || it.contains("GPU") })
    }
}
