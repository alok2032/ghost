package com.ayra.ai.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllSessionsOnce(): List<ChatSession>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: Long): ChatSession?

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    fun observeSessionById(sessionId: Long): Flow<ChatSession?>

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getMostRecentSession(): ChatSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSession): Long

    @Update
    suspend fun updateSession(session: ChatSession)

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun renameSession(sessionId: Long, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET isPinned = :pinned WHERE id = :sessionId")
    suspend fun setPinned(sessionId: Long, pinned: Boolean)

    @Query("UPDATE chat_sessions SET personaMode = :personaMode, modelTier = :modelTier WHERE id = :sessionId")
    suspend fun updateSessionPreferences(sessionId: Long, personaMode: String, modelTier: String)

    @Query(
        "UPDATE chat_sessions SET updatedAt = :updatedAt, " +
            "lastMessagePreview = :preview, " +
            "messageCount = (SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId) " +
            "WHERE id = :sessionId"
    )
    suspend fun refreshSessionActivity(sessionId: Long, preview: String, updatedAt: Long = System.currentTimeMillis())

    @Query(
        "SELECT * FROM chat_sessions " +
            "WHERE title LIKE '%' || :query || '%' OR lastMessagePreview LIKE '%' || :query || '%' " +
            "ORDER BY isPinned DESC, updatedAt DESC"
    )
    suspend fun searchSessions(query: String): List<ChatSession>

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: Long)

    @Query("SELECT COUNT(*) FROM chat_sessions")
    suspend fun getSessionCount(): Int
}
