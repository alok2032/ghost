package com.ayra.ai.app.ai

import android.content.Context
import android.media.MediaMetadataRetriever
import com.ayra.ai.app.BuildConfig
import com.ayra.ai.app.data.AyraPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

        // Sub-millisecond response cache for high concurrency repeat queries (keyed by settings state)
        val langPref = context?.let { AyraPreferences.getPreferredLanguage(it) } ?: "Auto"
        val localOnly = context?.let { AyraPreferences.isLocalOnlyAiModeEnabled(it) } ?: false
        val privacyMode = context?.let { AyraPreferences.isPrivacyModeEnabled(it) } ?: false
        val cacheKey = "${currentPersona}_${selectedModelTier}_${langPref}_${localOnly}_${privacyMode}_${cleanPrompt.lowercase()}"
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
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
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
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
            if (geminiApiKey.isNotBlank()) {
                val codePrompt = "Write COMPLETE, PRODUCTION-READY, FULLY FUNCTIONAL, SELF-CONTAINED code for this request without any placeholder comments:\n$cleanPrompt"
                val res = queryGeminiPro(codePrompt, attachedImageBase64)
                if (res.isNotBlank()) {
                    return@withContext AiResponse(text = res, craftType = "CODE_PROJECT", craftPayload = cleanPrompt, modelUsed = "Gemini 4 Argon Code Architect 🚀")
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
                } catch (e: CancellationException) {
                    throw e
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
                        try {
                            val dsResp = com.ayra.ai.app.ai.deepseek.DeepSeekHarnessEngine(context).executeHarness(effectivePrompt)
                            if (dsResp.text.isNotBlank()) return@withContext emitResponse(dsResp)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {}
                    }
                }

                AiModelTier.G0DM0D3 -> {
                    if (context != null) {
                        try {
                            val gmResp = com.ayra.ai.app.ai.godmode.GodModePliniusEngine(context).analyzeSecurityQuery(effectivePrompt)
                            if (gmResp.text.isNotBlank()) return@withContext emitResponse(gmResp)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {}
                    }
                }

                AiModelTier.ECC_COGNITIVE -> {
                    if (context != null) {
                        try {
                            val eccResp = com.ayra.ai.app.ai.ecc.EnterpriseCognitiveEngine(context).disambiguateAndExecute(effectivePrompt)
                            if (eccResp.text.isNotBlank()) return@withContext emitResponse(eccResp)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {}
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
                } catch (e: CancellationException) {
                    throw e
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
        } catch (e: CancellationException) {
            throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
                } catch (e: CancellationException) {
                    throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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

    private fun readVideoDurationText(file: File): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val totalSecs = (ms / 1000L).coerceAtLeast(1L)
            val mins = totalSecs / 60L
            val secs = totalSecs % 60L
            String.format(java.util.Locale.US, "%d:%02d", mins, secs)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun generateVideoCraft(prompt: String, attachedImageBase64: String? = null): AiResponse {
        val higgsfieldPayload = higgsfieldEngine.synthesizeVideoCraftWithAi(prompt)
        val cleanSubject = extractSubject(prompt, "VIDEO")
        var finalJsonPayload = higgsfieldPayload.jsonPayload
        var modelLabel = "Open-Higgsfield-AI Cinematic Motion Storyboard 🎬"
        var hasRenderedVideo = false
        var hasStoryKeyframes = false

        val comfyUrl = context?.let { AyraPreferences.getComfyUiUrl(it) }.orEmpty()
        if (context != null && comfyUrl.isNotBlank()) {
            val outDir = File(context.filesDir, "generated_images")
            val comfyVid = com.ayra.ai.app.ai.studios.ComfyUiClient().generateVideo(
                baseUrl = comfyUrl,
                prompt = cleanSubject,
                outputDir = outDir
            )
            if (comfyVid is com.ayra.ai.app.ai.studios.ComfyUiResult.VideoSuccess) {
                val measuredDuration = readVideoDurationText(comfyVid.file)
                finalJsonPayload = runCatching {
                    JSONObject(higgsfieldPayload.jsonPayload).apply {
                        put("localVideoPath", comfyVid.file.absolutePath)
                        put("isStoryboardOnly", false)
                        if (measuredDuration != null) put("duration", measuredDuration) else remove("duration")
                        put("cameraMotion", "Wan 2.1 ComfyUI MP4")
                    }.toString()
                }.getOrDefault(higgsfieldPayload.jsonPayload)
                modelLabel = "ComfyUI Wan 2.1 Neural Video MP4 🎬"
                hasRenderedVideo = true
            }
        }

        val canUseFirebaseImage = context != null &&
            !AyraPreferences.isLocalOnlyAiModeEnabled(context) &&
            AyraPreferences.hasExternalAiConsent(context) &&
            FirebaseAiEngine.isAvailable(context)

        if (!hasRenderedVideo && canUseFirebaseImage) {
            val framePrompt = if (!attachedImageBase64.isNullOrBlank()) {
                "Transform this attached image into an ultra-cinematic 16:9 movie keyframe depicting motion for: $cleanSubject"
            } else {
                "Ultra-cinematic 16:9 widescreen 4K movie film keyframe of: $cleanSubject, ${higgsfieldPayload.lighting}, ${higgsfieldPayload.lensType}"
            }
            val kf1 = firebaseAiEngine.generateOrEditImage(framePrompt, attachedImageBase64, "16:9")
            if (kf1 is FirebaseImageResult.Success) {
                val frameUri = android.net.Uri.fromFile(kf1.file).toString()
                val kf2 = firebaseAiEngine.generateOrEditImage(
                    "Cinematic next-moment continuation shot (${higgsfieldPayload.cameraMotion}): $cleanSubject",
                    null,
                    "16:9"
                )
                val framesArray = JSONArray().apply {
                    put(frameUri)
                    if (kf2 is FirebaseImageResult.Success) {
                        put(android.net.Uri.fromFile(kf2.file).toString())
                    }
                }
                finalJsonPayload = runCatching {
                    JSONObject(higgsfieldPayload.jsonPayload).apply {
                        put("previewUrl", frameUri)
                        put("keyframes", framesArray)
                        put("isStoryboardOnly", true)
                        put("frameCount", framesArray.length())
                    }.toString()
                }.getOrDefault(higgsfieldPayload.jsonPayload)
                modelLabel = "Nano Banana 2.1 + Open-Higgsfield 4K Storyboard 🎬"
                hasStoryKeyframes = true
            }
        }

        // Gate preview image inside OpenHiggsfield storyboard when local-only/unconsented
        val allowFreePool = context != null &&
            !AyraPreferences.isLocalOnlyAiModeEnabled(context) &&
            AyraPreferences.hasExternalAiConsent(context) &&
            AyraPreferences.isFreeAiPoolEnabled(context)
        if (!hasRenderedVideo && !hasStoryKeyframes && !allowFreePool) {
            finalJsonPayload = runCatching {
                JSONObject(finalJsonPayload).apply {
                    remove("previewUrl")
                    put("isStoryboardOnly", true)
                }.toString()
            }.getOrDefault(finalJsonPayload)
        }

        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"

        val replyText = if (isBengali) {
            when {
                hasRenderedVideo -> "🎬 ComfyUI Wan 2.1 থেকে রেন্ডার করা আসল MP4 ভিডিও তৈরি হয়েছে:\n**$cleanSubject**\nনিচে ভিডিও প্লেয়ারে প্লে করুন।"
                isGfMode -> "🎬 সোনা, সিনেমাটিক কী-ফ্রেম ও ক্যামেরা মোশন স্টোরিবোর্ড তৈরি করেছি:\n**$cleanSubject**\nক্যামেরা: ${higgsfieldPayload.cameraMotion} | লেন্স: ${higgsfieldPayload.lensType} (পূর্ণ MP4 ভিডিওর জন্য Tools-এ ComfyUI URL সেট করুন) ❤️"
                else -> "🎬 সিনেমাটিক মোশন স্টোরিবোর্ড ও কী-ফ্রেম ডিরেকশন প্রস্তুত:\n**$cleanSubject**\nক্যামেরা: ${higgsfieldPayload.cameraMotion} | লেন্স: ${higgsfieldPayload.lensType} (পূর্ণ MP4 ভিডিও রেন্ডার করতে ComfyUI সার্ভার সংযুক্ত করুন)।"
            }
        } else {
            when {
                hasRenderedVideo -> "🎬 Real MP4 video rendered via ComfyUI Wan 2.1 for:\n**$cleanSubject**"
                isGfMode -> "🎬 Sweetheart, synthesized your cinematic motion storyboard:\n**$cleanSubject**\nCamera: ${higgsfieldPayload.cameraMotion} | Lens: ${higgsfieldPayload.lensType} (configure ComfyUI in Tools for direct MP4 video) ❤️"
                else -> "🎬 Cinematic motion storyboard synthesized for:\n**$cleanSubject**\nCamera: ${higgsfieldPayload.cameraMotion} | Optics: ${higgsfieldPayload.lensType} (connect ComfyUI in Tools for live MP4 video rendering)"
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
        val aspectRatio = when {
            prompt.contains("16:9") || prompt.contains("landscape", ignoreCase = true) || prompt.contains("wallpaper", ignoreCase = true) -> "16:9"
            prompt.contains("9:16") || prompt.contains("portrait", ignoreCase = true) || prompt.contains("vertical", ignoreCase = true) -> "9:16"
            else -> "1:1"
        }

        // 1. Primary: Real Firebase AI Logic Nano Banana 2.1 Generation / Photo Editing
        var firebaseFailureReason: String? = null
        if (context != null && FirebaseAiEngine.isAvailable(context) && externalAiProviders.allowExternalWikiGrounding) {
            when (val fbRes = firebaseAiEngine.generateOrEditImage(prompt, attachedImageBase64, aspectRatio)) {
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
            val outDir = File(context.filesDir, "generated_images")
            val comfyClient = com.ayra.ai.app.ai.studios.ComfyUiClient()
            val comfyRes = if (!attachedImageBase64.isNullOrBlank()) {
                val refBytes = runCatching { android.util.Base64.decode(attachedImageBase64, android.util.Base64.DEFAULT) }.getOrNull()
                if (refBytes != null && refBytes.isNotEmpty()) {
                    comfyClient.generateImageToImage(comfyUrl, prompt, refBytes, outDir)
                } else com.ayra.ai.app.ai.studios.ComfyUiResult.Failure("Invalid reference image bytes")
            } else {
                val (w, h) = when (aspectRatio) {
                    "16:9" -> 1024 to 576
                    "9:16" -> 576 to 1024
                    else -> 768 to 768
                }
                comfyClient.generateImage(comfyUrl, prompt, outDir, width = w, height = h)
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

        // 2b. Remote Diffusion URL generation only when consented & Free AI Pool is enabled
        val canUseFreePool = context != null &&
            !AyraPreferences.isLocalOnlyAiModeEnabled(context) &&
            AyraPreferences.hasExternalAiConsent(context) &&
            AyraPreferences.isFreeAiPoolEnabled(context)

        if (!canUseFreePool) {
            val reasonNote = firebaseFailureReason?.let { "\n\nNano Banana status: $it" }.orEmpty()
            val errText = if (isBengali) {
                "⚠️ **ছবি তৈরি করা সম্ভব হয়নি**\n\nবর্তমানে Local-Only মোড চালু আছে অথবা Global Free AI Pool (Pollinations) বন্ধ রয়েছে। Tools & Keys থেকে **Global Free AI Pool** অন করুন অথবা **ComfyUI Server URL** যুক্ত করুন।$reasonNote"
            } else {
                "⚠️ **Image Generation Unavailable**\n\nPrimary cloud image generation did not succeed and the public Global Free AI Pool is disabled or blocked by Local-Only mode. Enable **Global Free AI Pool** or configure a **ComfyUI Server URL** in Tools & Keys.$reasonNote"
            }
            return AiResponse(text = errText, modelUsed = "Offline Status")
        }

        var effectivePrompt = prompt
        if (isEditMode && externalAiProviders.hasAnyConfiguredProvider()) {
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        val (cleanSubject, expandedPrompt) = AiMediaPromptSynthesizer.synthesizeVisualPrompt(effectivePrompt, "IMAGE", context)
        val imageUrl = AiMediaPromptSynthesizer.buildImageUrl(
            prompt = effectivePrompt,
            context = context,
            preExpandedPrompt = expandedPrompt,
            aspectRatio = aspectRatio
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        if (jsonPayload.isBlank()) {
            val offlineMsg = if (isBengali) {
                "⚠️ **গান ও লিরিক্স তৈরি করা যায়নি**\n\nমৌলিক গানের লিরিক্স ও কর্ড তৈরি করতে বর্তমানে কোনো AI প্রোভাইডার সংযুক্ত নেই। অনুগ্রহ করে ইন্টারনেট সংযোগ ও API কী পরীক্ষা করে আবার চেষ্টা করুন।"
            } else {
                "⚠️ **Song Composition Unavailable**\n\nNo active AI provider could be reached to compose original lyrics and chord progressions. Check your connection or API keys in Tools & Keys and try again."
            }
            return AiResponse(
                text = offlineMsg,
                craftType = null,
                craftPayload = null,
                modelUsed = "Offline Status"
            )
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

    private suspend fun generate3dModelCraft(prompt: String): AiResponse {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"
        val lower = prompt.lowercase()
        val (modelKind, baseTitle) = when {
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
        var usedFallbackPreset = false

        if (modelKind == "CUSTOM_AI" && context != null && hasCloudOrFirebaseAi()) {
            val aiObj = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                try {
                    val objPrompt = """
                        Generate a clean, low-poly 3D geometric mesh in Wavefront .OBJ format representing: "$baseTitle".
                        Output ONLY valid `v x y z` vertex lines (12-36 vertices, coordinates in -1.5..1.5) followed by `f i1 i2 i3` (or quad `f i1 i2 i3 i4`) 1-based face lines (at least 6 faces). No markdown, no explanations.
                    """.trimIndent()
                    com.ayra.ai.app.ai.providers.ProviderPool(context, externalAiProviders).executeSync(
                        com.ayra.ai.app.ai.providers.ChatRequest(
                            prompt = objPrompt,
                            mode = com.ayra.ai.app.ai.providers.ProviderPoolMode.FAST,
                            systemPromptOverride = "You are a 3D CAD geometry generator outputting strictly raw Wavefront .OBJ `v` and `f` lines."
                        )
                    ).text
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ""
                }
            }.orEmpty()
            val parsedMesh = Model3dMeshCatalog.parseWavefrontObj(aiObj, "CUSTOM_AI")
            if (parsedMesh != null) {
                mesh = parsedMesh
                effectiveKind = "CUSTOM_AI"
            } else {
                usedFallbackPreset = true
            }
        } else if (modelKind == "CUSTOM_AI") {
            usedFallbackPreset = true
        }

        val displayTitle = if (usedFallbackPreset) {
            "Cyber Core 3D (Fallback Preset for \"$baseTitle\")"
        } else {
            baseTitle
        }

        val objText = Model3dMeshCatalog.buildWavefrontObj(mesh, displayTitle)
        val jsonPayload = JSONObject().apply {
            put("title", displayTitle)
            put("kind", effectiveKind)
            put("isFallback", usedFallbackPreset)
            put("vertices", mesh.vertexCount)
            put("edges", mesh.edgeCount)
            put("faces", mesh.faceCount)
            put("format", "Wavefront .OBJ / Holographic WebGL")
            put("objContent", objText)
        }.toString()

        val fallbackBanner = if (usedFallbackPreset) {
            if (isBengali) "\n*(নোট: কাস্টম AI মেশ তৈরি না হওয়ায় ডিফল্ট ৩ডি Cyber Core প্রিসেট প্রদর্শিত হচ্ছে)*"
            else "\n*(Note: AI custom mesh synthesis unavailable or returned invalid geometry; displaying Cyber Core 3D preset.)*"
        } else ""

        val text = if (isBengali) {
            if (isGfMode) {
                "🌐 সোনা, তোমার ত্রিমাত্রিক (3D) মডেল প্রস্তুত:\n**$displayTitle**\nনিচে 3D ভিউপোর্টে হাত দিয়ে ঘুরিয়ে ও জুম করে দেখো তো কেমন হয়েছে! ❤️$fallbackBanner"
            } else {
                "🌐 ত্রিমাত্রিক (3D) মডেল লোড করা হয়েছে:\n**$displayTitle**\nনিচের ইন্টারঅ্যাক্টিভ ভিউপোর্টে স্পর্শ করে ৩৬০° কোণে পর্যবেক্ষণ করুন।$fallbackBanner"
            }
        } else {
            if (isGfMode) {
                "🌐 Darling, here is your 3D holographic viewport:\n**$displayTitle**\nRotate and explore it below in 360°! ❤️$fallbackBanner"
            } else {
                "🌐 3D CAD holographic viewport ready:\n**$displayTitle**\nInteract with the 360° spatial geometry below.$fallbackBanner"
            }
        }

        return AiResponse(
            text = text,
            craftType = "3D",
            craftPayload = jsonPayload,
            modelUsed = when {
                effectiveKind == "CUSTOM_AI" -> "Ayra AI 3D Mesh Architect"
                usedFallbackPreset -> "Ayra 3D Preset Viewport (Offline Fallback)"
                else -> "Holographic 3D Spatial Engine"
            }
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
            try {
                // Next try with search grounding
                val ans = queryGeminiModel(m, prompt, imageBase64, withSearchGrounding = true)
                if (ans.isNotBlank()) return ans
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
            try {
                val ans = queryGeminiModel(m, prompt, imageBase64, withSearchGrounding = true)
                if (ans.isNotBlank()) return ans
            } catch (e: CancellationException) {
                throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
    }



}
