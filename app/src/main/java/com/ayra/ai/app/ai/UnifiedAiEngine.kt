package com.ayra.ai.app.ai

import android.content.Context
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelTier
import com.ayra.ai.app.data.AyraPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class AiResponse(
    val text: String,
    val craftType: String? = null,
    val craftPayload: String? = null,
    val modelUsed: String? = null
)

enum class AiModelTier(
    val id: String,
    val displayName: String,
    val providerName: String,
    val speedLabel: String,
    val reasoningLabel: String,
    val description: String,
    val accentColorHex: Long
) {
    AUTO(
        id = "auto",
        displayName = "Auto Adaptive Router ⚡",
        providerName = "OmniRoute Intelligent Matrix",
        speedLabel = "Adaptive ⚡",
        reasoningLabel = "Dynamic 🧠",
        description = "OmniRoute স্মার্ট মাল্টি-প্রোভাইডার অপটিমাইজেশন — সাধারণ প্রশ্নে ফ্ল্যাশ ও জটিল বিষয়ে ডিপ রিজনিং",
        accentColorHex = 0xFF00FFFF
    ),
    OMNI_ROUTE(
        id = "omniroute",
        displayName = "OmniRoute Ensemble ⚡👑",
        providerName = "cheaper-inference/OmniRoute",
        speedLabel = "Consensus Synergy ⚡",
        reasoningLabel = "Multi-Model Fusion 👑",
        description = "বিশ্বের সেরা ফলাফল পাওয়ার জন্য সকল মডেলের সম্মিলিত জ্ঞান ও সিনার্জি ভিত্তিক উত্তর",
        accentColorHex = 0xFFFFD700
    ),
    GEMINI_FLASH(
        id = "gemini_flash",
        displayName = "Gemini 3.6 Flash Live ⚡",
        providerName = "Google AI / Firebase AI Logic",
        speedLabel = "Ultra Fast ⚡⚡",
        reasoningLabel = "High + Web Grounding 🌐",
        description = "জিরো-ল্যাটেন্সি দ্রুততম প্রতিক্রিয়া, দৈনন্দিন আলাপচারিতা, ফটো/পিডিএফ অ্যানালাইসিস ও লাইভ সার্চের জন্য সেরা",
        accentColorHex = 0xFF00FF7F
    ),
    GEMINI_PRO(
        id = "gemini_pro",
        displayName = "Gemini 3.1 Pro Deep Reasoning 🧠",
        providerName = "Google AI Frontier",
        speedLabel = "Deep Reasoning ⏳",
        reasoningLabel = "Chain-of-Thought & Coding 🧠🧠",
        description = "উন্নত ওয়েবসাইট তৈরি, অ্যান্ড্রয়েড অ্যাপ ডেভেলপমেন্ট, জটিল গণিত, বিজ্ঞান ও ডিপ রিসার্চ",
        accentColorHex = 0xFFB388FF
    ),
    DEEPSEEK_HARNESS(
        id = "deepseek_harness",
        displayName = "DeepSeek-R1 (Harness CoT) 🔬",
        providerName = "deepseek-ai/deepseek-harness",
        speedLabel = "Thinking Chain ⏳",
        reasoningLabel = "Chain-of-Thought 🧠🧠🧠",
        description = "যৌক্তিক ধাপভিত্তিক বিশ্লেষণ, গাণিতিক প্রমাণ ও স্বয়ংক্রিয় টেস্ট হারনেস যাচাই",
        accentColorHex = 0xFFFF80AB
    ),
    OPENROUTER_DEEPSEEK(
        id = "deepseek_r1",
        displayName = "DeepSeek-R1 (Chain-of-Thought) 🧠",
        providerName = "DeepSeek / OpenRouter",
        speedLabel = "Thinking Chain ⏳",
        reasoningLabel = "Chain-of-Thought 🧠🧠🧠",
        description = "উন্মুক্ত চেইন-অফ-থট রিজনিং ও যৌক্তিক ধাপভিত্তিক সমস্যা সমাধান",
        accentColorHex = 0xFFFF80AB
    ),
    OPENROUTER_LLAMA(
        id = "llama_3_3",
        displayName = "Llama 3.3 70B Instruct 🌐",
        providerName = "Meta / OpenRouter",
        speedLabel = "Fast ⚡",
        reasoningLabel = "Very High 🧠",
        description = "বহুভাষিক সাহিত্য রচনা, অনুবাদ ও গভীর জ্ঞানভিত্তিক কথোপকথন",
        accentColorHex = 0xFFFFD700
    ),
    OPEN_WEIGHTS(
        id = "open_weights",
        displayName = "Open-Weights Engine 🌐",
        providerName = "Local Ollama / OpenRouter Qwen",
        speedLabel = "Fast / Private ⚡",
        reasoningLabel = "Open-Weights 🧠",
        description = "ওপেন-ওয়েটস লোকাল ও ক্লাউড মডেল — নির্ভরযোগ্য, বস্তুনিষ্ঠ ও নিরাপদ প্রযুক্তিগত বিশ্লেষণ",
        accentColorHex = 0xFFFF5722
    ),
    SECURITY_RESEARCH(
        id = "security_research",
        displayName = "Security Research Mode 🛡️",
        providerName = "Ayra Defensive Security",
        speedLabel = "High-Throughput ⚡",
        reasoningLabel = "Defensive Security 🛡️",
        description = "ডিফেনসিভ সাইবার সিকিউরিটি, OWASP অডিট, CVE মিটিগেশন ও নিরাপদ সিস্টেম আর্কিটেকচার",
        accentColorHex = 0xFF00E5FF
    ),
    ECC_COGNITIVE(
        id = "ecc",
        displayName = "ECC Enterprise Cognitive 🌐",
        providerName = "affaan-m/ECC",
        speedLabel = "Cognitive Graph ⚡",
        reasoningLabel = "Context-Memory 🧠",
        description = "কনটেক্সট মেমরি গ্রাফ, ইন্টেন্ট ডিসঅ্যাম্বিগিউয়েশন ও ফ্যাক্টুয়াল গ্রাউন্ডিং",
        accentColorHex = 0xFF7C4DFF
    ),
    OPEN_HIGGSFIELD(
        id = "higgsfield",
        displayName = "Open-Higgsfield-AI Studio 🎬",
        providerName = "Autom8AI/Open-Higgsfield-AI",
        speedLabel = "Cinematic Physics 🎥",
        reasoningLabel = "Photometric Optics 🎨",
        description = "সিনেমাটিক ভিডিও জেনারেশন, ক্যামেরা মোশন ডিরেকশন ও অপটিক্যাল ফিজিক্স প্রম্পট ইঞ্জিন",
        accentColorHex = 0xFFFF4081
    ),
    SUPERPOWERS(
        id = "superpowers",
        displayName = "Superpowers Agent Engine ⚙️",
        providerName = "obra/superpowers",
        speedLabel = "Multi-Stage Agent 🤖",
        reasoningLabel = "TDD & Code Review 🔬",
        description = "সিস্টেমেটিক ডিবাগিং, টেস্ট ড্রিভেন ডেভেলপমেন্ট ও কোড রিভিউ অডিট গেট",
        accentColorHex = 0xFF00E676
    );

    fun toModelTier(): ModelTier = ModelTier.fromId(id)

    companion object {
        fun fromModelTier(tier: ModelTier): AiModelTier =
            entries.firstOrNull { it.id == tier.id } ?: AUTO
    }
}

class UnifiedAiEngine @JvmOverloads constructor(
    private val context: Context? = null,
    private val injectedExternalAiProviders: ExternalAiProviders? = null
) {

    companion object {
        private data class TimedAiResponse(val response: AiResponse, val timestampMs: Long)
        private val memoryResponseCache = java.util.concurrent.ConcurrentHashMap<String, TimedAiResponse>()
        private const val MAX_CACHE_ENTRIES = 300
        private const val RESPONSE_CACHE_TTL_MS = 5 * 60 * 1000L

        fun clearCache() {
            memoryResponseCache.clear()
        }

        fun getCachedResponse(key: String): AiResponse? {
            val entry = memoryResponseCache[key] ?: return null
            if (System.currentTimeMillis() - entry.timestampMs > RESPONSE_CACHE_TTL_MS) {
                memoryResponseCache.remove(key)
                return null
            }
            return entry.response
        }

        fun isCyberTerminalIntent(text: String): Boolean =
            AyraIntentClassifier.classifyIntent(text) == AyraToolIntent.TERMINAL

        fun isPdfStudioIntent(text: String, docText: String? = null, imageBase64: String? = null): Boolean =
            AyraIntentClassifier.classifyIntent(text) == AyraToolIntent.PDF

        fun isVideoGenerationIntent(text: String): Boolean =
            AyraIntentClassifier.classifyIntent(text) == AyraToolIntent.VIDEO

        fun isImageGenerationIntent(text: String): Boolean =
            AyraIntentClassifier.classifyIntent(text) == AyraToolIntent.IMAGE

        fun isSongGenerationIntent(text: String): Boolean =
            AyraIntentClassifier.classifyIntent(text) == AyraToolIntent.SONG

        fun is3dModelIntent(text: String): Boolean =
            AyraIntentClassifier.classifyIntent(text) == AyraToolIntent.MODEL_3D

        fun isDeepResearchIntent(text: String): Boolean =
            AyraIntentClassifier.classifyIntent(text) == AyraToolIntent.DEEP_RESEARCH
    }

    private val httpClient: OkHttpClient
        get() = ExternalAiProviders.sharedPooledHttpClient

    private val fastWebClient: OkHttpClient
        get() = ExternalAiProviders.sharedFastWebClient

    val externalAiProviders by lazy { injectedExternalAiProviders ?: ExternalAiProviders(context) }
    val omniRouteEngine by lazy { context?.let { com.ayra.ai.app.ai.omniroute.OmniRouteEngine(it) } }
    val deepSeekHarness by lazy { com.ayra.ai.app.ai.deepseek.DeepSeekHarnessEngine(context) }
    val eccEngine by lazy { com.ayra.ai.app.ai.ecc.EnterpriseCognitiveEngine(context) }
    val securityResearchEngine by lazy { com.ayra.ai.app.ai.securityresearch.SecurityResearchEngine(context) }
    val higgsfieldEngine by lazy { com.ayra.ai.app.ai.higgsfield.OpenHiggsfieldEngine(context) }
    val geminiCli by lazy { com.ayra.ai.app.ai.gemini.GeminiCliEngine(context) }
    val firebaseAiEngine by lazy { FirebaseAiEngine(context) }

    private val craftGenerators by lazy {
        AiCraftGenerators(context, externalAiProviders, higgsfieldEngine, firebaseAiEngine)
    }

    private val specializedExecutors by lazy {
        AiSpecializedExecutors(context, externalAiProviders, httpClient, fastWebClient)
    }

    private fun hasCloudOrFirebaseAi(): Boolean =
        context != null && (
            externalAiProviders.hasAnyConfiguredProvider() ||
                FirebaseAiEngine.isAvailable(context)
            )

    private val geminiApiKey: String
        get() = AyraPreferences.getEffectiveGeminiKey(context)

    private val openRouterApiKey: String
        get() = AyraPreferences.getEffectiveOpenRouterKey(context)

    var currentPersona: String = "AI_ASSISTANT"
    var selectedModelTier: String = "auto"

    suspend fun generateUnifiedResponse(
        prompt: String,
        attachedImageBase64: String? = null,
        attachedDocumentText: String? = null,
        attachedDocumentName: String? = null,
        personaMode: String = "AI_ASSISTANT",
        modelTier: String = selectedModelTier,
        conversationHistory: List<com.ayra.ai.app.data.ChatMessage>? = null
    ): AiResponse = withContext(Dispatchers.IO) {
        currentPersona = AyraPreferences.resolveSafePersona(context, personaMode)
        selectedModelTier = ModelTier.canonicalizeId(modelTier)

        val cleanPrompt = prompt.trim()
        val effectivePrompt = if (!attachedDocumentText.isNullOrBlank()) {
            val docName = attachedDocumentName ?: "Document"
            val userInstruction = if (cleanPrompt.isNotBlank()) cleanPrompt else "Analyze this document completely, summarize its contents, highlight all key details, and suggest any edits."
            "=== ATTACHED DOCUMENT: $docName ===\n$attachedDocumentText\n=== END OF DOCUMENT ===\n\nUser Question/Request:\n$userInstruction"
        } else if (cleanPrompt.isBlank() && attachedImageBase64 != null) {
            "Please analyze this uploaded photo in detail: describe everything visible, detect objects, read any text, and explain the context."
        } else {
            cleanPrompt
        }

        val langPref = context?.let { AyraPreferences.getPreferredLanguage(it) } ?: "Auto"
        val localOnly = context?.let { AyraPreferences.isLocalOnlyAiModeEnabled(it) } ?: false
        val privacyMode = context?.let { AyraPreferences.isPrivacyModeEnabled(it) } ?: false
        val isTimeSensitive = com.ayra.ai.app.ai.providers.ProviderPool.isTimeSensitiveQuery(cleanPrompt)
        val cacheKey = "${currentPersona}_${selectedModelTier}_${langPref}_${localOnly}_${privacyMode}_${cleanPrompt.lowercase()}"
        if (!isTimeSensitive && attachedImageBase64 == null && attachedDocumentText == null && conversationHistory.isNullOrEmpty() && cleanPrompt.isNotBlank()) {
            getCachedResponse(cacheKey)?.let { return@withContext it }
        }

        when (AyraIntentClassifier.classifyIntent(cleanPrompt)) {
            AyraToolIntent.PDF -> return@withContext specializedExecutors.executePdfStudioAction(cleanPrompt, attachedDocumentText, attachedDocumentName, currentPersona, attachedImageBase64)
            AyraToolIntent.IMAGE -> return@withContext craftGenerators.generateImageCraft(cleanPrompt, currentPersona, attachedImageBase64)
            AyraToolIntent.VIDEO -> return@withContext craftGenerators.generateVideoCraft(cleanPrompt, currentPersona, attachedImageBase64)
            AyraToolIntent.SONG -> return@withContext craftGenerators.generateSongCraft(cleanPrompt, currentPersona)
            AyraToolIntent.MODEL_3D -> return@withContext craftGenerators.generate3dModelCraft(cleanPrompt, currentPersona)
            AyraToolIntent.DEEP_RESEARCH -> return@withContext specializedExecutors.executeDeepResearch(cleanPrompt, effectivePrompt, currentPersona, attachedImageBase64)
            else -> {}
        }

        val superpower = com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.detectSuperpower(cleanPrompt)
        if (superpower != null) {
            return@withContext specializedExecutors.executeSuperpowerAction(superpower, cleanPrompt, effectivePrompt, currentPersona, attachedImageBase64)
        }

        val isBengali = cleanPrompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"

        if (AyraIntentClassifier.classifyIntent(cleanPrompt) == AyraToolIntent.TERMINAL) {
            val initialCmd = specializedExecutors.extractTerminalInitialCommand(cleanPrompt)
            val reply = if (isBengali) {
                if (isGfMode) {
                    "🛡️ সোনা, তোমার জন্য ইন-অ্যাপ **Cyber Security Terminal & Defensive Lab** সক্রিয় করেছি!\nনিচে রিয়েল-টাইম কমান্ড দিয়ে পিং, ট্রেসরুট, ডিআইজি, বা ডায়াগনস্টিক করতে পারবে ❤️:"
                } else {
                    "🛡️ **Ayra AI Cyber Security Educational Terminal & Defensive Lab** চালু করা হয়েছে। নিচে অনুমোদিত কমান্ড দিয়ে ডায়াগনস্টিক পরিচালনা করুন:"
                }
            } else {
                "🛡️ **Ayra AI Cyber Security Educational Terminal & Defensive Lab** active. Run network diagnostics, cryptographic digests, and audits in the interactive console below:"
            }
            return@withContext AiResponse(
                text = reply,
                craftType = "CYBER_TERMINAL",
                craftPayload = initialCmd,
                modelUsed = "Ayra AI Cyber Defense Terminal 🛡️"
            )
        }

        if (BuildYourOwnXKnowledgeEngine.isBuildYourOwnXIntent(cleanPrompt)) {
            if (hasCloudOrFirebaseAi() && context != null) {
                try {
                    val byoxPrompt = "Write a complete, real, working architectural breakdown and full clean code implementation from scratch for:\n$cleanPrompt\nInclude clear step-by-step explanations and production-ready code without placeholders."
                    val aiRes = com.ayra.ai.app.ai.providers.ProviderPool(context, externalAiProviders).executeSync(
                        com.ayra.ai.app.ai.providers.ChatRequest(
                            prompt = byoxPrompt,
                            persona = currentPersona,
                            mode = com.ayra.ai.app.ai.providers.ProviderPoolMode.BEST,
                            history = conversationHistory ?: emptyList(),
                            imageBase64 = attachedImageBase64,
                            documentText = attachedDocumentText
                        )
                    )
                    if (aiRes.text.isNotBlank()) {
                        return@withContext AiResponse(
                            text = aiRes.text,
                            craftType = "CODE_PROJECT",
                            craftPayload = cleanPrompt,
                            modelUsed = "BuildYourOwnX Multi-AI (${aiRes.modelUsed})"
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("UnifiedAiEngine", "BYOX Pool fallback: ${e.message}")
                }
            }
            val byoxFallback = BuildYourOwnXKnowledgeEngine.synthesizeBuildYourOwnX(cleanPrompt, isBengali, isGfMode)
            val byoxNotice = if (isBengali) {
                "⚠️ *লাইভ এআই সংযোগ পাওয়া যায়নি — নিচে কিউরেটেড রেফারেন্স ইমপ্লিমেন্টেশন দেখানো হলো:*\n\n"
            } else {
                "⚠️ *AI service offline — showing curated reference implementation:*\n\n"
            }
            return@withContext byoxFallback.copy(
                text = byoxNotice + byoxFallback.text,
                modelUsed = "${byoxFallback.modelUsed} (Offline Reference)"
            )
        }

        if (AiAppAndCodeStudio.isCodeOrAppGenerationIntent(cleanPrompt)) {
            if (hasCloudOrFirebaseAi() && context != null) {
                try {
                    val codePrompt = "Write COMPLETE, PRODUCTION-READY, FULLY FUNCTIONAL, SELF-CONTAINED code for this request without any placeholder comments or stub functions:\n$effectivePrompt"
                    val aiRes = com.ayra.ai.app.ai.providers.ProviderPool(context, externalAiProviders).executeSync(
                        com.ayra.ai.app.ai.providers.ChatRequest(
                            prompt = codePrompt,
                            persona = currentPersona,
                            mode = com.ayra.ai.app.ai.providers.ProviderPoolMode.BEST,
                            history = conversationHistory ?: emptyList(),
                            imageBase64 = attachedImageBase64,
                            documentText = attachedDocumentText
                        )
                    )
                    if (aiRes.text.isNotBlank()) {
                        return@withContext AiResponse(
                            text = aiRes.text,
                            craftType = "CODE_PROJECT",
                            craftPayload = cleanPrompt,
                            modelUsed = "Code Architect (${aiRes.modelUsed}) 🚀"
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("UnifiedAiEngine", "Code Studio Pool fallback: ${e.message}")
                }
            }
            val geminiAllowed = externalAiProviders.isTestHarnessMode ||
                com.ayra.ai.app.security.SecureAiGatewayClient.evaluateProviderAccess(context, com.ayra.ai.app.ai.providers.AiProviderType.GEMINI).allowed
            if (geminiApiKey.isNotBlank() && geminiAllowed) {
                val codePrompt = "Write COMPLETE, PRODUCTION-READY, FULLY FUNCTIONAL, SELF-CONTAINED code for this request without any placeholder comments:\n$cleanPrompt"
                val res = specializedExecutors.queryGeminiPro(codePrompt, attachedImageBase64, currentPersona)
                if (res.isNotBlank()) {
                    return@withContext AiResponse(text = res, craftType = "CODE_PROJECT", craftPayload = cleanPrompt, modelUsed = "Gemini 3.1 Pro Code Architect 🚀")
                }
            }
            val codeFallback = AiAppAndCodeStudio.generateCodeOrApp(cleanPrompt, isBengali, isGfMode)
            val codeNotice = if (isBengali) {
                "⚠️ *লাইভ এআই সংযোগ পাওয়া যায়নি — নিচে অফলাইন টেমপ্লেট দেখানো হলো:*\n\n"
            } else {
                "⚠️ *AI service offline — showing offline starter implementation:*\n\n"
            }
            return@withContext codeFallback.copy(
                text = codeNotice + codeFallback.text,
                modelUsed = "${codeFallback.modelUsed} (Offline Template)"
            )
        }

        val canonicalTier = ModelTier.fromId(selectedModelTier)
        val (targetTier, modelDisplayName) = if (canonicalTier == ModelTier.AUTO &&
            !selectedModelTier.equals("fast", ignoreCase = true) &&
            !selectedModelTier.equals("quality", ignoreCase = true)
        ) {
            determineOptimalModel(effectivePrompt)
        } else {
            val mapped = AiModelTier.fromModelTier(canonicalTier)
            mapped to canonicalTier.displayName
        }

        val shouldCache = (!isTimeSensitive && attachedImageBase64 == null && attachedDocumentText == null && conversationHistory.isNullOrEmpty() && cleanPrompt.isNotBlank())
        fun emitResponse(resp: AiResponse): AiResponse {
            if (shouldCache && resp.craftType == null && resp.modelUsed != "Offline Status") {
                if (memoryResponseCache.size > MAX_CACHE_ENTRIES) {
                    memoryResponseCache.clear()
                }
                memoryResponseCache[cacheKey] = TimedAiResponse(resp, System.currentTimeMillis())
            }
            return resp
        }

        val omni = omniRouteEngine
        if (omni != null && canonicalTier.usesOmniRouteFastPath) {
            val omniResp = omni.executeRoute(effectivePrompt, canonicalTier.id, attachedImageBase64, attachedDocumentText)
            if (omniResp.text.isNotBlank()) {
                return@withContext emitResponse(omniResp)
            }
        }

        val activeProviders = externalAiProviders.getActiveConfiguredProviders()
        var poolFailureMessage: String? = null
        if (context != null && (activeProviders.isNotEmpty() || FirebaseAiEngine.isAvailable(context))) {
            try {
                val isMemoryOn = AyraPreferences.isMemoryEnabled(context)
                val wsCtx = WorkspaceContextBuilder.resolve(context, currentPersona, selectedModelTier)

                if (isMemoryOn) {
                    val lower = cleanPrompt.lowercase()
                    if (lower.startsWith("remember that ") || lower.startsWith("মনে রেখো যে ") || lower.startsWith("মনে রাখো ")) {
                        val fact = cleanPrompt.replace(Regex("(?i)^(remember that|মনে রেখো যে|মনে রাখো)\\s*"), "").trim()
                        if (fact.isNotBlank()) {
                            AyraPreferences.addRememberedFact(context, fact)
                        }
                    }
                }

                val dispatchedDomain = com.ayra.ai.app.ai.agents.AyraAgentOrchestrator.classifyIntent(cleanPrompt)
                val sysPrompt = com.ayra.ai.app.ai.agents.AyraAgentOrchestrator.buildSystemPrompt(
                    domain = dispatchedDomain,
                    isBengali = isBengali,
                    isGfMode = isGfMode,
                    userCustomInstructions = wsCtx.combinedPersonalization,
                    rememberedFacts = wsCtx.rememberedFacts,
                    isBanglish = com.ayra.ai.app.ai.agents.AyraAgentOrchestrator.isBanglish(cleanPrompt),
                    personaStyle = wsCtx.effectivePersona,
                    thinkingMode = wsCtx.effectiveThinkingMode
                )

                val poolMode = canonicalTier.resolvePoolMode(wsCtx.effectiveThinkingMode)
                val (preferredProvider, preferredModel) =
                    canonicalTier.resolvePreferredProviderAndModel(externalAiProviders)
                val specialistDirective = canonicalTier.buildSpecialistSystemDirective(
                    prompt = effectivePrompt,
                    context = context,
                    baseSystemPrompt = sysPrompt
                ) ?: sysPrompt

                val routingMode = com.ayra.ai.app.ai.providers.AiRoutingMode.fromId(wsCtx.effectiveRoutingMode)
                val poolResp = com.ayra.ai.app.ai.providers.ProviderPool(context, externalAiProviders).executeSync(
                    com.ayra.ai.app.ai.providers.ChatRequest(
                        prompt = effectivePrompt,
                        persona = wsCtx.effectivePersona,
                        mode = poolMode,
                        history = conversationHistory ?: emptyList(),
                        imageBase64 = attachedImageBase64,
                        documentText = attachedDocumentText,
                        systemPromptOverride = specialistDirective,
                        preferredProvider = preferredProvider,
                        preferredModelId = preferredModel,
                        thinkingMode = wsCtx.effectiveThinkingMode,
                        workspaceContext = wsCtx.workspacePromptInjection.ifBlank { null },
                        routingMode = routingMode
                    )
                )
                if (poolResp.text.isNotBlank()) {
                    val refinedText = com.ayra.ai.app.ai.agents.AyraAgentOrchestrator.validateAndRefineResponse(
                        domain = dispatchedDomain,
                        rawResponse = poolResp.text,
                        originalPrompt = effectivePrompt,
                        isBengali = isBengali
                    )
                    return@withContext emitResponse(AiResponse(text = refinedText, modelUsed = poolResp.modelUsed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                poolFailureMessage = (e as? com.ayra.ai.app.ai.providers.AyraError)?.userFriendlyMessage
                    ?: e.localizedMessage
                    ?: e.message
            }
        }

        val canDirectGemini = externalAiProviders.isTestHarnessMode ||
            com.ayra.ai.app.security.SecureAiGatewayClient.evaluateProviderAccess(context, com.ayra.ai.app.ai.providers.AiProviderType.GEMINI).allowed
        val canDirectOpenRouter = externalAiProviders.isTestHarnessMode ||
            com.ayra.ai.app.security.SecureAiGatewayClient.evaluateProviderAccess(context, com.ayra.ai.app.ai.providers.AiProviderType.OPENROUTER).allowed

        when (targetTier) {
            AiModelTier.GEMINI_FLASH -> if (geminiApiKey.isNotBlank() && canDirectGemini) {
                val res = specializedExecutors.queryGeminiFlash(effectivePrompt, attachedImageBase64, currentPersona)
                if (res.isNotBlank()) return@withContext emitResponse(AiResponse(text = res, modelUsed = modelDisplayName))
            }
            AiModelTier.GEMINI_PRO -> if (geminiApiKey.isNotBlank() && canDirectGemini) {
                val res = specializedExecutors.queryGeminiPro(effectivePrompt, attachedImageBase64, currentPersona)
                if (res.isNotBlank()) return@withContext emitResponse(AiResponse(text = res, modelUsed = modelDisplayName))
            }
            AiModelTier.OPENROUTER_DEEPSEEK -> {
                if (openRouterApiKey.isNotBlank() && canDirectOpenRouter) {
                    val res = specializedExecutors.queryOpenRouterModel(ExternalAiProviders.OPENROUTER_QWEN_FREE_MODEL, effectivePrompt, currentPersona)
                    if (res.isNotBlank()) return@withContext emitResponse(AiResponse(text = res, modelUsed = modelDisplayName))
                }
                if (geminiApiKey.isNotBlank() && canDirectGemini) {
                    val res = specializedExecutors.queryGeminiPro(effectivePrompt, attachedImageBase64, currentPersona)
                    if (res.isNotBlank()) return@withContext emitResponse(AiResponse(text = res, modelUsed = "Gemini 3.1 Pro Reasoning 🧠"))
                }
            }
            AiModelTier.OPENROUTER_LLAMA -> {
                if (openRouterApiKey.isNotBlank() && canDirectOpenRouter) {
                    val res = specializedExecutors.queryOpenRouterModel(ExternalAiProviders.OPENROUTER_DEFAULT_MODEL, effectivePrompt, currentPersona)
                    if (res.isNotBlank()) return@withContext emitResponse(AiResponse(text = res, modelUsed = modelDisplayName))
                }
                if (geminiApiKey.isNotBlank() && canDirectGemini) {
                    val res = specializedExecutors.queryGeminiFlash(effectivePrompt, attachedImageBase64, currentPersona)
                    if (res.isNotBlank()) return@withContext emitResponse(AiResponse(text = res, modelUsed = "Gemini 3.6 Flash Live ⚡"))
                }
            }
            AiModelTier.OPEN_WEIGHTS -> if (context != null) {
                val openResp = com.ayra.ai.app.ai.openmodel.OpenModelEngine(context).queryOpenModel(effectivePrompt)
                if (openResp.text.isNotBlank()) return@withContext emitResponse(AiResponse(text = openResp.text, modelUsed = openResp.modelUsed))
            }
            AiModelTier.DEEPSEEK_HARNESS -> if (context != null) {
                try {
                    val dsResp = deepSeekHarness.executeHarness(effectivePrompt)
                    if (dsResp.text.isNotBlank()) return@withContext emitResponse(dsResp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("UnifiedAiEngine", "DeepSeek harness fallback failed: ${e.message}")
                }
            }
            AiModelTier.SECURITY_RESEARCH -> if (context != null) {
                try {
                    val secResp = securityResearchEngine.analyzeSecurityQuery(effectivePrompt)
                    if (secResp.text.isNotBlank()) return@withContext emitResponse(secResp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("UnifiedAiEngine", "Security research fallback failed: ${e.message}")
                }
            }
            AiModelTier.ECC_COGNITIVE -> if (context != null) {
                try {
                    val eccResp = eccEngine.disambiguateAndExecute(effectivePrompt)
                    if (eccResp.text.isNotBlank()) return@withContext emitResponse(eccResp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("UnifiedAiEngine", "ECC cognitive fallback failed: ${e.message}")
                }
            }
            AiModelTier.OPEN_HIGGSFIELD -> return@withContext craftGenerators.generateVideoCraft(effectivePrompt, currentPersona, attachedImageBase64)
            AiModelTier.SUPERPOWERS -> {
                val sp = com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.detectSuperpower(cleanPrompt)
                    ?: com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.SuperpowerType.SYSTEMATIC_DEBUGGING
                return@withContext emitResponse(specializedExecutors.executeSuperpowerAction(sp, cleanPrompt, effectivePrompt, currentPersona, attachedImageBase64))
            }
            AiModelTier.AUTO, AiModelTier.OMNI_ROUTE -> {}
        }

        if (geminiApiKey.isNotBlank() && canDirectGemini) {
            val fastRes = specializedExecutors.queryGeminiFlash(effectivePrompt, attachedImageBase64, currentPersona)
            if (fastRes.isNotBlank()) return@withContext emitResponse(AiResponse(text = fastRes, modelUsed = "Gemini 3.6 Flash Live ⚡"))
        }

        if (openRouterApiKey.isNotBlank() && canDirectOpenRouter) {
            try {
                val orRes = specializedExecutors.queryOpenRouterModel(ExternalAiProviders.OPENROUTER_QWEN_FREE_MODEL, effectivePrompt, currentPersona)
                if (orRes.isNotBlank()) return@withContext emitResponse(AiResponse(text = orRes, modelUsed = "OpenRouter Multi-AI"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("UnifiedAiEngine", "OpenRouter direct fallback failed: ${e.message}")
            }
        }

        val diagnosticSuffix = poolFailureMessage?.takeIf { it.isNotBlank() }?.let { "\n\nDetails: $it" }.orEmpty()
        val honestMessage = if (isBengali) {
            "⚠️ **এআই পরিষেবা অনুপলব্ধ**\n\nবর্তমানে কোনো এআই সরবরাহকারী সার্ভারে সংযোগ করা সম্ভব হয়নি বা বৈধ API কী নেই।\nইন্টারনেট সংযোগ পরীক্ষা করে আবার চেষ্টা করুন অথবা Settings থেকে API Key যুক্ত করুন।$diagnosticSuffix"
        } else {
            "⚠️ **AI Service Unavailable**\n\nCould not reach an active AI provider right now.\nPlease check your internet connection and try again, or add an API Key in Settings.$diagnosticSuffix"
        }
        emitResponse(AiResponse(text = honestMessage, modelUsed = "Offline Status"))
    }

    private fun determineOptimalModel(text: String): Pair<AiModelTier, String> {
        val lower = text.lowercase()
        val isDeepMathOrCode = lower.contains("code") || lower.contains("coding") || lower.contains("কোড") ||
            lower.contains("program") || lower.contains("python") || lower.contains("kotlin") ||
            lower.contains("java") || lower.contains("javascript") || lower.contains("c++") ||
            lower.contains("html") || lower.contains("css") || lower.contains("bug") ||
            lower.contains("debug") || lower.contains("algorithm") || lower.contains("অ্যালগরিদম") ||
            lower.contains("prove") || lower.contains("theorem") || lower.contains("calculus") ||
            lower.contains("integral") || lower.contains("সমীকরণ") || lower.contains("equation") ||
            lower.contains("physics") || lower.contains("chemistry") || lower.contains("গণিত") ||
            lower.contains("বিজ্ঞান") || lower.contains("research") || lower.contains("গবেষণা") ||
            lower.contains("document") || lower.contains("পিডিএফ") || lower.contains("pdf") ||
            text.length > 200

        if (isDeepMathOrCode) return Pair(AiModelTier.GEMINI_PRO, "Gemini 3.1 Pro Reasoning 🧠 (Auto: Code & Science)")

        val isLogicOrPhilosophy = lower.contains("philosophy") || lower.contains("দর্শন") ||
            lower.contains("riddle") || lower.contains("ধাঁধা") || lower.contains("step by step") ||
            lower.contains("ধাপে ধাপে") || lower.contains("logic") || lower.contains("যুক্তি") ||
            lower.contains("debate") || lower.contains("তর্ক") || lower.contains("chain of thought")

        if (isLogicOrPhilosophy && openRouterApiKey.isNotBlank()) {
            return Pair(AiModelTier.OPENROUTER_DEEPSEEK, "DeepSeek / OpenRouter Qwen 🧠 (Auto: Chain-of-Thought)")
        } else if (isLogicOrPhilosophy) {
            return Pair(AiModelTier.GEMINI_PRO, "Gemini 3.1 Pro Reasoning 🧠 (Auto: Deep Logic)")
        }

        val isCreativeOrTranslation = lower.contains("poem") || lower.contains("কবিতা") ||
            lower.contains("story") || lower.contains("গল্প") || lower.contains("translate") ||
            lower.contains("অনুবাদ") || lower.contains("essay") || lower.contains("রচনা") ||
            lower.contains("creative") || lower.contains("সৃজনশীল")

        if (isCreativeOrTranslation && openRouterApiKey.isNotBlank()) {
            return Pair(AiModelTier.OPENROUTER_LLAMA, "Llama 3.3 70B 🌐 (Auto: Literature & Translation)")
        }

        return Pair(AiModelTier.GEMINI_FLASH, "Gemini 3.6 Flash Live ⚡ (Auto: Ultra-Fast)")
    }
}
