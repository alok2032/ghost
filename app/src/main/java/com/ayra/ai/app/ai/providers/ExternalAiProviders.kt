package com.ayra.ai.app.ai.providers

import android.content.Context
import com.ayra.ai.app.AyraApplication
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher

enum class AiProviderType(val id: String, val displayName: String, val defaultBaseUrl: String) {
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/"),
    NVIDIA_NIM("nvidia_nim", "NVIDIA NIM AI", "https://integrate.api.nvidia.com/v1/"),
    GROQ("groq", "Groq Cloud (Free Tier)", "https://api.groq.com/openai/v1/"),
    CEREBRAS("cerebras", "Cerebras Cloud (Free Tier)", "https://api.cerebras.ai/v1/"),
    SAMBANOVA("sambanova", "SambaNova Cloud (Free Tier)", "https://api.sambanova.ai/v1/"),
    HUGGINGFACE("huggingface", "Hugging Face Router", "https://router.huggingface.co/v1/"),
    GITHUB_MODELS("github_models", "GitHub Models (Free Tier)", "https://models.inference.ai.azure.com/"),
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1/"),
    ANTHROPIC("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1/"),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com/"),
    XAI("xai", "xAI Grok", "https://api.x.ai/v1/"),
    MISTRAL("mistral", "Mistral AI", "https://api.mistral.ai/v1/"),
    PERPLEXITY("perplexity", "Perplexity AI", "https://api.perplexity.ai/"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/"),
    LOCAL_OLLAMA("local_ollama", "Local Ollama / LAN", "http://10.0.2.2:11434/v1/"),
    POLLINATIONS_FREE("pollinations_free", "Global Free AI Pool", "https://text.pollinations.ai/"),
    OMNI_ROUTE("omniroute", "OmniRoute Ensemble ⚡👑", "")
}

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
    CODING("coding", "Software & Coding", "💻", "Full-stack coding ensemble (GLM 5.3, Gemini 4 Argon, MiniMax M3.1, Qwen Coder)"),
    PRIVACY_LOCAL("local", "Private / Local LAN", "🏠", "Private local LLM endpoint running on your LAN/Ollama"),
    COST_EFFICIENT("cost", "Cost Efficient / Free", "🌱", "Fast zero/low-cost cloud models and Global Free AI Pool"),
    QWEN_UNCENSORED("qwen_uncensored", "Qwen 3.8 Uncensored", "🔓", "Direct unrestricted answers via Qwen 3.8 / OpenRouter / NIM")
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
        ProviderError("Provider rate limit reached (HTTP 429). Falling back to alternate AI...")
    class Unauthorized(provider: AiProviderType) :
        ProviderError("Invalid or unauthorized API key for ${provider.displayName}. Please check Settings -> Keys.")
    class ServerError(code: Int, message: String) :
        ProviderError("Remote server error (HTTP $code): $message")
    class Timeout(message: String = "Request timed out while waiting for model inference.") :
        ProviderError(message)
    class NetworkFailure(message: String) :
        ProviderError("Network connection failed: $message")
}

data class ChatTurn(
    val role: String, // "system", "user", "assistant"
    val content: String,
    val imageBase64: String? = null
)

/**
 * Unified Multi-AI Provider Engine for Ayra AI.
 * Connects Google Gemini (2.5 / Gemma 4), NVIDIA NIM (MiniMax M3.1 Flash, Z-AI GLM 5.3 Flash,
 * Meta Muse Glimmer 30B, Google Gemma 4 31B IT), OpenAI, Anthropic Claude, DeepSeek,
 * OpenRouter, xAI, Mistral, Perplexity, Local Ollama, and Pollinations Free AI Pool.
 */
class ExternalAiProviders @JvmOverloads constructor(
    private val context: Context? = null,
    private val baseUrlOverrides: Map<AiProviderType, String> = emptyMap(),
    private val customHttpClient: OkHttpClient? = null,
    private val channelKeyOverrides: Map<String, String> = emptyMap()
) {

    private fun resolveClientForProvider(provider: AiProviderType?): OkHttpClient {
        if (customHttpClient != null) return customHttpClient
        return if (provider == AiProviderType.LOCAL_OLLAMA) {
            com.ayra.ai.app.security.NetworkSecurityHelper.localLanClient
        } else {
            com.ayra.ai.app.security.NetworkSecurityHelper.hardenedPooledClient
        }
    }

    private val httpClient: OkHttpClient
        get() = customHttpClient ?: com.ayra.ai.app.security.NetworkSecurityHelper.hardenedPooledClient

    val circuitBreaker = ProviderCircuitBreaker()

    val allowExternalWikiGrounding: Boolean
        get() {
            val isLocalOnly = context?.let { AyraPreferences.isLocalOnlyAiModeEnabled(it) } ?: false
            val hasExternalConsent = context?.let { AyraPreferences.hasExternalAiConsent(it) } ?: true
            return baseUrlOverrides.isEmpty() &&
                channelKeyOverrides.isEmpty() &&
                customHttpClient == null &&
                !isLocalOnly &&
                hasExternalConsent
        }

    companion object {
        val sharedPooledHttpClient: OkHttpClient
            get() = com.ayra.ai.app.security.NetworkSecurityHelper.hardenedPooledClient

        val sharedFastWebClient: OkHttpClient by lazy {
            com.ayra.ai.app.security.NetworkSecurityHelper.hardenedPooledClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build()
        }

        const val GEMINI_FLASH_MODEL = "gemini-3.8-flash"
        const val GEMINI_PRO_MODEL = "gemini-4-argon"
        const val GEMINI_FLASH_LITE_MODEL = "gemini-3.5-flash-lite"
        const val GEMINI_GEMMA4_MODEL = "gemma-4-31b-it"

        const val NVIDIA_MINIMAX_MODEL = "minimaxai/minimax-m3.1-flash"
        const val NVIDIA_GLM_MODEL = "z-ai/glm-5.3-flash"
        const val NVIDIA_MUSE_MODEL = "meta/muse-glimmer-30b"
        const val NVIDIA_GEMMA_MODEL = "google/gemma-4-31b-it"

        const val GROQ_LLAMA_MODEL = "llama-3.3-70b-versatile"
        const val GROQ_QWEN_MODEL = "qwen/qwen3-32b"
        const val GROQ_WHISPER_MODEL = "whisper-large-v3-turbo"
        const val CEREBRAS_LLAMA_MODEL = "llama-3.3-70b"
        const val CEREBRAS_QWEN_MODEL = "qwen-3-32b"
        const val SAMBANOVA_DEEPSEEK_MODEL = "DeepSeek-V3.1-Terminus"
        const val SAMBANOVA_LLAMA_MODEL = "Meta-Llama-3.3-70B-Instruct"
        const val SAMBANOVA_QWEN_MODEL = "Qwen3-32B"
        const val SAMBANOVA_WHISPER_MODEL = "Whisper-Large-v3"
        const val HF_QWEN_MODEL = "Qwen/Qwen2.5-72B-Instruct"
        const val GITHUB_GPT4O_MODEL = "gpt-4o-mini"

        private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
        private val dynamicModelCache = ConcurrentHashMap<AiProviderType, List<AiModelInfo>>()

        /**
         * Resolves Gemini model aliases forward to live supported Gemini 3.x / 4.x / Gemma 4 endpoints.
         * Never maps to deprecated gemini-1.5 / 2.0 / 2.5 endpoints.
         */
        fun resolveGeminiModel(model: String): String {
            val clean = model.trim().lowercase().removePrefix("models/")
            return when {
                clean in listOf(
                    "gemini-3.8-flash", "gemini-2.5-flash", "gemini_flash", "gemini-flash",
                    "gemini-2.0-flash", "gemini-flash-2.0", "gemini-flash-latest"
                ) -> GEMINI_FLASH_MODEL

                clean in listOf(
                    "gemini-4-argon", "gemini-2.5-pro", "gemini_pro", "gemini-pro",
                    "gemini-1.5-pro", "gemini-pro-1.5", "gemini-pro-latest"
                ) -> GEMINI_PRO_MODEL

                clean in listOf(
                    "gemini-3.5-flash-lite", "gemini-2.5-flash-lite", "gemini_flash_lite", "gemini-flash-lite",
                    "gemini-2.0-flash-lite", "gemini-1.5-flash", "gemini_1_5_flash"
                ) -> GEMINI_FLASH_LITE_MODEL

                clean.isNotBlank() -> clean
                else -> GEMINI_FLASH_MODEL
            }
        }

        private fun nextGeminiFallback(currentModel: String): String? {
            return when (currentModel) {
                GEMINI_FLASH_MODEL -> GEMINI_FLASH_LITE_MODEL
                GEMINI_FLASH_LITE_MODEL -> null
                else -> GEMINI_FLASH_MODEL
            }
        }

        fun sanitizeModelOutput(raw: String): String {
            if (raw.isBlank()) return ""
            var cleaned = raw.replace(THINK_BLOCK_REGEX, "").trim()
            if (cleaned.isBlank()) {
                // If output only contained a <think> block, unwrap its inner text rather than returning empty
                cleaned = raw.replace(Regex("</?think>", RegexOption.IGNORE_CASE), "").trim()
            }
            return cleaned
        }

        val CURATED_MODELS = listOf(
            // Google Gemini (Live 3.x / 4.x & Gemma 4 series)
            AiModelInfo(GEMINI_FLASH_MODEL, "Gemini 3.8 Flash ⚡", AiProviderType.GEMINI, "Ultra-fast multimodal flagship with web grounding", "1M tokens", true, false, true),
            AiModelInfo(GEMINI_PRO_MODEL, "Gemini 4 Argon 🔬", AiProviderType.GEMINI, "Frontier deep reasoning, code architecture & STEM synthesis", "2M tokens", true, true, false),
            AiModelInfo(GEMINI_FLASH_LITE_MODEL, "Gemini 3.5 Flash-Lite 🚀", AiProviderType.GEMINI, "High-throughput low-latency conversational engine", "1M tokens", true, false, false),
            AiModelInfo(GEMINI_GEMMA4_MODEL, "Gemma 4 31B IT 🌐", AiProviderType.GEMINI, "Google Gemma 4 open-weights multilingual reasoning", "128k tokens", true, true, false),

            // Free-Tier / Open Weights Fast Inference Cloud Gateways
            AiModelInfo(GROQ_LLAMA_MODEL, "Groq Llama 3.3 70B ⚡", AiProviderType.GROQ, "Ultra-fast LPU inference with generous free tier", "128k tokens", false, false, false),
            AiModelInfo(GROQ_QWEN_MODEL, "Groq Qwen3 32B 🧠", AiProviderType.GROQ, "High-speed STEM & multilingual reasoning on Groq LPU", "128k tokens", false, true, false),
            AiModelInfo(CEREBRAS_LLAMA_MODEL, "Cerebras Llama 3.3 70B 🚀", AiProviderType.CEREBRAS, "Wafer-scale high-speed inference with generous free tier", "128k tokens", false, false, false),
            AiModelInfo(CEREBRAS_QWEN_MODEL, "Cerebras Qwen 3 32B ⚡", AiProviderType.CEREBRAS, "Wafer-scale STEM, math & coding reasoning", "128k tokens", false, true, false),
            AiModelInfo(SAMBANOVA_DEEPSEEK_MODEL, "SambaNova DeepSeek V3.1 🔬", AiProviderType.SAMBANOVA, "Fast RDU inference for coding, math & deep reasoning", "128k tokens", false, true, false),
            AiModelInfo(SAMBANOVA_QWEN_MODEL, "SambaNova Qwen3 32B 🌐", AiProviderType.SAMBANOVA, "Fast RDU multilingual & STEM reasoning", "64k tokens", false, true, false),
            AiModelInfo(HF_QWEN_MODEL, "HF Qwen 2.5 72B Instruct 🤗", AiProviderType.HUGGINGFACE, "Open-weights Hugging Face Inference Router", "128k tokens", false, true, false),
            AiModelInfo(GITHUB_GPT4O_MODEL, "GitHub Models GPT-4o Mini 🐙", AiProviderType.GITHUB_MODELS, "Free-tier GitHub Models inference gateway", "128k tokens", true, false, false),

            // NVIDIA NIM Multi-Model Suite
            AiModelInfo(NVIDIA_MINIMAX_MODEL, "MiniMax M3.1 Flash ⚡", AiProviderType.NVIDIA_NIM, "High-speed multimodal vision & coding intelligence via NVIDIA NIM", "128k tokens", true, true, true),
            AiModelInfo(NVIDIA_GLM_MODEL, "Z-AI GLM 5.3 Flash 🧠", AiProviderType.NVIDIA_NIM, "Fast STEM, mathematical reasoning & multilingual synthesis", "128k tokens", false, true, false),
            AiModelInfo(NVIDIA_MUSE_MODEL, "Meta Muse Glimmer 30B ✨", AiProviderType.NVIDIA_NIM, "Fluent creative writing, Bengali/Banglish & deep reasoning", "128k tokens", false, true, false),
            AiModelInfo(NVIDIA_GEMMA_MODEL, "Gemma 4 31B IT (NIM) 🔬", AiProviderType.NVIDIA_NIM, "Multilingual science, coding & analytical verification", "128k tokens", true, true, false),

            // OpenAI
            AiModelInfo("gpt-4o", "GPT-4o Omni 🧠", AiProviderType.OPENAI, "OpenAI flagship multimodal intelligence", "128k tokens", true, false, false),
            AiModelInfo("gpt-4o-mini", "GPT-4o Mini ⚡", AiProviderType.OPENAI, "Fast daily conversational model", "128k tokens", true, false, false),
            AiModelInfo("o3-mini", "o3-mini Reasoning 🔬", AiProviderType.OPENAI, "STEM, math & coding chain-of-thought", "200k tokens", false, true, false),

            // Anthropic Claude
            AiModelInfo("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet 🎭", AiProviderType.ANTHROPIC, "Hybrid reasoning & software architecture benchmark", "200k tokens", true, true, false),
            AiModelInfo("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet 👑", AiProviderType.ANTHROPIC, "Clean refactoring & production coding", "200k tokens", true, false, false),

            // DeepSeek
            AiModelInfo("deepseek-chat", "DeepSeek V3 🚀", AiProviderType.DEEPSEEK, "MoE general conversational & coding intelligence", "64k tokens", false, false, false),
            AiModelInfo("deepseek-reasoner", "DeepSeek R1 CoT 🔬", AiProviderType.DEEPSEEK, "Open mathematical proof & step-by-step CoT", "64k tokens", false, true, false),

            // xAI, Mistral, Perplexity
            AiModelInfo("grok-2-latest", "Grok 2 Latest 🤖", AiProviderType.XAI, "Frontier reasoning by xAI", "128k tokens", true, false, false),
            AiModelInfo("mistral-large-latest", "Mistral Large 🌐", AiProviderType.MISTRAL, "European multilingual intelligence", "128k tokens", false, false, false),
            AiModelInfo("codestral-latest", "Codestral 💻", AiProviderType.MISTRAL, "Dedicated programming & refactoring", "256k tokens", false, false, false),
            AiModelInfo("sonar-reasoning", "Perplexity Sonar 🔎", AiProviderType.PERPLEXITY, "Web-grounded research with citations", "128k tokens", false, true, false),

            // OpenRouter
            AiModelInfo("openrouter/auto", "OpenRouter Auto 🔀", AiProviderType.OPENROUTER, "Dynamic universal gateway", "128k tokens", true, false, false),
            AiModelInfo("qwen/qwen-2.5-72b-instruct", "Qwen 2.5 72B Instruct 🐉", AiProviderType.OPENROUTER, "Multilingual STEM & reasoning", "128k tokens", false, false, false),
            AiModelInfo("qwen/qwen-2.5-coder-32b-instruct", "Qwen 2.5 Coder 32B 💻", AiProviderType.OPENROUTER, "Coding and bug-fixing specialist", "128k tokens", false, false, false),

            // Local Ollama & Qwen 3.8 Uncensored
            AiModelInfo("orcarouter/Qwen3.8-27B-Uncensored:q4_k_m", "Qwen 3.8 27B Uncensored 🔓", AiProviderType.LOCAL_OLLAMA, "Local/LAN abliterated Qwen weights", "32k+ tokens", false, true, true),
            AiModelInfo("llama3.3", "Local Ollama / LM Studio 🏠", AiProviderType.LOCAL_OLLAMA, "Zero-cloud local LLM on your LAN", "8k+ tokens", false, false, false),

            // Global Free AI Pool (Expanded Specialists)
            AiModelInfo("openai", "Global Free AI Pool (GPT/OSS) 🌐", AiProviderType.POLLINATIONS_FREE, "Keyless free cloud AI fallback & ensemble backup", "32k tokens", false, false, false),
            AiModelInfo("openai-large", "Global Free Flagship (Large) ⚡", AiProviderType.POLLINATIONS_FREE, "High-reasoning keyless ensemble channel", "64k tokens", false, true, false),
            AiModelInfo("qwen-coder", "Global Free Qwen Coder 💻", AiProviderType.POLLINATIONS_FREE, "Keyless coding & architecture specialist", "32k tokens", false, false, false),
            AiModelInfo("deepseek", "Global Free DeepSeek R1/V3 🧠", AiProviderType.POLLINATIONS_FREE, "Keyless STEM, math & step-by-step logic channel", "64k tokens", false, true, false)
        )
    }

    init {
        if (context != null) {
            AyraApplication.ensureActiveAiKeysSeeded(context)
        }
    }

    /**
     * Returns all individually active AI channels ranked specifically for the detected question domain.
     * Ensures different providers and keys stay isolated while collaborating for the best answer.
     */
    fun getActiveChannels(
        domainName: String = "GENERAL_ASSISTANT",
        hasImage: Boolean = false
    ): List<AiModelChannel> {
        val ctx = context
        if (ctx != null) {
            AyraApplication.ensureActiveAiKeysSeeded(ctx)
        }

        val allDefinitions = listOf(
            AiModelChannel(
                id = "gemini_3_8_flash",
                provider = AiProviderType.GEMINI,
                modelId = GEMINI_FLASH_MODEL,
                displayName = "Gemini 3.8 Flash",
                specialties = setOf("general", "fast", "vision", "multilingual", "education", "research", "document", "creative"),
                supportsVision = true,
                isReasoning = false,
                maxTokens = 8192,
                keyResolver = { c -> AyraPreferences.getEffectiveGeminiKey(c) }
            ),
            AiModelChannel(
                id = "nvidia_minimax_m3_1",
                provider = AiProviderType.NVIDIA_NIM,
                modelId = NVIDIA_MINIMAX_MODEL,
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
                modelId = NVIDIA_MUSE_MODEL,
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
                modelId = NVIDIA_GLM_MODEL,
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
                modelId = NVIDIA_GEMMA_MODEL,
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
                modelId = GEMINI_PRO_MODEL,
                displayName = "Gemini 4 Argon",
                specialties = setOf("coding", "math", "reasoning", "research", "document", "cyber", "data", "vision"),
                supportsVision = true,
                isReasoning = true,
                maxTokens = 8192,
                keyResolver = { c -> AyraPreferences.getEffectiveGeminiKey(c) }
            ),
            AiModelChannel(
                id = "groq_llama_3_3",
                provider = AiProviderType.GROQ,
                modelId = GROQ_LLAMA_MODEL,
                displayName = "Groq Llama 3.3 70B",
                specialties = setOf("general", "fast", "coding", "reasoning", "education"),
                supportsVision = false,
                isReasoning = true,
                maxTokens = 4096,
                keyResolver = { c -> c?.let { AyraPreferences.getGroqKey(it) } ?: "" }
            ),
            AiModelChannel(
                id = "cerebras_llama_3_3",
                provider = AiProviderType.CEREBRAS,
                modelId = CEREBRAS_LLAMA_MODEL,
                displayName = "Cerebras Llama 3.3 70B",
                specialties = setOf("general", "fast", "coding", "reasoning"),
                supportsVision = false,
                isReasoning = false,
                maxTokens = 4096,
                keyResolver = { c -> c?.let { AyraPreferences.getCerebrasKey(it) } ?: "" }
            ),
            AiModelChannel(
                id = "sambanova_deepseek",
                provider = AiProviderType.SAMBANOVA,
                modelId = SAMBANOVA_DEEPSEEK_MODEL,
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
                modelId = HF_QWEN_MODEL,
                displayName = "HF Qwen 2.5 72B",
                specialties = setOf("coding", "math", "reasoning", "multilingual"),
                supportsVision = false,
                isReasoning = true,
                maxTokens = 4096,
                keyResolver = { c -> c?.let { AyraPreferences.getHuggingFaceKey(it) } ?: "" }
            ),
            AiModelChannel(
                id = "github_models_gpt4o_mini",
                provider = AiProviderType.GITHUB_MODELS,
                modelId = GITHUB_GPT4O_MODEL,
                displayName = "GitHub Models GPT-4o Mini",
                specialties = setOf("general", "coding", "fast", "research"),
                supportsVision = true,
                isReasoning = false,
                maxTokens = 4096,
                keyResolver = { c -> c?.let { AyraPreferences.getGitHubModelsKey(it) } ?: "" }
            ),
            AiModelChannel(
                id = "gemini_gemma_4",
                provider = AiProviderType.GEMINI,
                modelId = GEMINI_GEMMA4_MODEL,
                displayName = "Gemma 4 31B (Gemini)",
                specialties = setOf("education", "general", "multilingual", "creative", "vision"),
                supportsVision = true,
                isReasoning = true,
                maxTokens = 4096,
                keyResolver = { c -> AyraPreferences.getEffectiveGeminiKey(c) }
            ),
            AiModelChannel(
                id = "openai_gpt4o",
                provider = AiProviderType.OPENAI,
                modelId = "gpt-4o",
                displayName = "OpenAI GPT-4o",
                specialties = setOf("general", "coding", "vision", "creative", "research", "document", "data"),
                supportsVision = true,
                isReasoning = false,
                maxTokens = 4096,
                keyResolver = { c -> c?.let { AyraPreferences.getOpenAiKey(it) } ?: "" }
            ),
            AiModelChannel(
                id = "anthropic_claude_3_7",
                provider = AiProviderType.ANTHROPIC,
                modelId = "claude-3-7-sonnet-20250219",
                displayName = "Claude 3.7 Sonnet",
                specialties = setOf("coding", "cyber", "reasoning", "document", "research", "vision"),
                supportsVision = true,
                isReasoning = true,
                maxTokens = 4096,
                keyResolver = { c -> c?.let { AyraPreferences.getAnthropicKey(it) } ?: "" }
            ),
            AiModelChannel(
                id = "deepseek_reasoner",
                provider = AiProviderType.DEEPSEEK,
                modelId = "deepseek-reasoner",
                displayName = "DeepSeek R1",
                specialties = setOf("math", "coding", "reasoning", "data", "cyber"),
                supportsVision = false,
                isReasoning = true,
                maxTokens = 4096,
                keyResolver = { c -> c?.let { AyraPreferences.getDeepSeekKey(it) } ?: "" }
            ),
            AiModelChannel(
                id = "perplexity_sonar",
                provider = AiProviderType.PERPLEXITY,
                modelId = "sonar-reasoning",
                displayName = "Perplexity Sonar",
                specialties = setOf("research", "general", "education"),
                supportsVision = false,
                isReasoning = true,
                maxTokens = 4096,
                keyResolver = { c -> c?.let { AyraPreferences.getPerplexityKey(it) } ?: "" }
            ),
            AiModelChannel(
                id = "mistral_large",
                provider = AiProviderType.MISTRAL,
                modelId = if (domainName == "CODING_AGENT") "codestral-latest" else "mistral-large-latest",
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
                modelId = "grok-2-latest",
                displayName = "xAI Grok 2",
                specialties = setOf("general", "research", "creative"),
                supportsVision = true,
                isReasoning = false,
                maxTokens = 4096,
                keyResolver = { c -> c?.let { AyraPreferences.getXAiKey(it) } ?: "" }
            ),
            AiModelChannel(
                id = "openrouter_auto",
                provider = AiProviderType.OPENROUTER,
                modelId = if (domainName == "CODING_AGENT") "qwen/qwen-2.5-coder-32b-instruct" else "openrouter/auto",
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
                modelId = "llama3.3",
                displayName = "Local Ollama / Qwen",
                specialties = setOf("general", "coding", "privacy"),
                supportsVision = false,
                isReasoning = false,
                maxTokens = 4096,
                keyResolver = { c ->
                    if (c == null) "" else {
                        val url = AyraPreferences.getCustomEndpointUrl(c).trim()
                        if (url.isNotBlank()) AyraPreferences.getCustomEndpointKey(c).ifBlank { "local" } else ""
                    }
                }
            ),
            AiModelChannel(
                id = "pollinations_free_openai",
                provider = AiProviderType.POLLINATIONS_FREE,
                modelId = "openai",
                displayName = "Free AI Pool (GPT)",
                specialties = setOf("general", "fast", "creative", "education"),
                supportsVision = false,
                isReasoning = false,
                maxTokens = 4096,
                keyResolver = { c ->
                    if (channelKeyOverrides.isNotEmpty()) {
                        channelKeyOverrides["pollinations_free_openai"] ?: ""
                    } else {
                        val enabled = c?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: true
                        if (enabled) "free" else ""
                    }
                }
            ),
            AiModelChannel(
                id = "pollinations_free_openai_large",
                provider = AiProviderType.POLLINATIONS_FREE,
                modelId = "openai-large",
                displayName = "Free AI Flagship (Large)",
                specialties = setOf("general", "research", "reasoning", "document", "creative"),
                supportsVision = false,
                isReasoning = true,
                maxTokens = 4096,
                keyResolver = { c ->
                    if (channelKeyOverrides.isNotEmpty()) {
                        channelKeyOverrides["pollinations_free_openai_large"] ?: ""
                    } else {
                        val enabled = c?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: true
                        if (enabled) "free" else ""
                    }
                }
            ),
            AiModelChannel(
                id = "pollinations_free_qwen_coder",
                provider = AiProviderType.POLLINATIONS_FREE,
                modelId = "qwen-coder",
                displayName = "Free Qwen Coder",
                specialties = setOf("coding", "data", "cyber"),
                supportsVision = false,
                isReasoning = false,
                maxTokens = 4096,
                keyResolver = { c ->
                    if (channelKeyOverrides.isNotEmpty()) {
                        channelKeyOverrides["pollinations_free_qwen_coder"] ?: ""
                    } else {
                        val enabled = c?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: true
                        if (enabled) "free" else ""
                    }
                }
            ),
            AiModelChannel(
                id = "pollinations_free_deepseek",
                provider = AiProviderType.POLLINATIONS_FREE,
                modelId = "deepseek",
                displayName = "Free DeepSeek Reasoning",
                specialties = setOf("math", "reasoning", "research", "cyber"),
                supportsVision = false,
                isReasoning = true,
                maxTokens = 4096,
                keyResolver = { c ->
                    if (channelKeyOverrides.isNotEmpty()) {
                        channelKeyOverrides["pollinations_free_deepseek"] ?: ""
                    } else {
                        val enabled = c?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: true
                        if (enabled) "free" else ""
                    }
                }
            )
        )

        val desiredSpecialty = when (domainName) {
            "CODING_AGENT" -> "coding"
            "MATHEMATICS_AGENT" -> "math"
            "RESEARCH_AGENT" -> "research"
            "DOCUMENT_AGENT" -> "document"
            "CYBER_DEFENSE_AGENT" -> "cyber"
            "DATA_ANALYSIS_AGENT" -> "data"
            "IMAGE_AGENT", "VIDEO_AGENT" -> "creative"
            "EDUCATION_AGENT" -> "education"
            else -> "general"
        }

        val active = allDefinitions.filter { channel ->
            val key = resolveChannelKey(channel, ctx)
            val keyOk = if (channel.provider == AiProviderType.LOCAL_OLLAMA) {
                (channelKeyOverrides[channel.id]?.isNotBlank() == true) ||
                    (ctx != null && AyraPreferences.getCustomEndpointUrl(ctx).isNotBlank())
            } else {
                key.isNotBlank()
            }
            val breakerOk = circuitBreaker.canExecute(channel.provider) && circuitBreaker.canExecuteChannel(channel.id)
            keyOk && breakerOk
        }

        return active.sortedWith(
            compareByDescending<AiModelChannel> { if (hasImage && it.supportsVision) 10 else if (hasImage) -10 else 0 }
                .thenByDescending { if (it.specialties.contains(desiredSpecialty)) 6 else 0 }
                .thenByDescending { if (it.id == "gemini_3_8_flash" || it.id == "nvidia_minimax_m3_1") 3 else 0 }
                .thenByDescending { if (it.provider == AiProviderType.POLLINATIONS_FREE) -5 else 1 }
        )
    }

    private fun resolveChannelKey(channel: AiModelChannel, ctx: Context?): String {
        val overrideById = channelKeyOverrides[channel.id]
        if (!overrideById.isNullOrBlank()) return overrideById
        if (channel.provider != AiProviderType.POLLINATIONS_FREE) {
            val overrideByProvider = channelKeyOverrides[channel.provider.id]
            if (!overrideByProvider.isNullOrBlank()) return overrideByProvider
        }
        return channel.keyResolver(ctx)
    }

    /**
     * Converts Room ChatMessage history into token-budgeted multi-turn conversation turns.
     */
    fun buildConversationTurns(
        systemPrompt: String,
        history: List<ChatMessage>?,
        currentPrompt: String,
        currentImageBase64: String? = null,
        maxPreviousTurns: Int = 12
    ): List<ChatTurn> {
        val turns = mutableListOf<ChatTurn>()
        if (systemPrompt.isNotBlank()) {
            turns.add(ChatTurn(role = "system", content = systemPrompt))
        }

        if (!history.isNullOrEmpty()) {
            val relevantHistory = history
                .filter { it.content.isNotBlank() && (it.role == "user" || it.role == "assistant") }
                .takeLast(maxPreviousTurns)

            for (msg in relevantHistory) {
                val role = if (msg.role == "assistant") "assistant" else "user"
                turns.add(ChatTurn(role = role, content = msg.content))
            }
        }

        turns.add(ChatTurn(role = "user", content = currentPrompt, imageBase64 = currentImageBase64))
        return turns
    }

    /**
     * Executes a single specific [AiModelChannel] with its own isolated credentials and token budget.
     */
    fun callChannel(
        channel: AiModelChannel,
        turns: List<ChatTurn>,
        withSearchGrounding: Boolean = true
    ): String {
        val key = resolveChannelKey(channel, context)
        return when (channel.provider) {
            AiProviderType.GEMINI -> callGemini(turns, channel.modelId, key, withSearchGrounding)
            AiProviderType.ANTHROPIC -> callAnthropic(turns, channel.modelId, key)
            AiProviderType.POLLINATIONS_FREE -> {
                val sys = turns.firstOrNull { it.role == "system" }?.content ?: ""
                val usr = turns.lastOrNull { it.role == "user" }?.content ?: ""
                queryPollinationsFallback(usr, sys, channel.modelId)
            }
            AiProviderType.NVIDIA_NIM, AiProviderType.GROQ, AiProviderType.CEREBRAS, AiProviderType.SAMBANOVA,
            AiProviderType.HUGGINGFACE, AiProviderType.GITHUB_MODELS, AiProviderType.OPENAI, AiProviderType.DEEPSEEK,
            AiProviderType.XAI, AiProviderType.MISTRAL, AiProviderType.PERPLEXITY,
            AiProviderType.OPENROUTER, AiProviderType.LOCAL_OLLAMA -> {
                callOpenAiCompatible(channel.provider, turns, channel.modelId, key, channel.maxTokens)
            }
            AiProviderType.OMNI_ROUTE -> {
                val gKey = channelKeyOverrides[AiProviderType.GEMINI.id]
                    ?: context?.let { AyraPreferences.getEffectiveGeminiKey(it) }
                    ?: ""
                if (gKey.isNotBlank()) callGemini(turns, GEMINI_FLASH_MODEL, gKey, withSearchGrounding)
                else throw ProviderError.NoKeyConfigured(AiProviderType.GEMINI)
            }
        }
    }

    /**
     * Executes synchronous / unary model inference with automatic multi-provider fallback.
     */
    suspend fun generateChatResponse(
        prompt: String,
        systemPrompt: String,
        history: List<ChatMessage>? = null,
        imageBase64: String? = null,
        preferredProvider: AiProviderType? = null,
        preferredModelId: String? = null,
        withSearchGrounding: Boolean = true
    ): ProviderResponse = withContext(Dispatchers.IO) {
        val turns = buildConversationTurns(systemPrompt, history, prompt, imageBase64)
        val startTime = System.currentTimeMillis()

        // 0. Check LRU Cache for instant idempotent response (only when no image/history)
        val cacheKey = "${prompt.trim()}|${systemPrompt.take(120)}|${preferredProvider?.id}|$preferredModelId"
        if (imageBase64 == null && history.isNullOrEmpty()) {
            circuitBreaker.getCachedResponse(cacheKey)?.let { cached ->
                return@withContext ProviderResponse(
                    text = cached,
                    modelUsed = "Ayra Instant Cache ⚡",
                    provider = preferredProvider ?: getBestAvailableProvider(),
                    latencyMs = 0L
                )
            }
        }

        // 1. If explicit provider preferred and configured, try it first
        if (preferredProvider == AiProviderType.OMNI_ROUTE) {
            try {
                val omniEngine = context?.let { com.ayra.ai.app.ai.omniroute.OmniRouteEngine(it) }
                if (omniEngine != null) {
                    val omniResp = omniEngine.executeRoute(prompt, "omniroute", imageBase64)
                    if (omniResp.text.isNotBlank()) {
                        return@withContext ProviderResponse(
                            text = omniResp.text,
                            modelUsed = omniResp.modelUsed ?: "OmniRoute Ensemble ⚡👑",
                            provider = AiProviderType.OMNI_ROUTE,
                            latencyMs = System.currentTimeMillis() - startTime
                        )
                    }
                }
            } catch (_: Exception) {}
        } else if (preferredProvider != null) {
            if (allowExternalWikiGrounding) {
                val gate = com.ayra.ai.app.security.SecureAiGatewayClient.evaluateProviderAccess(context, preferredProvider)
                if (!gate.allowed) {
                    throw ProviderError.NetworkFailure(gate.denialReason ?: "Provider blocked by Ayra Zero-Trust policy.")
                }
            }
            val model = preferredModelId ?: getDefaultModelForProvider(preferredProvider)
            val key = getKeyForProviderAndModel(preferredProvider, model)
            if ((key.isNotBlank() || preferredProvider == AiProviderType.LOCAL_OLLAMA || preferredProvider == AiProviderType.POLLINATIONS_FREE) &&
                circuitBreaker.canExecute(preferredProvider)
            ) {
                try {
                    val callStart = System.currentTimeMillis()
                    val resultText = callSpecificProvider(preferredProvider, model, turns, key, withSearchGrounding)
                    val callLatency = (System.currentTimeMillis() - callStart).coerceAtLeast(1L)
                    if (resultText.isNotBlank()) {
                        AiUsageTracker.recordCall(context, preferredProvider, true, callLatency)
                        circuitBreaker.recordSuccess(preferredProvider)
                        if (imageBase64 == null && history.isNullOrEmpty()) {
                            circuitBreaker.putCachedResponse(cacheKey, resultText)
                        }
                        return@withContext ProviderResponse(
                            text = resultText,
                            modelUsed = "${preferredProvider.displayName} ($model)",
                            provider = preferredProvider,
                            latencyMs = System.currentTimeMillis() - startTime
                        )
                    }
                } catch (e: Exception) {
                    AiUsageTracker.recordCall(context, preferredProvider, false, System.currentTimeMillis() - startTime)
                    if (e is ProviderError.Unauthorized || preferredModelId == null) {
                        circuitBreaker.recordFailure(preferredProvider)
                    }
                }
            }
        }

        // 2. Specialist Channel Auto-Router across Gemini, NVIDIA NIM (all 4 models), Groq, Cerebras, SambaNova, OpenRouter, etc.
        val channels = getActiveChannels(hasImage = !imageBase64.isNullOrBlank())
        for (channel in channels) {
            val chStart = System.currentTimeMillis()
            try {
                val resultText = callChannel(channel, turns, withSearchGrounding)
                val chLatency = (System.currentTimeMillis() - chStart).coerceAtLeast(1L)
                if (resultText.isNotBlank()) {
                    AiUsageTracker.recordCall(context, channel.provider, true, chLatency)
                    circuitBreaker.recordSuccess(channel.provider)
                    circuitBreaker.recordChannelSuccess(channel.id)
                    if (imageBase64 == null && history.isNullOrEmpty()) {
                        circuitBreaker.putCachedResponse(cacheKey, resultText)
                    }
                    return@withContext ProviderResponse(
                        text = resultText,
                        modelUsed = channel.displayName,
                        provider = channel.provider,
                        latencyMs = System.currentTimeMillis() - startTime
                    )
                }
            } catch (_: Exception) {
                AiUsageTracker.recordCall(context, channel.provider, false, System.currentTimeMillis() - chStart)
                circuitBreaker.recordChannelFailure(channel.id)
                continue
            }
        }

        // 3. Final Free Keyless Fallback if Free AI Pool & External Cloud Consent are enabled
        val freeGate = if (baseUrlOverrides.isEmpty() && channelKeyOverrides.isEmpty() && customHttpClient == null) {
            com.ayra.ai.app.security.SecureAiGatewayClient.evaluateProviderAccess(context, AiProviderType.POLLINATIONS_FREE).allowed
        } else {
            context?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: true
        }
        if (freeGate) {
            try {
                val fallbackText = queryPollinationsFallback(prompt, systemPrompt)
                if (fallbackText.isNotBlank()) {
                    return@withContext ProviderResponse(
                        text = fallbackText,
                        modelUsed = "Global Free AI Pool 🌐",
                        provider = AiProviderType.POLLINATIONS_FREE,
                        latencyMs = System.currentTimeMillis() - startTime
                    )
                }
            } catch (_: Exception) {}
        }

        throw ProviderError.NetworkFailure("All AI providers are currently unreachable. Please check your internet connection or API keys in Settings.")
    }

    suspend fun generateChatResponseWithReasoning(
        prompt: String,
        systemPrompt: String,
        history: List<ChatMessage>? = null,
        preferredProvider: AiProviderType? = null,
        preferredModelId: String? = null
    ): ProviderResponse = withContext(Dispatchers.IO) {
        val turns = buildConversationTurns(systemPrompt, history, prompt, null)
        val startTime = System.currentTimeMillis()

        val candidates = mutableListOf<Pair<AiProviderType, String>>()
        if (preferredProvider != null && isProviderConfigured(preferredProvider) && circuitBreaker.canExecute(preferredProvider)) {
            candidates.add(preferredProvider to (preferredModelId ?: getDefaultModelForProvider(preferredProvider)))
        }
        val reasoningFallbacks = listOf(
            AiProviderType.DEEPSEEK to "deepseek-reasoner",
            AiProviderType.SAMBANOVA to SAMBANOVA_DEEPSEEK_MODEL,
            AiProviderType.OPENROUTER to "deepseek/deepseek-r1:free",
            AiProviderType.GROQ to GROQ_QWEN_MODEL,
            AiProviderType.CEREBRAS to CEREBRAS_QWEN_MODEL,
            AiProviderType.POLLINATIONS_FREE to "deepseek"
        )
        reasoningFallbacks.forEach { pair ->
            if (candidates.none { it.first == pair.first && it.second == pair.second } &&
                isProviderConfigured(pair.first) && circuitBreaker.canExecute(pair.first)
            ) {
                candidates.add(pair)
            }
        }

        for ((provider, model) in candidates) {
            val key = getKeyForProviderAndModel(provider, model)
            if (key.isBlank() && provider != AiProviderType.LOCAL_OLLAMA && provider != AiProviderType.POLLINATIONS_FREE) continue
            val callStart = System.currentTimeMillis()
            try {
                val text = when (provider) {
                    AiProviderType.POLLINATIONS_FREE -> queryPollinationsFallback(prompt, systemPrompt, model, preserveReasoning = true)
                    AiProviderType.GEMINI, AiProviderType.ANTHROPIC, AiProviderType.OMNI_ROUTE ->
                        callSpecificProviderPublic(provider, model, turns, key, false)
                    else -> callOpenAiCompatible(provider, turns, model, key, maxTokens = 8192, preserveReasoning = true)
                }
                val latency = (System.currentTimeMillis() - callStart).coerceAtLeast(1L)
                if (text.isNotBlank()) {
                    AiUsageTracker.recordCall(context, provider, true, latency)
                    circuitBreaker.recordSuccess(provider)
                    return@withContext ProviderResponse(
                        text = text,
                        modelUsed = "${provider.displayName} ($model)",
                        provider = provider,
                        latencyMs = System.currentTimeMillis() - startTime
                    )
                }
            } catch (_: Exception) {
                AiUsageTracker.recordCall(context, provider, false, System.currentTimeMillis() - callStart)
            }
        }

        return@withContext generateChatResponse(
            prompt = prompt,
            systemPrompt = systemPrompt,
            history = history,
            preferredProvider = preferredProvider,
            preferredModelId = preferredModelId,
            withSearchGrounding = false
        )
    }

    /**
     * Streams tokens in real-time via Kotlin Flow.
     */
    fun streamChatResponse(
        prompt: String,
        systemPrompt: String,
        history: List<ChatMessage>? = null,
        imageBase64: String? = null,
        preferredProvider: AiProviderType? = null,
        preferredModelId: String? = null
    ): Flow<String> = flow {
        val turns = buildConversationTurns(systemPrompt, history, prompt, imageBase64)
        val provider = preferredProvider ?: getBestAvailableProvider()
        if (baseUrlOverrides.isEmpty() && channelKeyOverrides.isEmpty() && customHttpClient == null) {
            val gate = com.ayra.ai.app.security.SecureAiGatewayClient.evaluateProviderAccess(context, provider)
            if (!gate.allowed) {
                throw ProviderError.NetworkFailure(gate.denialReason ?: "Provider blocked by Zero-Trust policy.")
            }
        }
        val model = preferredModelId ?: getDefaultModelForProvider(provider)
        val key = getKeyForProviderAndModel(provider, model)

        when (provider) {
            AiProviderType.GEMINI -> streamGemini(turns, model, key, this)
            AiProviderType.NVIDIA_NIM, AiProviderType.GROQ, AiProviderType.CEREBRAS, AiProviderType.SAMBANOVA,
            AiProviderType.HUGGINGFACE, AiProviderType.GITHUB_MODELS, AiProviderType.OPENAI, AiProviderType.DEEPSEEK, AiProviderType.XAI,
            AiProviderType.MISTRAL, AiProviderType.PERPLEXITY, AiProviderType.OPENROUTER, AiProviderType.LOCAL_OLLAMA -> {
                val maxTok = if (provider == AiProviderType.NVIDIA_NIM && model.contains("minimax", true)) 8192 else 4096
                streamOpenAiCompatible(provider, turns, model, key, maxTok, this)
            }
            AiProviderType.ANTHROPIC -> streamAnthropic(turns, model, key, this)
            AiProviderType.POLLINATIONS_FREE -> {
                val text = queryPollinationsFallback(prompt, systemPrompt, model.ifBlank { "openai" })
                if (text.isBlank()) throw ProviderError.NetworkFailure("Free AI Pool unreachable")
                emit(text)
            }
            AiProviderType.OMNI_ROUTE -> streamGemini(turns, GEMINI_FLASH_MODEL, key, this)
        }
    }.flowOn(Dispatchers.IO)

    // --- Provider Call Dispatchers ---

    fun callSpecificProviderPublic(
        provider: AiProviderType,
        model: String,
        turns: List<ChatTurn>,
        apiKey: String,
        withSearchGrounding: Boolean
    ): String {
        return when (provider) {
            AiProviderType.GEMINI -> callGemini(turns, model, apiKey, withSearchGrounding)
            AiProviderType.NVIDIA_NIM, AiProviderType.GROQ, AiProviderType.CEREBRAS, AiProviderType.SAMBANOVA,
            AiProviderType.HUGGINGFACE, AiProviderType.GITHUB_MODELS, AiProviderType.OPENAI, AiProviderType.DEEPSEEK, AiProviderType.XAI,
            AiProviderType.MISTRAL, AiProviderType.PERPLEXITY, AiProviderType.OPENROUTER, AiProviderType.LOCAL_OLLAMA -> {
                val maxTok = if (provider == AiProviderType.NVIDIA_NIM && model.contains("minimax", true)) 8192 else 4096
                callOpenAiCompatible(provider, turns, model, apiKey, maxTok)
            }
            AiProviderType.ANTHROPIC -> callAnthropic(turns, model, apiKey)
            AiProviderType.POLLINATIONS_FREE -> {
                val sys = turns.firstOrNull { it.role == "system" }?.content ?: ""
                val usr = turns.lastOrNull { it.role == "user" }?.content ?: ""
                val res = queryPollinationsFallback(usr, sys, model.ifBlank { "openai" })
                if (res.isBlank()) throw ProviderError.NetworkFailure("Free AI Pool returned empty response")
                res
            }
            AiProviderType.OMNI_ROUTE -> {
                val geminiKey = context?.let { AyraPreferences.getEffectiveGeminiKey(it) } ?: ""
                if (geminiKey.isNotBlank()) {
                    callGemini(turns, GEMINI_FLASH_MODEL, geminiKey, withSearchGrounding)
                } else {
                    throw ProviderError.NoKeyConfigured(AiProviderType.GEMINI)
                }
            }
        }
    }

    fun callSpecificProvider(
        provider: AiProviderType,
        model: String,
        turns: List<ChatTurn>,
        apiKey: String,
        withSearchGrounding: Boolean = true
    ): String = callSpecificProviderPublic(provider, model, turns, apiKey, withSearchGrounding)

    // --- 1. Google Gemini Implementation ---

    internal fun resolveGeminiModel(model: String): String = Companion.resolveGeminiModel(model)

    private fun buildGeminiUrl(model: String, apiKey: String, stream: Boolean): String {
        val base = baseUrlOverrides[AiProviderType.GEMINI]?.let {
            if (it.endsWith("/")) it else "$it/"
        } ?: "https://generativelanguage.googleapis.com/v1beta/"
        val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        val encodedKey = if (apiKey.isNotBlank()) URLEncoder.encode(apiKey, "UTF-8") else ""
        return if (encodedKey.isNotBlank()) {
            val sep = if (action.contains("?")) "&" else "?"
            "${base}models/$model:$action${sep}key=$encodedKey"
        } else {
            "${base}models/$model:$action"
        }
    }

    private fun callGemini(
        turns: List<ChatTurn>,
        model: String,
        apiKey: String,
        withSearchGrounding: Boolean,
        visitedModels: Set<String> = emptySet()
    ): String {
        val actualModel = resolveGeminiModel(model)
        val allowGrounding = withSearchGrounding && !actualModel.startsWith("gemma")
        val url = buildGeminiUrl(actualModel, apiKey, stream = false)

        val requestJson = JSONObject().apply {
            val systemTurn = turns.firstOrNull { it.role == "system" }
            if (systemTurn != null && systemTurn.content.isNotBlank() && !actualModel.startsWith("gemma")) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemTurn.content)))
                })
            }

            val contentsArray = JSONArray()
            val conversationTurns = turns.filter { it.role != "system" }
            val prefixSystem = if (actualModel.startsWith("gemma") && systemTurn != null && systemTurn.content.isNotBlank()) {
                "${systemTurn.content}\n\n"
            } else ""

            conversationTurns.forEachIndexed { index, turn ->
                val geminiRole = if (turn.role == "assistant") "model" else "user"
                val partsArray = JSONArray()
                val textContent = if (index == 0 && geminiRole == "user" && prefixSystem.isNotEmpty()) {
                    "$prefixSystem${turn.content}"
                } else turn.content
                partsArray.put(JSONObject().put("text", textContent))

                if (!turn.imageBase64.isNullOrBlank()) {
                    partsArray.put(JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", turn.imageBase64)
                        })
                    })
                }

                contentsArray.put(JSONObject().apply {
                    put("role", geminiRole)
                    put("parts", partsArray)
                })
            }

            put("contents", contentsArray)

            if (allowGrounding) {
                put("tools", JSONArray().put(JSONObject().apply {
                    put("googleSearch", JSONObject())
                }))
            }
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))

        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("x-goog-api-key", apiKey)
        }

        return httpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 400 && allowGrounding) {
                return callGemini(turns, actualModel, apiKey, withSearchGrounding = false, visitedModels = visitedModels)
            }
            if (response.code == 404) {
                val nextModel = nextGeminiFallback(actualModel)
                if (nextModel != null && nextModel !in visitedModels) {
                    return callGemini(turns, nextModel, apiKey, withSearchGrounding, visitedModels + actualModel)
                }
            }
            if (response.code == 429) throw ProviderError.RateLimited("Gemini quota exceeded.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(AiProviderType.GEMINI)
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw ProviderError.ServerError(response.code, errBody.ifBlank { response.message })
            }

            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val contentObj = candidates.getJSONObject(0).optJSONObject("content")
                val parts = contentObj?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        val partObj = parts.optJSONObject(i) ?: continue
                        if (!partObj.isNull("text")) {
                            sb.append(partObj.optString("text", ""))
                        }
                    }
                    return sanitizeModelOutput(sb.toString())
                }
            }
            ""
        }
    }

    private suspend fun streamGemini(
        turns: List<ChatTurn>,
        model: String,
        apiKey: String,
        collector: kotlinx.coroutines.flow.FlowCollector<String>,
        visitedModels: Set<String> = emptySet()
    ) {
        val actualModel = resolveGeminiModel(model)
        val url = buildGeminiUrl(actualModel, apiKey, stream = true)
        val requestJson = JSONObject().apply {
            val systemTurn = turns.firstOrNull { it.role == "system" }
            if (systemTurn != null && systemTurn.content.isNotBlank() && !actualModel.startsWith("gemma")) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemTurn.content)))
                })
            }
            val contentsArray = JSONArray()
            val prefixSystem = if (actualModel.startsWith("gemma") && systemTurn != null && systemTurn.content.isNotBlank()) {
                "${systemTurn.content}\n\n"
            } else ""

            turns.filter { it.role != "system" }.forEachIndexed { index, turn ->
                val partsArray = JSONArray()
                val textContent = if (index == 0 && turn.role != "assistant" && prefixSystem.isNotEmpty()) {
                    "$prefixSystem${turn.content}"
                } else turn.content
                partsArray.put(JSONObject().put("text", textContent))
                if (!turn.imageBase64.isNullOrBlank()) {
                    partsArray.put(JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", turn.imageBase64)
                        })
                    })
                }
                contentsArray.put(JSONObject().apply {
                    put("role", if (turn.role == "assistant") "model" else "user")
                    put("parts", partsArray)
                })
            }
            put("contents", contentsArray)
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))

        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("x-goog-api-key", apiKey)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 404) {
                val nextModel = nextGeminiFallback(actualModel)
                if (nextModel != null && nextModel !in visitedModels) {
                    return streamGemini(turns, nextModel, apiKey, collector, visitedModels + actualModel)
                }
            }
            if (response.code == 429) throw ProviderError.RateLimited("Gemini quota exceeded.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(AiProviderType.GEMINI)
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw ProviderError.ServerError(response.code, errBody.ifBlank { response.message })
            }

            val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return, StandardCharsets.UTF_8))
            var line: String?
            var emittedAnySse = false
            val rawFallbackBuffer = StringBuilder()
            while (reader.readLine().also { line = it } != null) {
                val rawLine = line ?: continue
                val l = rawLine.trim()
                if (l.startsWith("data:")) {
                    val jsonStr = l.substring(5).trim()
                    if (jsonStr.isNotBlank() && jsonStr != "[DONE]") {
                        try {
                            val json = JSONObject(jsonStr)
                            val candidates = json.optJSONArray("candidates")
                            if (candidates != null && candidates.length() > 0) {
                                val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                                if (parts != null && parts.length() > 0) {
                                    for (i in 0 until parts.length()) {
                                        val part = parts.optJSONObject(i)
                                        val token = if (part != null && !part.isNull("text")) part.optString("text", "") else ""
                                        if (token.isNotEmpty()) {
                                            emittedAnySse = true
                                            collector.emit(token)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } else if (!emittedAnySse && l.isNotEmpty()) {
                    rawFallbackBuffer.append(rawLine).append('\n')
                }
            }

            if (!emittedAnySse && rawFallbackBuffer.isNotBlank()) {
                try {
                    val json = JSONObject(rawFallbackBuffer.toString().trim())
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val sb = StringBuilder()
                            for (i in 0 until parts.length()) {
                                val partObj = parts.optJSONObject(i) ?: continue
                                if (!partObj.isNull("text")) {
                                    sb.append(partObj.optString("text", ""))
                                }
                            }
                            val cleaned = sanitizeModelOutput(sb.toString())
                            if (cleaned.isNotEmpty()) collector.emit(cleaned)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // --- 2. OpenAI-Compatible Implementation (NVIDIA NIM, OpenAI, DeepSeek, xAI, Mistral, Perplexity, OpenRouter, Ollama) ---

    internal fun getBaseUrlForProvider(provider: AiProviderType): String {
        baseUrlOverrides[provider]?.let { custom ->
            return if (custom.endsWith("/")) custom else "$custom/"
        }
        return when (provider) {
            AiProviderType.NVIDIA_NIM -> "https://integrate.api.nvidia.com/v1/"
            AiProviderType.GROQ -> "https://api.groq.com/openai/v1/"
            AiProviderType.CEREBRAS -> "https://api.cerebras.ai/v1/"
            AiProviderType.SAMBANOVA -> "https://api.sambanova.ai/v1/"
            AiProviderType.HUGGINGFACE -> "https://router.huggingface.co/v1/"
            AiProviderType.GITHUB_MODELS -> "https://models.inference.ai.azure.com/"
            AiProviderType.OPENAI -> "https://api.openai.com/v1/"
            AiProviderType.DEEPSEEK -> "https://api.deepseek.com/"
            AiProviderType.XAI -> "https://api.x.ai/v1/"
            AiProviderType.MISTRAL -> "https://api.mistral.ai/v1/"
            AiProviderType.PERPLEXITY -> "https://api.perplexity.ai/"
            AiProviderType.OPENROUTER -> "https://openrouter.ai/api/v1/"
            AiProviderType.POLLINATIONS_FREE -> "https://text.pollinations.ai/"
            AiProviderType.LOCAL_OLLAMA -> {
                val custom = context?.let { AyraPreferences.getCustomEndpointUrl(it) } ?: ""
                if (custom.isNotBlank()) {
                    if (custom.endsWith("/")) custom else "$custom/"
                } else {
                    val host = context?.let { AyraPreferences.getOllamaHost(it) } ?: "http://10.0.2.2:11434"
                    if (host.endsWith("/")) "${host}v1/" else "$host/v1/"
                }
            }
            else -> "https://api.openai.com/v1/"
        }
    }

    private fun supportsMultimodalImageUrl(provider: AiProviderType, model: String): Boolean {
        if (provider == AiProviderType.OPENAI || provider == AiProviderType.OPENROUTER) return true
        if (provider == AiProviderType.NVIDIA_NIM) {
            return model.contains("minimax", ignoreCase = true) ||
                model.contains("gemma", ignoreCase = true) ||
                model.contains("vision", ignoreCase = true)
        }
        return false
    }

    private fun buildOpenAiMessages(provider: AiProviderType, model: String, turns: List<ChatTurn>): JSONArray {
        val messagesArray = JSONArray()
        val canAttachImage = supportsMultimodalImageUrl(provider, model)
        for (turn in turns) {
            messagesArray.put(JSONObject().apply {
                put("role", turn.role)
                if (!turn.imageBase64.isNullOrBlank() && canAttachImage) {
                    val contentParts = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", turn.content)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,${turn.imageBase64}")
                            })
                        })
                    }
                    put("content", contentParts)
                } else {
                    put("content", turn.content)
                }
            })
        }
        return messagesArray
    }

    private fun callOpenAiCompatible(
        provider: AiProviderType,
        turns: List<ChatTurn>,
        model: String,
        apiKey: String,
        maxTokens: Int = 4096,
        preserveReasoning: Boolean = false
    ): String {
        val baseUrl = getBaseUrlForProvider(provider)
        if (provider == AiProviderType.LOCAL_OLLAMA && customHttpClient == null && baseUrlOverrides.isEmpty()) {
            val check = com.ayra.ai.app.security.NetworkSecurityHelper.validateLocalAiEndpointUrl(context, baseUrl)
            if (!check.allowed) {
                throw ProviderError.NetworkFailure(check.reason ?: "Blocked unsafe local endpoint URL.")
            }
        }
        val endpoint = "${baseUrl}chat/completions"

        val messagesArray = buildOpenAiMessages(provider, model, turns)

        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("max_tokens", maxTokens)
            if (model.contains("reasoner") || model.contains("o1") || model.contains("o3")) {
                // Reasoning models use default reasoning budget
            } else {
                put("temperature", 0.65)
                put("top_p", 0.95)
            }
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))

        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        if (provider == AiProviderType.OPENROUTER) {
            reqBuilder.addHeader("HTTP-Referer", "https://ayra.ai")
            reqBuilder.addHeader("X-Title", "Ayra AI")
        }

        return resolveClientForProvider(provider).newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 429) throw ProviderError.RateLimited("${provider.displayName} rate limit reached.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(provider)
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw ProviderError.ServerError(response.code, errBody.ifBlank { response.message })
            }

            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                val rawContent = if (message != null && !message.isNull("content")) {
                    message.optString("content", "")
                } else ""
                val reasoningContent = if (message != null && !message.isNull("reasoning_content")) {
                    message.optString("reasoning_content", "").trim()
                } else ""
                if (preserveReasoning) {
                    if (reasoningContent.isNotBlank() && rawContent.isNotBlank() && !rawContent.contains("<think>", ignoreCase = true)) {
                        return "<think>\n$reasoningContent\n</think>\n\n${rawContent.trim()}"
                    }
                    if (rawContent.isNotBlank()) return rawContent.trim()
                    if (reasoningContent.isNotBlank()) return "<think>\n$reasoningContent\n</think>"
                }
                val cleaned = sanitizeModelOutput(rawContent)
                if (cleaned.isNotBlank()) return cleaned
                // If model placed answer only in reasoning_content because of strict budget
                if (reasoningContent.isNotBlank()) return sanitizeModelOutput(reasoningContent)
            }
            ""
        }
    }

    private suspend fun streamOpenAiCompatible(
        provider: AiProviderType,
        turns: List<ChatTurn>,
        model: String,
        apiKey: String,
        maxTokens: Int = 4096,
        collector: kotlinx.coroutines.flow.FlowCollector<String>
    ) {
        val baseUrl = getBaseUrlForProvider(provider)
        if (provider == AiProviderType.LOCAL_OLLAMA && customHttpClient == null && baseUrlOverrides.isEmpty()) {
            val check = com.ayra.ai.app.security.NetworkSecurityHelper.validateLocalAiEndpointUrl(context, baseUrl)
            if (!check.allowed) {
                throw ProviderError.NetworkFailure(check.reason ?: "Blocked unsafe local endpoint URL.")
            }
        }
        val endpoint = "${baseUrl}chat/completions"

        val messagesArray = buildOpenAiMessages(provider, model, turns)

        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("max_tokens", maxTokens)
            put("stream", true)
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .header("Accept", "text/event-stream")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))

        if (apiKey.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        if (provider == AiProviderType.OPENROUTER) {
            reqBuilder.addHeader("HTTP-Referer", "https://ayra.ai")
            reqBuilder.addHeader("X-Title", "Ayra AI")
        }

        resolveClientForProvider(provider).newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 429) throw ProviderError.RateLimited("${provider.displayName} rate limit reached.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(provider)
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: response.message
                throw ProviderError.ServerError(response.code, err.ifBlank { response.message })
            }
            val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return, StandardCharsets.UTF_8))
            var line: String?
            var inThinkBlock = false
            var emittedAnySse = false
            val rawFallbackBuffer = StringBuilder()
            while (reader.readLine().also { line = it } != null) {
                val rawLine = line ?: continue
                val l = rawLine.trim()
                if (l.startsWith("data:")) {
                    val data = l.substring(5).trim()
                    if (data == "[DONE]") break
                    if (data.isNotBlank()) {
                        try {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                val rawText = if (delta != null && !delta.isNull("content")) delta.optString("content", "") else ""
                                if (rawText.isNotEmpty()) {
                                    var chunk = rawText
                                    if (chunk.contains("<think>", ignoreCase = true)) {
                                        inThinkBlock = true
                                        chunk = chunk.substringBefore("<think>")
                                    }
                                    if (inThinkBlock) {
                                        if (chunk.contains("</think>", ignoreCase = true)) {
                                            inThinkBlock = false
                                            chunk = chunk.substringAfter("</think>")
                                        } else {
                                            chunk = ""
                                        }
                                    }
                                    if (chunk.isNotEmpty()) {
                                        emittedAnySse = true
                                        collector.emit(chunk)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } else if (!emittedAnySse && l.isNotEmpty()) {
                    rawFallbackBuffer.append(rawLine).append('\n')
                }
            }

            if (!emittedAnySse && rawFallbackBuffer.isNotBlank()) {
                try {
                    val json = JSONObject(rawFallbackBuffer.toString().trim())
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val message = choices.getJSONObject(0).optJSONObject("message")
                        val rawContent = if (message != null && !message.isNull("content")) {
                            message.optString("content", "")
                        } else ""
                        val cleaned = sanitizeModelOutput(rawContent).ifBlank {
                            val r = if (message != null && !message.isNull("reasoning_content")) {
                                message.optString("reasoning_content", "")
                            } else ""
                            sanitizeModelOutput(r)
                        }
                        if (cleaned.isNotEmpty()) collector.emit(cleaned)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // --- 3. Anthropic Claude Implementation ---

    private fun getAnthropicEndpoint(): String {
        val base = baseUrlOverrides[AiProviderType.ANTHROPIC]?.let {
            if (it.endsWith("/")) it else "$it/"
        } ?: "https://api.anthropic.com/v1/"
        return "${base}messages"
    }

    private fun callAnthropic(turns: List<ChatTurn>, model: String, apiKey: String): String {
        val url = getAnthropicEndpoint()
        val systemTurn = turns.firstOrNull { it.role == "system" }

        val messagesArray = JSONArray()
        for (turn in turns.filter { it.role != "system" }) {
            messagesArray.put(JSONObject().apply {
                put("role", if (turn.role == "assistant") "assistant" else "user")
                if (turn.imageBase64 != null) {
                    val contentArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", turn.imageBase64)
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", turn.content)
                        })
                    }
                    put("content", contentArr)
                } else {
                    put("content", turn.content)
                }
            })
        }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            if (systemTurn != null) put("system", systemTurn.content)
            put("messages", messagesArray)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (response.code == 429) throw ProviderError.RateLimited("Anthropic Claude rate limit reached.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(AiProviderType.ANTHROPIC)
            if (!response.isSuccessful) throw ProviderError.ServerError(response.code, response.message)

            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            val contentArr = json.optJSONArray("content")
            if (contentArr != null && contentArr.length() > 0) {
                val block = contentArr.getJSONObject(0)
                return if (!block.isNull("text")) block.optString("text", "") else ""
            }
            ""
        }
    }

    private suspend fun streamAnthropic(
        turns: List<ChatTurn>,
        model: String,
        apiKey: String,
        collector: kotlinx.coroutines.flow.FlowCollector<String>
    ) {
        val url = getAnthropicEndpoint()
        val systemTurn = turns.firstOrNull { it.role == "system" }

        val messagesArray = JSONArray()
        for (turn in turns.filter { it.role != "system" }) {
            messagesArray.put(JSONObject().apply {
                put("role", if (turn.role == "assistant") "assistant" else "user")
                put("content", turn.content)
            })
        }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            put("stream", true)
            if (systemTurn != null) put("system", systemTurn.content)
            put("messages", messagesArray)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 429) throw ProviderError.RateLimited("Anthropic Claude rate limit reached.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(AiProviderType.ANTHROPIC)
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: response.message
                throw ProviderError.ServerError(response.code, err.ifBlank { response.message })
            }
            val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return, StandardCharsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.startsWith("data:")) {
                    val data = l.substring(5).trim()
                    try {
                        val json = JSONObject(data)
                        if (json.optString("type") == "content_block_delta") {
                            val delta = json.optJSONObject("delta")
                            val text = if (delta != null && !delta.isNull("text")) delta.optString("text", "") else ""
                            if (text.isNotEmpty()) collector.emit(text)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // --- 4. Free Keyless Fallback (Global Free AI Pool) ---

    private fun queryPollinationsFallback(
        prompt: String,
        systemPrompt: String,
        preferredModel: String = "openai",
        preserveReasoning: Boolean = false
    ): String {
        val url = baseUrlOverrides[AiProviderType.POLLINATIONS_FREE]?.let {
            if (it.endsWith("/")) it else "$it/"
        } ?: "https://text.pollinations.ai/"
        val fastFreeClient = httpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
        val models = listOf(preferredModel, "openai-large", "openai", "mistral").distinct()
        for (m in models) {
            try {
                val jsonPayload = JSONObject().apply {
                    put("messages", JSONArray().apply {
                        if (systemPrompt.isNotBlank()) {
                            put(JSONObject().apply {
                                put("role", "system")
                                put("content", systemPrompt)
                            })
                        }
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                    put("model", m)
                }

                val req = Request.Builder()
                    .url(url)
                    .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", "AyraAi/3.1")
                    .build()

                val result = fastFreeClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val text = resp.body?.string()?.trim() ?: ""
                        if (text.length >= 2 && !text.contains("error code", ignoreCase = true) && !text.startsWith("<!DOCTYPE")) text else ""
                    } else ""
                }
                if (result.isNotBlank()) return if (preserveReasoning) result else sanitizeModelOutput(result)
            } catch (_: Exception) {}
        }
        return ""
    }

    // --- Provider Helpers & Status Checkers ---

    fun getKeyForProviderAndModel(provider: AiProviderType, modelId: String): String {
        if (provider == AiProviderType.NVIDIA_NIM) {
            val m = modelId.lowercase()
            val channelId = when {
                m.contains("minimax") -> "nvidia_minimax_m3_1"
                m.contains("glm") -> "nvidia_glm_5_3"
                m.contains("muse") -> "nvidia_muse_30b"
                m.contains("gemma") -> "nvidia_gemma_4_31b"
                else -> ""
            }
            if (channelId.isNotEmpty()) {
                channelKeyOverrides[channelId]?.takeIf { it.isNotBlank() }?.let { return it }
            }
            val ctx = context
            if (ctx != null) {
                AyraApplication.ensureActiveAiKeysSeeded(ctx)
                val resolved = when {
                    m.contains("minimax") -> AyraPreferences.getNvidiaMinimaxKey(ctx)
                    m.contains("glm") -> AyraPreferences.getNvidiaGlmKey(ctx)
                    m.contains("muse") -> AyraPreferences.getNvidiaMuseKey(ctx)
                    m.contains("gemma") -> AyraPreferences.getNvidiaGemmaKey(ctx)
                    else -> AyraPreferences.getNvidiaKey(ctx)
                }
                if (resolved.isNotBlank()) return resolved
            }
        }
        return getProviderKey(provider)
    }

    fun getProviderKey(provider: AiProviderType): String {
        channelKeyOverrides[provider.id]?.let { if (it.isNotBlank()) return it }
        if (provider == AiProviderType.NVIDIA_NIM) {
            val overriddenNim = channelKeyOverrides["nvidia_minimax_m3_1"]
                ?: channelKeyOverrides["nvidia_muse_30b"]
                ?: channelKeyOverrides["nvidia_glm_5_3"]
                ?: channelKeyOverrides["nvidia_gemma_4_31b"]
            if (!overriddenNim.isNullOrBlank()) return overriddenNim
        }
        val ctx = context ?: return ""
        AyraApplication.ensureActiveAiKeysSeeded(ctx)
        return when (provider) {
            AiProviderType.GEMINI -> AyraPreferences.getEffectiveGeminiKey(ctx)
            AiProviderType.NVIDIA_NIM -> AyraPreferences.getNvidiaMinimaxKey(ctx)
                .ifBlank { AyraPreferences.getNvidiaMuseKey(ctx) }
                .ifBlank { AyraPreferences.getNvidiaGlmKey(ctx) }
                .ifBlank { AyraPreferences.getNvidiaGemmaKey(ctx) }
                .ifBlank { AyraPreferences.getNvidiaKey(ctx) }
            AiProviderType.GROQ -> AyraPreferences.getGroqKey(ctx)
            AiProviderType.CEREBRAS -> AyraPreferences.getCerebrasKey(ctx)
            AiProviderType.SAMBANOVA -> AyraPreferences.getSambaNovaKey(ctx)
            AiProviderType.HUGGINGFACE -> AyraPreferences.getHuggingFaceKey(ctx)
            AiProviderType.GITHUB_MODELS -> AyraPreferences.getGitHubModelsKey(ctx)
            AiProviderType.OPENAI -> AyraPreferences.getOpenAiKey(ctx)
            AiProviderType.ANTHROPIC -> AyraPreferences.getAnthropicKey(ctx)
            AiProviderType.DEEPSEEK -> AyraPreferences.getDeepSeekKey(ctx)
            AiProviderType.XAI -> AyraPreferences.getXAiKey(ctx)
            AiProviderType.MISTRAL -> AyraPreferences.getMistralKey(ctx)
            AiProviderType.PERPLEXITY -> AyraPreferences.getPerplexityKey(ctx)
            AiProviderType.OPENROUTER -> AyraPreferences.getEffectiveOpenRouterKey(ctx)
            AiProviderType.LOCAL_OLLAMA -> {
                val url = AyraPreferences.getCustomEndpointUrl(ctx)
                if (url.isNotBlank()) AyraPreferences.getCustomEndpointKey(ctx).ifBlank { url } else ""
            }
            AiProviderType.POLLINATIONS_FREE -> if (AyraPreferences.isFreeAiPoolEnabled(ctx)) "free" else ""
            AiProviderType.OMNI_ROUTE -> AyraPreferences.getEffectiveGeminiKey(ctx)
        }
    }

    fun isProviderConfigured(provider: AiProviderType): Boolean {
        if (baseUrlOverrides.isEmpty() && channelKeyOverrides.isEmpty() && customHttpClient == null) {
            if (!com.ayra.ai.app.security.SecureAiGatewayClient.evaluateProviderAccess(context, provider).allowed) {
                return false
            }
        }
        if (provider == AiProviderType.OMNI_ROUTE) {
            return !(context != null && AyraPreferences.isLocalOnlyAiModeEnabled(context))
        }
        if (provider == AiProviderType.LOCAL_OLLAMA) {
            return (channelKeyOverrides[AiProviderType.LOCAL_OLLAMA.id]?.isNotBlank() == true) ||
                (context?.let {
                    AyraPreferences.getCustomEndpointUrl(it).isNotBlank() ||
                        AyraPreferences.hasExplicitOllamaHost(it)
                } ?: false)
        }
        if (provider == AiProviderType.POLLINATIONS_FREE) {
            if (channelKeyOverrides.isNotEmpty()) {
                return channelKeyOverrides.any { (k, v) -> k.startsWith("pollinations_free") && v.isNotBlank() }
            }
            return context?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: false
        }
        return getProviderKey(provider).isNotBlank()
    }

    fun getActiveConfiguredProviders(): List<AiProviderType> {
        val list = mutableListOf<AiProviderType>()
        if (isProviderConfigured(AiProviderType.GEMINI)) list.add(AiProviderType.GEMINI)
        if (isProviderConfigured(AiProviderType.GROQ)) list.add(AiProviderType.GROQ)
        if (isProviderConfigured(AiProviderType.CEREBRAS)) list.add(AiProviderType.CEREBRAS)
        if (isProviderConfigured(AiProviderType.SAMBANOVA)) list.add(AiProviderType.SAMBANOVA)
        if (isProviderConfigured(AiProviderType.NVIDIA_NIM)) list.add(AiProviderType.NVIDIA_NIM)
        if (isProviderConfigured(AiProviderType.GITHUB_MODELS)) list.add(AiProviderType.GITHUB_MODELS)
        if (isProviderConfigured(AiProviderType.HUGGINGFACE)) list.add(AiProviderType.HUGGINGFACE)
        if (isProviderConfigured(AiProviderType.OPENAI)) list.add(AiProviderType.OPENAI)
        if (isProviderConfigured(AiProviderType.ANTHROPIC)) list.add(AiProviderType.ANTHROPIC)
        if (isProviderConfigured(AiProviderType.DEEPSEEK)) list.add(AiProviderType.DEEPSEEK)
        if (isProviderConfigured(AiProviderType.PERPLEXITY)) list.add(AiProviderType.PERPLEXITY)
        if (isProviderConfigured(AiProviderType.XAI)) list.add(AiProviderType.XAI)
        if (isProviderConfigured(AiProviderType.MISTRAL)) list.add(AiProviderType.MISTRAL)
        if (isProviderConfigured(AiProviderType.OPENROUTER)) list.add(AiProviderType.OPENROUTER)
        if (isProviderConfigured(AiProviderType.LOCAL_OLLAMA)) list.add(AiProviderType.LOCAL_OLLAMA)
        if (isProviderConfigured(AiProviderType.POLLINATIONS_FREE)) list.add(AiProviderType.POLLINATIONS_FREE)
        return list
    }

    fun hasAnyConfiguredProvider(): Boolean = getActiveConfiguredProviders().isNotEmpty()

    fun getBestAvailableProvider(): AiProviderType {
        return getActiveConfiguredProviders().firstOrNull() ?: AiProviderType.GEMINI
    }

    fun getDefaultModelForProvider(provider: AiProviderType): String {
        return when (provider) {
            AiProviderType.GEMINI -> GEMINI_FLASH_MODEL
            AiProviderType.NVIDIA_NIM -> NVIDIA_MINIMAX_MODEL
            AiProviderType.GROQ -> GROQ_LLAMA_MODEL
            AiProviderType.CEREBRAS -> CEREBRAS_LLAMA_MODEL
            AiProviderType.SAMBANOVA -> SAMBANOVA_DEEPSEEK_MODEL
            AiProviderType.HUGGINGFACE -> HF_QWEN_MODEL
            AiProviderType.GITHUB_MODELS -> GITHUB_GPT4O_MODEL
            AiProviderType.OPENAI -> "gpt-4o"
            AiProviderType.ANTHROPIC -> "claude-3-7-sonnet-20250219"
            AiProviderType.DEEPSEEK -> "deepseek-chat"
            AiProviderType.XAI -> "grok-2-latest"
            AiProviderType.MISTRAL -> "mistral-large-latest"
            AiProviderType.PERPLEXITY -> "sonar-reasoning"
            AiProviderType.OPENROUTER -> "openrouter/auto"
            AiProviderType.LOCAL_OLLAMA -> "llama3.3"
            AiProviderType.POLLINATIONS_FREE -> "openai"
            AiProviderType.OMNI_ROUTE -> GEMINI_FLASH_MODEL
        }
    }

    /**
     * One-tap connection test for any provider.
     * Returns latency in milliseconds, or throws [ProviderError].
     */
    suspend fun testProviderConnection(
        provider: AiProviderType,
        customKey: String? = null,
        customModel: String? = null
    ): Long = withContext(Dispatchers.IO) {
        val model = customModel ?: getDefaultModelForProvider(provider)
        val key = customKey ?: getKeyForProviderAndModel(provider, model)
        if (key.isBlank() &&
            provider != AiProviderType.LOCAL_OLLAMA &&
            provider != AiProviderType.OMNI_ROUTE &&
            provider != AiProviderType.POLLINATIONS_FREE
        ) {
            throw ProviderError.NoKeyConfigured(provider)
        }

        val start = System.currentTimeMillis()
        try {
            val testTurns = listOf(ChatTurn("user", "Reply with just the single word: READY"))
            val response = callSpecificProvider(provider, model, testTurns, key, withSearchGrounding = false)
            if (response.isBlank()) throw ProviderError.NetworkFailure("Empty response received from ${provider.displayName}")
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            AiUsageTracker.recordCall(context, provider, true, elapsed)
            elapsed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AiUsageTracker.recordCall(context, provider, false, System.currentTimeMillis() - start)
            throw e
        }
    }

    /**
     * Intelligent Router: Selects optimal provider and model based on user routing mode.
     */
    fun getOptimalModelForRoutingMode(mode: AiRoutingMode): Pair<AiProviderType, String> {
        val configured = getActiveConfiguredProviders()
        return when (mode) {
            AiRoutingMode.FAST -> when {
                configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, GROQ_LLAMA_MODEL)
                configured.contains(AiProviderType.CEREBRAS) -> Pair(AiProviderType.CEREBRAS, CEREBRAS_LLAMA_MODEL)
                configured.contains(AiProviderType.GEMINI) -> Pair(AiProviderType.GEMINI, GEMINI_FLASH_MODEL)
                configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, NVIDIA_MINIMAX_MODEL)
                configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, SAMBANOVA_QWEN_MODEL)
                configured.contains(AiProviderType.OPENAI) -> Pair(AiProviderType.OPENAI, "gpt-4o-mini")
                configured.contains(AiProviderType.DEEPSEEK) -> Pair(AiProviderType.DEEPSEEK, "deepseek-chat")
                else -> Pair(AiProviderType.GEMINI, GEMINI_FLASH_MODEL)
            }
            AiRoutingMode.BEST_QUALITY -> when {
                configured.contains(AiProviderType.GEMINI) -> Pair(AiProviderType.GEMINI, GEMINI_PRO_MODEL)
                configured.contains(AiProviderType.ANTHROPIC) -> Pair(AiProviderType.ANTHROPIC, "claude-3-7-sonnet-20250219")
                configured.contains(AiProviderType.OPENAI) -> Pair(AiProviderType.OPENAI, "gpt-4o")
                configured.contains(AiProviderType.DEEPSEEK) -> Pair(AiProviderType.DEEPSEEK, "deepseek-reasoner")
                configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, SAMBANOVA_DEEPSEEK_MODEL)
                configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, NVIDIA_MUSE_MODEL)
                configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, GROQ_LLAMA_MODEL)
                else -> Pair(AiProviderType.GEMINI, GEMINI_FLASH_MODEL)
            }
            AiRoutingMode.REASONING -> when {
                configured.contains(AiProviderType.DEEPSEEK) -> Pair(AiProviderType.DEEPSEEK, "deepseek-reasoner")
                configured.contains(AiProviderType.GEMINI) -> Pair(AiProviderType.GEMINI, GEMINI_PRO_MODEL)
                configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, SAMBANOVA_DEEPSEEK_MODEL)
                configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, GROQ_QWEN_MODEL)
                configured.contains(AiProviderType.CEREBRAS) -> Pair(AiProviderType.CEREBRAS, CEREBRAS_QWEN_MODEL)
                configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, NVIDIA_GLM_MODEL)
                configured.contains(AiProviderType.OPENAI) -> Pair(AiProviderType.OPENAI, "o3-mini")
                configured.contains(AiProviderType.PERPLEXITY) -> Pair(AiProviderType.PERPLEXITY, "sonar-reasoning")
                else -> Pair(AiProviderType.OPENROUTER, "deepseek/deepseek-r1:free")
            }
            AiRoutingMode.CODING -> when {
                configured.contains(AiProviderType.ANTHROPIC) -> Pair(AiProviderType.ANTHROPIC, "claude-3-7-sonnet-20250219")
                configured.contains(AiProviderType.MISTRAL) -> Pair(AiProviderType.MISTRAL, "codestral-latest")
                configured.contains(AiProviderType.DEEPSEEK) -> Pair(AiProviderType.DEEPSEEK, "deepseek-chat")
                configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, SAMBANOVA_DEEPSEEK_MODEL)
                configured.contains(AiProviderType.GEMINI) -> Pair(AiProviderType.GEMINI, GEMINI_PRO_MODEL)
                configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, NVIDIA_GLM_MODEL)
                configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, GROQ_LLAMA_MODEL)
                configured.contains(AiProviderType.GITHUB_MODELS) -> Pair(AiProviderType.GITHUB_MODELS, GITHUB_GPT4O_MODEL)
                configured.contains(AiProviderType.HUGGINGFACE) -> Pair(AiProviderType.HUGGINGFACE, HF_QWEN_MODEL)
                configured.contains(AiProviderType.OPENAI) -> Pair(AiProviderType.OPENAI, "gpt-4o")
                else -> Pair(AiProviderType.OPENROUTER, "qwen/qwen-2.5-coder-32b-instruct")
            }
            AiRoutingMode.PRIVACY_LOCAL -> when {
                configured.contains(AiProviderType.LOCAL_OLLAMA) -> Pair(AiProviderType.LOCAL_OLLAMA, "llama3.3")
                configured.contains(AiProviderType.DEEPSEEK) -> Pair(AiProviderType.DEEPSEEK, "deepseek-chat")
                else -> Pair(AiProviderType.OPENROUTER, "meta-llama/llama-3.3-70b-instruct")
            }
            AiRoutingMode.COST_EFFICIENT -> when {
                configured.contains(AiProviderType.GEMINI) -> Pair(AiProviderType.GEMINI, GEMINI_FLASH_MODEL)
                configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, GROQ_LLAMA_MODEL)
                configured.contains(AiProviderType.CEREBRAS) -> Pair(AiProviderType.CEREBRAS, CEREBRAS_LLAMA_MODEL)
                configured.contains(AiProviderType.GITHUB_MODELS) -> Pair(AiProviderType.GITHUB_MODELS, GITHUB_GPT4O_MODEL)
                configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, SAMBANOVA_DEEPSEEK_MODEL)
                configured.contains(AiProviderType.POLLINATIONS_FREE) -> Pair(AiProviderType.POLLINATIONS_FREE, "openai")
                configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, NVIDIA_MINIMAX_MODEL)
                else -> Pair(AiProviderType.GEMINI, GEMINI_FLASH_LITE_MODEL)
            }
            AiRoutingMode.QWEN_UNCENSORED -> when {
                configured.contains(AiProviderType.LOCAL_OLLAMA) -> Pair(AiProviderType.LOCAL_OLLAMA, "orcarouter/Qwen3.8-27B-Uncensored:q4_k_m")
                configured.contains(AiProviderType.OPENROUTER) -> Pair(AiProviderType.OPENROUTER, "qwen/qwen-2.5-72b-instruct")
                configured.contains(AiProviderType.HUGGINGFACE) -> Pair(AiProviderType.HUGGINGFACE, HF_QWEN_MODEL)
                configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, GROQ_QWEN_MODEL)
                configured.contains(AiProviderType.CEREBRAS) -> Pair(AiProviderType.CEREBRAS, CEREBRAS_QWEN_MODEL)
                configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, SAMBANOVA_QWEN_MODEL)
                configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, NVIDIA_MUSE_MODEL)
                else -> Pair(AiProviderType.GEMINI, GEMINI_FLASH_MODEL)
            }
            AiRoutingMode.AUTO -> {
                val best = getBestAvailableProvider()
                Pair(best, getDefaultModelForProvider(best))
            }
        }
    }

    /**
     * Dynamic Model Discovery: queries live catalogues when online, falls back cleanly to curated list.
     */
    suspend fun discoverDynamicModels(provider: AiProviderType): List<AiModelInfo> = withContext(Dispatchers.IO) {
        dynamicModelCache[provider]?.let { if (it.isNotEmpty()) return@withContext it }
        if (baseUrlOverrides.isEmpty() &&
            channelKeyOverrides.isEmpty() &&
            customHttpClient == null &&
            provider != AiProviderType.LOCAL_OLLAMA &&
            !com.ayra.ai.app.security.SecureAiGatewayClient.evaluateProviderAccess(context, provider).allowed
        ) {
            return@withContext CURATED_MODELS.filter { it.provider == provider }
        }
        val key = getProviderKey(provider)
        val discovered = mutableListOf<AiModelInfo>()
        try {
            when (provider) {
                AiProviderType.GEMINI -> {
                    if (key.isNotBlank()) {
                        val url = "https://generativelanguage.googleapis.com/v1beta/models"
                        val req = Request.Builder().url(url).header("x-goog-api-key", key).get().build()
                        httpClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val root = JSONObject(resp.body?.string() ?: "")
                                val arr = root.optJSONArray("models") ?: JSONArray()
                                for (i in 0 until arr.length()) {
                                    val obj = arr.getJSONObject(i)
                                    val name = obj.optString("name", "").removePrefix("models/")
                                    val displayName = obj.optString("displayName", name)
                                    val desc = obj.optString("description", "")
                                    val inputLimit = obj.optInt("inputTokenLimit", 0)
                                    if (name.contains("gemini") || name.contains("gemma")) {
                                        discovered.add(
                                            AiModelInfo(
                                                id = name,
                                                displayName = displayName,
                                                provider = AiProviderType.GEMINI,
                                                description = desc.ifBlank { "Google official model" },
                                                contextWindow = if (inputLimit > 0) "${inputLimit / 1000}k tokens" else "1M tokens",
                                                supportsVision = true,
                                                isReasoning = name.contains("pro") || name.contains("gemma"),
                                                isDefault = name == GEMINI_FLASH_MODEL
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                AiProviderType.NVIDIA_NIM, AiProviderType.GROQ, AiProviderType.CEREBRAS, AiProviderType.SAMBANOVA,
                AiProviderType.HUGGINGFACE, AiProviderType.GITHUB_MODELS, AiProviderType.OPENAI, AiProviderType.DEEPSEEK, AiProviderType.XAI,
                AiProviderType.MISTRAL, AiProviderType.PERPLEXITY, AiProviderType.OPENROUTER -> {
                    val baseUrl = getBaseUrlForProvider(provider)
                    val url = "${baseUrl}models"
                    val reqBuilder = Request.Builder().url(url)
                    if (key.isNotBlank()) {
                        reqBuilder.addHeader("Authorization", "Bearer $key")
                    }
                    httpClient.newCall(reqBuilder.get().build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val root = JSONObject(resp.body?.string() ?: "")
                            val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
                            for (i in 0 until data.length()) {
                                val obj = data.getJSONObject(i)
                                val modelId = obj.optString("id", obj.optString("name", ""))
                                if (modelId.isNotBlank()) {
                                    val isVision = modelId.contains("vision") || modelId.contains("4o") || modelId.contains("pixtral") || modelId.contains("minimax")
                                    val isReasoning = modelId.contains("r1") || modelId.contains("o1") || modelId.contains("o3") || modelId.contains("reasoner") || modelId.contains("glm") || modelId.contains("gemma")
                                    discovered.add(
                                        AiModelInfo(
                                            id = modelId,
                                            displayName = modelId,
                                            provider = provider,
                                            description = "${provider.displayName} live model",
                                            contextWindow = "Adaptive",
                                            supportsVision = isVision,
                                            isReasoning = isReasoning,
                                            isDefault = false
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                AiProviderType.ANTHROPIC -> {
                    if (key.isNotBlank()) {
                        val req = Request.Builder()
                            .url("https://api.anthropic.com/v1/models")
                            .header("x-api-key", key)
                            .header("anthropic-version", "2023-06-01")
                            .get()
                            .build()
                        httpClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val root = JSONObject(resp.body?.string() ?: "")
                                val data = root.optJSONArray("data") ?: JSONArray()
                                for (i in 0 until data.length()) {
                                    val obj = data.getJSONObject(i)
                                    val modelId = obj.optString("id", "")
                                    val displayName = obj.optString("display_name", modelId)
                                    if (modelId.isNotBlank()) {
                                        discovered.add(
                                            AiModelInfo(
                                                id = modelId,
                                                displayName = displayName,
                                                provider = AiProviderType.ANTHROPIC,
                                                description = "Anthropic Claude model",
                                                contextWindow = "200k tokens",
                                                supportsVision = true,
                                                isReasoning = modelId.contains("3-7") || modelId.contains("sonnet"),
                                                isDefault = modelId.contains("3-7-sonnet")
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {}

        if (discovered.isNotEmpty()) {
            dynamicModelCache[provider] = discovered
            discovered
        } else {
            CURATED_MODELS.filter { it.provider == provider }
        }
    }

    /**
     * Genuine Whisper Speech-to-Text fallback using configured Groq (`whisper-large-v3-turbo`),
     * SambaNova (`Whisper-Large-v3`), or OpenAI (`whisper-1`) `/audio/transcriptions`.
     */
    suspend fun transcribeAudioOpenAiCompatible(
        audioFile: File,
        promptHint: String = ""
    ): String = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() <= 0L) return@withContext ""
        val candidates = listOf(
            Triple(AiProviderType.GROQ, GROQ_WHISPER_MODEL, getProviderKey(AiProviderType.GROQ)),
            Triple(AiProviderType.SAMBANOVA, SAMBANOVA_WHISPER_MODEL, getProviderKey(AiProviderType.SAMBANOVA)),
            Triple(AiProviderType.OPENAI, "whisper-1", getProviderKey(AiProviderType.OPENAI))
        ).filter { it.third.isNotBlank() }

        for ((provider, model, key) in candidates) {
            if (customHttpClient == null && !com.ayra.ai.app.security.SecureAiGatewayClient.evaluateProviderAccess(context, provider).allowed) {
                continue
            }
            val start = System.currentTimeMillis()
            try {
                val baseUrl = getBaseUrlForProvider(provider)
                val endpoint = "${baseUrl}audio/transcriptions"
                val mime = if (audioFile.name.endsWith(".wav", true)) "audio/wav" else "audio/mp4"
                val bodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mime.toMediaType()))
                    .addFormDataPart("model", model)
                if (promptHint.isNotBlank()) {
                    bodyBuilder.addFormDataPart("prompt", promptHint)
                }
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $key")
                    .post(bodyBuilder.build())
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    if (resp.isSuccessful && raw.isNotBlank()) {
                        val text = runCatching { JSONObject(raw).optString("text", "") }.getOrDefault("")
                        if (text.isNotBlank()) {
                            AiUsageTracker.recordCall(context, provider, true, latency)
                            return@withContext text.trim()
                        }
                    }
                    AiUsageTracker.recordCall(context, provider, false, latency)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                AiUsageTracker.recordCall(context, provider, false, System.currentTimeMillis() - start)
            }
        }
        ""
    }
}
