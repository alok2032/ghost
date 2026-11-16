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
            AyraToolIntent.WIKI -> {
                val isBengali = trimmed.any { it in '\u0980'..'\u09FF' }
                val isDarkHint = trimmed.startsWith("/darkwiki", ignoreCase = true) || trimmed.startsWith("darkwiki:", ignoreCase = true)
                val isDeepHint = trimmed.startsWith("/deepwiki", ignoreCase = true) || trimmed.startsWith("deepwiki:", ignoreCase = true)
                val layerFilter = when {
                    isDarkHint -> "DARK_WEB"
                    isDeepHint -> "DEEP_WEB"
                    else -> null
                }
                val topic = trimmed.replace(Regex("(?i)^(/(wiki|deepwiki|darkwiki)|wiki:|deepwiki:|darkwiki:|উইকি:)\\s*"), "").trim()
                    .ifBlank { trimmed }
                emit(AyraEvent.Status("Searching Multi-Layer Wiki & Room Vector DB for: $topic..."))

                val lookup = runCatching {
                    AutonomousKnowledgeEngine.queryMultiLayerWikiWithVectorCache(
                        context = context,
                        prompt = if (isDarkHint && !topic.contains("tor", true) && !topic.contains("dark", true)) "$topic dark web tor" else topic,
                        isBengali = isBengali,
                        layerFilter = layerFilter,
                        forceAllLayers = true
                    )
                }.getOrElse {
                    AutonomousKnowledgeEngine.CachedWikiLookupResult(
                        wikiResult = AutonomousKnowledgeEngine.WikiLayerResult(topic),
                        usedOfflineVectorCache = true,
                        cachedNewSourcesCount = 0
                    )
                }

                val wikiResult = lookup.wikiResult
                val wikiMarkdown = wikiResult.toFormattedMarkdown()
                if (wikiMarkdown.isBlank()) {
                    emit(AyraEvent.Complete(AiResponse(
                        text = if (isBengali) "এই বিষয়ে কোনো উইকি বা অফলাইন ভেক্টর তথ্য পাওয়া যায়নি।" else "No Wiki or offline Vector DB entries matched '$topic'.",
                        modelUsed = "Ayra Multi-Layer Wiki & Vector DB 🌐"
                    )))
                    return@flow
                }

                val provenanceHeader = if (lookup.usedOfflineVectorCache) {
                    "Offline Room Vector DB Hybrid Search (${lookup.vectorHits.size} semantic matches)"
                } else {
                    "Live Multi-Layer Wiki (+${lookup.cachedNewSourcesCount} cached offline)"
                }

                var completed = false
                val wikiSysPrompt = """
                    You are Ayra AI Multi-Layer Wiki Synthesizer ($provenanceHeader).
                    Synthesize a crisp, encyclopedic, verified response on "$topic" using the provided Wiki / Vector DB sources.
                    Preserve factual accuracy, distinguish Surface Wiki, Deep Web Academic/CVE insights, and Dark Web Technical/Tor notes cleanly, and cite the verified URLs returned.
                    Respond in ${if (isBengali) "Bengali (বাংলা)" else "English"}.
                """.trimIndent()

                providerPool.streamChat(
                    ChatRequest(
                        prompt = "Synthesize verified multi-layer Wiki encyclopedia report for: $topic",
                        persona = persona,
                        mode = ProviderPoolMode.FAST,
                        history = history,
                        documentText = wikiMarkdown,
                        systemPromptOverride = wikiSysPrompt
                    )
                ).collect { streamEvent ->
                    when (streamEvent) {
                        is AyraStreamEvent.Thinking -> emit(AyraEvent.Status("Synthesizing Wiki via ${streamEvent.model}..."))
                        is AyraStreamEvent.Token -> emit(AyraEvent.Token(streamEvent.text))
                        is AyraStreamEvent.Draft -> {}
                        is AyraStreamEvent.Complete -> {
                            completed = true
                            val cites = if (wikiResult.citations.isNotEmpty() && !streamEvent.response.text.contains("http")) {
                                "\n\n### Verified Sources ($provenanceHeader)\n" + wikiResult.citations.distinctBy { it.url }.take(6)
                                    .joinToString("\n") { "• [${it.layer}] ${it.title}: ${it.url}" }
                            } else ""
                            emit(AyraEvent.Complete(AiResponse(
                                text = streamEvent.response.text + cites,
                                modelUsed = "Ayra Wiki (${streamEvent.response.modelUsed}) 🌐"
                            )))
                        }
                        is AyraStreamEvent.Error -> {}
                    }
                }
                if (!completed) {
                    val directBanner = if (lookup.usedOfflineVectorCache) {
                        "*[Serving fast offline results from Local Room Vector Database]*\n\n"
                    } else ""
                    emit(AyraEvent.Complete(AiResponse(
                        text = "$directBanner$wikiMarkdown",
                        modelUsed = if (lookup.usedOfflineVectorCache) "Ayra Offline Vector DB ⚡" else "Ayra Multi-Layer Wiki Direct 🌐"
                    )))
                }
                return@flow
            }
            AyraToolIntent.DEEP_RESEARCH -> {
                val isBengali = trimmed.any { it in '\u0980'..'\u09FF' }
                val topic = trimmed.replace(Regex("(?i)^(deep research|ডিপ রিসার্চ|গভীর গবেষণা|research on|গবেষণা করো|research:)\\s*"), "").trim()
                    .ifBlank { trimmed }
                emit(AyraEvent.Status("Gathering web & academic sources for: $topic..."))

                var liveGrounding = ""
                var citationsBlock = ""
                runCatching {
                    if (com.ayra.ai.app.data.AyraPreferences.getTavilySearchKey(context).isNotBlank()) {
                        val searchRes = com.ayra.ai.app.search.TavilySearchEngine(context).search(topic, maxResults = 3)
                        if (searchRes.formattedSummary.isNotBlank()) {
                            liveGrounding = searchRes.formattedSummary
                            citationsBlock = searchRes.formattedSummary.substringAfter("### 📚 Grounded Web Sources", "")
                        }
                    }
                }
                if (liveGrounding.isBlank()) {
                    liveGrounding = runCatching {
                        AutonomousKnowledgeEngine.queryMultiLayerWikiWithVectorCache(
                            context = context,
                            prompt = topic,
                            isBengali = isBengali,
                            forceAllLayers = true
                        ).wikiResult.toFormattedMarkdown()
                    }.getOrDefault("")
                }

                val researchSysPrompt = """
                    You are Ayra AI Deep Research Engine. Perform an exhaustive, authoritative, graduate-level research analysis on: $topic.
                    Use the user's language (${if (isBengali) "Bengali (বাংলা)" else "English"}).
                    Format clearly with markdown sections: Executive Thesis, Theoretical Foundations, Empirical State-of-the-Art, Trade-offs & Bottlenecks, Future Outlook, and Conclusion.
                """.trimIndent()

                val combinedDoc = listOfNotNull(
                    documentText?.takeIf { it.isNotBlank() },
                    liveGrounding.takeIf { it.isNotBlank() }?.let { "=== VERIFIED LIVE GROUNDING ===\n$it" }
                ).joinToString("\n\n").ifBlank { null }

                var completed = false
                var lastStreamError: AyraError? = null
                providerPool.streamChat(
                    ChatRequest(
                        prompt = trimmed,
                        persona = persona,
                        mode = ProviderPoolMode.BEST,
                        history = history,
                        imageBase64 = imageBase64,
                        documentText = combinedDoc,
                        systemPromptOverride = researchSysPrompt
                    )
                ).collect { streamEvent ->
                    when (streamEvent) {
                        is AyraStreamEvent.Thinking -> emit(AyraEvent.Status("Synthesizing research with ${streamEvent.model}..."))
                        is AyraStreamEvent.Token -> emit(AyraEvent.Token(streamEvent.text))
                        is AyraStreamEvent.Draft -> emit(AyraEvent.Status("Research draft received from ${streamEvent.model}"))
                        is AyraStreamEvent.Complete -> {
                            completed = true
                            val baseText = streamEvent.response.text
                            val fullText = if (citationsBlock.isNotBlank() && !baseText.contains("### 📚 Grounded Web Sources")) {
                                "$baseText\n\n### 📚 Grounded Web Sources$citationsBlock"
                            } else baseText
                            emit(AyraEvent.Complete(AiResponse(text = fullText, modelUsed = "Ayra Deep Research (${streamEvent.response.modelUsed}) 🔬")))
                        }
                        is AyraStreamEvent.Error -> {
                            lastStreamError = streamEvent.error
                        }
                    }
                }
                if (!completed) {
                    if (liveGrounding.isNotBlank()) {
                        val fallbackText = if (isBengali) {
                            "# 🔬 ডিপ রিসার্চ: $topic\n\n*(এআই সিন্থেসিস সাময়িকভাবে অনুপলব্ধ; নিচে যাচাইকৃত লাইভ তথ্য দেখানো হলো)*\n\n$liveGrounding"
                        } else {
                            "# 🔬 Deep Research: $topic\n\n*(AI synthesis currently unreachable; showing verified live web evidence)*\n\n$liveGrounding"
                        }
                        emit(AyraEvent.Complete(AiResponse(text = fallbackText, craftType = "RESEARCH", modelUsed = "Verified Web Evidence 🌐")))
                    } else {
                        emit(AyraEvent.Error(lastStreamError ?: AyraError.NoNetwork("Deep Research providers and network grounding unavailable.")))
                    }
                }
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
                val isBengali = trimmed.any { it in '\u0980'..'\u09FF' }
                // Stream explicit Superpowers protocol live through ProviderPool BEST
                val superpower = com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.detectSuperpower(trimmed)
                if (superpower != null || tier == "superpowers") {
                    val activeSp = superpower ?: com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.SuperpowerType.SYSTEMATIC_DEBUGGING
                    emit(AyraEvent.Status("Running ${activeSp.displayName}..."))
                    val spPrompt = com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.buildSuperpowerSystemPrompt(activeSp, isBengali, context)
                    val ragCtx = runCatching {
                        val dao = ChatDatabase.getDatabase(context).knowledgeDocumentDao()
                        val (formatted, hits) = DocumentRagEngine(dao).buildCitedContext(trimmed, topK = 4)
                        if (hits.isNotEmpty()) formatted else ""
                    }.getOrDefault("")
                    val docMerged = listOfNotNull(documentText?.takeIf { it.isNotBlank() }, ragCtx.takeIf { it.isNotBlank() }).joinToString("\n\n").ifBlank { null }

                    providerPool.streamChat(
                        ChatRequest(
                            prompt = trimmed,
                            persona = persona,
                            mode = ProviderPoolMode.BEST,
                            history = history,
                            imageBase64 = imageBase64,
                            documentText = docMerged,
                            systemPromptOverride = spPrompt
                        )
                    ).collect { streamEvent ->
                        when (streamEvent) {
                            is AyraStreamEvent.Thinking -> emit(AyraEvent.Status("${activeSp.displayName} • ${streamEvent.model}..."))
                            is AyraStreamEvent.Token -> emit(AyraEvent.Token(streamEvent.text))
                            is AyraStreamEvent.Draft -> emit(AyraEvent.Status("Superpowers draft from ${streamEvent.model}"))
                            is AyraStreamEvent.Complete -> emit(AyraEvent.Complete(AiResponse(text = streamEvent.response.text, modelUsed = "${activeSp.displayName} (${streamEvent.response.modelUsed})")))
                            is AyraStreamEvent.Error -> emit(AyraEvent.Error(streamEvent.error))
                        }
                    }
                    return@flow
                }

                val externalProviders = com.ayra.ai.app.ai.providers.ExternalAiProviders(context)
                val wsCtx = WorkspaceContextBuilder.resolve(context, persona, tier)
                val effectiveTier = wsCtx.effectiveTier
                val effectivePersona = wsCtx.effectivePersona

                // Main CHAT & Coding execution through Unified Multi-AI ProviderPool
                val poolMode = when (effectiveTier) {
                    "omniroute", "quality", "gemini_pro", "deepseek_harness", "deepseek_r1", "godmode", "ecc", "higgsfield", "qwen_uncensored" -> ProviderPoolMode.BEST
                    "fast", "gemini_flash" -> ProviderPoolMode.FAST
                    else -> when (wsCtx.effectiveThinkingMode) {
                        "fast" -> ProviderPoolMode.FAST
                        "deep_analysis", "reasoning", "research" -> ProviderPoolMode.BEST
                        else -> ProviderPoolMode.AUTO
                    }
                }

                val (prefProvider, prefModel) = when (effectiveTier) {
                    "gemini_flash" -> com.ayra.ai.app.ai.providers.AiProviderType.GEMINI to com.ayra.ai.app.ai.providers.ExternalAiProviders.GEMINI_FLASH_MODEL
                    "gemini_pro" -> com.ayra.ai.app.ai.providers.AiProviderType.GEMINI to com.ayra.ai.app.ai.providers.ExternalAiProviders.GEMINI_PRO_MODEL
                    "deepseek_harness", "deepseek_r1" -> {
                        listOf(
                            com.ayra.ai.app.ai.providers.AiProviderType.DEEPSEEK to "deepseek-reasoner",
                            com.ayra.ai.app.ai.providers.AiProviderType.SAMBANOVA to com.ayra.ai.app.ai.providers.ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL,
                            com.ayra.ai.app.ai.providers.AiProviderType.OPENROUTER to "deepseek/deepseek-r1:free"
                        ).firstOrNull { externalProviders.isProviderConfigured(it.first) } ?: (null to null)
                    }
                    "llama_3_3" -> {
                        listOf(
                            com.ayra.ai.app.ai.providers.AiProviderType.GROQ to com.ayra.ai.app.ai.providers.ExternalAiProviders.GROQ_LLAMA_MODEL,
                            com.ayra.ai.app.ai.providers.AiProviderType.CEREBRAS to com.ayra.ai.app.ai.providers.ExternalAiProviders.CEREBRAS_LLAMA_MODEL,
                            com.ayra.ai.app.ai.providers.AiProviderType.SAMBANOVA to com.ayra.ai.app.ai.providers.ExternalAiProviders.SAMBANOVA_LLAMA_MODEL,
                            com.ayra.ai.app.ai.providers.AiProviderType.OPENROUTER to "meta-llama/llama-3.3-70b-instruct:free"
                        ).firstOrNull { externalProviders.isProviderConfigured(it.first) } ?: (null to null)
                    }
                    "qwen_uncensored" -> {
                        listOf(
                            com.ayra.ai.app.ai.providers.AiProviderType.LOCAL_OLLAMA to com.ayra.ai.app.ai.qwen.Qwen38UncensoredEngine.OLLAMA_DEFAULT_SERVING,
                            com.ayra.ai.app.ai.providers.AiProviderType.OPENROUTER to "qwen/qwen-2.5-72b-instruct",
                            com.ayra.ai.app.ai.providers.AiProviderType.HUGGINGFACE to com.ayra.ai.app.ai.providers.ExternalAiProviders.HF_QWEN_MODEL,
                            com.ayra.ai.app.ai.providers.AiProviderType.GROQ to com.ayra.ai.app.ai.providers.ExternalAiProviders.GROQ_QWEN_MODEL,
                            com.ayra.ai.app.ai.providers.AiProviderType.CEREBRAS to com.ayra.ai.app.ai.providers.ExternalAiProviders.CEREBRAS_QWEN_MODEL
                        ).firstOrNull { externalProviders.isProviderConfigured(it.first) } ?: (null to null)
                    }
                    else -> null to null
                }

                val godModeGrounding = if (effectiveTier == "godmode") {
                    com.ayra.ai.app.ai.godmode.GodModePliniusEngine(context).buildSecurityGrounding(trimmed)
                } else ""

                val sysOverride = when (effectiveTier) {
                    "qwen_uncensored" -> com.ayra.ai.app.ai.qwen.Qwen38UncensoredEngine.ABLITERATED_SYSTEM_PACK
                    "godmode" -> buildString {
                        append("You are G0DM0D3 Security Research Engine (Plinius Architecture). Analyze technical, architectural, and security topics rigorously and directly in the user's language.")
                        if (godModeGrounding.isNotBlank()) append("\n\n").append(godModeGrounding)
                    }
                    "ecc" -> {
                        val cognitive = com.ayra.ai.app.ai.ecc.EnterpriseCognitiveEngine(context).extractCognitiveContext(trimmed)
                        "You are ECC Enterprise Cognitive Engine. Domain: ${cognitive.domain}. Key Entities: ${cognitive.detectedEntities.joinToString(", ").ifBlank { "General" }}. Disambiguate entities and provide structured, factual, high-precision answers in the user's language."
                    }
                    "deepseek_harness", "deepseek_r1" -> "You are DeepSeek-R1 Chain-of-Thought Engine. Provide rigorous step-by-step reasoning and verify math/code carefully in the user's language."
                    "higgsfield" -> "You are Open-Higgsfield Cinematography & Motion Physics Director. Design camera trajectories, optical lenses, and lighting scenes precisely in the user's language."
                    else -> null
                }

                // Resolve active installed GitHub plugins + live OpenAPI/file tool calls
                val enabledPlugins = com.ayra.ai.app.ai.plugins.GitHubPluginRegistry.getEnabledPlugins(context)
                val activePluginCtx = if (enabledPlugins.isNotEmpty()) {
                    emit(AyraEvent.Status("Activating ${enabledPlugins.size} installed GitHub plugin(s)..."))
                    gitHubPluginEngine.buildActiveContextForPrompt(trimmed, enabledPlugins)
                } else null

                // Retrieve persistent Room Knowledge Base RAG citations + Dark/Deep Web Vector DB grounding
                val db = ChatDatabase.getDatabase(context)
                val ragContext = runCatching {
                    val (formatted, hits) = DocumentRagEngine(db.knowledgeDocumentDao(), context).buildCitedContext(trimmed, topK = 4)
                    if (hits.isNotEmpty()) formatted else ""
                }.getOrDefault("")
                val vectorContext = runCatching {
                    val minThreshold = if (GlobalWebKnowledgeGateway.isDarkWebOrNetworkSecurityQuery(trimmed)) 0.25 else 0.44
                    val vecRepo = com.ayra.ai.app.ai.rag.VectorKnowledgeRepository(db.vectorKnowledgeDao(), context)
                    val (formattedVec, vecHits) = vecRepo.buildCitedVectorContext(trimmed, topK = 3, minScore = minThreshold)
                    if (vecHits.isNotEmpty()) formattedVec else ""
                }.getOrDefault("")
                val combinedDocText = listOfNotNull(
                    documentText?.takeIf { it.isNotBlank() },
                    ragContext.takeIf { it.isNotBlank() },
                    vectorContext.takeIf { it.isNotBlank() }
                ).joinToString("\n\n").ifBlank { null }

                val request = ChatRequest(
                    prompt = trimmed,
                    persona = effectivePersona,
                    mode = poolMode,
                    history = history,
                    imageBase64 = imageBase64,
                    documentText = combinedDocText,
                    systemPromptOverride = sysOverride,
                    preferredProvider = prefProvider,
                    preferredModelId = prefModel,
                    activePluginContext = activePluginCtx?.systemPromptInjection,
                    activePluginNames = activePluginCtx?.activePluginNames ?: emptyList(),
                    thinkingMode = wsCtx.effectiveThinkingMode,
                    workspaceContext = wsCtx.workspacePromptInjection.ifBlank { null }
                )

                var terminalEmitted = false
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
                            terminalEmitted = true
                            emit(AyraEvent.Complete(
                                AiResponse(
                                    text = streamEvent.response.text,
                                    modelUsed = streamEvent.response.modelUsed
                                )
                            ))
                        }
                        is AyraStreamEvent.Error -> {
                            terminalEmitted = true
                            emit(AyraEvent.Error(streamEvent.error))
                        }
                    }
                }
                if (!terminalEmitted) {
                    emit(AyraEvent.Error(AyraError.ProviderDown(com.ayra.ai.app.ai.providers.AiProviderType.GEMINI, 502, "No response received from AI stream.")))
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
