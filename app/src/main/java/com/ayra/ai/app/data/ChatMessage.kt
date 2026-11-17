package com.ayra.ai.app.data

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Immutable
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"]), Index(value = ["timestamp"])]
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(defaultValue = "1")
    val sessionId: Long = 1L,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachedImagePath: String? = null,
    val craftType: String? = null, // "IMAGE", "SONG", "3D", "LOCATION", "ACTION"
    val craftPayload: String? = null,
    val actionCommand: String? = null
)
