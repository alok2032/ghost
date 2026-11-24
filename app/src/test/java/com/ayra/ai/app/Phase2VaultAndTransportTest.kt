package com.ayra.ai.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.providers.ChatTurn
import com.ayra.ai.app.ai.providers.ProviderGeminiTransport
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.security.VaultDatabaseKeyManager
import com.ayra.ai.app.security.VaultState
import com.ayra.ai.app.ui.screens.VaultRecoveryScreen
import com.ayra.ai.app.ui.theme.AyraTheme
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase2VaultAndTransportTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ChatDatabase.closeDatabaseForMaintenance()
        VaultDatabaseKeyManager.destroyDatabaseKeyMetadata(context)
        context.deleteDatabase("ayra_chat_database")
    }

    @After
    fun tearDown() {
        ChatDatabase.closeDatabaseForMaintenance()
        VaultDatabaseKeyManager.destroyDatabaseKeyMetadata(context)
        context.deleteDatabase("ayra_chat_database")
    }

    @Test
    fun geminiNonStreamingAndStreamingSendKeyInHeaderNeverInUrl() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"Hello securely"}]}}]}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Stream OK\"}]}}]}\n\ndata: [DONE]\n\n")
        )
        server.start()
        try {
            val baseUrl = server.url("/v1beta/").toString()
            val secretKey = "AIzaSyFAKE_TEST_SECRET_DO_NOT_LEAK_12345"
            val client = OkHttpClient()
            val turns = listOf(ChatTurn(role = "user", content = "Ping"))

            val reply = ProviderGeminiTransport.callGemini(
                httpClient = client,
                baseUrlOverride = baseUrl,
                turns = turns,
                model = "gemini-3.6-flash",
                apiKey = secretKey,
                withSearchGrounding = false
            )
            assertEquals("Hello securely", reply)

            val streamed = StringBuilder()
            flow {
                ProviderGeminiTransport.streamGemini(
                    httpClient = client,
                    baseUrlOverride = baseUrl,
                    turns = turns,
                    model = "gemini-3.6-flash",
                    apiKey = secretKey,
                    collector = this
                )
            }.collect { chunk -> streamed.append(chunk) }
            assertEquals("Stream OK", streamed.toString())

            val req1 = server.takeRequest()
            val req2 = server.takeRequest()
            assertFalse("Non-streaming URL must never contain key=", req1.path.orEmpty().contains("key="))
            assertFalse("Streaming URL must never contain key=", req2.path.orEmpty().contains("key="))
            assertEquals(secretKey, req1.getHeader("x-goog-api-key"))
            assertEquals(secretKey, req2.getHeader("x-goog-api-key"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun corruptedWrappedEnvelopeTriggersRecoveryInsteadOfCrashingOrOpeningPlaintext() {
        // Seed healthy vault first
        val firstOpen = ChatDatabase.tryGetDatabase(context)
        assertTrue(firstOpen is ChatDatabase.VaultOpenResult.Ready)
        ChatDatabase.closeDatabaseForMaintenance()

        // Wipe cached passphrase and corrupt the wrapped Keystore envelope in SharedPreferences
        VaultDatabaseKeyManager.destroyDatabaseKeyMetadata(context)
        context.getSharedPreferences("ayra_vault_db_key_meta", Context.MODE_PRIVATE)
            .edit()
            .putString("wrapped_db_passphrase_v1", "{\"iv\":\"invalid\",\"ct\":\"tampered\"}")
            .commit()

        val failedOpen = ChatDatabase.tryGetDatabase(context)
        assertTrue(
            "Corrupted/missing Keystore envelope must return NeedsRecovery, never throw or open unencrypted DB",
            failedOpen is ChatDatabase.VaultOpenResult.NeedsRecovery
        )
        val recovery = failedOpen as ChatDatabase.VaultOpenResult.NeedsRecovery
        assertEquals(VaultState.KEY_LOST, recovery.vaultState)

        // Explicit user-confirmed reset restores clean working vault
        VaultDatabaseKeyManager.resetCorruptedVaultAndDatabase(context)
        val reopened = ChatDatabase.tryGetDatabase(context)
        assertTrue(reopened is ChatDatabase.VaultOpenResult.Ready)
    }

    @Test
    fun vaultRecoveryScreenRequiresTypedResetAndRecoversCleanly() {
        var recovered = false
        composeRule.setContent {
            AyraTheme {
                VaultRecoveryScreen(
                    vaultState = VaultState.KEY_LOST,
                    reason = "Simulated Keystore key invalidation",
                    onRecovered = { recovered = true }
                )
            }
        }
        composeRule.onNodeWithTag("vault_recovery_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("vault_recovery_restore_button").assertIsDisplayed()
        composeRule.onNodeWithTag("vault_recovery_reset_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("vault_recovery_confirm_reset_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("vault_recovery_confirm_input").performTextInput("RESET")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("vault_recovery_confirm_reset_button").assertIsEnabled()
        composeRule.onNodeWithTag("vault_recovery_confirm_reset_button").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            recovered
        }
        assertTrue(recovered)
    }

    @Test
    fun migration4To10CreatesAll13TablesAndEnforcesForeignKeys() {
        val dbName = "test_full_migration_4_10_${System.nanoTime()}.db"
        try {
            val helper = object : SQLiteOpenHelper(context, dbName, null, 4) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `chat_messages` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `role` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `attachedImagePath` TEXT,
                            `craftType` TEXT,
                            `craftPayload` TEXT,
                            `actionCommand` TEXT
                        )
                        """.trimIndent()
                    )
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

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }
            helper.writableDatabase.use { db ->
                db.execSQL(
                    "INSERT INTO `chat_messages` (`id`, `role`, `content`, `timestamp`) VALUES (1, 'user', 'v4 preserved row', 999)"
                )
            }
            helper.close()

            val migratedDb = Room.databaseBuilder(context, ChatDatabase::class.java, dbName)
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

            val writable = migratedDb.openHelper.writableDatabase
            writable.execSQL("PRAGMA foreign_keys=ON;")
            val tableNames = mutableSetOf<String>()
            writable.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                while (cursor.moveToNext()) {
                    tableNames.add(cursor.getString(0))
                }
            }

            val expectedTables = listOf(
                "chat_sessions",
                "chat_messages",
                "scheduled_tasks",
                "installed_github_plugins",
                "knowledge_documents",
                "document_chunks",
                "projects",
                "custom_assistants",
                "prompt_templates",
                "prompt_versions",
                "vector_knowledge_sources",
                "vector_knowledge_chunks",
                "long_term_memories"
            )
            expectedTables.forEach { table ->
                assertTrue("Expected migrated table '$table' to exist", table in tableNames)
            }

            migratedDb.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }
}
