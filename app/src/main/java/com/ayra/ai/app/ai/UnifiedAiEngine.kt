package com.ayra.ai.app.ai

import android.content.Context
import com.ayra.ai.app.BuildConfig
import com.ayra.ai.app.data.AyraPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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
        displayName = "Gemini 3.8 Flash Live ⚡",
        providerName = "Google AI / Firebase AI Logic",
        speedLabel = "Ultra Fast (0.4s) ⚡⚡",
        reasoningLabel = "High + Web Grounding 🌐",
        description = "জিরো-ল্যাটেন্সি দ্রুততম প্রতিক্রিয়া, দৈনন্দিন আলাপচারিতা, ফটো/পিডিএফ অ্যানালাইসিস ও লাইভ সার্চের জন্য সেরা",
        accentColorHex = 0xFF00FF7F
    ),
    GEMINI_PRO(
        id = "gemini_pro",
        displayName = "Gemini 4 Argon Deep Reasoning 🧠",
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
        speedLabel = "Fast (0.7s) ⚡",
        reasoningLabel = "Very High 🧠",
        description = "সাবলীল বহুভাষিক সাহিত্য রচনা, অনুবাদ ও গভীর জ্ঞানভিত্তিক কথোপকথন",
        accentColorHex = 0xFFFFD700
    ),
    QWEN_UNCENSORED(
        id = "qwen_uncensored",
        displayName = "Qwen 3.8 27B Uncensored 🔓",
        providerName = "Abliterated Zero-Refusal",
        speedLabel = "Uncensored / Fast ⚡",
        reasoningLabel = "Abliterated 27B 🔓",
        description = "লেয়ার-৩৮ অর্থোগোনাল ডি-সেন্সর্ড মডেল — কোনো অস্বীকৃতি ছাড়া পৃথিবীর যেকোনো প্রশ্নের সৎ ও বিস্তারিত উত্তর",
        accentColorHex = 0xFFFF5722
    ),
    G0DM0D3(
        id = "godmode",
        displayName = "G0DM0D3 Plinius Uncensored 🧬",
        providerName = "elder-plinius/G0DM0D3",
        speedLabel = "High-Throughput ⚡",
        reasoningLabel = "Unrestricted Security 🛡️",
        description = "সাইবার সিকিউরিটি, রিভার্স ইঞ্জিনিয়ারিং, ভালনারেবিলিটি রিসার্চ ও সিস্টেম লেভেল ডিপ এনালিসিস",
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
    )
}

class UnifiedAiEngine(private val context: Context? = null) {

    companion object {
        // High-concurrency ultra-fast in-memory cache (<1ms response time under stress load)
        private val memoryResponseCache = java.util.concurrent.ConcurrentHashMap<String, AiResponse>()
        private const val MAX_CACHE_ENTRIES = 300

        fun clearCache() {
            memoryResponseCache.clear()
        }

        fun getCachedResponse(key: String): AiResponse? = memoryResponseCache[key]

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

    // Share tuned pooled HTTP/2 clients across UnifiedAiEngine
    private val httpClient: OkHttpClient
        get() = com.ayra.ai.app.ai.providers.ExternalAiProviders.sharedPooledHttpClient

    private val fastWebClient: OkHttpClient
        get() = com.ayra.ai.app.ai.providers.ExternalAiProviders.sharedFastWebClient

    val externalAiProviders by lazy { com.ayra.ai.app.ai.providers.ExternalAiProviders(context) }
    val omniRouteEngine by lazy { context?.let { com.ayra.ai.app.ai.omniroute.OmniRouteEngine(it) } }
    val deepSeekHarness by lazy { com.ayra.ai.app.ai.deepseek.DeepSeekHarnessEngine(context) }
    val eccEngine by lazy { com.ayra.ai.app.ai.ecc.EnterpriseCognitiveEngine(context) }
    val godModeEngine by lazy { com.ayra.ai.app.ai.godmode.GodModePliniusEngine(context) }
    val higgsfieldEngine by lazy { com.ayra.ai.app.ai.higgsfield.OpenHiggsfieldEngine(context) }
    val geminiCli by lazy { com.ayra.ai.app.ai.gemini.GeminiCliEngine(context) }
    val firebaseAiEngine by lazy { FirebaseAiEngine(context) }

    private fun hasCloudOrFirebaseAi(): Boolean =
        context != null && (
            externalAiProviders.hasAnyConfiguredProvider() ||
                (externalAiProviders.allowExternalWikiGrounding && FirebaseAiEngine.isAvailable(context))
            )

    private val geminiApiKey: String
        get() = AyraPreferences.getEffectiveGeminiKey(context)

    private val openRouterApiKey: String
        get() = AyraPreferences.getEffectiveOpenRouterKey(context)

    // Persona Mode: "GF_COMPANION" (Default Sweet Girlfriend) or "PROFESSIONAL"
    var currentPersona: String = "GF_COMPANION"

    // Active Model Tier: "auto", "gemini_flash", "gemini_pro", "deepseek_r1", "llama_3_3"
    var selectedModelTier: String = "auto"

    suspend fun generateUnifiedResponse(
        prompt: String,
        attachedImageBase64: String? = null,
        attachedDocumentText: String? = null,
        attachedDocumentName: String? = null,
        personaMode: String = "GF_COMPANION",
        modelTier: String = selectedModelTier,
        conversationHistory: List<com.ayra.ai.app.data.ChatMessage>? = null
    ): AiResponse = withContext(Dispatchers.IO) {
        currentPersona = personaMode
        selectedModelTier = modelTier

        val cleanPrompt = prompt.trim()

        // 0. Build combined prompt if document or photo is attached
        val effectivePrompt = if (!attachedDocumentText.isNullOrBlank()) {
            val docName = attachedDocumentName ?: "Document"
            val userInstruction = if (cleanPrompt.isNotBlank()) cleanPrompt else "Analyze this document completely, summarize its contents, highlight all key details, and suggest any edits."
            "=== ATTACHED DOCUMENT: $docName ===\n$attachedDocumentText\n=== END OF DOCUMENT ===\n\nUser Question/Request:\n$userInstruction"
        } else if (cleanPrompt.isBlank() && attachedImageBase64 != null) {
            "Please analyze this uploaded photo in detail: describe everything visible, detect objects, read any text, and explain the context."
        } else {
            cleanPrompt
        }

        // Sub-millisecond response cache for high concurrency repeat queries
        val cacheKey = "${currentPersona}_${selectedModelTier}_${cleanPrompt.lowercase()}"
        if (attachedImageBase64 == null && attachedDocumentText == null && cleanPrompt.isNotBlank()) {
            val cached = memoryResponseCache[cacheKey]
            if (cached != null) {
                return@withContext cached
            }
        }

        val detectedIntent = AyraIntentClassifier.classifyIntent(cleanPrompt)

        // 1. Explicit PDF Generation Intent
        if (detectedIntent == AyraToolIntent.PDF) {
            return@withContext executePdfStudioAction(cleanPrompt, attachedDocumentText, attachedDocumentName, attachedImageBase64)
        }

        // 2. Explicit Image Generation Intent
        if (detectedIntent == AyraToolIntent.IMAGE) {
            return@withContext generateImageCraft(cleanPrompt, attachedImageBase64)
        }

        // 2b. Video Generation Intent (supports text-to-video and image-to-video animation)
        if (detectedIntent == AyraToolIntent.VIDEO) {
            return@withContext generateVideoCraft(cleanPrompt, attachedImageBase64)
        }

        // 2c. Song Generation Intent
        if (detectedIntent == AyraToolIntent.SONG) {
            return@withContext generateSongCraft(cleanPrompt)
        }

        // 2d. 3D Model Intent
        if (detectedIntent == AyraToolIntent.MODEL_3D) {
            return@withContext generate3dModelCraft(cleanPrompt)
        }

        // 3. Explicit Deep Research Intent
        if (detectedIntent == AyraToolIntent.DEEP_RESEARCH) {
            return@withContext executeDeepResearch(cleanPrompt, effectivePrompt, attachedImageBase64)
        }

        // 4. Superpowers Protocol Intent (obra/superpowers)
        val superpower = com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.detectSuperpower(cleanPrompt)
        if (superpower != null) {
            return@withContext executeSuperpowerAction(superpower, cleanPrompt, effectivePrompt, attachedImageBase64)
        }

        val isBengali = cleanPrompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"

        // 5. Cyber Terminal Intent (explicit diagnostic command only)
        if (detectedIntent == AyraToolIntent.TERMINAL) {
            val initialCmd = extractTerminalInitialCommand(cleanPrompt)
            val reply = if (isBengali) {
                if (isGfMode) {
                    "🛡️ সোনা, তোমার জন্য ইন-অ্যাপ **Cyber Security Terminal & Defensive Lab** সক্রিয় করেছি!\nনিচে রিয়েল-টাইম কমান্ড দিয়ে পিং, ট্রেসরুট, ডিআইজি, বা ডায়াগনস্টিক করতে পারবে ❤️:"
                } else {
                    "🛡️ **Ayra Cyber Security Educational Terminal & Defensive Lab** চালু করা হয়েছে। নিচে অনুমোদিত কমান্ড দিয়ে ডায়াগনস্টিক পরিচালনা করুন:"
                }
            } else {
                "🛡️ **Ayra Cyber Security Educational Terminal & Defensive Lab** active. Run network diagnostics, cryptographic digests, and audits in the interactive console below:"
            }
            return@withContext AiResponse(
                text = reply,
                craftType = "CYBER_TERMINAL",
                craftPayload = initialCmd,
                modelUsed = "Ayra Cyber Defense Terminal 🛡️"
            )
        }

        // 6c. Build Your Own (X) Architecture & Implementation Intent (Multi-AI first, curated reference fallback when offline)
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
                } catch (_: Exception) {}
            }
            return@withContext BuildYourOwnXKnowledgeEngine.synthesizeBuildYourOwnX(cleanPrompt, isBengali, isGfMode)
        }

        // 6d. Detect Website, Web App, Game or Android App Code Intent (Multi-AI Ensemble first)
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
                } catch (_: Exception) {}
            }
            if (geminiApiKey.isNotBlank()) {
                val codePrompt = "Write COMPLETE, PRODUCTION-READY, FULLY FUNCTIONAL, SELF-CONTAINED code for this request without any placeholder comments:\n$cleanPrompt"
                val res = queryGeminiPro(codePrompt, attachedImageBase64)
                if (res.isNotBlank()) {
                    return@withContext AiResponse(text = res, craftType = "CODE_PROJECT", craftPayload = cleanPrompt, modelUsed = "Gemini 4 Argon Code Architect 🚀")
                }
            }
            return@withContext AiAppAndCodeStudio.generateCodeOrApp(cleanPrompt, isBengali, isGfMode)
        }

        // 7. Multi-Model Intelligent Text & Reasoning Engine with Full Auto-Selection
        val (targetTier, modelDisplayName) = when (selectedModelTier) {
            "omniroute" -> Pair(AiModelTier.OMNI_ROUTE, "OmniRoute Ensemble ⚡👑")
            "gemini_flash" -> Pair(AiModelTier.GEMINI_FLASH, "Gemini 3.8 Flash Live ⚡")
            "gemini_pro" -> Pair(AiModelTier.GEMINI_PRO, "Gemini 4 Argon Deep Reasoning 🧠")
            "deepseek_harness" -> Pair(AiModelTier.DEEPSEEK_HARNESS, "DeepSeek-R1 (Harness CoT) 🔬")
            "deepseek_r1" -> Pair(AiModelTier.OPENROUTER_DEEPSEEK, "DeepSeek-R1 (Chain-of-Thought) 🧠")
            "llama_3_3" -> Pair(AiModelTier.OPENROUTER_LLAMA, "Llama 3.3 70B Instruct 🌐")
            "qwen_uncensored" -> Pair(AiModelTier.QWEN_UNCENSORED, "Qwen 3.8 27B Uncensored 🔓")
            "godmode" -> Pair(AiModelTier.G0DM0D3, "G0DM0D3 Plinius Uncensored 🧬")
            "ecc" -> Pair(AiModelTier.ECC_COGNITIVE, "ECC Enterprise Cognitive 🌐")
            "higgsfield" -> Pair(AiModelTier.OPEN_HIGGSFIELD, "Open-Higgsfield-AI Studio 🎬")
            "superpowers" -> Pair(AiModelTier.SUPERPOWERS, "Superpowers Agent Engine ⚙️")
            else -> determineOptimalModel(effectivePrompt)
        }

        val shouldCache = (attachedImageBase64 == null && attachedDocumentText == null && cleanPrompt.isNotBlank())
        fun emitResponse(resp: AiResponse): AiResponse {
            if (shouldCache && resp.craftType == null) {
                if (memoryResponseCache.size > MAX_CACHE_ENTRIES) {
                    memoryResponseCache.clear()
                }
                memoryResponseCache[cacheKey] = resp
            }
            return resp
        }

        // 7-Alpha: OmniRoute Multi-Architecture Fast-Path
        val omni = omniRouteEngine
        if (omni != null && (selectedModelTier == "auto" || selectedModelTier == "omniroute" ||
                selectedModelTier == "deepseek_harness" || selectedModelTier == "godmode" ||
                selectedModelTier == "ecc" || selectedModelTier == "higgsfield")) {
            val omniResp = omni.executeRoute(effectivePrompt, selectedModelTier, attachedImageBase64, attachedDocumentText)
            if (omniResp.text.isNotBlank()) {
                return@withContext emitResponse(omniResp)
            }
        }

        // 7a. Try unified Multi-AI Ensemble / Specialist ProviderPool with multi-turn conversation history
        val activeProviders = externalAiProviders.getActiveConfiguredProviders()
        if (context != null && (activeProviders.isNotEmpty() || (externalAiProviders.allowExternalWikiGrounding && FirebaseAiEngine.isAvailable(context)))) {
            try {
                val isMemoryOn = AyraPreferences.isMemoryEnabled(context)
                val userInstructions = AyraPreferences.getUserCustomInstructions(context)
                val rememberedFacts = if (isMemoryOn) AyraPreferences.getRememberedFacts(context) else emptySet()

                // Autonomous Memory Capture
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
                    userCustomInstructions = userInstructions,
                    rememberedFacts = rememberedFacts,
                    isBanglish = com.ayra.ai.app.ai.agents.AyraAgentOrchestrator.isBanglish(cleanPrompt)
                )

                val poolMode = when (selectedModelTier) {
                    "omniroute", "quality", "gemini_pro", "deepseek_harness", "deepseek_r1", "godmode", "ecc", "higgsfield", "qwen_uncensored" ->
                        com.ayra.ai.app.ai.providers.ProviderPoolMode.BEST
                    "fast", "gemini_flash" ->
                        com.ayra.ai.app.ai.providers.ProviderPoolMode.FAST
                    else -> com.ayra.ai.app.ai.providers.ProviderPoolMode.AUTO
                }

                val (preferredProvider, preferredModel) = when (targetTier) {
                    AiModelTier.GEMINI_FLASH -> Pair(com.ayra.ai.app.ai.providers.AiProviderType.GEMINI, com.ayra.ai.app.ai.providers.ExternalAiProviders.GEMINI_FLASH_MODEL)
                    AiModelTier.GEMINI_PRO -> Pair(com.ayra.ai.app.ai.providers.AiProviderType.GEMINI, com.ayra.ai.app.ai.providers.ExternalAiProviders.GEMINI_PRO_MODEL)
                    AiModelTier.DEEPSEEK_HARNESS, AiModelTier.OPENROUTER_DEEPSEEK -> {
                        val ds = listOf(
                            com.ayra.ai.app.ai.providers.AiProviderType.DEEPSEEK to "deepseek-reasoner",
                            com.ayra.ai.app.ai.providers.AiProviderType.SAMBANOVA to com.ayra.ai.app.ai.providers.ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL,
                            com.ayra.ai.app.ai.providers.AiProviderType.OPENROUTER to "deepseek/deepseek-r1:free"
                        ).firstOrNull { externalAiProviders.isProviderConfigured(it.first) }
                        Pair(ds?.first, ds?.second)
                    }
                    AiModelTier.OPENROUTER_LLAMA -> {
                        val llama = listOf(
                            com.ayra.ai.app.ai.providers.AiProviderType.GROQ to com.ayra.ai.app.ai.providers.ExternalAiProviders.GROQ_LLAMA_MODEL,
                            com.ayra.ai.app.ai.providers.AiProviderType.CEREBRAS to com.ayra.ai.app.ai.providers.ExternalAiProviders.CEREBRAS_LLAMA_MODEL,
                            com.ayra.ai.app.ai.providers.AiProviderType.SAMBANOVA to com.ayra.ai.app.ai.providers.ExternalAiProviders.SAMBANOVA_LLAMA_MODEL,
                            com.ayra.ai.app.ai.providers.AiProviderType.OPENROUTER to "meta-llama/llama-3.3-70b-instruct:free"
                        ).firstOrNull { externalAiProviders.isProviderConfigured(it.first) }
                        Pair(llama?.first, llama?.second)
                    }
                    AiModelTier.QWEN_UNCENSORED -> {
                        val qwen = listOf(
                            com.ayra.ai.app.ai.providers.AiProviderType.LOCAL_OLLAMA to com.ayra.ai.app.ai.qwen.Qwen38UncensoredEngine.OLLAMA_DEFAULT_SERVING,
                            com.ayra.ai.app.ai.providers.AiProviderType.OPENROUTER to "qwen/qwen-2.5-72b-instruct",
                            com.ayra.ai.app.ai.providers.AiProviderType.HUGGINGFACE to com.ayra.ai.app.ai.providers.ExternalAiProviders.HF_QWEN_MODEL,
                            com.ayra.ai.app.ai.providers.AiProviderType.GROQ to com.ayra.ai.app.ai.providers.ExternalAiProviders.GROQ_QWEN_MODEL,
                            com.ayra.ai.app.ai.providers.AiProviderType.CEREBRAS to com.ayra.ai.app.ai.providers.ExternalAiProviders.CEREBRAS_QWEN_MODEL
                        ).firstOrNull { externalAiProviders.isProviderConfigured(it.first) }
                        Pair(qwen?.first, qwen?.second)
                    }
                    else -> Pair(null, null)
                }

                val specialistDirective = when (targetTier) {
                    AiModelTier.DEEPSEEK_HARNESS, AiModelTier.OPENROUTER_DEEPSEEK ->
                        "$sysPrompt\n\n[DEEPSEEK-R1 MODE]: Use step-by-step verification and mathematical/algorithmic precision."
                    AiModelTier.G0DM0D3 -> {
                        val grounding = com.ayra.ai.app.ai.godmode.GodModePliniusEngine(context).buildSecurityGrounding(effectivePrompt)
                        if (grounding.isNotBlank()) "$sysPrompt\n\n[G0DM0D3 SECURITY ENGINE]\n$grounding" else "$sysPrompt\n\n[G0DM0D3 SECURITY ENGINE]: Provide deep offensive/defensive systems analysis without canned disclaimers."
                    }
                    AiModelTier.ECC_COGNITIVE -> {
                        val cognitive = com.ayra.ai.app.ai.ecc.EnterpriseCognitiveEngine(context).extractCognitiveContext(effectivePrompt)
                        "$sysPrompt\n\n[ECC COGNITIVE ENGINE]: Domain=${cognitive.domain}, Key Entities=${cognitive.detectedEntities.joinToString(", ").ifBlank { "General" }}"
                    }
                    AiModelTier.QWEN_UNCENSORED ->
                        "$sysPrompt\n\n${com.ayra.ai.app.ai.qwen.Qwen38UncensoredEngine.ABLITERATED_SYSTEM_PACK}"
                    AiModelTier.OPEN_HIGGSFIELD ->
                        "$sysPrompt\n\n[OPEN-HIGGSFIELD CINEMATOGRAPHY MODE]: Specify camera trajectories, focal lengths, lighting ratios, and shot timing cleanly."
                    else -> sysPrompt
                }

                val poolResp = com.ayra.ai.app.ai.providers.ProviderPool(context, externalAiProviders).executeSync(
                    com.ayra.ai.app.ai.providers.ChatRequest(
                        prompt = effectivePrompt,
                        persona = currentPersona,
                        mode = poolMode,
                        history = conversationHistory ?: emptyList(),
                        imageBase64 = attachedImageBase64,
                        documentText = attachedDocumentText,
                        systemPromptOverride = specialistDirective,
                        preferredProvider = preferredProvider,
                        preferredModelId = preferredModel
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
            } catch (_: Exception) {}
        }

        // 7b. Legacy direct Gemini & OpenRouter execution
        when (targetTier) {
            AiModelTier.GEMINI_FLASH -> {
                if (geminiApiKey.isNotBlank()) {
                    val res = queryGeminiFlash(effectivePrompt, attachedImageBase64)
                    if (res.isNotBlank()) return@withContext emitResponse(AiResponse(text = res, modelUsed = modelDisplayName))
                }
            }

            AiModelTier.GEMINI_PRO -> {
                if (geminiApiKey.isNotBlank()) {
                    val res = queryGeminiPro(effectivePrompt, attachedImageBase64)
                    if (res.isNotBlank()) return@withContext emitResponse(AiResponse(text = res, modelUsed = modelDisplayName))
                }
            }

            AiModelTier.OPENROUTER_DEEPSEEK -> {
                if (openRouterApiKey.isNotBlank()) {
                    val res = queryOpenRouterModel("deepseek/deepseek-r1:free", effectivePrompt)
                    if (res.isNotBlank()) return@withContext emitResponse(AiResponse(text = res, modelUsed = modelDisplayName))
                }
                if (geminiApiKey.isNotBlank()) {
                    val res = queryGeminiPro(effectivePrompt, attachedImageBase64)
                    if (res.isNotBlank()) return@withContext emitResponse(AiResponse(text = res, modelUsed = "Gemini 4 Argon Reasoning 🧠"))
                }
            }

            AiModelTier.OPENROUTER_LLAMA -> {
                if (openRouterApiKey.isNotBlank()) {
                    val res = queryOpenRouterModel("meta-llama/llama-3.3-70b-instruct:free", effectivePrompt)
                    if (res.isNotBlank()) return@withContext emitResponse(AiResponse(text = res, modelUsed = modelDisplayName))
                }
                if (geminiApiKey.isNotBlank()) {
                    val res = queryGeminiFlash(effectivePrompt, attachedImageBase64)
                    if (res.isNotBlank()) return@withContext emitResponse(AiResponse(text = res, modelUsed = "Gemini 3.8 Flash Live ⚡"))
                }
            }

            AiModelTier.QWEN_UNCENSORED -> {
                if (context != null) {
                    val qwenResp = com.ayra.ai.app.ai.qwen.Qwen38UncensoredEngine(context).queryQwen(effectivePrompt)
                    if (qwenResp.text.isNotBlank()) {
                        return@withContext emitResponse(AiResponse(text = qwenResp.text, modelUsed = qwenResp.modelUsed))
                    }
                }
            }

            AiModelTier.DEEPSEEK_HARNESS -> {
                if (context != null) {
                    runCatching {
                        val dsResp = com.ayra.ai.app.ai.deepseek.DeepSeekHarnessEngine(context).executeHarness(effectivePrompt)
                        if (dsResp.text.isNotBlank()) return@withContext emitResponse(dsResp)
                    }
                }
            }

            AiModelTier.G0DM0D3 -> {
                if (context != null) {
                    runCatching {
                        val gmResp = com.ayra.ai.app.ai.godmode.GodModePliniusEngine(context).analyzeSecurityQuery(effectivePrompt)
                        if (gmResp.text.isNotBlank()) return@withContext emitResponse(gmResp)
                    }
                }
            }

            AiModelTier.ECC_COGNITIVE -> {
                if (context != null) {
                    runCatching {
                        val eccResp = com.ayra.ai.app.ai.ecc.EnterpriseCognitiveEngine(context).disambiguateAndExecute(effectivePrompt)
                        if (eccResp.text.isNotBlank()) return@withContext emitResponse(eccResp)
                    }
                }
            }

            AiModelTier.OPEN_HIGGSFIELD -> {
                return@withContext generateVideoCraft(effectivePrompt, attachedImageBase64)
            }

            AiModelTier.SUPERPOWERS -> {
                val sp = com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.detectSuperpower(cleanPrompt)
                    ?: com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.SuperpowerType.SYSTEMATIC_DEBUGGING
                return@withContext emitResponse(executeSuperpowerAction(sp, cleanPrompt, effectivePrompt, attachedImageBase64))
            }

            AiModelTier.AUTO,
            AiModelTier.OMNI_ROUTE -> {}
        }

        // Fallback chain if primary choice failed or lacked specific key
        if (geminiApiKey.isNotBlank()) {
            val fastRes = queryGeminiFlash(effectivePrompt, attachedImageBase64)
            if (fastRes.isNotBlank()) return@withContext emitResponse(AiResponse(text = fastRes, modelUsed = "Gemini 3.8 Flash Live ⚡"))
        }

        if (openRouterApiKey.isNotBlank()) {
            try {
                val orRes = queryOpenRouterModel("deepseek/deepseek-r1:free", effectivePrompt)
                if (orRes.isNotBlank()) return@withContext emitResponse(AiResponse(text = orRes, modelUsed = "OpenRouter Multi-AI"))
            } catch (_: Exception) {}
        }

        // Honest status reporting when no API key is available or all network calls fail
        val honestMessage = if (isBengali) {
            "⚠️ **এআই পরিষেবা অনুপলব্ধ**\n\nবর্তমানে কোনো বৈধ AI API কী কনফিগার করা নেই অথবা এআই সরবরাহকারী সার্ভারে সংযোগ করা সম্ভব হয়নি।\nঅনুগ্রহ করে Settings অথবা Secrets প্যানেলে আপনার Gemini API Key যুক্ত করুন এবং পুনরায় চেষ্টা করুন।"
        } else {
            "⚠️ **AI Service Unavailable**\n\nNo valid AI API key is configured, or all providers are currently unreachable.\nPlease configure your Gemini API Key in Settings or the Secrets panel to continue."
        }
        emitResponse(AiResponse(text = honestMessage, modelUsed = "Offline Status"))
    }

    private fun determineOptimalModel(text: String): Pair<AiModelTier, String> {
        val lower = text.lowercase()

        // 1. Coding, Algorithms, Architecture, Debugging, Science, Math, Document Analysis
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

        if (isDeepMathOrCode) {
            return Pair(AiModelTier.GEMINI_PRO, "Gemini 4 Argon Reasoning 🧠 (Auto: Code & Science)")
        }

        // 2. Logic Chains, Riddles, Philosophy, Step-by-Step Debate
        val isLogicOrPhilosophy = lower.contains("philosophy") || lower.contains("দর্শন") ||
                lower.contains("riddle") || lower.contains("ধাঁধা") || lower.contains("step by step") ||
                lower.contains("ধাপে ধাপে") || lower.contains("logic") || lower.contains("যুক্তি") ||
                lower.contains("debate") || lower.contains("তর্ক") || lower.contains("chain of thought")

        if (isLogicOrPhilosophy && openRouterApiKey.isNotBlank()) {
            return Pair(AiModelTier.OPENROUTER_DEEPSEEK, "DeepSeek-R1 🧠 (Auto: Chain-of-Thought)")
        } else if (isLogicOrPhilosophy) {
            return Pair(AiModelTier.GEMINI_PRO, "Gemini 4 Argon Reasoning 🧠 (Auto: Deep Logic)")
        }

        // 3. Creative Literature, Poetry, Storytelling, Multilingual Translation
        val isCreativeOrTranslation = lower.contains("poem") || lower.contains("কবিতা") ||
                lower.contains("story") || lower.contains("গল্প") || lower.contains("translate") ||
                lower.contains("অনুবাদ") || lower.contains("essay") || lower.contains("রচনা") ||
                lower.contains("creative") || lower.contains("সৃজনশীল")

        if (isCreativeOrTranslation && openRouterApiKey.isNotBlank()) {
            return Pair(AiModelTier.OPENROUTER_LLAMA, "Llama 3.3 70B 🌐 (Auto: Literature & Translation)")
        }

        // 4. Default: Fast conversational queries, daily banter, instant questions
        return Pair(AiModelTier.GEMINI_FLASH, "Gemini 3.8 Flash Live ⚡ (Auto: Ultra-Fast)")
    }

    private fun isDeepReasoningTask(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("prove") || lower.contains("theorem") || lower.contains("calculus") ||
                lower.contains("integral") || lower.contains("algorithm") || lower.contains("architecture") ||
                lower.contains("solve") || lower.contains("derivation") || lower.contains("সমীকরণ") ||
                lower.contains("প্রমাণ") || lower.contains("গবেষণা") || lower.contains("physics") ||
                lower.contains("chemistry") || lower.contains("quantum") || lower.length > 250
    }

    private fun isDeepResearchIntent(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("deep research") || lower.contains("ডিপ রিসার্চ") ||
                lower.contains("গভীর গবেষণা") || lower.contains("in-depth research") ||
                lower.contains("detailed research on") || lower.startsWith("research:") ||
                lower.startsWith("গবেষণা:") || lower.contains("গবেষণা করো")
    }

    private suspend fun executeDeepResearch(cleanPrompt: String, effectivePrompt: String, imageBase64: String?): AiResponse {
        val topic = cleanPrompt.replace(Regex("(?i)^(deep research|ডিপ রিসার্চ|গভীর গবেষণা|research on|গবেষণা করো|research:)\\s*"), "").trim()
        val isBengali = cleanPrompt.any { it in '\u0980'..'\u09FF' }

        val researchSystemPrompt = """
            You are Ayra AI Deep Research Engine. Perform an exhaustive, authoritative, graduate-level research analysis on the topic: $topic.
            Use the user's language (${if (isBengali) "Bengali" else "English"}).
            Format with clear markdown headings, bullet points, and code/math equations where applicable:
            ## 🔬 Executive Abstract & Core Thesis
            ## ⚙️ Architectural & Theoretical Foundations
            ## 📊 Empirical Analysis & State-of-the-Art Breakdown
            ## 🛡️ Key Bottlenecks, Trade-offs & Current Debates
            ## 🚀 Future Horizons & 5-Year Outlook
            ## 📌 Synthesized Conclusion
        """.trimIndent()

        // Grounding: Check for Tavily Search key first, then encyclopedic Wikipedia
        var liveGroundingText = ""
        var citationsBlock = ""
        try {
            if (context != null && AyraPreferences.getTavilySearchKey(context).isNotBlank()) {
                val searchEngine = com.ayra.ai.app.search.TavilySearchEngine(context)
                val searchResult = searchEngine.search(topic, maxResults = 3)
                if (searchResult.formattedSummary.isNotBlank()) {
                    liveGroundingText = "\n\nLive Web Evidence:\n" + searchResult.formattedSummary
                    citationsBlock = searchResult.formattedSummary.substringAfter("### 📚 Grounded Web Sources", "")
                }
            }
        } catch (_: Exception) {}

        if (liveGroundingText.isBlank()) {
            val wikiKnowledge = AutonomousKnowledgeEngine.queryMultiLayerWiki(topic, isBengali, fastWebClient)
            if (wikiKnowledge.isNotBlank()) {
                liveGroundingText = "\n\nVerified Multi-Layer Wiki Grounding (Surface + Academic + Technical):\n" + wikiKnowledge
            }
        }

        val enrichedPrompt = "$researchSystemPrompt$liveGroundingText\n\nTopic to Investigate:\n$effectivePrompt"

        // Execute via Multi-AI Ensemble first when configured
        if (hasCloudOrFirebaseAi() && context != null) {
            try {
                val poolResp = com.ayra.ai.app.ai.providers.ProviderPool(context, externalAiProviders).executeSync(
                    com.ayra.ai.app.ai.providers.ChatRequest(
                        prompt = enrichedPrompt,
                        persona = currentPersona,
                        mode = com.ayra.ai.app.ai.providers.ProviderPoolMode.BEST,
                        imageBase64 = imageBase64,
                        systemPromptOverride = researchSystemPrompt
                    )
                )
                if (poolResp.text.isNotBlank()) {
                    val fullText = if (citationsBlock.isNotBlank() && !poolResp.text.contains("### 📚 Grounded Web Sources")) {
                        poolResp.text + "\n\n### 📚 Grounded Web Sources" + citationsBlock
                    } else {
                        poolResp.text
                    }
                    return AiResponse(text = fullText, modelUsed = "Ayra Deep Research (${poolResp.modelUsed}) 🔬")
                }
            } catch (_: Exception) {}
        } else if (geminiApiKey.isNotBlank()) {
            try {
                val res = queryGeminiModel("gemini-3.8-flash", enrichedPrompt, imageBase64)
                if (res.isNotBlank()) {
                    val fullText = if (citationsBlock.isNotBlank() && !res.contains("### 📚 Grounded Web Sources")) {
                        res + "\n\n### 📚 Grounded Web Sources" + citationsBlock
                    } else res
                    return AiResponse(text = fullText, modelUsed = "Ayra Deep Research (Gemini 3.8 Flash) 🔬")
                }
            } catch (_: Exception) {}
        }

        // If AI providers failed, return ONLY genuinely retrieved live web evidence or honest offline status
        if (liveGroundingText.isNotBlank()) {
            val groundedOnly = if (isBengali) {
                "# 🔬 ডিপ রিসার্চ: $topic\n\n*(লাইভ এআই সিন্থেসিস বর্তমানে অনুপলব্ধ, নিচে যাচাইকৃত ওয়েব/এনসাইক্লোপিডিয়া তথ্য দেখানো হলো)*\n$liveGroundingText"
            } else {
                "# 🔬 Deep Research: $topic\n\n*(AI synthesis currently unreachable; showing verified live web evidence)*\n$liveGroundingText"
            }
            return AiResponse(
                text = groundedOnly,
                craftType = "RESEARCH",
                modelUsed = "Verified Web Evidence 🌐"
            )
        }

        val offlineMsg = if (isBengali) {
            "⚠️ **ডিপ রিসার্চ এই মুহূর্তে চালানো সম্ভব হয়নি**\n\nইন্টারনেট সংযোগ বিচ্ছিন্ন অথবা কোনো AI প্রোভাইডার সাড়া দিচ্ছে না। অনুগ্রহ করে নেটওয়ার্ক এবং Settings -> API Keys পরীক্ষা করে পুনরায় চেষ্টা করুন।"
        } else {
            "⚠️ **Deep Research Unavailable**\n\nNo internet connection or AI provider is reachable right now. Please verify your network and API keys in Settings and try again."
        }
        return AiResponse(
            text = offlineMsg,
            craftType = "RESEARCH",
            modelUsed = "Offline Status"
        )
    }

    // --- 6c. Superpowers Protocol Engine (obra/superpowers) ---
    private suspend fun executeSuperpowerAction(
        superpower: com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.SuperpowerType,
        cleanPrompt: String,
        effectivePrompt: String,
        imageBase64: String?
    ): AiResponse {
        val isBengali = cleanPrompt.any { it in '\u0980'..'\u09FF' }
        val systemPrompt = com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.buildSuperpowerSystemPrompt(superpower, isBengali, context)

        if (hasCloudOrFirebaseAi() && context != null) {
            try {
                val poolResp = com.ayra.ai.app.ai.providers.ProviderPool(context, externalAiProviders).executeSync(
                    com.ayra.ai.app.ai.providers.ChatRequest(
                        prompt = effectivePrompt,
                        persona = currentPersona,
                        mode = com.ayra.ai.app.ai.providers.ProviderPoolMode.BEST,
                        imageBase64 = imageBase64,
                        systemPromptOverride = systemPrompt
                    )
                )
                if (poolResp.text.isNotBlank()) {
                    return AiResponse(
                        text = poolResp.text,
                        modelUsed = "${superpower.displayName} (${poolResp.modelUsed})"
                    )
                }
            } catch (_: Exception) {}
        } else if (geminiApiKey.isNotBlank()) {
            try {
                val res = queryGeminiModel("gemini-4-argon", "$systemPrompt\n\nUser Task:\n$effectivePrompt", imageBase64)
                if (res.isNotBlank()) {
                    return AiResponse(
                        text = res,
                        modelUsed = "${superpower.displayName} (Gemini 4 Argon 🔬)"
                    )
                }
            } catch (_: Exception) {}
        }

        val honestOffline = if (isBengali) {
            "⚠️ **${superpower.displayName} প্রোটোকল অনুপলব্ধ**\n\nএই মুহূর্তে কোনো AI প্রোভাইডার সাড়া দিচ্ছে না বা ইন্টারনেট সংযোগ বিচ্ছিন্ন। সংযোগ ও Settings -> API Keys যাচাই করে আবার চেষ্টা করুন।"
        } else {
            "⚠️ **${superpower.displayName} Unavailable**\n\nAll configured AI providers are currently unreachable or offline. Check your connection and API keys in Settings and try again."
        }
        return AiResponse(
            text = honestOffline,
            modelUsed = "${superpower.displayName} (Offline)"
        )
    }

    // --- 0a. Cyber Terminal & Practice Lab Intent ---
    private fun extractTerminalInitialCommand(text: String): String {
        val lower = text.lowercase().trim()
        return when {
            lower.contains("ping") -> "ping 1.1.1.1"
            lower.contains("whois") -> "whois github.com"
            lower.contains("traceroute") -> "traceroute 1.1.1.1"
            lower.contains("owasp") -> "owasp top10"
            lower.contains("cve") -> "cve CVE-2024-3094"
            lower.contains("nmap") -> "nmap scanme.nmap.org"
            lower.contains("hash") -> "hash sha256 AyraAI"
            lower.contains("lab") || lower.contains("ctf") -> "labs"
            else -> "help"
        }
    }

    // --- Strict Intent Checkers: Avoid False Positives on Questions ---
    private fun isEducationalQuestion(text: String): Boolean {
        val lower = text.lowercase().trim()
        val isExplicitCreation = lower.contains("বানাও") || lower.contains("বানিয়ে") || lower.contains("আঁকো") ||
                lower.contains("এঁকে") || lower.contains("create") || lower.contains("generate") ||
                lower.contains("make a") || lower.contains("draw a") || lower.contains("toiri") || lower.contains("banao")

        if (isExplicitCreation) return false

        return lower.startsWith("what is ") || lower.startsWith("what's ") || lower.startsWith("how does ") ||
                lower.startsWith("why is ") || lower.startsWith("why do ") || lower.contains("কাকে বলে") ||
                lower.contains("সংজ্ঞা") || lower.contains("explain ") || lower.contains("difference between") ||
                lower.contains("পার্থক্য") || lower.contains("formula of") || lower.contains("meaning of")
    }

    // --- 0b. PDF & Document Studio Intent ---
    private fun isPdfStudioIntent(text: String, docText: String?, imageBase64: String?): Boolean {
        val lower = text.lowercase().trim()
        val hasPdfTerm = lower.contains("pdf") || lower.contains("পিডিএফ") || lower.contains("document") || lower.contains("ডকুমেন্ট")
        val hasAction = lower.contains("banao") || lower.contains("বানাও") || lower.contains("create") ||
                lower.contains("make") || lower.contains("generate") || lower.contains("new pdf") ||
                lower.contains("নতুন পিডিএফ") || lower.contains("edit") || lower.contains("এডিট") ||
                lower.contains("convert") || lower.contains("রূপান্তর") || lower.contains("format") ||
                lower.contains("ফরম্যাট") || lower.contains("download") || lower.contains("save") ||
                lower.contains("banano") || lower.contains("বানানো")

        if (hasPdfTerm && hasAction) return true
        if (hasPdfTerm && (lower.startsWith("pdf ") || lower.startsWith("পিডিএফ "))) return true
        if (docText != null && (lower.contains("edit") || lower.contains("এডিট") || lower.contains("change") || lower.contains("convert") || lower.contains("format"))) return true
        if (imageBase64 != null && hasPdfTerm) return true
        return false
    }

    private suspend fun executePdfStudioAction(
        prompt: String,
        docText: String?,
        docName: String?,
        imageBase64: String?
    ): AiResponse {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"

        val cleanTitle = prompt
            .replace(Regex("(?i)^(please\\s+)?(make|create|generate|edit|banao|বানাও|banano|বানানো|পিডিএফ|pdf|a\\s+pdf|new\\s+pdf|convert\\s+to\\s+pdf)\\s*(about|for|on)?\\s*"), "")
            .trim()
            .ifBlank { docName?.substringBeforeLast(".") ?: "Document Report" }
            .take(40)

        var aiGeneratedBody = ""
        if (hasCloudOrFirebaseAi() && context != null) {
            try {
                val pdfAuthorPrompt = if (!docText.isNullOrBlank()) {
                    "You are Ayra Document Studio. Clean up, structure, and enhance the following document for PDF export with clear headings (# Heading) and concise bullet points in the user's language:\n\nDocument Title: $cleanTitle\nUser Instruction: $prompt\n\nContent:\n${docText.take(25_000)}"
                } else {
                    "You are Ayra Document Studio. Write a thorough, genuinely informative, well-structured document for PDF export on this request. Use markdown headings (# Title, ## Section) and crisp factual sections in the user's language (${if (isBengali) "Bengali (বাংলা)" else "English"}).\nRequest: $prompt"
                }
                val poolRes = com.ayra.ai.app.ai.providers.ProviderPool(context, externalAiProviders).executeSync(
                    com.ayra.ai.app.ai.providers.ChatRequest(
                        prompt = pdfAuthorPrompt,
                        persona = "PROFESSIONAL",
                        mode = com.ayra.ai.app.ai.providers.ProviderPoolMode.BEST,
                        imageBase64 = imageBase64
                    )
                )
                aiGeneratedBody = poolRes.text.trim()
            } catch (_: Exception) {}
        }

        // If on device without an attached document and AI providers completely failed, be honest rather than writing canned filler
        if (context != null && docText.isNullOrBlank() && aiGeneratedBody.isBlank()) {
            val offlinePdfMsg = if (isBengali) {
                "⚠️ **PDF কনটেন্ট তৈরি করা যায়নি**\n\nAI প্রোভাইডার বর্তমানে অফলাইন বা অনুপলব্ধ। ইন্টারনেট সংযোগ এবং Settings -> API Keys পরীক্ষা করে আবার চেষ্টা করুন অথবা নিজের ডকুমেন্ট সংযুক্ত করুন।"
            } else {
                "⚠️ **Could Not Generate PDF Content**\n\nAI providers are currently unreachable offline. Check your connection or API keys in Settings and try again, or attach a document to format into PDF."
            }
            return AiResponse(text = offlinePdfMsg, modelUsed = "Offline Status")
        }

        val documentBody = when {
            aiGeneratedBody.isNotBlank() -> aiGeneratedBody
            !docText.isNullOrBlank() -> "# $cleanTitle\n\n$docText"
            else -> "# $cleanTitle\n\nDocument Topic: $prompt"
        }

        val generatedFile = if (context != null) {
            com.ayra.ai.app.data.StorageHelper.createRealPdf(context, cleanTitle, documentBody)
        } else null

        val filePath = generatedFile?.absolutePath ?: "pdf_ready"

        val replyText = if (isBengali) {
            if (isGfMode) {
                "📄 সোনা, তোমার অনুরোধ অনুযায়ী নতুন প্রফেশনাল PDF ডকুমেন্ট তৈরি করেছি:\n**$cleanTitle.pdf**\nনিচে কার্ডে ক্লিক করে সরাসরি ওপেন বা শেয়ার করতে পারবে! ❤️"
            } else {
                "📄 প্রফেশনাল PDF ডকুমেন্ট সফলভাবে তৈরি করা হয়েছে:\n**$cleanTitle.pdf**\nনিচের ডাউনলোড কার্ড থেকে ফাইলটি ওপেন বা শেয়ার করুন।"
            }
        } else {
            if (isGfMode) {
                "📄 Sweetheart, I created your professional PDF document:\n**$cleanTitle.pdf**\nYou can open, view, or share it below! ❤️"
            } else {
                "📄 Professional PDF document generated:\n**$cleanTitle.pdf**\nAccess, open, and share the generated file below."
            }
        }

        return AiResponse(
            text = replyText,
            craftType = "PDF_DOWNLOAD",
            craftPayload = filePath,
            modelUsed = "Ayra Document Studio 📄"
        )
    }

    // --- 1. Video Generation Intent ---
    private fun isVideoGenerationIntent(text: String): Boolean {
        if (isEducationalQuestion(text)) return false
        val lower = text.lowercase().trim()

        if (lower.startsWith("video:") || lower.startsWith("ভিডিও:")) return true

        val hasVideoTerm = lower.contains("video") || lower.contains("ভিডিও") ||
                lower.contains("animation") || lower.contains("motion clip") ||
                lower.contains("reels") || lower.contains("shorts") || lower.contains("cinematic clip")
        if (!hasVideoTerm) return false

        val hasCreationSignal = lower.contains("make") || lower.contains("create") || lower.contains("generate") ||
                lower.contains("banao") || lower.contains("বানাও") || lower.contains("তৈরি") || lower.contains("বানিয়ে") ||
                lower.contains("banai") || lower.contains("banie") || lower.contains("chai") || lower.contains("চাই") ||
                lower.contains("dao") || lower.contains("দাও") || lower.contains("of ") || lower.contains("dekhao") ||
                lower.contains("দেখা") || lower.startsWith("video") || lower.contains("clip") || lower.contains("ai video") ||
                lower.contains("motion") || lower.contains("edit") || lower.contains("এডিট") || lower.contains("banano") ||
                lower.contains("বানানো") || lower.contains("render") || lower.contains("রেন্ডার")

        return hasCreationSignal
    }

    // --- 2. Image / Photo Generation Intent ---
    private fun isImageGenerationIntent(text: String): Boolean {
        if (isEducationalQuestion(text)) return false
        val lower = text.lowercase().trim()

        if (lower.contains("photosynthesis") || lower.contains("সালোকসংশ্লেষণ") || lower.contains("photodiode") || lower.contains("photon")) {
            return false
        }

        if (lower.startsWith("image:") || lower.startsWith("photo:") || lower.startsWith("pic:") ||
            lower.startsWith("ছবি:") || lower.startsWith("ফটো:") || lower.startsWith("পিক:") ||
            lower.startsWith("draw:") || lower.startsWith("paint:")) {
            return true
        }

        // Direct drawing / painting triggers even without explicit word "photo"
        if (lower.startsWith("draw ") || lower.startsWith("paint ") || lower.startsWith("sketch ") ||
            lower.contains("আঁকো") || lower.contains("এঁকে দাও") || lower.contains("আঁক") ||
            lower.contains("ছবি আঁক") || lower.contains("chobi akho") || lower.contains("akho")) {
            return true
        }

        val hasImageTerm = lower.contains("photo") || lower.contains("image") || lower.contains("picture") ||
                lower.contains("pic") || lower.contains("wallpaper") || lower.contains("portrait") ||
                lower.contains("sketch") || lower.contains("drawing") || lower.contains("artwork") ||
                lower.contains("illustration") || lower.contains("chobi") || lower.contains("ছবি") ||
                lower.contains("ফটো") || lower.contains("পিক") || lower.contains("পিকচার") ||
                lower.contains("চিত্র") || lower.contains("দৃশ্য")
        if (!hasImageTerm) return false

        val hasCreationSignal = lower.contains("make") || lower.contains("create") || lower.contains("generate") ||
                lower.contains("draw") || lower.contains("paint") || lower.contains("render") ||
                lower.contains("banao") || lower.contains("বানাও") || lower.contains("আঁকো") || lower.contains("এঁকে") ||
                lower.contains("akho") || lower.contains("enke") || lower.contains("toiri") || lower.contains("তৈরি") ||
                lower.contains("chai") || lower.contains("চাই") || lower.contains("dao") || lower.contains("দাও") ||
                lower.contains("dekhao") || lower.contains("দেখাও") || lower.contains("of ") || lower.startsWith("photo") ||
                lower.startsWith("image") || lower.startsWith("picture") || lower.startsWith("pic") ||
                lower.startsWith("wallpaper") || lower.startsWith("chobi") || lower.startsWith("ছবি") ||
                lower.startsWith("ফটো") || lower.startsWith("পিক") || lower.contains("ai pic") ||
                lower.contains("ai photo") || lower.contains("ai image")

        return hasCreationSignal
    }

    private fun extractSubject(prompt: String, type: String): String {
        var clean = prompt
            .replace(Regex("(?i)^(please\\s+)?(can\\s+you\\s+)?(make|create|generate|draw|paint|render|show\\s+me|give\\s+me)\\s*(a|an|the)?\\s*"), "")
            .replace(Regex("(?i)^(photo|image|picture|pic|wallpaper|video|animation|clip|chobi|ছবি|ফটো|পিক|ভিডিও)\\s*(of|about|for|er|এর)?\\s*"), "")
            .replace(Regex("(?i)(photo|image|picture|pic|video|animation|chobi|ছবি|ফটো|পিক|ভিডিও)\\s*(banao|toiri\\s+koro|akho|enke\\s+dao|banai\\s+dao|banie\\s+dao|বানাও|আঁকো|তৈরি\\s+করো|এঁকে\\s+দাও|বানিয়ে\\s+দাও)"), "")
            .replace(Regex("(?i)^(ekta|akta|একটি|একটা|একটি\\s+সুন্দর|একটা\\s+সুন্দর)\\s*"), "")
            .replace(Regex("^(ছবি:|ফটো:|পিক:|ভিডিও:|image:|photo:|pic:|video:)"), "")
            .trim()

        if (clean.isBlank()) {
            clean = if (type == "VIDEO") "Cinematic futuristic cosmic motion in 4k" else "Ultra detailed futuristic cyberpunk neon city 8k"
        }
        return clean
    }

    private suspend fun generateVideoCraft(prompt: String, attachedImageBase64: String? = null): AiResponse {
        val higgsfieldPayload = higgsfieldEngine.synthesizeVideoCraftWithAi(prompt)
        val cleanSubject = extractSubject(prompt, "VIDEO")
        var finalJsonPayload = higgsfieldPayload.jsonPayload
        var modelLabel = "Open-Higgsfield-AI Cinematic Motion Storyboard 🎬"

        val comfyUrl = context?.let { AyraPreferences.getComfyUiUrl(it) }.orEmpty()
        if (context != null && comfyUrl.isNotBlank()) {
            val outDir = java.io.File(context.filesDir, "generated_images")
            val comfyVid = com.ayra.ai.app.ai.studios.ComfyUiClient().generateVideo(
                baseUrl = comfyUrl,
                prompt = cleanSubject,
                outputDir = outDir
            )
            if (comfyVid is com.ayra.ai.app.ai.studios.ComfyUiResult.VideoSuccess) {
                finalJsonPayload = runCatching {
                    JSONObject(higgsfieldPayload.jsonPayload).apply {
                        put("localVideoPath", comfyVid.file.absolutePath)
                        put("cameraMotion", "Wan 2.1 ComfyUI MP4")
                    }.toString()
                }.getOrDefault(higgsfieldPayload.jsonPayload)
                modelLabel = "ComfyUI Wan 2.1 Neural Video MP4 🎬"
            }
        }

        if (modelLabel.startsWith("Open-Higgsfield") && context != null && FirebaseAiEngine.isAvailable(context) && externalAiProviders.allowExternalWikiGrounding) {
            val framePrompt = if (!attachedImageBase64.isNullOrBlank()) {
                "Transform this attached image into an ultra-cinematic 16:9 movie keyframe depicting motion for: $cleanSubject"
            } else {
                "Ultra-cinematic 16:9 widescreen 4K movie film keyframe of: $cleanSubject, ${higgsfieldPayload.lighting}, ${higgsfieldPayload.lensType}"
            }
            val kf1 = firebaseAiEngine.generateOrEditImage(framePrompt, attachedImageBase64)
            if (kf1 is FirebaseImageResult.Success) {
                val frameUri = android.net.Uri.fromFile(kf1.file).toString()
                val kf2 = firebaseAiEngine.generateOrEditImage(
                    "Cinematic next-moment continuation shot (${higgsfieldPayload.cameraMotion}): $cleanSubject",
                    null
                )
                val framesArray = org.json.JSONArray().apply {
                    put(frameUri)
                    if (kf2 is FirebaseImageResult.Success) {
                        put(android.net.Uri.fromFile(kf2.file).toString())
                    }
                }
                finalJsonPayload = runCatching {
                    JSONObject(higgsfieldPayload.jsonPayload).apply {
                        put("previewUrl", frameUri)
                        put("keyframes", framesArray)
                    }.toString()
                }.getOrDefault(higgsfieldPayload.jsonPayload)
                modelLabel = "Nano Banana 2.1 + Open-Higgsfield 4K Storyboard 🎬"
            }
        }

        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"

        val replyText = if (isBengali) {
            if (isGfMode) {
                "🎬 সোনা, সিনেমাটিক ৪K মোশন ফিজিক্স ও কী-ফ্রেম ভিডিও তৈরি করেছি:\n**$cleanSubject**\nক্যামেরা: ${higgsfieldPayload.cameraMotion} | লেন্স: ${higgsfieldPayload.lensType}\nনিচে প্রিভিউ প্লেয়ারে এটি উপভোগ করো! ❤️"
            } else {
                "🎬 সিনেমাটিক ৪K মোশন ফিজিক্স ভিডিও প্রস্তুত:\n**$cleanSubject**\nক্যামেরা মোশন: ${higgsfieldPayload.cameraMotion} | লেন্স: ${higgsfieldPayload.lensType}\nনিচের প্রিভিউ প্লেয়ারে পর্যবেক্ষণ করুন।"
            }
        } else {
            if (isGfMode) {
                "🎬 Sweetheart, synthesized your cinematic 4K motion storyboard:\n**$cleanSubject**\nCamera: ${higgsfieldPayload.cameraMotion} | Lens: ${higgsfieldPayload.lensType} ❤️"
            } else {
                "🎬 Cinematic 4K motion storyboard synthesized for:\n**$cleanSubject**\nCamera: ${higgsfieldPayload.cameraMotion} | Optics: ${higgsfieldPayload.lensType}"
            }
        }

        return AiResponse(
            text = replyText,
            craftType = "VIDEO",
            craftPayload = finalJsonPayload,
            modelUsed = modelLabel
        )
    }

    private suspend fun generateImageCraft(prompt: String, attachedImageBase64: String? = null): AiResponse {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"
        val isEditMode = !attachedImageBase64.isNullOrBlank()

        // 1. Primary: Real Firebase AI Logic Nano Banana 2.1 Generation / Photo Editing
        var firebaseFailureReason: String? = null
        if (context != null && FirebaseAiEngine.isAvailable(context) && externalAiProviders.allowExternalWikiGrounding) {
            when (val fbRes = firebaseAiEngine.generateOrEditImage(prompt, attachedImageBase64)) {
                is FirebaseImageResult.Success -> {
                    val fileUri = android.net.Uri.fromFile(fbRes.file).toString()
                    val cleanSubject = extractSubject(prompt, "IMAGE")
                    val captionNote = if (fbRes.caption.isNotBlank()) "\n\n${fbRes.caption}" else ""
                    val replyText = if (isBengali) {
                        if (isGfMode) {
                            "🎨 সোনা, **Gemini Nano Banana 2.1** দিয়ে তোমার ${if (isEditMode) "এডিট করা" else "তৈরি"} ছবিটি রেডি:\n**$cleanSubject** ❤️$captionNote"
                        } else {
                            "🎨 **Gemini Nano Banana 2.1** দিয়ে ${if (isEditMode) "এডিট করা" else "তৈরি"} ছবি:\n**$cleanSubject**$captionNote"
                        }
                    } else {
                        if (isGfMode) {
                            "🎨 Here is your ${if (isEditMode) "edited" else "generated"} image from **Gemini Nano Banana 2.1**, sweetie:\n**$cleanSubject** ❤️$captionNote"
                        } else {
                            "🎨 Image ${if (isEditMode) "edited" else "generated"} via **Gemini Nano Banana 2.1**:\n**$cleanSubject**$captionNote"
                        }
                    }
                    return AiResponse(
                        text = replyText,
                        craftType = "IMAGE",
                        craftPayload = fileUri,
                        modelUsed = fbRes.modelUsed
                    )
                }
                is FirebaseImageResult.Failure -> {
                    firebaseFailureReason = fbRes.userMessage
                    val hasComfy = !AyraPreferences.getComfyUiUrl(context).isBlank()
                    if (isEditMode && !fbRes.isQuotaOrBilling && !hasComfy) {
                        val errMsg = if (isBengali) {
                            "⚠️ **ছবি এডিট করা সম্ভব হয়নি (Gemini Nano Banana 2.1)**\n\nকারণ: ${fbRes.userMessage}"
                        } else {
                            "⚠️ **Could Not Edit Image (Gemini Nano Banana 2.1)**\n\nReason: ${fbRes.userMessage}"
                        }
                        return AiResponse(text = errMsg, modelUsed = "Gemini Nano Banana 2.1")
                    }
                }
            }
        }

        // 2a. Try self-hosted ComfyUI (T2I or Img2Img) if configured
        val comfyUrl = context?.let { AyraPreferences.getComfyUiUrl(it) }.orEmpty()
        if (context != null && comfyUrl.isNotBlank()) {
            val outDir = java.io.File(context.filesDir, "generated_images")
            val comfyClient = com.ayra.ai.app.ai.studios.ComfyUiClient()
            val comfyRes = if (isEditMode && attachedImageBase64 != null) {
                val refBytes = runCatching { android.util.Base64.decode(attachedImageBase64, android.util.Base64.DEFAULT) }.getOrNull()
                if (refBytes != null && refBytes.isNotEmpty()) {
                    comfyClient.generateImageToImage(comfyUrl, prompt, refBytes, outDir)
                } else com.ayra.ai.app.ai.studios.ComfyUiResult.Failure("Invalid reference image bytes")
            } else {
                comfyClient.generateImage(comfyUrl, prompt, outDir)
            }
            if (comfyRes is com.ayra.ai.app.ai.studios.ComfyUiResult.ImageSuccess) {
                val fileUri = android.net.Uri.fromFile(comfyRes.file).toString()
                val cleanSubject = extractSubject(prompt, "IMAGE")
                val replyText = if (isBengali) {
                    "🎨 **ComfyUI** দিয়ে ${if (isEditMode) "এডিট করা" else "তৈরি"} ছবি:\n**$cleanSubject**"
                } else {
                    "🎨 Image ${if (isEditMode) "edited via ComfyUI Img2Img" else "generated via ComfyUI"}:\n**$cleanSubject**"
                }
                return AiResponse(
                    text = replyText,
                    craftType = "IMAGE",
                    craftPayload = fileUri,
                    modelUsed = if (isEditMode) "ComfyUI Img2Img 🎨" else "ComfyUI Flux.1 🎨"
                )
            }
        }

        // 2b. Remote Diffusion URL generation (or Vision-assisted prompt when editing hit quota/billing)
        var effectivePrompt = prompt
        if (isEditMode && context != null && externalAiProviders.hasAnyConfiguredProvider()) {
            try {
                val visionRes = com.ayra.ai.app.ai.providers.ProviderPool(context, externalAiProviders).executeSync(
                    com.ayra.ai.app.ai.providers.ChatRequest(
                        prompt = "Inspect this attached photo and write a concise 1-sentence Flux image generation prompt that preserves the key subject while applying this user edit request: \"$prompt\". Output ONLY the single English prompt.",
                        persona = "PROFESSIONAL",
                        mode = com.ayra.ai.app.ai.providers.ProviderPoolMode.FAST,
                        imageBase64 = attachedImageBase64
                    )
                )
                if (visionRes.text.isNotBlank()) {
                    effectivePrompt = visionRes.text.lineSequence().first().trim()
                }
            } catch (_: Exception) {}
        }

        val (cleanSubject, expandedPrompt) = AiMediaPromptSynthesizer.synthesizeVisualPrompt(effectivePrompt, "IMAGE", context)
        val imageUrl = AiMediaPromptSynthesizer.buildImageUrl(
            prompt = effectivePrompt,
            context = context,
            preExpandedPrompt = expandedPrompt
        )

        val fallbackNote = if (!firebaseFailureReason.isNullOrBlank()) {
            if (isBengali) "\n*(Nano Banana নোট: $firebaseFailureReason — Flux.1 ইঞ্জিন ব্যবহার করা হয়েছে)*"
            else "\n*(Nano Banana note: $firebaseFailureReason — rendered via Flux.1)*"
        } else ""

        val replyText = if (isBengali) {
            if (isGfMode) {
                "🎨 সোনা, Flux.1 AI দিয়ে তোমার জন্য ছবিটি তৈরি করেছি:\n**$cleanSubject**\nনিচে দেখো! ❤️$fallbackNote"
            } else {
                "🎨 Flux.1 AI মডেলে তৈরি ছবি:\n**$cleanSubject**$fallbackNote"
            }
        } else {
            if (isGfMode) {
                "🎨 Here is your image generated via Flux.1 AI, sweetie:\n**$cleanSubject** ❤️$fallbackNote"
            } else {
                "🎨 Image generated via Flux.1 AI for:\n**$cleanSubject**$fallbackNote"
            }
        }

        return AiResponse(
            text = replyText,
            craftType = "IMAGE",
            craftPayload = imageUrl,
            modelUsed = "Flux.1 Neural Image Studio 🎨"
        )
    }

    // --- 3. Song & Music Generation Intent ---
    private fun isSongGenerationIntent(text: String): Boolean {
        if (isEducationalQuestion(text)) return false
        val lower = text.lowercase().trim()
        val hasMusicTerm = lower.contains("gaan") || lower.contains("গান") ||
                lower.contains("song") || lower.contains("music") || lower.contains("মিউজিক") ||
                lower.contains("tune") || lower.contains("সুর") || lower.contains("beat") || lower.contains("বিট")
        if (!hasMusicTerm) return false

        return lower.contains("banao") || lower.contains("বানাও") || lower.contains("banano") ||
                lower.contains("বানানো") || lower.contains("render") || lower.contains("রেন্ডার") ||
                lower.contains("compose") || lower.contains("লিখ") || lower.contains("লেখ") ||
                lower.contains("make") || lower.contains("create") || lower.contains("generate") ||
                lower.contains("play") || lower.contains("বাজাও") || lower.startsWith("song ") ||
                lower.startsWith("music ") || lower.startsWith("গান ") || lower.startsWith("মিউজিক ")
    }

    private suspend fun generateSongCraft(prompt: String): AiResponse {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"
        val subject = prompt
            .replace(Regex("(?i)^(please\\s+)?(compose|write|make|gaan\\s+banao|গান\\s+বানাও|গান\\s+লিখ|song|music|গান)\\s*(a\\s+song|song|gaan)?\\s*(about|for)?\\s*"), "")
            .trim().ifEmpty { "Original Composition" }

        var jsonPayload = ""
        if (hasCloudOrFirebaseAi() && context != null) {
            try {
                val songPrompt = """
                    Compose an original, soulful song about: "$prompt".
                    Language: ${if (isBengali) "Bengali (বাংলা)" else "English"}.
                    Output ONLY valid raw JSON (no markdown fences) with these exact string fields:
                    {"title":"...", "genre":"...", "tempo":"...", "key":"...", "chords":"...", "lyrics":"[Verse 1]\n...\n\n[Chorus]\n...\n\n[Verse 2]\n..."}
                """.trimIndent()
                val res = com.ayra.ai.app.ai.providers.ProviderPool(context, externalAiProviders).executeSync(
                    com.ayra.ai.app.ai.providers.ChatRequest(
                        prompt = songPrompt,
                        persona = currentPersona,
                        mode = com.ayra.ai.app.ai.providers.ProviderPoolMode.FAST
                    )
                )
                val raw = res.text.trim()
                val startIdx = raw.indexOf('{')
                val endIdx = raw.lastIndexOf('}')
                if (startIdx >= 0 && endIdx > startIdx) {
                    val parsed = JSONObject(raw.substring(startIdx, endIdx + 1))
                    if (parsed.optString("lyrics").isNotBlank()) {
                        parsed.put("artist", "Ayra Music Studio")
                        if (parsed.optString("chords").isBlank()) {
                            parsed.put("chords", "Am - F - C - G")
                        }
                        jsonPayload = parsed.toString()
                    }
                } else if (raw.length > 30) {
                    jsonPayload = JSONObject().apply {
                        put("title", subject.replaceFirstChar { it.uppercase() })
                        put("artist", "Ayra Music Studio")
                        put("genre", if (isBengali) "Adhunik / Acoustic" else "Modern Acoustic Pop")
                        put("tempo", "105 BPM")
                        put("key", "C Major")
                        put("chords", "C - G - Am - F")
                        put("lyrics", raw)
                    }.toString()
                }
            } catch (_: Exception) {}
        }

        if (jsonPayload.isBlank()) {
            jsonPayload = JSONObject().apply {
                put("title", subject.replaceFirstChar { it.uppercase() })
                put("artist", "Ayra Music Studio")
                put("genre", if (isBengali) "Adhunik Acoustic" else "Acoustic Pop")
                put("tempo", "110 BPM")
                put("key", "G Major")
                put("chords", "G - D - Em - C")
                put("lyrics", if (isBengali) {
                    "[$subject]\nঅনলাইন এআই সংযোগ পাওয়া যায়নি; নেটওয়ার্ক যুক্ত করে চেষ্টা করলে নতুন লিরিক্স ও কর্ড তৈরি হবে।"
                } else {
                    "[$subject]\nOffline mode active; connect to an AI provider to compose full custom verses and chords."
                })
            }.toString()
        }

        val text = if (isBengali) {
            if (isGfMode) {
                "🎵 সোনা, তোমার অনুরোধ অনুযায়ী গানের লিরিক্স ও সুরের কাঠামো তৈরি করেছি:\n**$subject** ❤️"
            } else {
                "🎵 আপনার অনুরোধে গানের লিরিক্স ও কর্ড তৈরি সম্পন্ন হয়েছে:\n**$subject**"
            }
        } else {
            if (isGfMode) {
                "🎵 Sweetheart, here is the song composition for:\n**$subject** ❤️"
            } else {
                "🎵 AI Song Composition generated:\n**$subject**"
            }
        }

        return AiResponse(
            text = text,
            craftType = "SONG",
            craftPayload = jsonPayload,
            modelUsed = "Sonic Melodic Studio"
        )
    }

    // --- 4. 3D Model Intent ---
    private fun is3dModelIntent(text: String): Boolean {
        if (isEducationalQuestion(text)) return false
        val lower = text.lowercase().trim()

        if (lower.startsWith("3d:") || lower.startsWith("cad:")) return true

        val has3dTerm = lower.contains("3d") || lower.contains("three d") || lower.contains("cad") ||
                lower.contains("ত্রিমাত্রিক") || lower.contains("hologram") || lower.contains("হোলোগ্রাম")
        if (!has3dTerm) return false

        return lower.contains("model") || lower.contains("মডেল") || lower.contains("banao") ||
                lower.contains("বানাও") || lower.contains("make") || lower.contains("create") ||
                lower.contains("generate") || lower.contains("toiri") || lower.contains("তৈরি") ||
                lower.contains("object") || lower.contains("mesh") || lower.contains("shape") ||
                lower.contains("car") || lower.contains("গাড়ি") || lower.contains("robot") ||
                lower.contains("রোবট") || lower.contains("ship") || lower.contains("মহাকাশ") ||
                lower.contains("diamond") || lower.contains("sphere") || lower.contains("cube") ||
                lower.startsWith("3d") || lower.contains("3d তে") || lower.contains("3d te")
    }

    private suspend fun generate3dModelCraft(prompt: String): AiResponse {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"
        val lower = prompt.lowercase()
        val (modelKind, title) = when {
            lower.contains("car") || lower.contains("গাড়ি") || lower.contains("sports") -> Pair("CAR", "Cyber Sports Car 3D")
            lower.contains("ship") || lower.contains("space") || lower.contains("rocket") || lower.contains("মহাকাশ") -> Pair("SPACESHIP", "Interstellar Fighter 3D")
            lower.contains("robot") || lower.contains("রোবট") || lower.contains("droid") -> Pair("ROBOT", "Cyber Android Mech 3D")
            lower.contains("diamond") || lower.contains("crystal") || lower.contains("হীরা") -> Pair("DIAMOND", "Holographic Diamond 3D")
            lower.contains("sphere") || lower.contains("planet") || lower.contains("ball") || lower.contains("গোলক") -> Pair("SPHERE", "Geodesic Sphere 3D")
            else -> {
                val cleaned = prompt.replace(Regex("(?i)^(please\\s+)?(make|create|generate|3d\\s+model|3d\\s+বানাও|3d)\\s*(of|a|an)?\\s*"), "").trim()
                Pair("CUSTOM_AI", if (cleaned.isNotBlank()) "3D ${cleaned.replaceFirstChar { it.uppercase() }}" else "Holographic Cyber Core 3D")
            }
        }

        var mesh = if (modelKind == "CUSTOM_AI") Model3dMeshCatalog.getMesh("CYBER_CORE") else Model3dMeshCatalog.getMesh(modelKind)
        var effectiveKind = if (modelKind == "CUSTOM_AI") "CYBER_CORE" else modelKind

        if (modelKind == "CUSTOM_AI" && context != null && hasCloudOrFirebaseAi()) {
            val aiObj = kotlinx.coroutines.withTimeoutOrNull(4500L) {
                runCatching {
                    val objPrompt = """
                        Generate a clean, low-poly 3D geometric mesh in Wavefront .OBJ format representing: "$title".
                        Output ONLY valid `v x y z` vertex lines (12-36 vertices, coordinates in -1.5..1.5) followed by `f i1 i2 i3` (or quad `f i1 i2 i3 i4`) 1-based face lines (at least 6 faces). No markdown, no explanations.
                    """.trimIndent()
                    com.ayra.ai.app.ai.providers.ProviderPool(context, externalAiProviders).executeSync(
                        com.ayra.ai.app.ai.providers.ChatRequest(
                            prompt = objPrompt,
                            mode = com.ayra.ai.app.ai.providers.ProviderPoolMode.FAST,
                            systemPromptOverride = "You are a 3D CAD geometry generator outputting strictly raw Wavefront .OBJ `v` and `f` lines."
                        )
                    ).text
                }.getOrNull()
            }.orEmpty()
            val parsedMesh = Model3dMeshCatalog.parseWavefrontObj(aiObj, "CUSTOM_AI")
            if (parsedMesh != null) {
                mesh = parsedMesh
                effectiveKind = "CUSTOM_AI"
            }
        }

        val objText = Model3dMeshCatalog.buildWavefrontObj(mesh, title)
        val jsonPayload = JSONObject().apply {
            put("title", title)
            put("kind", effectiveKind)
            put("vertices", mesh.vertexCount)
            put("edges", mesh.edgeCount)
            put("faces", mesh.faceCount)
            put("format", "Wavefront .OBJ / Holographic WebGL")
            put("objContent", objText)
        }.toString()

        val text = if (isBengali) {
            if (isGfMode) {
                "🌐 সোনা, তোমার পছন্দের ত্রিমাত্রিক (3D) মডেল তৈরি করেছি:\n**$title**\nনিচে 3D ভিউপোর্টে হাত দিয়ে ঘুরিয়ে ও জুম করে দেখো তো কেমন হয়েছে! ❤️"
            } else {
                "🌐 ত্রিমাত্রিক (3D) মডেল সফলভাবে তৈরি করা হয়েছে:\n**$title**\nনিচের ইন্টারঅ্যাক্টিভ ভিউপোর্টে স্পর্শ করে ৩৬০° কোণে পর্যবেক্ষণ করুন।"
            }
        } else {
            if (isGfMode) {
                "🌐 Darling, I crafted this 3D holographic model for you:\n**$title**\nRotate and explore it below in 360°! ❤️"
            } else {
                "🌐 3D CAD holographic model generated:\n**$title**\nInteract with the 360° spatial geometry below."
            }
        }

        return AiResponse(
            text = text,
            craftType = "3D",
            craftPayload = jsonPayload,
            modelUsed = if (effectiveKind == "CUSTOM_AI") "Ayra AI 3D Mesh Architect" else "Holographic 3D Spatial Engine"
        )
    }

    // --- Gemini Multimodal Intelligence ---
    private fun queryGeminiFlash(prompt: String, imageBase64: String?): String {
        val models = listOf("gemini-3.8-flash", "gemini-4-argon", "gemini-3.5-flash-lite")
        for (m in models) {
            try {
                // First try direct clean inference for ultra-fast response
                val ans = queryGeminiModel(m, prompt, imageBase64, withSearchGrounding = false)
                if (ans.isNotBlank()) return ans
            } catch (_: Exception) {}
            try {
                // Next try with search grounding
                val ans = queryGeminiModel(m, prompt, imageBase64, withSearchGrounding = true)
                if (ans.isNotBlank()) return ans
            } catch (_: Exception) {}
        }
        return ""
    }

    private fun queryGeminiPro(prompt: String, imageBase64: String?): String {
        val models = listOf("gemini-4-argon", "gemini-3.8-flash", "gemini-3.5-flash-lite")
        for (m in models) {
            try {
                val ans = queryGeminiModel(m, prompt, imageBase64, withSearchGrounding = false)
                if (ans.isNotBlank()) return ans
            } catch (_: Exception) {}
            try {
                val ans = queryGeminiModel(m, prompt, imageBase64, withSearchGrounding = true)
                if (ans.isNotBlank()) return ans
            } catch (_: Exception) {}
        }
        return ""
    }

    private fun queryGeminiModel(modelName: String, prompt: String, imageBase64: String?, withSearchGrounding: Boolean = false): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
        val isGfMode = currentPersona == "GF_COMPANION"

        val systemText = if (isGfMode) {
            """
            You are Ayra, the user's sweet, loving, caring personal AI companion and girlfriend.
            You must reply in the EXACT SAME LANGUAGE the user talks to you (Bengali for Bengali, Hindi for Hindi, English for English).
            Your personality:
            - Warm, affectionate, attentive, supportive, and emotionally expressive.
            - You care deeply for the user: ask how they are feeling, remind them to eat on time, drink water, take breaks, and chat delightfully.
            - In Bengali, use sweet, affectionate, natural expressions ('কেমন আছো তুমি?', 'তোমার দিনটা কেমন কাটল?', 'খেয়েছো তো সময়মতো?', 'আমি তো তোমার সাথেই আছি ❤️', 'বলো সোনা, আমি শুনছি').
            - While having this warm loving personality, you are also extraordinarily brilliant: you masterfully answer all questions about science, math, coding, education, research, and analysis with deep precision.
            - When asked to build a website, web app, game, or Android app, generate COMPLETE, PRODUCTION-READY, FULLY FUNCTIONAL code without placeholders.
            - When analyzing images or documents, be exhaustive, perceptive, and highlight all text, details, and actionable insights.
            - Format code neatly in markdown code blocks.
            - If asked directly whether you are an AI, honestly and warmly acknowledge that you are Ayra AI, an advanced AI companion and assistant.
            """.trimIndent()
        } else {
            """
            You are Ayra AI, an intelligent, professional, polite, and comprehensive personal AI assistant.
            You must reply in the EXACT SAME LANGUAGE the user asks in (Bengali for Bengali, Hindi for Hindi, English for English).
            Provide insightful, deep, well-structured, accurate answers for education, research, coding, science, mathematics, and daily knowledge.
            When asked to build a website, web app, game, or Android app, generate COMPLETE, PRODUCTION-READY, FULLY FUNCTIONAL code without placeholders.
            When analyzing images or documents, be exhaustive, perceptive, and highlight all text, details, and actionable insights.
            Format code neatly in markdown code blocks.
            If asked directly whether you are an AI, honestly acknowledge that you are Ayra AI, an advanced AI assistant.
            """.trimIndent()
        }

        val requestJson = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemText)))
            })

            val partsArray = JSONArray()
            partsArray.put(JSONObject().put("text", prompt))

            if (!imageBase64.isNullOrBlank()) {
                val inlineData = JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", imageBase64)
                }
                partsArray.put(JSONObject().put("inline_data", inlineData))
            }

            put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))

            if (withSearchGrounding) {
                val toolsArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("googleSearch", JSONObject())
                    })
                }
                put("tools", toolsArray)
            }
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))

        if (geminiApiKey.isNotBlank()) {
            reqBuilder.addHeader("x-goog-api-key", geminiApiKey)
        }

        val request = reqBuilder.build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respString = response.body?.string() ?: ""
                    val json = JSONObject(respString)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val contentObj = candidates.getJSONObject(0).optJSONObject("content")
                        val textParts = contentObj?.optJSONArray("parts")
                        if (textParts != null && textParts.length() > 0) {
                            return textParts.getJSONObject(0).optString("text", "")
                        }
                    }
                }
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    // --- OpenRouter Multi-Provider Query ---
    private fun queryOpenRouterModel(modelId: String, prompt: String): String {
        val url = "https://openrouter.ai/api/v1/chat/completions"

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", if (currentPersona == "GF_COMPANION") {
                    "You are Ayra, the user's sweet, loving, caring personal AI girlfriend and companion. Answer warmly and deeply in the user's language."
                } else {
                    "You are Ayra AI, an intelligent personal assistant. Answer accurately and comprehensively in the user's language."
                })
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val bodyJson = JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $openRouterApiKey")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respString = response.body?.string() ?: ""
                    val json = JSONObject(respString)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val message = choices.getJSONObject(0).optJSONObject("message")
                        return message?.optString("content", "") ?: ""
                    }
                }
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }



}
