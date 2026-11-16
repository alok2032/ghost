package com.ayra.ai.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Single Source of Truth repository managing Room chat sessions and scoped message history.
 * Guarantees active session selection and metadata synchronization across app restarts.
 */
class ChatRepository(
    private val context: Context,
    private val chatDao: ChatDao,
    private val sessionDao: ChatSessionDao
) {

    fun observeSessions(): Flow<List<ChatSession>> = sessionDao.getAllSessions()

    fun observeMessages(sessionId: Long): Flow<List<ChatMessage>> =
        chatDao.getMessagesForSession(sessionId)

    fun observeSession(sessionId: Long): Flow<ChatSession?> =
        sessionDao.observeSessionById(sessionId)

    suspend fun getRecentMessages(sessionId: Long, limit: Int = 15): List<ChatMessage> =
        chatDao.getRecentMessagesForSession(sessionId, limit)

    suspend fun ensureActiveSession(): ChatSession {
        val preferredId = AyraPreferences.getLastActiveSessionId(context)
        val existingPreferred = sessionDao.getSessionById(preferredId)
        if (existingPreferred != null) {
            return existingPreferred
        }
        val mostRecent = sessionDao.getMostRecentSession()
        if (mostRecent != null) {
            AyraPreferences.setLastActiveSessionId(context, mostRecent.id)
            return mostRecent
        }
        return createNewSession(
            title = "Ayra Main Chat",
            personaMode = AyraPreferences.getSavedPersona(context),
            modelTier = AyraPreferences.getSavedModelTier(context)
        )
    }

    suspend fun createNewSession(
        title: String = "New Conversation",
        personaMode: String = AyraPreferences.getSavedPersona(context),
        modelTier: String = AyraPreferences.getSavedModelTier(context),
        projectId: Long? = null,
        assistantId: Long? = null
    ): ChatSession {
        val now = System.currentTimeMillis()
        val insertedId = sessionDao.insertSession(
            ChatSession(
                title = title.ifBlank { "New Conversation" }.take(80),
                personaMode = personaMode,
                modelTier = modelTier,
                createdAt = now,
                updatedAt = now,
                messageCount = 0,
                lastMessagePreview = "",
                projectId = projectId,
                assistantId = assistantId
            )
        )
        AyraPreferences.setLastActiveSessionId(context, insertedId)
        return sessionDao.getSessionById(insertedId) ?: ChatSession(
            id = insertedId,
            title = title,
            personaMode = personaMode,
            modelTier = modelTier,
            createdAt = now,
            updatedAt = now,
            projectId = projectId,
            assistantId = assistantId
        )
    }

    suspend fun switchSession(sessionId: Long): ChatSession? {
        val target = sessionDao.getSessionById(sessionId) ?: return null
        AyraPreferences.setLastActiveSessionId(context, target.id)
        return target
    }

    suspend fun appendMessage(sessionId: Long, message: ChatMessage): Long {
        val existingSession = sessionDao.getSessionById(sessionId)
        val effectiveSessionId = if (existingSession != null) {
            sessionId
        } else {
            ensureActiveSession().id
        }
        val scopedMessage = message.copy(sessionId = effectiveSessionId)
        val insertedId = chatDao.insertMessage(scopedMessage)

        val preview = message.content
            .replace('\n', ' ')
            .trim()
            .take(110)

        // Auto-title session from the user's first prompt if it still has a generic default title
        val session = sessionDao.getSessionById(effectiveSessionId)
        if (session != null &&
            message.role == "user" &&
            session.messageCount == 0 &&
            (session.title == "New Conversation" || session.title == "Ayra Main Chat")
        ) {
            val autoTitle = deriveSessionTitle(message.content)
            sessionDao.renameSession(effectiveSessionId, autoTitle, message.timestamp)
        }

        sessionDao.refreshSessionActivity(
            sessionId = effectiveSessionId,
            preview = preview,
            updatedAt = message.timestamp
        )
        return insertedId
    }

    suspend fun clearSession(sessionId: Long) {
        chatDao.clearSessionMessages(sessionId)
        sessionDao.refreshSessionActivity(
            sessionId = sessionId,
            preview = "",
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun deleteSession(sessionId: Long): ChatSession {
        sessionDao.deleteSessionById(sessionId)
        val fallback = sessionDao.getMostRecentSession()
            ?: createNewSession(
                title = "Ayra Main Chat",
                personaMode = AyraPreferences.getSavedPersona(context),
                modelTier = AyraPreferences.getSavedModelTier(context)
            )
        AyraPreferences.setLastActiveSessionId(context, fallback.id)
        return fallback
    }

    suspend fun renameSession(sessionId: Long, newTitle: String) {
        val clean = newTitle.trim().take(80)
        if (clean.isNotEmpty()) {
            sessionDao.renameSession(sessionId, clean, System.currentTimeMillis())
        }
    }

    suspend fun togglePinSession(sessionId: Long) {
        val current = sessionDao.getSessionById(sessionId) ?: return
        sessionDao.setPinned(sessionId, !current.isPinned)
    }

    suspend fun searchSessions(query: String): List<ChatSession> {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) sessionDao.getAllSessionsOnce()
        else sessionDao.searchSessions(trimmed)
    }

    companion object {
        fun deriveSessionTitle(prompt: String): String {
            val cleaned = prompt
                .replace(Regex("📄 \\[[^\\]]+\\]\\s*"), "")
                .replace('\n', ' ')
                .trim()
            if (cleaned.isBlank()) return "New Conversation"
            return if (cleaned.length <= 44) cleaned else cleaned.take(41).trimEnd() + "..."
        }
    }
}
