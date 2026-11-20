package com.ayra.ai.app.ai.providers

import android.content.Context
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.data.AyraPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class ModelCapability {
    TEXT,
    VISION,
    TOOLS,
    IMAGE_GEN,
    AUDIO,
    CODE,
    REASONING
}

enum class ModelCostTier(val label: String) {
    FREE_PLATFORM("Free / Firebase AI Logic"),
    FREE_TIER("Free Developer Tier"),
    LOW("Low Cost"),
    MEDIUM("Standard Cost"),
    HIGH("Frontier / Paid"),
    LOCAL("Local / Zero Cost")
}

enum class ModelVerificationStatus(val label: String) {
    VERIFIED_PLATFORM("Verified Platform"),
    VERIFIED_LIVE("Verified Live (/models)"),
    CONFIGURED("Configured Default"),
    UNVERIFIED("Unverified (Opt-In Only)"),
    UNAVAILABLE("Unavailable (Cooldown)"),
    RETIRED("Retired"),
    RATE_LIMITED("Rate Limited (Cooldown)")
}

data class ModelCatalogEntry(
    val id: String,
    val provider: AiProviderType,
    val displayName: String,
    val capabilities: Set<ModelCapability>,
    val contextTokens: Int,
    val costTier: ModelCostTier,
    val verificationStatus: ModelVerificationStatus,
    val isDefaultCandidate: Boolean = true,
    val fallbackModelId: String? = null,
    val notes: String = "",
    val verifiedAtMs: Long? = null,
    val verifiedByProvider: String? = null,
    val verificationMethod: String = "static_baseline",
    val lastSuccessMs: Long? = null,
    val expiresAtMs: Long? = null,
    val failureReason: String? = null,
    val pricingVerified: Boolean = false
)

/**
 * Single Source of Truth for AI model metadata, verification status, runtime discovery,
 * remote config overrides, and 404/invalid-model fallback chains.
 */
object ModelCatalog {

    private data class HealthBlock(
        val httpStatus: Int,
        val reason: String,
        val blockedUntilMs: Long,
        val statusOverride: ModelVerificationStatus
    )

    private val baselineCatalog: List<ModelCatalogEntry> = listOf(
        // --- Firebase AI Logic Verified Platform Models ---
        ModelCatalogEntry(
            id = FirebaseAiEngine.MODEL_FLASH,
            provider = AiProviderType.GEMINI,
            displayName = "Gemini 3.8 Flash",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.AUDIO, ModelCapability.CODE),
            contextTokens = 1_000_000,
            costTier = ModelCostTier.FREE_PLATFORM,
            verificationStatus = ModelVerificationStatus.VERIFIED_PLATFORM,
            fallbackModelId = FirebaseAiEngine.MODEL_FAST_LITE,
            notes = "Firebase AI Logic default multimodal engine"
        ),
        ModelCatalogEntry(
            id = FirebaseAiEngine.MODEL_FAST_LITE,
            provider = AiProviderType.GEMINI,
            displayName = "Gemini 3.5 Flash-Lite",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS),
            contextTokens = 1_000_000,
            costTier = ModelCostTier.FREE_PLATFORM,
            verificationStatus = ModelVerificationStatus.VERIFIED_PLATFORM,
            fallbackModelId = FirebaseAiEngine.MODEL_FLASH
        ),
        ModelCatalogEntry(
            id = FirebaseAiEngine.MODEL_PRO_REASONING,
            provider = AiProviderType.GEMINI,
            displayName = "Gemini 4 Argon",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 2_000_000,
            costTier = ModelCostTier.MEDIUM,
            verificationStatus = ModelVerificationStatus.VERIFIED_PLATFORM,
            fallbackModelId = FirebaseAiEngine.MODEL_FLASH
        ),
        ModelCatalogEntry(
            id = FirebaseAiEngine.MODEL_IMAGE_NANO_BANANA,
            provider = AiProviderType.GEMINI,
            displayName = "Gemini Nano Banana 2.1",
            capabilities = setOf(ModelCapability.IMAGE_GEN, ModelCapability.VISION),
            contextTokens = 128_000,
            costTier = ModelCostTier.MEDIUM,
            verificationStatus = ModelVerificationStatus.VERIFIED_PLATFORM,
            fallbackModelId = "gemini-3-pro-image"
        ),
        ModelCatalogEntry(
            id = "gemini-3-pro-image",
            provider = AiProviderType.GEMINI,
            displayName = "Gemini 3 Pro Image",
            capabilities = setOf(ModelCapability.IMAGE_GEN, ModelCapability.VISION),
            contextTokens = 128_000,
            costTier = ModelCostTier.HIGH,
            verificationStatus = ModelVerificationStatus.VERIFIED_PLATFORM,
            fallbackModelId = "gemini-3.1-flash-lite-image"
        ),
        ModelCatalogEntry(
            id = "gemini-3.1-flash-lite-image",
            provider = AiProviderType.GEMINI,
            displayName = "Gemini 3.1 Flash-Lite Image",
            capabilities = setOf(ModelCapability.IMAGE_GEN, ModelCapability.VISION),
            contextTokens = 128_000,
            costTier = ModelCostTier.LOW,
            verificationStatus = ModelVerificationStatus.VERIFIED_PLATFORM
        ),
        ModelCatalogEntry(
            id = "gemini-3.8-flash-tts",
            provider = AiProviderType.GEMINI,
            displayName = "Gemini 3.8 Flash TTS",
            capabilities = setOf(ModelCapability.AUDIO),
            contextTokens = 32_000,
            costTier = ModelCostTier.LOW,
            verificationStatus = ModelVerificationStatus.VERIFIED_PLATFORM
        ),

        // --- Free-Tier & Cloud Open-Weights Configured Models ---
        ModelCatalogEntry(
            id = ExternalAiProviders.GROQ_QWEN_MODEL,
            provider = AiProviderType.GROQ,
            displayName = "Groq Qwen 3.8 27B",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 131_072,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.GROQ_GPT_OSS_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.GROQ_GPT_OSS_MODEL,
            provider = AiProviderType.GROQ,
            displayName = "Groq GPT-OSS 120B",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 131_072,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.GROQ_LLAMA_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.GROQ_LLAMA_MODEL,
            provider = AiProviderType.GROQ,
            displayName = "Groq Llama 3.1 8B Instant",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.CODE),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.GROQ_QWEN_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.GROQ_WHISPER_MODEL,
            provider = AiProviderType.GROQ,
            displayName = "Groq Whisper Large v3 Turbo",
            capabilities = setOf(ModelCapability.AUDIO),
            contextTokens = 25_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.CEREBRAS_LLAMA_MODEL,
            provider = AiProviderType.CEREBRAS,
            displayName = "Cerebras GPT-OSS 120B",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.CEREBRAS_QWEN_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.CEREBRAS_QWEN_MODEL,
            provider = AiProviderType.CEREBRAS,
            displayName = "Cerebras Qwen 3.8 27B",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.CEREBRAS_LLAMA_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL,
            provider = AiProviderType.SAMBANOVA,
            displayName = "SambaNova DeepSeek V3.1 Terminus",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.SAMBANOVA_LLAMA_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.SAMBANOVA_LLAMA_MODEL,
            provider = AiProviderType.SAMBANOVA,
            displayName = "SambaNova Llama 3.3 70B",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.CODE),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.SAMBANOVA_QWEN_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.SAMBANOVA_QWEN_MODEL,
            provider = AiProviderType.SAMBANOVA,
            displayName = "SambaNova Qwen3 32B",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.REASONING),
            contextTokens = 64_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.HF_QWEN_MODEL,
            provider = AiProviderType.HUGGINGFACE,
            displayName = "HF Qwen 2.5 72B Instruct",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.GITHUB_RETIRED_MODEL,
            provider = AiProviderType.GITHUB_MODELS,
            displayName = "GitHub Models (Azure Inference Retired)",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.CODE),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.RETIRED,
            isDefaultCandidate = false,
            notes = "models.inference.ai.azure.com shut down 2026-07-30"
        ),

        // --- Commercial User-Key Providers ---
        ModelCatalogEntry(
            id = ExternalAiProviders.OPENAI_DEFAULT_MODEL,
            provider = AiProviderType.OPENAI,
            displayName = "OpenAI GPT-6.2 Sol",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 256_000,
            costTier = ModelCostTier.HIGH,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.OPENAI_MINI_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.OPENAI_MINI_MODEL,
            provider = AiProviderType.OPENAI,
            displayName = "OpenAI GPT-6 Luna",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.CODE),
            contextTokens = 128_000,
            costTier = ModelCostTier.LOW,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.OPENAI_DEFAULT_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.OPENAI_REASONING_MODEL,
            provider = AiProviderType.OPENAI,
            displayName = "OpenAI GPT-6.2 Astra",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 256_000,
            costTier = ModelCostTier.HIGH,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.OPENAI_DEFAULT_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.ANTHROPIC_SONNET_MODEL,
            provider = AiProviderType.ANTHROPIC,
            displayName = "Claude Sonnet 5.5",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 200_000,
            costTier = ModelCostTier.HIGH,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.ANTHROPIC_HAIKU_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.ANTHROPIC_OPUS_MODEL,
            provider = AiProviderType.ANTHROPIC,
            displayName = "Claude Opus 5.5",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 200_000,
            costTier = ModelCostTier.HIGH,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.ANTHROPIC_SONNET_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.ANTHROPIC_HAIKU_MODEL,
            provider = AiProviderType.ANTHROPIC,
            displayName = "Claude Haiku 5.5",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.CODE),
            contextTokens = 200_000,
            costTier = ModelCostTier.MEDIUM,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.ANTHROPIC_SONNET_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.DEEPSEEK_CHAT_MODEL,
            provider = AiProviderType.DEEPSEEK,
            displayName = "DeepSeek Flash",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.CODE),
            contextTokens = 128_000,
            costTier = ModelCostTier.LOW,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.DEEPSEEK_REASONER_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.DEEPSEEK_REASONER_MODEL,
            provider = AiProviderType.DEEPSEEK,
            displayName = "DeepSeek V4 Pro",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 128_000,
            costTier = ModelCostTier.LOW,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.DEEPSEEK_CHAT_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.XAI_DEFAULT_MODEL,
            provider = AiProviderType.XAI,
            displayName = "xAI Grok 4.7",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.REASONING),
            contextTokens = 256_000,
            costTier = ModelCostTier.HIGH,
            verificationStatus = ModelVerificationStatus.CONFIGURED
        ),
        ModelCatalogEntry(
            id = "mistral-large-latest",
            provider = AiProviderType.MISTRAL,
            displayName = "Mistral Large",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.CODE),
            contextTokens = 128_000,
            costTier = ModelCostTier.MEDIUM,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = "codestral-latest"
        ),
        ModelCatalogEntry(
            id = "codestral-latest",
            provider = AiProviderType.MISTRAL,
            displayName = "Codestral",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.CODE),
            contextTokens = 256_000,
            costTier = ModelCostTier.LOW,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = "mistral-large-latest"
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.PERPLEXITY_REASONING_MODEL,
            provider = AiProviderType.PERPLEXITY,
            displayName = "Perplexity Sonar Reasoning Pro",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.REASONING),
            contextTokens = 128_000,
            costTier = ModelCostTier.MEDIUM,
            verificationStatus = ModelVerificationStatus.CONFIGURED
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.OPENROUTER_DEFAULT_MODEL,
            provider = AiProviderType.OPENROUTER,
            displayName = "OpenRouter Free Router",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.CODE),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.OPENROUTER_QWEN_FREE_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.OPENROUTER_QWEN_FREE_MODEL,
            provider = AiProviderType.OPENROUTER,
            displayName = "Qwen 3.8 27B Free (OpenRouter)",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.OPENROUTER_AUTO_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.OPENROUTER_CODER_MODEL,
            provider = AiProviderType.OPENROUTER,
            displayName = "Qwen3 Coder Free (OpenRouter)",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.CODE),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.OPENROUTER_DEFAULT_MODEL
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.OPENROUTER_AUTO_MODEL,
            provider = AiProviderType.OPENROUTER,
            displayName = "OpenRouter Auto",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.CODE),
            contextTokens = 128_000,
            costTier = ModelCostTier.LOW,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = ExternalAiProviders.OPENROUTER_DEFAULT_MODEL
        ),

        // --- Local / LAN Self-Hosted ---
        ModelCatalogEntry(
            id = "llama3.3",
            provider = AiProviderType.LOCAL_OLLAMA,
            displayName = "Local Ollama Llama 3.3",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.CODE),
            contextTokens = 32_000,
            costTier = ModelCostTier.LOCAL,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = "qwen2.5:14b"
        ),
        ModelCatalogEntry(
            id = "qwen2.5:14b",
            provider = AiProviderType.LOCAL_OLLAMA,
            displayName = "Local Ollama Qwen 2.5 14B",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 32_000,
            costTier = ModelCostTier.LOCAL,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            fallbackModelId = "llama3.3"
        ),

        // --- Unverified / Preview IDs (Explicitly excluded from default routing unless discovered live) ---
        ModelCatalogEntry(
            id = ExternalAiProviders.NVIDIA_MINIMAX_MODEL,
            provider = AiProviderType.NVIDIA_NIM,
            displayName = "MiniMax M3.1 Flash (NIM)",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.CODE),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.UNVERIFIED,
            isDefaultCandidate = false,
            fallbackModelId = "meta/llama-3.3-70b-instruct",
            notes = "Requires live /v1/models verification on NIM endpoint"
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.NVIDIA_GLM_MODEL,
            provider = AiProviderType.NVIDIA_NIM,
            displayName = "Z-AI GLM 5.3 Flash (NIM)",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.CODE, ModelCapability.REASONING),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.UNVERIFIED,
            isDefaultCandidate = false,
            fallbackModelId = "meta/llama-3.3-70b-instruct",
            notes = "Requires live /v1/models verification on NIM endpoint"
        ),
        ModelCatalogEntry(
            id = ExternalAiProviders.NVIDIA_MUSE_MODEL,
            provider = AiProviderType.NVIDIA_NIM,
            displayName = "Meta Muse Glimmer 30B (NIM)",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.REASONING),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.UNVERIFIED,
            isDefaultCandidate = false,
            fallbackModelId = "meta/llama-3.3-70b-instruct",
            notes = "Requires live /v1/models verification on NIM endpoint"
        ),
        ModelCatalogEntry(
            id = "meta/llama-3.3-70b-instruct",
            provider = AiProviderType.NVIDIA_NIM,
            displayName = "NVIDIA NIM Llama 3.3 70B",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.CODE),
            contextTokens = 128_000,
            costTier = ModelCostTier.FREE_TIER,
            verificationStatus = ModelVerificationStatus.CONFIGURED,
            isDefaultCandidate = true
        )
    )

    private val discoveredModels = ConcurrentHashMap<String, ModelCatalogEntry>()
    private val remoteOverrideEntries = ConcurrentHashMap<String, ModelCatalogEntry>()
    private val blockedModels = ConcurrentHashMap<String, HealthBlock>()
    private val retiredModels = ConcurrentHashMap<String, String>()
    private val lastSuccessTimestamps = ConcurrentHashMap<String, Long>()

    private const val MODEL_COOLDOWN_MS = 10 * 60 * 1000L
    private const val LIVE_DISCOVERY_TTL_MS = 24 * 60 * 60 * 1000L

    private fun key(provider: AiProviderType, modelId: String): String =
        "${provider.id}::${modelId.trim().lowercase()}"

    private fun applyRuntimeHealth(entry: ModelCatalogEntry): ModelCatalogEntry {
        val k = key(entry.provider, entry.id)
        val lastSuccess = lastSuccessTimestamps[k] ?: entry.lastSuccessMs
        val retiredReason = retiredModels[k]
        if (retiredReason != null) {
            return entry.copy(
                verificationStatus = ModelVerificationStatus.RETIRED,
                isDefaultCandidate = false,
                lastSuccessMs = lastSuccess,
                failureReason = retiredReason
            )
        }
        val block = blockedModels[k]
        if (block != null) {
            if (System.currentTimeMillis() > block.blockedUntilMs) {
                blockedModels.remove(k)
            } else {
                return entry.copy(
                    verificationStatus = block.statusOverride,
                    isDefaultCandidate = false,
                    lastSuccessMs = lastSuccess,
                    expiresAtMs = block.blockedUntilMs,
                    failureReason = block.reason
                )
            }
        }
        return if (lastSuccess != null && lastSuccess != entry.lastSuccessMs) {
            entry.copy(lastSuccessMs = lastSuccess)
        } else {
            entry
        }
    }

    fun allEntries(): List<ModelCatalogEntry> {
        val merged = LinkedHashMap<String, ModelCatalogEntry>()
        baselineCatalog.forEach { merged[key(it.provider, it.id)] = applyRuntimeHealth(it) }
        remoteOverrideEntries.forEach { (k, v) -> merged[k] = applyRuntimeHealth(v) }
        discoveredModels.forEach { (k, v) -> merged[k] = applyRuntimeHealth(v) }
        return merged.values.toList()
    }

    fun findEntry(provider: AiProviderType, modelId: String): ModelCatalogEntry? {
        val k = key(provider, modelId)
        val raw = discoveredModels[k]
            ?: remoteOverrideEntries[k]
            ?: baselineCatalog.firstOrNull { key(it.provider, it.id) == k }
            ?: return null
        return applyRuntimeHealth(raw)
    }

    fun isVerifiedOrConfigured(provider: AiProviderType, modelId: String): Boolean {
        val entry = findEntry(provider, modelId) ?: return false
        return entry.verificationStatus in setOf(
            ModelVerificationStatus.VERIFIED_PLATFORM,
            ModelVerificationStatus.VERIFIED_LIVE,
            ModelVerificationStatus.CONFIGURED
        ) && entry.isDefaultCandidate
    }

    fun registerLiveDiscoveredModels(provider: AiProviderType, models: List<AiModelInfo>) {
        val now = System.currentTimeMillis()
        models.forEach { info ->
            val caps = buildSet {
                add(ModelCapability.TEXT)
                if (info.supportsVision) add(ModelCapability.VISION)
                if (info.isReasoning) add(ModelCapability.REASONING)
                if (info.id.contains("code", ignoreCase = true) || info.id.contains("sonnet", ignoreCase = true)) {
                    add(ModelCapability.CODE)
                }
            }
            val entry = ModelCatalogEntry(
                id = info.id,
                provider = provider,
                displayName = info.displayName,
                capabilities = caps,
                contextTokens = 128_000,
                costTier = if (provider == AiProviderType.LOCAL_OLLAMA) ModelCostTier.LOCAL else ModelCostTier.MEDIUM,
                verificationStatus = ModelVerificationStatus.VERIFIED_LIVE,
                isDefaultCandidate = true,
                notes = "Discovered via live provider /models API",
                verifiedAtMs = now,
                verifiedByProvider = provider.id,
                verificationMethod = "live_models_api",
                lastSuccessMs = now,
                expiresAtMs = now + LIVE_DISCOVERY_TTL_MS,
                pricingVerified = false
            )
            val k = key(provider, info.id)
            discoveredModels[k] = entry
            blockedModels.remove(k)
            retiredModels.remove(k)
        }
    }

    fun recordModelSuccess(provider: AiProviderType, modelId: String) {
        val k = key(provider, modelId)
        val now = System.currentTimeMillis()
        lastSuccessTimestamps[k] = now
        blockedModels.remove(k)
        retiredModels.remove(k)
    }

    fun markModelRetired(provider: AiProviderType, modelId: String, reason: String) {
        val k = key(provider, modelId)
        retiredModels[k] = reason.take(180)
        blockedModels.remove(k)
    }

    fun markModelUnavailable(provider: AiProviderType, modelId: String, httpStatus: Int, reason: String) {
        val k = key(provider, modelId)
        val statusOverride = when {
            httpStatus == 429 -> ModelVerificationStatus.RATE_LIMITED
            httpStatus == 410 -> ModelVerificationStatus.RETIRED
            else -> ModelVerificationStatus.UNAVAILABLE
        }
        blockedModels[k] = HealthBlock(
            httpStatus = httpStatus,
            reason = reason.take(180),
            blockedUntilMs = System.currentTimeMillis() + MODEL_COOLDOWN_MS,
            statusOverride = statusOverride
        )
    }

    fun isModelTemporarilyUnavailable(provider: AiProviderType, modelId: String): Boolean {
        val k = key(provider, modelId)
        if (retiredModels.containsKey(k)) return true
        val block = blockedModels[k] ?: return false
        if (System.currentTimeMillis() > block.blockedUntilMs) {
            blockedModels.remove(k)
            return false
        }
        return true
    }

    fun defaultModelFor(
        provider: AiProviderType,
        requiredCapability: ModelCapability? = null
    ): ModelCatalogEntry? {
        val candidates = allEntries().filter { entry ->
            entry.provider == provider &&
                entry.isDefaultCandidate &&
                entry.verificationStatus in setOf(
                    ModelVerificationStatus.VERIFIED_PLATFORM,
                    ModelVerificationStatus.VERIFIED_LIVE,
                    ModelVerificationStatus.CONFIGURED
                ) &&
                !isModelTemporarilyUnavailable(provider, entry.id) &&
                (requiredCapability == null || entry.capabilities.contains(requiredCapability))
        }
        return candidates.firstOrNull { it.verificationStatus == ModelVerificationStatus.VERIFIED_PLATFORM }
            ?: candidates.firstOrNull { it.verificationStatus == ModelVerificationStatus.VERIFIED_LIVE }
            ?: candidates.firstOrNull()
    }

    /**
     * Resolves an alternate verified/configured model ID when a requested model returns 404 or fails validation.
     */
    fun nextFallbackModel(
        provider: AiProviderType,
        failedModelId: String,
        requiredCapability: ModelCapability? = null
    ): String? {
        val current = findEntry(provider, failedModelId)
        val explicitFallback = current?.fallbackModelId
        if (!explicitFallback.isNullOrBlank() &&
            !explicitFallback.equals(failedModelId, ignoreCase = true) &&
            !isModelTemporarilyUnavailable(provider, explicitFallback)
        ) {
            return explicitFallback
        }
        return allEntries()
            .filter { entry ->
                entry.provider == provider &&
                    !entry.id.equals(failedModelId, ignoreCase = true) &&
                    entry.isDefaultCandidate &&
                    entry.verificationStatus in setOf(
                        ModelVerificationStatus.VERIFIED_PLATFORM,
                        ModelVerificationStatus.VERIFIED_LIVE,
                        ModelVerificationStatus.CONFIGURED
                    ) &&
                    !isModelTemporarilyUnavailable(provider, entry.id) &&
                    (requiredCapability == null || entry.capabilities.contains(requiredCapability))
            }
            .sortedBy {
                when (it.verificationStatus) {
                    ModelVerificationStatus.VERIFIED_PLATFORM -> 0
                    ModelVerificationStatus.VERIFIED_LIVE -> 1
                    ModelVerificationStatus.CONFIGURED -> 2
                    ModelVerificationStatus.UNVERIFIED -> 3
                    ModelVerificationStatus.RATE_LIMITED -> 4
                    ModelVerificationStatus.UNAVAILABLE -> 5
                    ModelVerificationStatus.RETIRED -> 6
                }
            }
            .firstOrNull()
            ?.id
    }

    /**
     * Loads and applies remote-config style catalog overrides persisted safely in encrypted prefs.
     */
    fun loadCachedRemoteOverrides(context: Context?) {
        if (context == null) return
        val raw = AyraPreferences.getRemoteModelCatalogJson(context)
        if (raw.isNotBlank()) {
            runCatching { applyRemoteCatalogOverridesJson(raw, context) }
        }
    }

    fun applyRemoteCatalogOverridesJson(jsonText: String, context: Context? = null): Int {
        if (jsonText.isBlank()) return 0
        val root = JSONObject(jsonText)
        val arr = root.optJSONArray("models") ?: JSONArray()
        val now = System.currentTimeMillis()
        var applied = 0
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val providerId = obj.optString("provider").trim()
            val modelId = obj.optString("id").trim()
            val provider = AiProviderType.entries.firstOrNull { it.id.equals(providerId, ignoreCase = true) } ?: continue
            if (modelId.isBlank()) continue
            val displayName = obj.optString("displayName", modelId).ifBlank { modelId }
            val fallback = obj.optString("fallbackModelId", "").ifBlank { null }
            val contextTokens = obj.optInt("contextTokens", 128_000).coerceAtLeast(4096)
            val isDefault = obj.optBoolean("isDefault", true)
            val pricingVerified = obj.optBoolean("pricingVerified", false)
            val entry = ModelCatalogEntry(
                id = modelId,
                provider = provider,
                displayName = displayName,
                capabilities = setOf(ModelCapability.TEXT, ModelCapability.CODE),
                contextTokens = contextTokens,
                costTier = ModelCostTier.LOW,
                verificationStatus = ModelVerificationStatus.CONFIGURED,
                isDefaultCandidate = isDefault,
                fallbackModelId = fallback,
                notes = "Remote catalog override",
                verifiedAtMs = now,
                verifiedByProvider = provider.id,
                verificationMethod = "remote_override",
                pricingVerified = pricingVerified
            )
            remoteOverrideEntries[key(provider, modelId)] = entry
            applied++
        }
        if (applied > 0 && context != null) {
            AyraPreferences.setRemoteModelCatalogJson(context, jsonText)
        }
        return applied
    }

    fun clearRuntimeHealth() {
        blockedModels.clear()
        retiredModels.clear()
        lastSuccessTimestamps.clear()
        discoveredModels.clear()
        remoteOverrideEntries.clear()
    }
}
