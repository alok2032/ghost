package com.ayra.ai.app.ai

import android.content.Context
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.CustomAssistantEntity
import com.ayra.ai.app.data.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves the active isolated Project, Custom Assistant persona, Thinking Mode,
 * and Personalization/Memory settings cleanly before dispatching AI inference.
 */
object WorkspaceContextBuilder {

    data class WorkspaceExecutionContext(
        val activeProject: ProjectEntity? = null,
        val activeAssistant: CustomAssistantEntity? = null,
        val effectivePersona: String = "AI_ASSISTANT",
        val effectiveTier: String = "auto",
        val effectiveThinkingMode: String = "balanced",
        val preferredLanguage: String = "Auto",
        val combinedPersonalization: String = "",
        val rememberedFacts: Set<String> = emptySet(),
        val workspacePromptInjection: String = "",
        val effectiveRoutingMode: String = "auto"
    )

    suspend fun resolve(
        context: Context?,
        requestedPersona: String,
        requestedTier: String,
        prompt: String = ""
    ): WorkspaceExecutionContext = withContext(Dispatchers.IO) {
        if (context == null) {
            return@withContext WorkspaceExecutionContext(
                effectivePersona = requestedPersona,
                effectiveTier = requestedTier
            )
        }

        val projectId = AyraPreferences.getActiveProjectId(context)
        val assistantId = AyraPreferences.getActiveAssistantId(context)
        val globalThinking = AyraPreferences.getThinkingMode(context)
        val globalLang = AyraPreferences.getPreferredLanguage(context)
        val isMemoryOn = AyraPreferences.isMemoryEnabled(context)
        val roomMemories = if (isMemoryOn) {
            runCatching {
                com.ayra.ai.app.ai.memory.LongTermMemoryManager.retrieveRelevantMemories(context, prompt, topK = 6)
            }.getOrDefault(emptyList())
        } else emptyList()
        val legacyFacts = if (isMemoryOn) AyraPreferences.getRememberedFacts(context) else emptySet()
        val facts = (roomMemories.map { "[${it.category}] ${it.content}" } + legacyFacts)
            .filter { !com.ayra.ai.app.ai.memory.LongTermMemoryManager.containsSensitiveCredentialOrPii(it) }
            .toSet()
        val personalization = AyraPreferences.buildCombinedPersonalizationBlock(context)

        val dao = runCatching { ChatDatabase.getDatabase(context).ayraWorkspaceDao() }.getOrNull()
        val project = if (projectId > 0L && dao != null) {
            runCatching { dao.getProjectById(projectId) }.getOrNull()
        } else null
        val assistant = if (assistantId > 0L && dao != null) {
            runCatching { dao.getAssistantById(assistantId) }.getOrNull()
        } else null

        val rawPersona = when {
            assistant != null && requestedPersona == "AI_ASSISTANT" -> assistant.personaStyle
            else -> requestedPersona
        }
        val effectivePersona = AyraPreferences.resolveSafePersona(context, rawPersona)

        val rawTier = when {
            requestedTier != "auto" -> requestedTier
            assistant != null && assistant.defaultModelTier.isNotBlank() && assistant.defaultModelTier != "auto" ->
                assistant.defaultModelTier
            project != null && project.defaultModelTier.isNotBlank() && project.defaultModelTier != "auto" ->
                project.defaultModelTier
            else -> "auto"
        }
        val effectiveTier = com.ayra.ai.app.ai.providers.ModelTier.canonicalizeId(rawTier)

        val effectiveThinking = when {
            globalThinking != "balanced" -> globalThinking
            assistant != null && assistant.thinkingMode.isNotBlank() -> assistant.thinkingMode
            else -> globalThinking
        }

        val effectiveLang = if (assistant != null && assistant.preferredLanguage != "Auto" && globalLang == "Auto") {
            assistant.preferredLanguage
        } else {
            globalLang
        }

        val injection = buildString {
            if (assistant != null) {
                append("=== ACTIVE CUSTOM AI ASSISTANT: ${assistant.iconEmoji} ${assistant.name} ===\n")
                if (assistant.description.isNotBlank()) {
                    append("Role Overview: ${assistant.description}\n")
                }
                if (assistant.systemPrompt.isNotBlank()) {
                    append("Assistant Core Instructions:\n${assistant.systemPrompt.trim()}\n")
                }
                append("\n")
            }
            if (project != null) {
                append("=== ACTIVE ISOLATED PROJECT WORKSPACE: ${project.name} ===\n")
                if (project.description.isNotBlank()) {
                    append("Project Summary: ${project.description}\n")
                }
                if (project.customInstructions.isNotBlank()) {
                    append("Project Instructions:\n${project.customInstructions.trim()}\n")
                }
                if (project.memoryNotes.isNotBlank()) {
                    append("Project Memory & Context:\n${project.memoryNotes.trim()}\n")
                }
            }
        }.trim()

        WorkspaceExecutionContext(
            activeProject = project,
            activeAssistant = assistant,
            effectivePersona = effectivePersona,
            effectiveTier = effectiveTier,
            effectiveThinkingMode = effectiveThinking,
            preferredLanguage = effectiveLang,
            combinedPersonalization = personalization,
            rememberedFacts = facts,
            workspacePromptInjection = injection,
            effectiveRoutingMode = AyraPreferences.getRoutingMode(context)
        )
    }
}
