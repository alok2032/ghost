package com.ayra.ai.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.WorkspaceContextBuilder
import com.ayra.ai.app.ai.memory.LongTermMemoryManager
import com.ayra.ai.app.ai.memory.MemorySaveResult
import com.ayra.ai.app.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LongTermMemoryAndViewModelTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ChatDatabase.closeDatabaseForMaintenance()
    }

    @After
    fun tearDown() {
        ChatDatabase.closeDatabaseForMaintenance()
    }

    @Test
    fun `memory defaults to opt-in off, blocks secrets and PII, and excludes deleted memories from prompts`() = runBlocking {
        // Opt-in privacy-by-default check
        context.getSharedPreferences("ayra_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(AyraPreferences.isMemoryEnabled(context))

        // Saving while opted out fails cleanly
        val disabledSave = LongTermMemoryManager.trySaveMemory(context, "PROFILE", "My favorite language is Kotlin")
        assertTrue(disabledSave is MemorySaveResult.Rejected)

        // Enable opt-in memory
        AyraPreferences.setMemoryEnabled(context, true)

        // Secret / PII blocking
        val apiKeySave = LongTermMemoryManager.trySaveMemory(context, "GENERAL", "My key is AIzaSyDummySecretKey1234567890abcd")
        assertTrue(apiKeySave is MemorySaveResult.Rejected)
        val pwSave = LongTermMemoryManager.trySaveMemory(context, "GENERAL", "password is supersecretpassword123")
        assertTrue(pwSave is MemorySaveResult.Rejected)
        assertFalse(AyraPreferences.addRememberedFact(context, "Bearer eyJhbGciOiJIUzI1NiJ9.abcdefghijkl.1234567890"))

        // Valid memory save & prompt context retrieval
        val ok = LongTermMemoryManager.trySaveMemory(context, "PREFERENCE", "Prefer Kotlin coroutines and concise answers", isPinned = true)
        assertTrue(ok is MemorySaveResult.Saved)
        val savedEntity = (ok as MemorySaveResult.Saved).entity

        val ctxBefore = WorkspaceContextBuilder.resolve(context, "AI_ASSISTANT", "auto", "help with kotlin")
        assertTrue(ctxBefore.rememberedFacts.any { it.contains("Prefer Kotlin coroutines") })

        // Delete & confirm immediate removal from prompt context
        LongTermMemoryManager.deleteMemory(context, savedEntity.id)
        val ctxAfter = WorkspaceContextBuilder.resolve(context, "AI_ASSISTANT", "auto", "help with kotlin")
        assertFalse(ctxAfter.rememberedFacts.any { it.contains("Prefer Kotlin coroutines") })
    }

    @Test
    fun `room MIGRATION_8_9 creates long_term_memories table without losing data`() {
        val dbName = "migration_8_9_test.db"
        context.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `chat_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `personaMode` TEXT NOT NULL, `modelTier` TEXT NOT NULL, `isPinned` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `messageCount` INTEGER NOT NULL, `lastMessagePreview` TEXT NOT NULL, `projectId` INTEGER, `assistantId` INTEGER)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val sqlDb = helper.writableDatabase
        ChatDatabase.MIGRATION_8_9.migrate(sqlDb)
        sqlDb.execSQL("INSERT INTO `long_term_memories` (`category`, `content`, `sourceSessionId`, `isPinned`, `createdAt`, `updatedAt`) VALUES ('PROFILE', 'Test Engineer', NULL, 1, 1000, 1000)")
        val cursor = sqlDb.query("SELECT category, content FROM `long_term_memories`")
        assertTrue(cursor.moveToFirst())
        assertEquals("PROFILE", cursor.getString(0))
        assertEquals("Test Engineer", cursor.getString(1))
        cursor.close()
        sqlDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `chatViewModel stopGeneration preserves streamed partial tokens in Room`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = ChatRepository(context, db.chatDao(), db.chatSessionDao())
        val sessionId = repo.ensureActiveSession().id
        val vm = ChatViewModel(repo)
        assertTrue(vm.tryStartSending())
        vm.appendStreamingToken("Partial streamed explanation about ")
        vm.appendStreamingToken("Room migrations")
        vm.stopGeneration()
        var partialMsg: ChatMessage? = null
        repeat(40) {
            val saved = repo.getRecentMessages(sessionId, 10)
            partialMsg = saved.lastOrNull { it.role == "assistant" }
            if (partialMsg != null) return@repeat
            delay(50)
        }
        assertNotNull(partialMsg)
        assertTrue(partialMsg!!.content.contains("Partial streamed explanation about Room migrations"))
        assertTrue(partialMsg!!.content.contains("(Stopped by user)"))
        db.close()
    }
}
