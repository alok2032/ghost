package com.ayra.ai.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.ChatRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatDatabaseSessionTest {

    private lateinit var context: Context
    private lateinit var dbFileName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFileName = "test_ayra_sessions_${System.nanoTime()}.db"
        context.deleteDatabase(dbFileName)
        AyraPreferences.setLastActiveSessionId(context, 1L)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbFileName)
    }

    private fun openDiskDb(name: String = dbFileName): ChatDatabase {
        return Room.databaseBuilder(context, ChatDatabase::class.java, name)
            .addMigrations(ChatDatabase.MIGRATION_4_5, ChatDatabase.MIGRATION_5_6)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val now = System.currentTimeMillis()
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO `chat_sessions`
                        (`id`, `title`, `personaMode`, `modelTier`, `isPinned`, `createdAt`, `updatedAt`, `messageCount`, `lastMessagePreview`)
                        VALUES (1, 'Ayra Main Chat', 'GF_COMPANION', 'auto', 0, $now, $now, 0, '')
                        """.trimIndent()
                    )
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys=ON;")
                }
            })
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun sessionsAndMessagesPersistAcrossDatabaseRestart() = runBlocking {
        // Session 1 & Session 2 created and populated before simulated "app close"
        val db1 = openDiskDb()
        val repo1 = ChatRepository(context, db1.chatDao(), db1.chatSessionDao())

        val firstSession = repo1.createNewSession(title = "New Conversation")
        repo1.appendMessage(
            firstSession.id,
            ChatMessage(role = "user", content = "Explain black holes in simple terms", timestamp = 1000L)
        )
        repo1.appendMessage(
            firstSession.id,
            ChatMessage(role = "assistant", content = "A black hole is a region of spacetime with intense gravity.", timestamp = 1001L)
        )

        val secondSession = repo1.createNewSession(title = "Kotlin Architecture")
        repo1.appendMessage(
            secondSession.id,
            ChatMessage(role = "user", content = "Show me Room ForeignKey cascade best practices", timestamp = 2000L)
        )
        repo1.switchSession(firstSession.id)
        db1.close()

        // Reopen fresh Room database instance simulating cold app restart
        val db2 = openDiskDb()
        val repo2 = ChatRepository(context, db2.chatDao(), db2.chatSessionDao())

        val restoredActive = repo2.ensureActiveSession()
        assertEquals(firstSession.id, restoredActive.id)
        assertEquals("Explain black holes in simple terms", restoredActive.title)
        assertEquals(2, restoredActive.messageCount)

        val sessionOneMessages = repo2.observeMessages(firstSession.id).first()
        assertEquals(2, sessionOneMessages.size)
        assertEquals("Explain black holes in simple terms", sessionOneMessages[0].content)
        assertEquals("A black hole is a region of spacetime with intense gravity.", sessionOneMessages[1].content)

        val sessionTwoMessages = repo2.observeMessages(secondSession.id).first()
        assertEquals(1, sessionTwoMessages.size)
        assertEquals("Show me Room ForeignKey cascade best practices", sessionTwoMessages[0].content)
        db2.close()
    }

    @Test
    fun deleteSessionCascadesMessagesWithoutAffectingOtherSessions() = runBlocking {
        val db = openDiskDb()
        val repo = ChatRepository(context, db.chatDao(), db.chatSessionDao())

        val s1 = repo.createNewSession("Session Alpha")
        val s2 = repo.createNewSession("Session Beta")
        repo.appendMessage(s1.id, ChatMessage(role = "user", content = "Alpha message 1", timestamp = 100L))
        repo.appendMessage(s1.id, ChatMessage(role = "assistant", content = "Alpha reply 1", timestamp = 101L))
        repo.appendMessage(s2.id, ChatMessage(role = "user", content = "Beta message 1", timestamp = 200L))

        val nextActive = repo.deleteSession(s1.id)
        assertNotNull(nextActive)
        assertEquals(0, db.chatDao().countMessagesForSession(s1.id))
        assertEquals(1, db.chatDao().countMessagesForSession(s2.id))
        assertTrue(repo.observeMessages(s1.id).first().isEmpty())
        db.close()
    }

    @Test
    fun pinRenameAndSearchSessionsWorkEndToEnd() = runBlocking {
        val db = openDiskDb()
        val repo = ChatRepository(context, db.chatDao(), db.chatSessionDao())

        val s1 = repo.createNewSession("Physics Discussion")
        val s2 = repo.createNewSession("Recipe Ideas")
        repo.appendMessage(s1.id, ChatMessage(role = "user", content = "Quantum entanglement", timestamp = 500L))
        repo.appendMessage(s2.id, ChatMessage(role = "user", content = "Biriyani spice mix", timestamp = 900L))

        repo.togglePinSession(s1.id)
        repo.renameSession(s1.id, "Quantum Physics Lab")

        val allOrdered = repo.observeSessions().first()
        // Pinned session must bubble above newer unpinned session
        assertEquals(s1.id, allOrdered.first().id)
        assertEquals("Quantum Physics Lab", allOrdered.first().title)
        assertTrue(allOrdered.first().isPinned)

        val searchHit = repo.searchSessions("Biriyani")
        assertEquals(1, searchHit.size)
        assertEquals(s2.id, searchHit.first().id)
        db.close()
    }

    @Test
    fun migration4To5PreservesExistingMessagesUnderDefaultSession() = runBlocking {
        val migrationDbName = "test_migration_4_5_${System.nanoTime()}.db"
        try {
            // Create a v4 schema SQLite database and insert legacy messages
            val helper = object : SQLiteOpenHelper(context, migrationDbName, null, 4) {
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

            val legacyDb = helper.writableDatabase
            legacyDb.execSQL(
                "INSERT INTO `chat_messages` (`id`, `role`, `content`, `timestamp`) VALUES (10, 'user', 'Legacy question before update', 123456)"
            )
            legacyDb.execSQL(
                "INSERT INTO `chat_messages` (`id`, `role`, `content`, `timestamp`) VALUES (11, 'assistant', 'Legacy answer preserved', 123457)"
            )
            legacyDb.close()
            helper.close()

            // Open through Room with MIGRATION_4_5 + MIGRATION_5_6
            val upgradedDb = Room.databaseBuilder(context, ChatDatabase::class.java, migrationDbName)
                .addMigrations(ChatDatabase.MIGRATION_4_5, ChatDatabase.MIGRATION_5_6)
                .allowMainThreadQueries()
                .build()

            val defaultSession = upgradedDb.chatSessionDao().getSessionById(1L)
            assertNotNull(defaultSession)
            assertEquals(2, defaultSession!!.messageCount)
            assertEquals("Legacy answer preserved", defaultSession.lastMessagePreview)

            val restoredMsgs = upgradedDb.chatDao().getMessagesForSession(1L).first()
            assertEquals(2, restoredMsgs.size)
            assertEquals("Legacy question before update", restoredMsgs[0].content)
            assertEquals("Legacy answer preserved", restoredMsgs[1].content)
            upgradedDb.close()
        } finally {
            context.deleteDatabase(migrationDbName)
        }
    }
}
