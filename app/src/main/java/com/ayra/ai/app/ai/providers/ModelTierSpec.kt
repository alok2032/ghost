package com.ayra.ai.app.ai.providers

import android.content.Context
import androidx.compose.runtime.Immutable
import com.ayra.ai.app.ai.ecc.EnterpriseCognitiveEngine
import com.ayra.ai.app.ai.openmodel.OpenModelEngine
import com.ayra.ai.app.ai.securityresearch.SecurityResearchEngine
import com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine
import java.util.Locale

/**
 * Single source of truth for all Ayra AI model routing tiers and legacy aliases.
 * Consolidates previously duplicated tier lists across UnifiedAiEngine, AyraOrchestrator,
 * OmniRouteEngine, ExternalAiProviders, AyraPreferences, and UI pickers.
 */
@Immutable
enum class ModelTier(
    val id: String,
    val displayName: String,
    val providerName: String,
    val speedLabel: String,
    val reasoningLabel: String,
    val description: String,
    val accentColorHex: Long,
    val defaultPoolMode: ProviderPoolMode,
    val isSelectableTier: Boolean = true,
    val usesOmniRouteFastPath: Boolean = false,
    val aliases: Set<String> = emptySet()
) {
    AUTO(
        id = "auto",
        displayName = "Auto Adaptive Router ⚡",
        providerName = "OmniRoute Intelligent Matrix",
        speedLabel = "Adaptive ⚡",
        reasoningLabel = "Dynamic 🧠",
        description = "স্মার্ট মাল্টি-প্রোভাইডার অপটিমাইজেশন — সাধারণ প্রশ্নে ফ্ল্যাশ ও জটিল বিষয়ে ডিপ রিজনিং",
        accentColorHex = 0xFF00FFFF,
        defaultPoolMode = ProviderPoolMode.AUTO,
        aliases = setOf("balanced")
    ),
    OMNI_ROUTE(
        id = "omniroute",
        displayName = "OmniRoute Ensemble ⚡👑",
        providerName = "OmniRoute Multi-Model Synthesis",
        speedLabel = "Consensus Synergy ⚡",
        reasoningLabel = "Multi-Model Fusion 👑",
        description = "সর্বোত্তম ফলাফলের জন্য একাধিক সক্রিয় এআই মডেলের সম্মিলিত বিশ্লেষণ",
        accentColorHex = 0xFFFFD700,
        defaultPoolMode = ProviderPoolMode.BEST,
        usesOmniRouteFastPath = true
    ),
    GEMINI_FLASH(
        id = "gemini_flash",
        displayName = "Gemini Flash Live ⚡",
        providerName = "Google AI / Firebase AI Logic",
        speedLabel = "Ultra Fast ⚡⚡",
        reasoningLabel = "High + Web Grounding 🌐",
        description = "কম ল্যাটেন্সিতে দ্রুত প্রতিক্রিয়া, দৈনন্দিন চ্যাট, ছবি/পিডিএফ বিশ্লেষণ ও ওয়েব সার্চ",
        accentColorHex = 0xFF00FF7F,
        defaultPoolMode = ProviderPoolMode.FAST,
        aliases = setOf("fast")
    ),
    GEMINI_PRO(
        id = "gemini_pro",
        displayName = "Gemini Pro Deep Reasoning 🧠",
        providerName = "Google AI / Firebase AI Logic",
        speedLabel = "Deep Reasoning ⏳",
        reasoningLabel = "Chain-of-Thought & Coding 🧠🧠",
        description = "অ্যাপ ডেভেলপমেন্ট, জটিল গণিত, বিজ্ঞান ও ডিপ রিসার্চ",
        accentColorHex = 0xFFB388FF,
        defaultPoolMode = ProviderPoolMode.BEST,
        aliases = setOf("quality")
    ),
    DEEPSEEK_HARNESS(
        id = "deepseek_harness",
        displayName = "DeepSeek-R1 (Harness CoT) 🔬",
        providerName = "DeepSeek / SambaNova / OpenRouter",
        speedLabel = "Thinking Chain ⏳",
        reasoningLabel = "Chain-of-Thought 🧠🧠🧠",
        description = "যৌক্তিক ধাপভিত্তিক বিশ্লেষণ, গাণিতিক প্রমাণ ও অ্যালগরিদম যাচাই",
        accentColorHex = 0xFFFF80AB,
        defaultPoolMode = ProviderPoolMode.BEST,
        usesOmniRouteFastPath = true
    ),
    OPENROUTER_DEEPSEEK(
        id = "deepseek_r1",
        displayName = "DeepSeek-R1 (Chain-of-Thought) 🧠",
        providerName = "DeepSeek / OpenRouter",
        speedLabel = "Thinking Chain ⏳",
        reasoningLabel = "Chain-of-Thought 🧠🧠🧠",
        description = "উন্মুক্ত চেইন-অফ-থট রিজনিং ও ধাপভিত্তিক সমস্যা সমাধান",
        accentColorHex = 0xFFFF80AB,
        defaultPoolMode = ProviderPoolMode.BEST
    ),
    OPENROUTER_LLAMA(
        id = "llama_3_3",
        displayName = "Llama 3.3 70B Instruct 🌐",
        providerName = "Groq / Cerebras / SambaNova / OpenRouter",
        speedLabel = "Fast ⚡",
        reasoningLabel = "Very High 🧠",
        description = "বহুভাষিক সাহিত্য রচনা, অনুবাদ ও জ্ঞানভিত্তিক কথোপকথন",
        accentColorHex = 0xFFFFD700,
        defaultPoolMode = ProviderPoolMode.AUTO
    ),
    OPEN_WEIGHTS(
        id = "open_weights",
        displayName = "Open-Weights Engine 🌐",
        providerName = "Local Ollama / OpenRouter Qwen",
        speedLabel = "Fast / Private ⚡",
        reasoningLabel = "Open-Weights 🧠",
        description = "ওপেন-ওয়েটস লোকাল ও ক্লাউড মডেল — নির্ভরযোগ্য, বস্তুনিষ্ঠ ও নিরাপদ প্রযুক্তিগত বিশ্লেষণ",
        accentColorHex = 0xFFFF5722,
        defaultPoolMode = ProviderPoolMode.BEST,
        aliases = setOf("local")
    ),
    SECURITY_RESEARCH(
        id = "security_research",
        displayName = "Security Research Mode 🛡️",
        providerName = "Ayra Defensive Security",
        speedLabel = "High-Throughput ⚡",
        reasoningLabel = "Defensive Security 🛡️",
        description = "ডিফেনসিভ সাইবার সিকিউরিটি, OWASP অডিট, CVE মিটিগেশন ও নিরাপদ সিস্টেম আর্কিটেকচার",
        accentColorHex = 0xFF00E5FF,
        defaultPoolMode = ProviderPoolMode.BEST,
        usesOmniRouteFastPath = true
    ),
    ECC_COGNITIVE(
        id = "ecc",
        displayName = "ECC Enterprise Cognitive 🌐",
        providerName = "Enterprise Cognitive Context",
        speedLabel = "Cognitive Graph ⚡",
        reasoningLabel = "Context-Memory 🧠",
        description = "কনটেক্সট মেমরি গ্রাফ, ইন্টেন্ট ডিসঅ্যাম্বিগিউয়েশন ও ফ্যাক্টুয়াল গ্রাউন্ডিং",
        accentColorHex = 0xFF7C4DFF,
        defaultPoolMode = ProviderPoolMode.BEST,
        usesOmniRouteFastPath = true
    ),
    OPEN_HIGGSFIELD(
        id = "higgsfield",
        displayName = "Open-Higgsfield-AI Studio 🎬",
        providerName = "Cinematic Physics Director",
        speedLabel = "Cinematic Physics 🎥",
        reasoningLabel = "Photometric Optics 🎨",
        description = "সিনেমাটিক ভিডিও জেনারেশন, ক্যামেরা মোশন ডিরেকশন ও অপটিক্যাল প্রম্পট ইঞ্জিন",
        accentColorHex = 0xFFFF4081,
        defaultPoolMode = ProviderPoolMode.BEST,
        usesOmniRouteFastPath = true
    ),
    SUPERPOWERS(
        id = "superpowers",
        displayName = "Superpowers Agent Engine ⚙️",
        providerName = "Structured Engineering Workflow",
        speedLabel = "Multi-Stage Agent 🤖",
        reasoningLabel = "TDD & Code Review 🔬",
        description = "সিস্টেমেটিক ডিবাগিং, টেস্ট ড্রিভেন ডেভেলপমেন্ট ও কোড রিভিউ গেট",
        accentColorHex = 0xFF00E676,
        defaultPoolMode = ProviderPoolMode.AUTO
    );

    fun resolvePoolMode(thinkingMode: String = "balanced"): ProviderPoolMode {
        return if (this == AUTO) {
            when (thinkingMode.lowercase(Locale.ROOT)) {
                "fast" -> ProviderPoolMode.FAST
                "deep_analysis", "reasoning", "research" -> ProviderPoolMode.BEST
                else -> ProviderPoolMode.AUTO
            }
        } else {
            defaultPoolMode
        }
    }

    fun resolvePreferredProviderAndModel(
        externalProviders: ExternalAiProviders
    ): Pair<AiProviderType?, String?> {
        return when (this) {
            GEMINI_FLASH -> AiProviderType.GEMINI to ExternalAiProviders.GEMINI_FLASH_MODEL
            GEMINI_PRO -> AiProviderType.GEMINI to ExternalAiProviders.GEMINI_PRO_MODEL
            DEEPSEEK_HARNESS, OPENROUTER_DEEPSEEK -> {
                listOf(
                    AiProviderType.DEEPSEEK to "deepseek-reasoner",
                    AiProviderType.SAMBANOVA to ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL,
                    AiProviderType.OPENROUTER to "deepseek/deepseek-r1:free"
                ).firstOrNull { externalProviders.isProviderConfigured(it.first) } ?: (null to null)
            }
            OPENROUTER_LLAMA -> {
                listOf(
                    AiProviderType.GROQ to ExternalAiProviders.GROQ_LLAMA_MODEL,
                    AiProviderType.CEREBRAS to ExternalAiProviders.CEREBRAS_LLAMA_MODEL,
                    AiProviderType.SAMBANOVA to ExternalAiProviders.SAMBANOVA_LLAMA_MODEL,
                    AiProviderType.OPENROUTER to "meta-llama/llama-3.3-70b-instruct:free"
                ).firstOrNull { externalProviders.isProviderConfigured(it.first) } ?: (null to null)
            }
            OPEN_WEIGHTS -> {
                listOf(
                    AiProviderType.LOCAL_OLLAMA to OpenModelEngine.OLLAMA_DEFAULT_SERVING,
                    AiProviderType.OPENROUTER to "qwen/qwen-2.5-72b-instruct",
                    AiProviderType.HUGGINGFACE to ExternalAiProviders.HF_QWEN_MODEL,
                    AiProviderType.GROQ to ExternalAiProviders.GROQ_QWEN_MODEL,
                    AiProviderType.CEREBRAS to ExternalAiProviders.CEREBRAS_QWEN_MODEL
                ).firstOrNull { externalProviders.isProviderConfigured(it.first) } ?: (null to null)
            }
            else -> null to null
        }
    }

    /**
     * Builds the optional tier-specific specialist system directive or wraps a base system prompt.
     */
    suspend fun buildSpecialistSystemDirective(
        prompt: String,
        context: Context? = null,
        baseSystemPrompt: String? = null
    ): String? {
        val directive = when (this) {
            DEEPSEEK_HARNESS, OPENROUTER_DEEPSEEK ->
                "[DEEPSEEK-R1 MODE]: Use rigorous step-by-step verification and mathematical/algorithmic precision in the user's language."
            SECURITY_RESEARCH -> buildString {
                append(SecurityResearchEngine.SAFE_DEFENSIVE_PROMPT)
                val grounding = try {
                    SecurityResearchEngine(context).buildSecurityGrounding(prompt)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ""
                }
                if (grounding.isNotBlank()) {
                    append("\n\n").append(grounding)
                }
            }
            ECC_COGNITIVE -> {
                val cognitive = EnterpriseCognitiveEngine(context).extractCognitiveContext(prompt)
                "[ECC COGNITIVE ENGINE]: Domain=${cognitive.domain}, Key Entities=${cognitive.detectedEntities.joinToString(", ").ifBlank { "General" }}. Disambiguate entities and provide structured, factual answers in the user's language."
            }
            OPEN_WEIGHTS -> OpenModelEngine.SAFE_SYSTEM_PROMPT
            OPEN_HIGGSFIELD ->
                "You are Open-Higgsfield Cinematography & Motion Physics Director. Design camera trajectories, optical lenses, lighting ratios, and shot lists precisely in the user's language."
            SUPERPOWERS -> {
                val isBn = prompt.any { it in '\u0980'..'\u09FF' }
                val sp = AyraSuperpowersEngine.detectSuperpower(prompt)
                    ?: AyraSuperpowersEngine.SuperpowerType.SYSTEMATIC_DEBUGGING
                AyraSuperpowersEngine.buildSuperpowerSystemPrompt(sp, isBn, context)
            }
            else -> null
        } ?: return null

        return if (!baseSystemPrompt.isNullOrBlank()) {
            "$baseSystemPrompt\n\n$directive"
        } else {
            directive
        }
    }

    /**
     * Automatically inspects the user's question in Adaptive/Ensemble modes and activates
     * relevant specialist engines (ECC Cognitive Graph, Defensive Security Grounding,
     * DeepSeek Step-by-Step Verification) with zero manual tier switching required.
     */
    suspend fun buildQuestionAwareAutoDirectives(
        prompt: String,
        domainName: String,
        context: Context? = null
    ): String? {
        val clean = prompt.trim()
        if (clean.length < 10) return null
        val lower = clean.lowercase(Locale.ROOT)
        val sections = mutableListOf<String>()

        if (clean.length >= 18) {
            val cognitive = runCatching { EnterpriseCognitiveEngine(context).extractCognitiveContext(clean) }.getOrNull()
            if (cognitive != null) {
                val entities = cognitive.detectedEntities.joinToString(", ").ifBlank { "General" }
                sections.add("[ECC COGNITIVE ENGINE]: Domain=${cognitive.domain}, Key Entities=$entities. Disambiguate entities and answer thoroughly in the user's language.")
            }
        }

        val isSecurityQuery = domainName == "CYBER_DEFENSE_AGENT" ||
            Regex("\\b(cve-\\d+|owasp|vulnerability|pentest|firewall|encryption|tor|onion|xss|sqli|zero[- ]trust|malware)\\b")
                .containsMatchIn(lower)
        if (isSecurityQuery) {
            val grounding = try {
                SecurityResearchEngine(context).buildSecurityGrounding(clean)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ModelTier", "Security auto-grounding skipped: ${e.message}")
                ""
            }
            sections.add(buildString {
                append(SecurityResearchEngine.SAFE_DEFENSIVE_PROMPT)
                if (grounding.isNotBlank()) append("\n").append(grounding)
            })
        }

        val isMathOrProof = domainName == "MATHEMATICS_AGENT" ||
            Regex("\\b(prove|theorem|integral|derivative|equation|complexity|algorithm|সমীকরণ|প্রমাণ)\\b")
                .containsMatchIn(lower)
        if (isMathOrProof) {
            sections.add("[DEEPSEEK-R1 AUTO VERIFICATION]: Verify every intermediate calculation step and edge case before stating the final conclusion.")
        }

        return sections.joinToString("\n\n").ifBlank { null }
    }

    companion object {
        val selectableIds: List<String> by lazy { entries.filter { it.isSelectableTier }.map { it.id } }

        val projectDefaultIds: List<String> = listOf(
            AUTO.id,
            GEMINI_PRO.id,
            OMNI_ROUTE.id,
            DEEPSEEK_HARNESS.id,
            OPEN_WEIGHTS.id
        )

        val assistantDefaultIds: List<String> = listOf(
            AUTO.id,
            OMNI_ROUTE.id,
            GEMINI_PRO.id,
            GEMINI_FLASH.id,
            DEEPSEEK_HARNESS.id,
            OPENROUTER_LLAMA.id,
            OPEN_WEIGHTS.id,
            SECURITY_RESEARCH.id
        )

        fun fromId(raw: String?): ModelTier {
            val clean = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (clean.isEmpty()) return AUTO
            entries.firstOrNull { it.id.equals(clean, ignoreCase = true) }?.let { return it }
            entries.firstOrNull { tier -> tier.aliases.any { it.equals(clean, ignoreCase = true) } }?.let { return it }
            return AUTO
        }

        fun canonicalizeId(raw: String?): String = fromId(raw).id
    }
}
