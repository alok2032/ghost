package com.ayra.ai.app.ai

import android.content.Context
import com.ayra.ai.app.ai.providers.AyraError
import com.ayra.ai.app.ai.providers.AyraStreamEvent
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.automation.DeviceAutomationController
import com.ayra.ai.app.automation.ProposedAction
import com.ayra.ai.app.ai.rag.DocumentRagEngine
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.voice.CrisisDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

sealed class AyraEvent {
    data class ActionRequired(val action: ProposedAction) : AyraEvent()
    data class Status(val message: String) : AyraEvent()
    data class Token(val text: String) : AyraEvent()
    data class Complete(val response: AiResponse) : AyraEvent()
    data class Error(val error: AyraError) : AyraEvent()
}

/**
 * Section 2: Unified Orchestrator pipeline for Ayra AI.
 * Single entry point ensuring:
 * 1. Crisis / Safety checks run first.
 * 2. Device hardware automation triggers ActionGate confirmation card.
 * 3. Tool intents classified with zero false positives.
 * 4. General questions routed through ProviderPool with streaming tokens.
 * 5. Complete, non-template answers.
 */
class AyraOrchestrator(private val context: Context) {

    private val providerPool by lazy { ProviderPool(context) }
    private val automationController by lazy { DeviceAutomationController(context) }
    private val unifiedAiEngine by lazy { UnifiedAiEngine(context) }
    private val gitHubPluginEngine by lazy { com.ayra.ai.app.ai.plugins.GitHubPluginEngine(context) }

    fun handle(
        prompt: String,
        persona: String = "AI_ASSISTANT",
        tier: String = "auto",
        history: List<ChatMessage> = emptyList(),
        imageBase64: String? = null,
        documentText: String? = null,
        documentName: String? = null
    ): Flow<AyraEvent> = flow {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return@flow

        // 1. Safety & Crisis Pre-checks (Blocking, deterministic, immediate)
        val crisisResult = CrisisDetector.evaluate(trimmed)
        if (crisisResult.isCrisis && crisisResult.supportiveMessage != null) {
            emit(AyraEvent.Complete(AiResponse(text = crisisResult.supportiveMessage, modelUsed = "Ayra Safety Guardian 🛡️")))
            return@flow
        }

        // 2. Scheduled Task / Device Automation Pre-checks -> ActionGate Confirmation
        val proposedAction = automationController.proposeAction(trimmed)
        if (proposedAction != null) {
            emit(AyraEvent.ActionRequired(proposedAction))
            return@flow
        }

        // 3. Tool Intent Classification (Strict)
        val toolIntent = AyraIntentClassifier.classifyIntent(trimmed)

        when (toolIntent) {
            AyraToolIntent.PDF -> {
                emit(AyraEvent.Status("Generating PDF Document..."))
                val pdfResp = unifiedAiEngine.generateUnifiedResponse(
                    prompt = trimmed,
                    personaMode = persona,
                    modelTier = tier,
                    attachedDocumentText = documentText,
                    attachedDocumentName = documentName,
                    attachedImageBase64 = imageBase64
                )
                emit(AyraEvent.Complete(pdfResp))
                return@flow
            }
            AyraToolIntent.TERMINAL -> {
                val cmd = trimmed.removePrefix("/terminal").removePrefix("terminal:").trim()
                val isBengali = trimmed.any { it in '\u0980'..'\u09FF' }
                val reply = if (isBengali) {
                    "🛡️ **Ayra Cyber Security Educational Terminal & Defensive Lab** সক্রিয় হয়েছে। অনুমোদিত ডায়াগনস্টিক চালান:"
                } else {
                    "🛡️ **Ayra Cyber Security Educational Terminal & Defensive Lab** active. Executing diagnostics:"
                }
                emit(AyraEvent.Complete(
                    AiResponse(
                        text = reply,
                        craftType = "CYBER_TERMINAL",
                        craftPayload = cmd,
                        modelUsed = "Ayra Terminal Engine"
                    )
                ))
                return@flow
            }
            AyraToolIntent.IMAGE -> {
                emit(AyraEvent.Status("Generating high-res image (Nano Banana 2.1 / Flux.1)..."))
                val imgResp = unifiedAiEngine.generateUnifiedResponse(
                    prompt = trimmed,
                    personaMode = persona,
                    modelTier = tier,
                    attachedImageBase64 = imageBase64
                )
                emit(AyraEvent.Complete(imgResp))
                return@flow
            }
            AyraToolIntent.VIDEO -> {
                emit(AyraEvent.Status("Synthesizing 4K cinematic keyframes & motion storyboard..."))
                val vidResp = unifiedAiEngine.generateUnifiedResponse(
                    prompt = trimmed,
                    personaMode = persona,
                    modelTier = tier,
                    attachedImageBase64 = imageBase64
                )
                emit(AyraEvent.Complete(vidResp))
                return@flow
            }
            AyraToolIntent.SONG -> {
                emit(AyraEvent.Status("Composing chords, melody & lyrics..."))
                val songResp = unifiedAiEngine.generateUnifiedResponse(
                    prompt = trimmed,
                    personaMode = persona,
                    modelTier = tier
                )
                emit(AyraEvent.Complete(songResp))
                return@flow
            }
            AyraToolIntent.MODEL_3D -> {
                emit(AyraEvent.Status("Generating 3D holographic model..."))
                val modelResp = unifiedAiEngine.generateUnifiedResponse(
                    prompt = trimmed,
                    personaMode = persona,
                    modelTier = tier
                )
                emit(AyraEvent.Complete(modelResp))
                return@flow
            }
            AyraToolIntent.DEEP_RESEARCH -> {
                emit(AyraEvent.Status("Conducting grounded multi-source research..."))
                val researchResp = unifiedAiEngine.generateUnifiedResponse(
                    prompt = trimmed,
                    personaMode = persona,
                    modelTier = tier,
                    attachedDocumentText = documentText,
                    attachedImageBase64 = imageBase64
                )
                emit(AyraEvent.Complete(researchResp))
                return@flow
            }
            AyraToolIntent.GITHUB_PLUGIN -> {
                emit(AyraEvent.Status("Inspecting GitHub repository & extracting skills/APIs..."))
                val cleanTarget = trimmed
                    .removePrefix("/plugin").removePrefix("/github")
                    .removePrefix("plugin:").removePrefix("প্লাগইন:")
                    .trim()
                val installResult = gitHubPluginEngine.installPlugin(cleanTarget)
                val plugin = installResult.plugin
                if (installResult.success && plugin != null) {
                    val payloadJson = org.json.JSONObject()
                        .put("repoFullName", plugin.repoFullName)
                        .put("repoUrl", plugin.repoUrl)
                        .put("name", plugin.name)
                        .put("description", plugin.description)
                        .put("stars", plugin.stars)
                        .put("primaryLanguage", plugin.primaryLanguage)
                        .put("pluginType", plugin.pluginType)
                        .put("commandsJson", plugin.commandsJson)
                        .put("openApiSpecJson", plugin.openApiSpecJson)
                        .put("fileTreeJson", plugin.fileTreeJson)
                        .toString()
                    emit(AyraEvent.Complete(
                        AiResponse(
                            text = installResult.userSummaryText,
                            craftType = "GITHUB_PLUGIN",
                            craftPayload = payloadJson,
                            modelUsed = "Ayra GitHub Plugin Engine 🔌"
                        )
                    ))
                } else {
                    emit(AyraEvent.Complete(
                        AiResponse(
                            text = "⚠️ ${installResult.errorMessage ?: "Could not install GitHub repository plugin."}",
                            modelUsed = "Ayra GitHub Plugin Engine 🔌"
                        )
                    ))
                }
                return@flow
            }
            AyraToolIntent.CHAT -> {
                // Honor explicit Superpowers protocol first if triggered
                val superpower = com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.detectSuperpower(trimmed)
                if (superpower != null || tier == "superpowers") {
                    emit(AyraEvent.Status("Running ${superpower?.displayName ?: "Superpowers Engine"}..."))
                    val spResp = unifiedAiEngine.generateUnifiedResponse(
                        prompt = trimmed,
                        personaMode = persona,
                        modelTier = tier,
                        attachedDocumentText = documentText,
                        attachedDocumentName = documentName,
                        attachedImageBase64 = imageBase64,
                        conversationHistory = history
                    )
                    emit(AyraEvent.Complete(spResp))
                    return@flow
                }

                // Main CHAT & Coding execution through Unified Multi-AI ProviderPool
                val poolMode = when (tier) {
                    "omniroute", "quality", "gemini_pro", "deepseek_harness", "deepseek_r1", "godmode", "ecc" -> ProviderPoolMode.BEST
                    "fast", "gemini_flash" -> ProviderPoolMode.FAST
                    else -> ProviderPoolMode.AUTO
                }

                val prefProvider = when (tier) {
                    "gemini_flash", "gemini_pro" -> com.ayra.ai.app.ai.providers.AiProviderType.GEMINI
                    else -> null
                }

                val prefModel = when (tier) {
                    "gemini_flash" -> com.ayra.ai.app.ai.providers.ExternalAiProviders.GEMINI_FLASH_MODEL
                    "gemini_pro" -> com.ayra.ai.app.ai.providers.ExternalAiProviders.GEMINI_PRO_MODEL
                    else -> null
                }

                val sysOverride = when (tier) {
                    "qwen_uncensored" -> com.ayra.ai.app.ai.qwen.Qwen38UncensoredEngine.ABLITERATED_SYSTEM_PACK
                    "godmode" -> "You are G0DM0D3 Security Research Engine. Analyze technical, architectural, and security topics rigorously and directly in the user's language."
                    "ecc" -> "You are ECC Enterprise Cognitive Engine. Disambiguate entities and provide structured, factual, high-precision answers in the user's language."
                    "deepseek_harness", "deepseek_r1" -> "You are DeepSeek Chain-of-Thought Engine. Provide rigorous step-by-step reasoning and verify math/code carefully in the user's language."
                    else -> null
                }

                // Resolve active installed GitHub plugins + live OpenAPI/file tool calls
                val enabledPlugins = com.ayra.ai.app.ai.plugins.GitHubPluginRegistry.getEnabledPlugins(context)
                val activePluginCtx = if (enabledPlugins.isNotEmpty()) {
                    emit(AyraEvent.Status("Activating ${enabledPlugins.size} installed GitHub plugin(s)..."))
                    gitHubPluginEngine.buildActiveContextForPrompt(trimmed, enabledPlugins)
                } else null

                // Retrieve persistent Room Knowledge Base RAG citations when indexed documents exist
                val ragContext = runCatching {
                    val dao = ChatDatabase.getDatabase(context).knowledgeDocumentDao()
                    val (formatted, hits) = DocumentRagEngine(dao).buildCitedContext(trimmed, topK = 4)
                    if (hits.isNotEmpty()) formatted else ""
                }.getOrDefault("")
                val combinedDocText = listOfNotNull(
                    documentText?.takeIf { it.isNotBlank() },
                    ragContext.takeIf { it.isNotBlank() }
                ).joinToString("\n\n").ifBlank { null }

                val request = ChatRequest(
                    prompt = trimmed,
                    persona = persona,
                    mode = poolMode,
                    history = history,
                    imageBase64 = imageBase64,
                    documentText = combinedDocText,
                    systemPromptOverride = sysOverride,
                    preferredProvider = prefProvider,
                    preferredModelId = prefModel,
                    activePluginContext = activePluginCtx?.systemPromptInjection,
                    activePluginNames = activePluginCtx?.activePluginNames ?: emptyList()
                )

                providerPool.streamChat(request).collect { streamEvent ->
                    when (streamEvent) {
                        is AyraStreamEvent.Thinking -> {
                            emit(AyraEvent.Status("Thinking with ${streamEvent.model}..."))
                        }
                        is AyraStreamEvent.Token -> {
                            emit(AyraEvent.Token(streamEvent.text))
                        }
                        is AyraStreamEvent.Draft -> {
                            emit(AyraEvent.Status("Consensus draft received from ${streamEvent.model}"))
                        }
                        is AyraStreamEvent.Complete -> {
                            emit(AyraEvent.Complete(
                                AiResponse(
                                    text = streamEvent.response.text,
                                    modelUsed = streamEvent.response.modelUsed
                                )
                            ))
                        }
                        is AyraStreamEvent.Error -> {
                            emit(AyraEvent.Error(streamEvent.error))
                        }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
