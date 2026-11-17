package com.ayra.ai.app.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val personaMode: String = "AI_ASSISTANT",
    val modelTier: String = "auto",
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val lastMessagePreview: String = "",
    val projectId: Long? = null,
    val assistantId: Long? = null
)
