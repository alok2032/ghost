package com.ayra.ai.app.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Isolated Project Workspace with its own custom instructions, memory notes, and default routing tier.
 */
@Immutable
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val customInstructions: String = "",
    val defaultModelTier: String = "auto",
    val memoryNotes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Custom User-Authored AI Assistant / Agent Persona with its own system prompt, model, thinking mode, and tools.
 */
@Immutable
@Entity(tableName = "custom_assistants")
data class CustomAssistantEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val iconEmoji: String = "🤖",
    val systemPrompt: String,
    val personaStyle: String = "AI_ASSISTANT",
    val defaultModelTier: String = "auto",
    val thinkingMode: String = "balanced",
    val allowedToolsJson: String = "[\"wiki\",\"search\",\"rag\",\"code\"]",
    val preferredLanguage: String = "Auto",
    val isBuiltInPreset: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Structured Prompt Template saved in Prompt Library.
 */
@Immutable
@Entity(tableName = "prompt_templates")
data class PromptTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val category: String = "General",
    val roleText: String = "",
    val objectiveText: String = "",
    val contextText: String = "",
    val inputText: String = "",
    val requirementsText: String = "",
    val constraintsText: String = "",
    val outputFormatText: String = "",
    val validationCriteriaText: String = "",
    val compiledPrompt: String,
    val tagsCsv: String = "",
    val versionNumber: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Immutable historical snapshot of a PromptTemplate for version history & one-tap revert.
 */
@Entity(
    tableName = "prompt_versions",
    foreignKeys = [
        ForeignKey(
            entity = PromptTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["templateId"])]
)
@Immutable
data class PromptVersionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val templateId: Long,
    val versionNumber: Int,
    val compiledPrompt: String,
    val changeNote: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
