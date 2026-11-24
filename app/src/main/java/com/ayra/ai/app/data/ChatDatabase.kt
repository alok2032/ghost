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
        DocumentChunkEntity::class,
        ProjectEntity::class,
        CustomAssistantEntity::class,
        PromptTemplateEntity::class,
        PromptVersionEntity::class,
        VectorKnowledgeSourceEntity::class,
        VectorKnowledgeChunkEntity::class,
        LongTermMemoryEntity::class
    ],
    version = 10,
    exportSchema = true
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun gitHubPluginDao(): GitHubPluginDao
    abstract fun knowledgeDocumentDao(): KnowledgeDocumentDao
    abstract fun ayraWorkspaceDao(): AyraWorkspaceDao
    abstract fun vectorKnowledgeDao(): VectorKnowledgeDao
    abstract fun longTermMemoryDao(): LongTermMemoryDao

    sealed class VaultOpenResult {
        data class Ready(val database: ChatDatabase) : VaultOpenResult()
        data class NeedsRecovery(
            val vaultState: com.ayra.ai.app.security.VaultState,
            val reason: String
        ) : VaultOpenResult()
    }

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
                    VALUES (1, 'Ayra Main Chat', 'AI_ASSISTANT', 'auto', 0, $now, $now, 0, '')
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

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `projectId` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `assistantId` INTEGER DEFAULT NULL")
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
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vector_knowledge_sources` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sourceKey` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `layer` TEXT NOT NULL,
                        `providerName` TEXT NOT NULL,
                        `sourceUrl` TEXT NOT NULL,
                        `originType` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `summaryText` TEXT NOT NULL,
                        `chunkCount` INTEGER NOT NULL,
                        `fetchedAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vector_knowledge_sources_sourceKey` ON `vector_knowledge_sources` (`sourceKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vector_knowledge_sources_layer` ON `vector_knowledge_sources` (`layer`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vector_knowledge_sources_originType` ON `vector_knowledge_sources` (`originType`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vector_knowledge_chunks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sourceId` INTEGER NOT NULL,
                        `sourceKey` TEXT NOT NULL,
                        `layer` TEXT NOT NULL,
                        `providerName` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `sourceUrl` TEXT NOT NULL,
                        `originType` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `chunkIndex` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `keywordsCsv` TEXT NOT NULL,
                        `tokenCount` INTEGER NOT NULL,
                        `embeddingDims` INTEGER NOT NULL,
                        `embeddingBlob` BLOB NOT NULL,
                        `fetchedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`sourceId`) REFERENCES `vector_knowledge_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vector_knowledge_chunks_sourceId` ON `vector_knowledge_chunks` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vector_knowledge_chunks_sourceKey` ON `vector_knowledge_chunks` (`sourceKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vector_knowledge_chunks_layer` ON `vector_knowledge_chunks` (`layer`)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `long_term_memories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `category` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `sourceSessionId` INTEGER DEFAULT NULL,
                        `isPinned` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_long_term_memories_updatedAt` ON `long_term_memories` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_long_term_memories_category` ON `long_term_memories` (`category`)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `vector_knowledge_chunks` ADD COLUMN `embeddingModelId` TEXT NOT NULL DEFAULT 'ayra-bilingual-subword-256d'"
                )
                db.execSQL(
                    "ALTER TABLE `vector_knowledge_chunks` ADD COLUMN `embeddingFingerprint` TEXT NOT NULL DEFAULT 'ayra-bilingual-subword-256d:v2:256'"
                )
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
                runCatching { db.execSQL("PRAGMA secure_delete=ON;") }
                ensureDefaultSessionRow(db)
            }
        }

        private fun ensureDefaultSessionRow(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            db.execSQL(
                """
                INSERT OR IGNORE INTO `chat_sessions`
                (`id`, `title`, `personaMode`, `modelTier`, `isPinned`, `createdAt`, `updatedAt`, `messageCount`, `lastMessagePreview`)
                SELECT 1, 'Ayra Main Chat', 'AI_ASSISTANT', 'auto', 0, $now, $now, 0, ''
                WHERE NOT EXISTS (SELECT 1 FROM `chat_sessions`)
                """.trimIndent()
            )
        }

        fun tryGetDatabase(context: Context): VaultOpenResult {
            return try {
                val db = getDatabase(context)
                // Eagerly open SQLite/Room so missing migration paths (< v4) or unreadable encrypted
                // vaults transition cleanly to VaultRecoveryScreen without silent data loss or UI crash.
                db.openHelper.writableDatabase
                VaultOpenResult.Ready(db)
            } catch (e: com.ayra.ai.app.security.VaultKeyRecoveryException) {
                closeDatabaseForMaintenance()
                VaultOpenResult.NeedsRecovery(
                    vaultState = e.vaultState,
                    reason = e.message ?: "Encrypted vault key could not be unwrapped."
                )
            } catch (e: Exception) {
                closeDatabaseForMaintenance()
                VaultOpenResult.NeedsRecovery(
                    vaultState = com.ayra.ai.app.security.VaultState.CORRUPT,
                    reason = e.message ?: "Encrypted vault database failed integrity verification."
                )
            }
        }

        fun getDatabase(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE?.let { return@synchronized it }
                val appCtx = context.applicationContext
                val factory = com.ayra.ai.app.security.VaultDatabaseKeyManager
                    .createOpenHelperFactory(appCtx, "ayra_chat_database")

                val builder = Room.databaseBuilder(
                    appCtx,
                    ChatDatabase::class.java,
                    "ayra_chat_database"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .addCallback(seedDefaultSessionCallback)

                if (factory != null) {
                    builder.openHelperFactory(factory)
                }

                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }

        @Synchronized
        fun closeDatabaseForMaintenance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
