package com.ayra.ai.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatSession::class,
        ChatMessage::class,
        ScheduledTask::class,
        InstalledGitHubPlugin::class,
        KnowledgeDocumentEntity::class,
        DocumentChunkEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun gitHubPluginDao(): GitHubPluginDao
    abstract fun knowledgeDocumentDao(): KnowledgeDocumentDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
                val now = System.currentTimeMillis()
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `chat_sessions`
                    (`id`, `title`, `personaMode`, `modelTier`, `isPinned`, `createdAt`, `updatedAt`, `messageCount`, `lastMessagePreview`)
                    VALUES (1, 'Ayra Main Chat', 'GF_COMPANION', 'auto', 0, $now, $now, 0, '')
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_messages_new` (
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

                db.execSQL(
                    """
                    INSERT INTO `chat_messages_new` (`id`, `sessionId`, `role`, `content`, `timestamp`, `attachedImagePath`, `craftType`, `craftPayload`, `actionCommand`)
                    SELECT `id`, 1, `role`, `content`, `timestamp`, `attachedImagePath`, `craftType`, `craftPayload`, `actionCommand`
                    FROM `chat_messages`
                    """.trimIndent()
                )

                db.execSQL("DROP TABLE `chat_messages`")
                db.execSQL("ALTER TABLE `chat_messages_new` RENAME TO `chat_messages`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId` ON `chat_messages` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_timestamp` ON `chat_messages` (`timestamp`)")
                db.execSQL(
                    """
                    UPDATE `chat_sessions`
                    SET `messageCount` = (SELECT COUNT(*) FROM `chat_messages` WHERE `sessionId` = 1),
                        `lastMessagePreview` = COALESCE((SELECT SUBSTR(`content`, 1, 100) FROM `chat_messages` WHERE `sessionId` = 1 ORDER BY `timestamp` DESC, `id` DESC LIMIT 1), '')
                    WHERE `id` = 1
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
        }

        private val seedDefaultSessionCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                ensureDefaultSessionRow(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys=ON;")
                ensureDefaultSessionRow(db)
            }
        }

        private fun ensureDefaultSessionRow(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            db.execSQL(
                """
                INSERT OR IGNORE INTO `chat_sessions`
                (`id`, `title`, `personaMode`, `modelTier`, `isPinned`, `createdAt`, `updatedAt`, `messageCount`, `lastMessagePreview`)
                SELECT 1, 'Ayra Main Chat', 'GF_COMPANION', 'auto', 0, $now, $now, 0, ''
                WHERE NOT EXISTS (SELECT 1 FROM `chat_sessions`)
                """.trimIndent()
            )
        }

        fun getDatabase(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "ayra_chat_database"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    .addCallback(seedDefaultSessionCallback)
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
