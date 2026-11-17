package com.ayra.ai.app.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Immutable
@Entity(
    tableName = "long_term_memories",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["category"])
    ]
)
data class LongTermMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val category: String = "GENERAL",
    val content: String,
    val sourceSessionId: Long? = null,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
