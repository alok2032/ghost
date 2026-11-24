package com.ayra.ai.app.ai.providers

import android.content.Context
import androidx.compose.runtime.Immutable
import com.ayra.ai.app.data.AyraPreferences

enum class AiProviderType(val id: String, val displayName: String, val defaultBaseUrl: String) {
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/"),
    NVIDIA_NIM("nvidia_nim", "NVIDIA NIM AI", "https://integrate.api.nvidia.com/v1/"),
    GROQ("groq", "Groq Cloud (Free Tier)", "https://api.groq.com/openai/v1/"),
    CEREBRAS("cerebras", "Cerebras Cloud (Free Tier)", "https://api.cerebras.ai/v1/"),
    SAMBANOVA("sambanova", "SambaNova Cloud (Free Tier)", "https://api.sambanova.ai/v1/"),
    HUGGINGFACE("huggingface", "Hugging Face Router", "https://router.huggingface.co/v1/"),
    GITHUB_MODELS("github_models", "GitHub Models (Retired)", ""),
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1/"),
    ANTHROPIC("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1/"),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com/"),
    XAI("xai", "xAI Grok", "https://api.x.ai/v1/"),
    MISTRAL("mistral", "Mistral AI", "https://api.mistral.ai/v1/"),
    PERPLEXITY("perplexity", "Perplexity AI", "https://api.perplexity.ai/"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/"),
    LOCAL_OLLAMA("local_ollama", "Local Ollama / LAN", "http://10.0.2.2:11434/v1/"),
    POLLINATIONS_FREE("pollinations_free", "Global Free AI Pool", "https://gen.pollinations.ai/"),
    OMNI_ROUTE("omniroute", "OmniRoute Ensemble ⚡👑", "")
}

@Immutable
data class AiModelInfo(
    val id: String,
    val displayName: String,
    val provider: AiProviderType,
    val description: String,
    val contextWindow: String,
    val supportsVision: Boolean = true,
    val isReasoning: Boolean = false,
    val isDefault: Boolean = false
)

data class AiModelChannel(
    val id: String,
    val provider: AiProviderType,
    val modelId: String,
    val displayName: String,
    val specialties: Set<String>,
    val supportsVision: Boolean = false,
    val isReasoning: Boolean = false,
    val maxTokens: Int = 4096,
    val keyResolver: (Context?) -> String
)

enum class AiRoutingMode(val id: String, val title: String, val icon: String, val description: String) {
    AUTO("auto", "Auto Adaptive", "⚡", "Routes by question domain & combines active AIs in parallel"),
    BEST_QUALITY("quality", "Best Quality", "👑", "Multi-AI consensus synthesis across frontier reasoning models"),
    FAST("fast", "Ultra Fast", "🚀", "Lowest-latency streaming specialist model for the query"),
    REASONING("reasoning", "Deep Reasoning", "🧠", "Deliberate step-by-step math, science & logic verification"),
    CODING("coding", "Software & Coding", "💻", "Full-stack coding ensemble across verified Frontier & Open-Weights models"),
    PRIVACY_LOCAL("local", "Private / Local LAN", "🏠", "Private local LLM endpoint running on your LAN/Ollama"),
    COST_EFFICIENT("cost", "Cost Efficient / Free", "🌱", "Fast zero/low-cost cloud models and Global Free AI Pool"),
    OPEN_WEIGHTS("open_weights", "Open-Weights Engine", "🌐", "Direct objective answers via local Ollama or OpenRouter / Groq open-weights models");

    companion object {
        fun fromId(raw: String): AiRoutingMode {
            val cleanRaw = raw.trim().lowercase(java.util.Locale.ROOT)
            if (cleanRaw.isEmpty()) return AUTO
            entries.firstOrNull { it.id.equals(cleanRaw, ignoreCase = true) }?.let { return it }
            return when (cleanRaw) {
                "balanced" -> AUTO
                "best_quality", "gemini_pro" -> BEST_QUALITY
                "gemini_flash" -> FAST
                "privacy", "privacy_local" -> PRIVACY_LOCAL
                "deepseek_harness", "deepseek_r1" -> REASONING
                else -> {
                    val cleanTier = ModelTier.canonicalizeId(cleanRaw)
                    entries.firstOrNull { it.id.equals(cleanTier, ignoreCase = true) } ?: AUTO
                }
            }
        }
    }
}

data class ProviderResponse(
    val text: String,
    val modelUsed: String,
    val provider: AiProviderType,
    val finishReason: String = "stop",
    val latencyMs: Long = 0
)

sealed class ProviderError(val displayMessage: String) : Exception(displayMessage) {
    class NoKeyConfigured(provider: AiProviderType) :
        ProviderError("No API key configured for ${provider.displayName}. Tap Settings -> Keys to configure.")
    class RateLimited(message: String) :
        ProviderError("Provider rate limit reached (HTTP 429). Please retry shortly or switch provider.")
    class Unauthorized(provider: AiProviderType) :
        ProviderError("Invalid or unauthorized API key for ${provider.displayName}. Please check Settings -> Keys.")
    class ModelNotFound(
        val provider: AiProviderType,
        val modelId: String,
        details: String = "",
        val httpStatus: Int = 404
    ) : ProviderError("Model '$modelId' unavailable on ${provider.displayName} (HTTP $httpStatus). ${details.take(120)}")
    class ServerError(code: Int, message: String) :
        ProviderError("Remote server error (HTTP $code): $message")
    class Timeout(message: String = "Request timed out while waiting for model inference.") :
        ProviderError(message)
    class NetworkFailure(message: String) :
        ProviderError("Network connection failed: $message")
    class ContentBlocked(val reason: String) :
        ProviderError("Content blocked by safety policy: $reason")
}

data class ChatTurn(
    val role: String,
    val content: String,
    val imageBase64: String? = null
)

internal object DefaultAiChannelRegistry {
    fun buildAllDefinitions(
        domainName: String,
        channelKeyOverrides: Map<String, String>
    ): List<AiModelChannel> = listOf(
        AiModelChannel(
            id = "gemini_3_8_flash",
            provider = AiProviderType.GEMINI,
            modelId = ExternalAiProviders.GEMINI_FLASH_MODEL,
            displayName = "Gemini 3.8 Flash",
            specialties = setOf("general", "fast", "vision", "multilingual", "education", "research", "document", "creative"),
            supportsVision = true,
            isReasoning = false,
            maxTokens = 8192,
            keyResolver = { c -> AyraPreferences.getEffectiveGeminiKey(c) }
        ),
        AiModelChannel(
            id = "nvidia_llama_3_3_70b",
            provider = AiProviderType.NVIDIA_NIM,
            modelId = ExternalAiProviders.NVIDIA_LLAMA_MODEL,
            displayName = "Llama 3.3 70B Instruct (NIM)",
            specialties = setOf("general", "coding", "multilingual", "education", "research"),
            supportsVision = false,
            isReasoning = true,
            maxTokens = 4096,
            keyResolver = { c -> c?.let { AyraPreferences.getNvidiaKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "nvidia_minimax_m3_1",
            provider = AiProviderType.NVIDIA_NIM,
            modelId = ExternalAiProviders.NVIDIA_MINIMAX_MODEL,
            displayName = "MiniMax M3.1 Flash (NIM)",
            specialties = setOf("general", "fast", "vision", "coding", "multilingual", "education", "cyber"),
            supportsVision = true,
            isReasoning = true,
            maxTokens = 8192,
            keyResolver = { c -> c?.let { AyraPreferences.getNvidiaMinimaxKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "nvidia_muse_30b",
            provider = AiProviderType.NVIDIA_NIM,
            modelId = ExternalAiProviders.NVIDIA_MUSE_MODEL,
            displayName = "Meta Muse 30B (NIM)",
            specialties = setOf("general", "creative", "multilingual", "reasoning", "education"),
            supportsVision = false,
            isReasoning = true,
            maxTokens = 4096,
            keyResolver = { c -> c?.let { AyraPreferences.getNvidiaMuseKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "nvidia_glm_5_3",
            provider = AiProviderType.NVIDIA_NIM,
            modelId = ExternalAiProviders.NVIDIA_GLM_MODEL,
            displayName = "GLM 5.3 Flash (NIM)",
            specialties = setOf("coding", "math", "reasoning", "data", "cyber", "research"),
            supportsVision = false,
            isReasoning = true,
            maxTokens = 4096,
            keyResolver = { c -> c?.let { AyraPreferences.getNvidiaGlmKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "nvidia_gemma_4_31b",
            provider = AiProviderType.NVIDIA_NIM,
            modelId = ExternalAiProviders.NVIDIA_GEMMA_MODEL,
            displayName = "Gemma 4 31B (NIM)",
            specialties = setOf("math", "coding", "reasoning", "research", "education", "multilingual"),
            supportsVision = true,
            isReasoning = true,
            maxTokens = 4096,
            keyResolver = { c -> c?.let { AyraPreferences.getNvidiaGemmaKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "gemini_4_argon",
            provider = AiProviderType.GEMINI,
            modelId = ExternalAiProviders.GEMINI_PRO_MODEL,
            displayName = "Gemini 4 Argon",
            specialties = setOf("coding", "math", "reasoning", "research", "document", "cyber", "data", "vision"),
            supportsVision = true,
            isReasoning = true,
            maxTokens = 8192,
            keyResolver = { c -> AyraPreferences.getEffectiveGeminiKey(c) }
        ),
        AiModelChannel(
            id = "groq_qwen_3_8",
            provider = AiProviderType.GROQ,
            modelId = ExternalAiProviders.GROQ_QWEN_MODEL,
            displayName = "Groq Qwen 3.8 27B",
            specialties = setOf("general", "fast", "coding", "reasoning", "education"),
            supportsVision = false,
            isReasoning = true,
            maxTokens = 4096,
            keyResolver = { c -> c?.let { AyraPreferences.getGroqKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "cerebras_gpt_oss",
            provider = AiProviderType.CEREBRAS,
            modelId = ExternalAiProviders.CEREBRAS_LLAMA_MODEL,
            displayName = "Cerebras GPT-OSS 120B",
            specialties = setOf("general", "fast", "coding", "reasoning"),
            supportsVision = false,
            isReasoning = true,
            maxTokens = 4096,
            keyResolver = { c -> c?.let { AyraPreferences.getCerebrasKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "sambanova_deepseek",
            provider = AiProviderType.SAMBANOVA,
            modelId = ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL,
            displayName = "SambaNova DeepSeek V3.1",
            specialties = setOf("coding", "math", "reasoning", "research"),
            supportsVision = false,
            isReasoning = true,
            maxTokens = 4096,
            keyResolver = { c -> c?.let { AyraPreferences.getSambaNovaKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "huggingface_qwen_72b",
            provider = AiProviderType.HUGGINGFACE,
            modelId = ExternalAiProviders.HF_QWEN_MODEL,
            displayName = "HF Qwen 2.5 72B",
            specialties = setOf("coding", "math", "reasoning", "multilingual"),
            supportsVision = false,
            isReasoning = true,
            maxTokens = 4096,
            keyResolver = { c -> c?.let { AyraPreferences.getHuggingFaceKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "gemini_gemma_4",
            provider = AiProviderType.GEMINI,
            modelId = ExternalAiProviders.GEMINI_GEMMA4_MODEL,
            displayName = "Gemma 4 31B (Gemini)",
            specialties = setOf("education", "general", "multilingual", "creative", "vision"),
            supportsVision = true,
            isReasoning = true,
            maxTokens = 4096,
            keyResolver = { c -> AyraPreferences.getEffectiveGeminiKey(c) }
        ),
        AiModelChannel(
            id = "openai_gpt_6_2_sol",
            provider = AiProviderType.OPENAI,
            modelId = ExternalAiProviders.OPENAI_DEFAULT_MODEL,
            displayName = "OpenAI GPT-6.2 Sol",
            specialties = setOf("general", "coding", "vision", "creative", "research", "document", "data"),
            supportsVision = true,
            isReasoning = true,
            maxTokens = 8192,
            keyResolver = { c -> c?.let { AyraPreferences.getOpenAiKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "anthropic_claude_5_5",
            provider = AiProviderType.ANTHROPIC,
            modelId = ExternalAiProviders.ANTHROPIC_SONNET_MODEL,
            displayName = "Claude Sonnet 5.5",
            specialties = setOf("coding", "cyber", "reasoning", "document", "research", "vision"),
            supportsVision = true,
            isReasoning = true,
            maxTokens = 8192,
            keyResolver = { c -> c?.let { AyraPreferences.getAnthropicKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "deepseek_v4_pro",
            provider = AiProviderType.DEEPSEEK,
            modelId = ExternalAiProviders.DEEPSEEK_REASONER_MODEL,
            displayName = "DeepSeek V4 Pro",
            specialties = setOf("math", "coding", "reasoning", "data", "cyber"),
            supportsVision = false,
            isReasoning = true,
            maxTokens = 8192,
            keyResolver = { c -> c?.let { AyraPreferences.getDeepSeekKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "perplexity_sonar",
            provider = AiProviderType.PERPLEXITY,
            modelId = ExternalAiProviders.PERPLEXITY_REASONING_MODEL,
            displayName = "Perplexity Sonar Pro",
            specialties = setOf("research", "general", "education"),
            supportsVision = false,
            isReasoning = true,
            maxTokens = 4096,
            keyResolver = { c -> c?.let { AyraPreferences.getPerplexityKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "mistral_large",
            provider = AiProviderType.MISTRAL,
            modelId = if (domainName == "CODING_AGENT") ModelCatalog.MISTRAL_CODESTRAL_MODEL else ModelCatalog.MISTRAL_LARGE_MODEL,
            displayName = if (domainName == "CODING_AGENT") "Mistral Codestral" else "Mistral Large",
            specialties = setOf("coding", "multilingual", "general"),
            supportsVision = false,
            isReasoning = false,
            maxTokens = 4096,
            keyResolver = { c -> c?.let { AyraPreferences.getMistralKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "xai_grok",
            provider = AiProviderType.XAI,
            modelId = ExternalAiProviders.XAI_DEFAULT_MODEL,
            displayName = "xAI Grok 4.7",
            specialties = setOf("general", "research", "creative"),
            supportsVision = true,
            isReasoning = true,
            maxTokens = 4096,
            keyResolver = { c -> c?.let { AyraPreferences.getXAiKey(it) } ?: "" }
        ),
        AiModelChannel(
            id = "openrouter_free",
            provider = AiProviderType.OPENROUTER,
            modelId = if (domainName == "CODING_AGENT") ExternalAiProviders.OPENROUTER_CODER_MODEL else ExternalAiProviders.OPENROUTER_DEFAULT_MODEL,
            displayName = "OpenRouter Multi-Model",
            specialties = setOf("general", "coding", "math", "reasoning", "creative"),
            supportsVision = true,
            isReasoning = false,
            maxTokens = 4096,
            keyResolver = { c -> AyraPreferences.getEffectiveOpenRouterKey(c) }
        ),
        AiModelChannel(
            id = "local_ollama",
            provider = AiProviderType.LOCAL_OLLAMA,
            modelId = ModelCatalog.OLLAMA_LLAMA_MODEL,
            displayName = "Local Ollama / Qwen",
            specialties = setOf("general", "coding", "privacy"),
            supportsVision = false,
            isReasoning = false,
            maxTokens = 4096,
            keyResolver = { c ->
                if (c == null) "" else {
                    val url = AyraPreferences.getCustomEndpointUrl(c).trim()
                    val hasOllama = AyraPreferences.hasExplicitOllamaHost(c)
                    if (url.isNotBlank() || hasOllama) AyraPreferences.getCustomEndpointKey(c).ifBlank { "local" } else ""
                }
            }
        ),
        AiModelChannel(
            id = "pollinations_free_openai",
            provider = AiProviderType.POLLINATIONS_FREE,
            modelId = ModelCatalog.POLLINATIONS_OPENAI_MODEL,
            displayName = "Free AI Pool (GPT)",
            specialties = setOf("general", "fast", "creative", "education"),
            supportsVision = false,
            isReasoning = false,
            maxTokens = 4096,
            keyResolver = { c ->
                if (channelKeyOverrides.isNotEmpty()) {
                    channelKeyOverrides["pollinations_free_openai"] ?: ""
                } else {
                    val enabled = c?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: false
                    if (enabled) AyraPreferences.getPollinationsKey(c).ifBlank { "free" } else ""
                }
            }
        ),
        AiModelChannel(
            id = "pollinations_free_openai_large",
            provider = AiProviderType.POLLINATIONS_FREE,
            modelId = ModelCatalog.POLLINATIONS_LARGE_MODEL,
            displayName = "Free AI Flagship (Large)",
            specialties = setOf("general", "research", "reasoning", "document", "creative"),
            supportsVision = false,
            isReasoning = true,
            maxTokens = 4096,
            keyResolver = { c ->
                if (channelKeyOverrides.isNotEmpty()) {
                    channelKeyOverrides["pollinations_free_openai_large"] ?: ""
                } else {
                    val enabled = c?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: false
                    if (enabled) AyraPreferences.getPollinationsKey(c).ifBlank { "free" } else ""
                }
            }
        ),
        AiModelChannel(
            id = "pollinations_free_qwen_coder",
            provider = AiProviderType.POLLINATIONS_FREE,
            modelId = ModelCatalog.POLLINATIONS_CODER_MODEL,
            displayName = "Free Qwen Coder",
            specialties = setOf("coding", "data", "cyber"),
            supportsVision = false,
            isReasoning = false,
            maxTokens = 4096,
            keyResolver = { c ->
                if (channelKeyOverrides.isNotEmpty()) {
                    channelKeyOverrides["pollinations_free_qwen_coder"] ?: ""
                } else {
                    val enabled = c?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: false
                    if (enabled) AyraPreferences.getPollinationsKey(c).ifBlank { "free" } else ""
                }
            }
        ),
        AiModelChannel(
            id = "pollinations_free_deepseek",
            provider = AiProviderType.POLLINATIONS_FREE,
            modelId = ModelCatalog.POLLINATIONS_DEEPSEEK_MODEL,
            displayName = "Free DeepSeek Reasoning",
            specialties = setOf("math", "reasoning", "research", "cyber"),
            supportsVision = false,
            isReasoning = true,
            maxTokens = 4096,
            keyResolver = { c ->
                if (channelKeyOverrides.isNotEmpty()) {
                    channelKeyOverrides["pollinations_free_deepseek"] ?: ""
                } else {
                    val enabled = c?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: false
                    if (enabled) AyraPreferences.getPollinationsKey(c).ifBlank { "free" } else ""
                }
            }
        )
    )
}
