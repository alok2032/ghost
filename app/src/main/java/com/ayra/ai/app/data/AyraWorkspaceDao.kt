package com.ayra.ai.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AyraWorkspaceDao {

    // --- Projects ---
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    suspend fun getProjectsOnce(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: Long): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: Long)

    // --- Custom Assistants ---
    @Query("SELECT * FROM custom_assistants ORDER BY isBuiltInPreset DESC, updatedAt DESC")
    fun observeAssistants(): Flow<List<CustomAssistantEntity>>

    @Query("SELECT * FROM custom_assistants ORDER BY isBuiltInPreset DESC, updatedAt DESC")
    suspend fun getAssistantsOnce(): List<CustomAssistantEntity>

    @Query("SELECT * FROM custom_assistants WHERE id = :id LIMIT 1")
    suspend fun getAssistantById(id: Long): CustomAssistantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssistant(assistant: CustomAssistantEntity): Long

    @Update
    suspend fun updateAssistant(assistant: CustomAssistantEntity)

    @Query("DELETE FROM custom_assistants WHERE id = :id")
    suspend fun deleteAssistantById(id: Long)

    // --- Prompt Templates & Version History ---
    @Query("SELECT * FROM prompt_templates ORDER BY updatedAt DESC")
    fun observePromptTemplates(): Flow<List<PromptTemplateEntity>>

    @Query(
        "SELECT * FROM prompt_templates " +
            "WHERE title LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' " +
            "OR tagsCsv LIKE '%' || :query || '%' OR compiledPrompt LIKE '%' || :query || '%' " +
            "ORDER BY updatedAt DESC"
    )
    fun searchPromptTemplates(query: String): Flow<List<PromptTemplateEntity>>

    @Query("SELECT * FROM prompt_templates WHERE id = :id LIMIT 1")
    suspend fun getPromptTemplateById(id: Long): PromptTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPromptTemplate(template: PromptTemplateEntity): Long

    @Update
    suspend fun updatePromptTemplate(template: PromptTemplateEntity)

    @Query("DELETE FROM prompt_templates WHERE id = :id")
    suspend fun deletePromptTemplateById(id: Long)

    @Query("SELECT * FROM prompt_versions WHERE templateId = :templateId ORDER BY versionNumber DESC")
    fun observePromptVersions(templateId: Long): Flow<List<PromptVersionEntity>>

    @Query("SELECT * FROM prompt_versions WHERE templateId = :templateId ORDER BY versionNumber DESC")
    suspend fun getPromptVersionsOnce(templateId: Long): List<PromptVersionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPromptVersion(version: PromptVersionEntity): Long

    @Transaction
    suspend fun savePromptWithVersion(
        template: PromptTemplateEntity,
        changeNote: String
    ): Long {
        val now = System.currentTimeMillis()
        if (template.id == 0L) {
            val newId = insertPromptTemplate(template.copy(versionNumber = 1, createdAt = now, updatedAt = now))
            insertPromptVersion(
                PromptVersionEntity(
                    templateId = newId,
                    versionNumber = 1,
                    compiledPrompt = template.compiledPrompt,
                    changeNote = changeNote.ifBlank { "Initial version" },
                    createdAt = now
                )
            )
            return newId
        } else {
            val nextVersion = template.versionNumber + 1
            val updated = template.copy(versionNumber = nextVersion, updatedAt = now)
            updatePromptTemplate(updated)
            insertPromptVersion(
                PromptVersionEntity(
                    templateId = template.id,
                    versionNumber = nextVersion,
                    compiledPrompt = template.compiledPrompt,
                    changeNote = changeNote.ifBlank { "Updated to v$nextVersion" },
                    createdAt = now
                )
            )
            return template.id
        }
    }
}
