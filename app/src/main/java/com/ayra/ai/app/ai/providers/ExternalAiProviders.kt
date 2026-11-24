package com.ayra.ai.app.ai.providers

import android.content.Context
import com.ayra.ai.app.AyraApplication
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.security.BlockedByPolicyException
import com.ayra.ai.app.security.NetworkSecurityHelper
import com.ayra.ai.app.security.NetworkSecurityHelper.readBounded
import com.ayra.ai.app.security.SecureAiGatewayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Unified Multi-AI Provider Engine for Ayra AI.
 * Coordinates Gemini, NVIDIA NIM, Groq, Cerebras, SambaNova, HuggingFace, GitHub Models,
 * OpenAI, Anthropic Claude, DeepSeek, OpenRouter, xAI, Mistral, Perplexity, Local Ollama,
 * and Pollinations Free AI Pool via modular transport layers.
 */
class ExternalAiProviders @JvmOverloads constructor(
    private val context: Context? = null,
    private val baseUrlOverrides: Map<AiProviderType, String> = emptyMap(),
    private val customHttpClient: OkHttpClient? = null,
    private val channelKeyOverrides: Map<String, String> = emptyMap()
) {

    data class OpenAiToolCallSpec(
        val id: String,
        val name: String,
        val argumentsJson: String
    )

    data class OpenAiToolTurnResponse(
        val content: String = "",
        val toolCalls: List<OpenAiToolCallSpec> = emptyList()
    )

    private fun resolveClientForProvider(provider: AiProviderType?): OkHttpClient {
        if (customHttpClient != null) return customHttpClient
        return if (provider == AiProviderType.LOCAL_OLLAMA) {
            NetworkSecurityHelper.localLanClient
        } else {
            NetworkSecurityHelper.hardenedPooledClient
        }
    }

    private val httpClient: OkHttpClient
        get() = customHttpClient ?: NetworkSecurityHelper.hardenedPooledClient

    val circuitBreaker = ProviderCircuitBreaker()

    val isTestHarnessMode: Boolean
        get() = baseUrlOverrides.isNotEmpty() ||
            channelKeyOverrides.isNotEmpty() ||
            customHttpClient != null

    val allowExternalWikiGrounding: Boolean
        get() {
            val isLocalOnly = context?.let { AyraPreferences.isLocalOnlyAiModeEnabled(it) } ?: false
            val hasExternalConsent = context?.let { AyraPreferences.hasExternalAiConsent(it) } ?: false
            return !isTestHarnessMode &&
                !isLocalOnly &&
                hasExternalConsent
        }

    companion object {
        val sharedPooledHttpClient: OkHttpClient
            get() = NetworkSecurityHelper.hardenedPooledClient

        val sharedFastWebClient: OkHttpClient by lazy {
            NetworkSecurityHelper.hardenedPooledClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build()
        }

        const val GEMINI_FLASH_MODEL = ModelCatalog.GEMINI_FLASH_MODEL
        const val GEMINI_PRO_MODEL = ModelCatalog.GEMINI_PRO_MODEL
        const val GEMINI_FLASH_LITE_MODEL = ModelCatalog.GEMINI_FAST_LITE_MODEL
        const val GEMINI_GEMMA4_MODEL = ModelCatalog.GEMINI_GEMMA4_MODEL

        const val OPENAI_DEFAULT_MODEL = ModelCatalog.OPENAI_DEFAULT_MODEL
        const val OPENAI_REASONING_MODEL = ModelCatalog.OPENAI_REASONING_MODEL
        const val OPENAI_MINI_MODEL = ModelCatalog.OPENAI_MINI_MODEL

        const val ANTHROPIC_SONNET_MODEL = ModelCatalog.ANTHROPIC_SONNET_MODEL
        const val ANTHROPIC_OPUS_MODEL = ModelCatalog.ANTHROPIC_OPUS_MODEL
        const val ANTHROPIC_HAIKU_MODEL = ModelCatalog.ANTHROPIC_HAIKU_MODEL

        const val DEEPSEEK_CHAT_MODEL = ModelCatalog.DEEPSEEK_CHAT_MODEL
        const val DEEPSEEK_REASONER_MODEL = ModelCatalog.DEEPSEEK_REASONER_MODEL

        const val XAI_DEFAULT_MODEL = ModelCatalog.XAI_DEFAULT_MODEL
        const val MISTRAL_LARGE_MODEL = ModelCatalog.MISTRAL_LARGE_MODEL
        const val MISTRAL_CODESTRAL_MODEL = ModelCatalog.MISTRAL_CODESTRAL_MODEL
        const val PERPLEXITY_REASONING_MODEL = ModelCatalog.PERPLEXITY_REASONING_MODEL

        const val OPENROUTER_DEFAULT_MODEL = ModelCatalog.OPENROUTER_DEFAULT_MODEL
        const val OPENROUTER_AUTO_MODEL = ModelCatalog.OPENROUTER_AUTO_MODEL
        const val OPENROUTER_QWEN_FREE_MODEL = ModelCatalog.OPENROUTER_QWEN_FREE_MODEL
        const val OPENROUTER_CODER_MODEL = ModelCatalog.OPENROUTER_CODER_MODEL

        const val NVIDIA_LLAMA_MODEL = ModelCatalog.NVIDIA_LLAMA_MODEL
        const val NVIDIA_MINIMAX_MODEL = ModelCatalog.NVIDIA_MINIMAX_MODEL
        const val NVIDIA_GLM_MODEL = ModelCatalog.NVIDIA_GLM_MODEL
        const val NVIDIA_MUSE_MODEL = ModelCatalog.NVIDIA_MUSE_MODEL
        const val NVIDIA_GEMMA_MODEL = ModelCatalog.NVIDIA_GEMMA_MODEL

        const val GROQ_LLAMA_MODEL = ModelCatalog.GROQ_LLAMA_MODEL
        const val GROQ_QWEN_MODEL = ModelCatalog.GROQ_QWEN_MODEL
        const val GROQ_GPT_OSS_MODEL = ModelCatalog.GROQ_GPT_OSS_MODEL
        const val GROQ_DEFAULT_MODEL = GROQ_QWEN_MODEL
        const val GROQ_WHISPER_MODEL = ModelCatalog.GROQ_WHISPER_MODEL
        const val CEREBRAS_LLAMA_MODEL = ModelCatalog.CEREBRAS_LLAMA_MODEL
        const val CEREBRAS_QWEN_MODEL = ModelCatalog.CEREBRAS_QWEN_MODEL
        const val SAMBANOVA_DEEPSEEK_MODEL = ModelCatalog.SAMBANOVA_DEEPSEEK_MODEL
        const val SAMBANOVA_LLAMA_MODEL = ModelCatalog.SAMBANOVA_LLAMA_MODEL
        const val SAMBANOVA_QWEN_MODEL = ModelCatalog.SAMBANOVA_QWEN_MODEL
        const val SAMBANOVA_WHISPER_MODEL = ModelCatalog.SAMBANOVA_WHISPER_MODEL
        const val HF_QWEN_MODEL = ModelCatalog.HF_QWEN_MODEL
        const val GITHUB_RETIRED_MODEL = ModelCatalog.GITHUB_RETIRED_MODEL

        private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
        private val dynamicModelCache = ConcurrentHashMap<AiProviderType, List<AiModelInfo>>()

        @JvmStatic
        fun clearDynamicModelCache(provider: AiProviderType? = null) {
            if (provider == null) {
                dynamicModelCache.clear()
            } else {
                dynamicModelCache.remove(provider)
            }
        }

        fun resolveGeminiModel(model: String): String {
            val clean = model.trim().lowercase().removePrefix("models/")
            return when {
                clean.isBlank() -> GEMINI_FLASH_MODEL
                clean == GEMINI_FLASH_MODEL || clean == "gemini_flash" || clean == "gemini-flash" ||
                    clean == "gemini-flash-2.0" || clean == "gemini-flash-latest" ||
                    clean.matches(Regex("^gemini-(2\\.5|2\\.0)-flash$")) -> GEMINI_FLASH_MODEL
                clean == GEMINI_PRO_MODEL || clean == "gemini_pro" || clean == "gemini-pro" ||
                    clean == "gemini-pro-1.5" || clean == "gemini-pro-latest" ||
                    clean.matches(Regex("^gemini-(2\\.5|1\\.5)-pro$")) -> GEMINI_PRO_MODEL
                clean == GEMINI_FLASH_LITE_MODEL || clean == "gemini_flash_lite" || clean == "gemini-flash-lite" ||
                    clean == "gemini_1_5_flash" ||
                    clean.matches(Regex("^gemini-(2\\.5|2\\.0)-flash-lite$")) ||
                    clean.matches(Regex("^gemini-1\\.5-flash$")) -> GEMINI_FLASH_LITE_MODEL
                else -> clean
            }
        }

        fun sanitizeModelOutput(raw: String): String {
            if (raw.isBlank()) return ""
            var cleaned = raw.replace(THINK_BLOCK_REGEX, "").trim()
            if (cleaned.isBlank()) {
                cleaned = raw.replace(Regex("</?think>", RegexOption.IGNORE_CASE), "").trim()
            }
            return cleaned
        }

        val CURATED_MODELS = listOf(
            AiModelInfo(GEMINI_FLASH_MODEL, "Gemini 3.8 Flash ⚡", AiProviderType.GEMINI, "Ultra-fast multimodal flagship with web grounding", "1M tokens", true, false, true),
            AiModelInfo(GEMINI_PRO_MODEL, "Gemini 4 Argon 🔬", AiProviderType.GEMINI, "Frontier deep reasoning, code architecture & STEM synthesis", "2M tokens", true, true, false),
            AiModelInfo(GEMINI_FLASH_LITE_MODEL, "Gemini 3.5 Flash-Lite 🚀", AiProviderType.GEMINI, "High-throughput low-latency conversational engine", "1M tokens", true, false, false),
            AiModelInfo(GEMINI_GEMMA4_MODEL, "Gemma 4 31B IT 🌐", AiProviderType.GEMINI, "Google Gemma 4 open-weights multilingual reasoning", "128k tokens", true, true, false),
            AiModelInfo(GROQ_QWEN_MODEL, "Groq Qwen 3.8 27B 🧠", AiProviderType.GROQ, "High-speed STEM, coding & multilingual reasoning on Groq LPU", "131k tokens", false, true, true),
            AiModelInfo(GROQ_GPT_OSS_MODEL, "Groq GPT-OSS 120B ⚡", AiProviderType.GROQ, "Open-weights 120B flagship on Groq LPU free developer tier", "131k tokens", false, true, false),
            AiModelInfo(GROQ_LLAMA_MODEL, "Groq Llama 3.1 8B Instant 🚀", AiProviderType.GROQ, "Ultra-low-latency fast conversational engine on Groq LPU", "128k tokens", false, false, false),
            AiModelInfo(CEREBRAS_LLAMA_MODEL, "Cerebras GPT-OSS 120B 🚀", AiProviderType.CEREBRAS, "Wafer-scale high-speed 120B inference on free developer tier", "128k tokens", false, true, true),
            AiModelInfo(CEREBRAS_QWEN_MODEL, "Cerebras Qwen 3.8 27B ⚡", AiProviderType.CEREBRAS, "Wafer-scale STEM, math & coding reasoning", "128k tokens", false, true, false),
            AiModelInfo(SAMBANOVA_DEEPSEEK_MODEL, "SambaNova DeepSeek V3.1 🔬", AiProviderType.SAMBANOVA, "Fast RDU inference for coding, math & deep reasoning", "128k tokens", false, true, false),
            AiModelInfo(SAMBANOVA_QWEN_MODEL, "SambaNova Qwen3 32B 🌐", AiProviderType.SAMBANOVA, "Fast RDU multilingual & STEM reasoning", "64k tokens", false, true, false),
            AiModelInfo(HF_QWEN_MODEL, "HF Qwen 2.5 72B Instruct 🤗", AiProviderType.HUGGINGFACE, "Open-weights Hugging Face Inference Router", "128k tokens", false, true, false),
            AiModelInfo(NVIDIA_LLAMA_MODEL, "NVIDIA Llama 3.3 70B Instruct 🦙", AiProviderType.NVIDIA_NIM, "Verified general, coding & multilingual NIM baseline", "128k tokens", false, true, true),
            AiModelInfo(NVIDIA_MINIMAX_MODEL, "MiniMax M3.1 Flash ⚡", AiProviderType.NVIDIA_NIM, "High-speed multimodal vision & coding intelligence via NVIDIA NIM", "128k tokens", true, true, false),
            AiModelInfo(NVIDIA_GLM_MODEL, "Z-AI GLM 5.3 Flash 🧠", AiProviderType.NVIDIA_NIM, "Fast STEM, mathematical reasoning & multilingual synthesis", "128k tokens", false, true, false),
            AiModelInfo(NVIDIA_MUSE_MODEL, "Meta Muse Glimmer 30B ✨", AiProviderType.NVIDIA_NIM, "Fluent creative writing, Bengali/Banglish & deep reasoning", "128k tokens", false, true, false),
            AiModelInfo(NVIDIA_GEMMA_MODEL, "Gemma 4 31B IT (NIM) 🔬", AiProviderType.NVIDIA_NIM, "Multilingual science, coding & analytical verification", "128k tokens", true, true, false),
            AiModelInfo(OPENAI_DEFAULT_MODEL, "GPT-6.2 Sol 🧠", AiProviderType.OPENAI, "OpenAI frontier multimodal flagship intelligence", "256k tokens", true, true, true),
            AiModelInfo(OPENAI_REASONING_MODEL, "GPT-6.2 Astra 🔬", AiProviderType.OPENAI, "Deep STEM, formal verification & reasoning model", "256k tokens", false, true, false),
            AiModelInfo(OPENAI_MINI_MODEL, "GPT-6 Luna ⚡", AiProviderType.OPENAI, "Ultra-fast daily conversational & coding model", "128k tokens", true, false, false),
            AiModelInfo(ANTHROPIC_SONNET_MODEL, "Claude Sonnet 5.5 🎭", AiProviderType.ANTHROPIC, "Hybrid reasoning & software architecture flagship", "200k tokens", true, true, true),
            AiModelInfo(ANTHROPIC_OPUS_MODEL, "Claude Opus 5.5 👑", AiProviderType.ANTHROPIC, "Highest-capability Claude frontier synthesis & engineering", "200k tokens", true, true, false),
            AiModelInfo(ANTHROPIC_HAIKU_MODEL, "Claude Haiku 5.5 ⚡", AiProviderType.ANTHROPIC, "Fast coding, analysis & concise synthesis", "200k tokens", true, false, false),
            AiModelInfo(DEEPSEEK_CHAT_MODEL, "DeepSeek Flash 🚀", AiProviderType.DEEPSEEK, "Fast conversational & full-stack coding intelligence", "128k tokens", false, false, false),
            AiModelInfo(DEEPSEEK_REASONER_MODEL, "DeepSeek V4 Pro 🔬", AiProviderType.DEEPSEEK, "Deep mathematical proof, formal code & step-by-step CoT", "128k tokens", false, true, true),
            AiModelInfo(XAI_DEFAULT_MODEL, "Grok 4.7 🤖", AiProviderType.XAI, "Frontier reasoning & real-time analysis by xAI", "256k tokens", true, true, true),
            AiModelInfo(MISTRAL_LARGE_MODEL, "Mistral Large 🌐", AiProviderType.MISTRAL, "European multilingual intelligence", "128k tokens", false, false, false),
            AiModelInfo(MISTRAL_CODESTRAL_MODEL, "Codestral 💻", AiProviderType.MISTRAL, "Dedicated programming & refactoring", "256k tokens", false, false, false),
            AiModelInfo(PERPLEXITY_REASONING_MODEL, "Perplexity Sonar Pro 🔎", AiProviderType.PERPLEXITY, "Web-grounded reasoning research with citations", "128k tokens", false, true, true),
            AiModelInfo(OPENROUTER_DEFAULT_MODEL, "OpenRouter Free Router 🔀", AiProviderType.OPENROUTER, "Dynamic zero-cost open-weights router", "128k tokens", true, false, true),
            AiModelInfo(OPENROUTER_QWEN_FREE_MODEL, "Qwen 3.8 27B Free 🐉", AiProviderType.OPENROUTER, "Multilingual STEM & reasoning on OpenRouter free tier", "128k tokens", false, true, false),
            AiModelInfo(OPENROUTER_CODER_MODEL, "Qwen3 Coder Free 💻", AiProviderType.OPENROUTER, "Coding and bug-fixing specialist", "128k tokens", false, false, false),
            AiModelInfo(OPENROUTER_AUTO_MODEL, "OpenRouter Auto ⚡", AiProviderType.OPENROUTER, "Adaptive frontier & open-weights gateway", "128k tokens", true, false, false),
            AiModelInfo(ModelCatalog.OLLAMA_QWEN_MODEL, "Local Ollama Qwen 2.5 14B 🌐", AiProviderType.LOCAL_OLLAMA, "Local/LAN open-weights Qwen model", "32k tokens", false, true, false),
            AiModelInfo(ModelCatalog.OLLAMA_LLAMA_MODEL, "Local Ollama / LM Studio 🏠", AiProviderType.LOCAL_OLLAMA, "Zero-cloud local LLM on your LAN", "32k tokens", false, false, true),
            AiModelInfo(ModelCatalog.POLLINATIONS_OPENAI_MODEL, "Global Free AI Pool (GPT/OSS) 🌐", AiProviderType.POLLINATIONS_FREE, "Keyless free cloud AI fallback & ensemble backup", "32k tokens", false, false, false),
            AiModelInfo(ModelCatalog.POLLINATIONS_LARGE_MODEL, "Global Free Flagship (Large) ⚡", AiProviderType.POLLINATIONS_FREE, "High-reasoning keyless ensemble channel", "64k tokens", false, true, false),
            AiModelInfo(ModelCatalog.POLLINATIONS_CODER_MODEL, "Global Free Qwen Coder 💻", AiProviderType.POLLINATIONS_FREE, "Keyless coding & architecture specialist", "32k tokens", false, false, false),
            AiModelInfo(ModelCatalog.POLLINATIONS_DEEPSEEK_MODEL, "Global Free DeepSeek Reasoning 🧠", AiProviderType.POLLINATIONS_FREE, "Keyless STEM, math & step-by-step logic channel", "64k tokens", false, true, false)
        )
    }

    init {
        if (context != null) {
            AyraApplication.ensureActiveAiKeysSeeded(context)
        }
    }

    fun getActiveChannels(
        domainName: String = "GENERAL_ASSISTANT",
        hasImage: Boolean = false
    ): List<AiModelChannel> {
        val ctx = context
        if (ctx != null) AyraApplication.ensureActiveAiKeysSeeded(ctx)
        val allDefinitions = DefaultAiChannelRegistry.buildAllDefinitions(domainName, channelKeyOverrides)
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
            if (channel.provider == AiProviderType.GITHUB_MODELS && !baseUrlOverrides.containsKey(AiProviderType.GITHUB_MODELS)) {
                return@filter false
            }
            if (!isTestHarnessMode && !SecureAiGatewayClient.evaluateProviderAccess(ctx, channel.provider).allowed) {
                return@filter false
            }
            val key = resolveChannelKey(channel, ctx)
            val keyOk = if (channel.provider == AiProviderType.LOCAL_OLLAMA) {
                (channelKeyOverrides[channel.id]?.isNotBlank() == true) ||
                    (ctx != null && (AyraPreferences.getCustomEndpointUrl(ctx).isNotBlank() || AyraPreferences.hasExplicitOllamaHost(ctx)))
            } else {
                key.isNotBlank()
            }
            val breakerOk = circuitBreaker.canExecute(channel.provider) && circuitBreaker.canExecuteChannel(channel.id)
            keyOk && breakerOk
        }

        return active.sortedWith(
            compareByDescending<AiModelChannel> { if (hasImage && it.supportsVision) 10 else if (hasImage) -10 else 0 }
                .thenByDescending { if (it.specialties.contains(desiredSpecialty)) 6 else 0 }
                .thenByDescending { if (ModelCatalog.isVerifiedOrConfigured(it.provider, it.modelId)) 4 else 0 }
                .thenByDescending { if (it.id == "gemini_3_8_flash" || it.id == "nvidia_llama_3_3_70b") 3 else 0 }
                .thenByDescending { if (it.provider == AiProviderType.POLLINATIONS_FREE) -5 else 1 }
        )
    }

    private fun resolveChannelKey(channel: AiModelChannel, ctx: Context?): String {
        channelKeyOverrides[channel.id]?.takeIf { it.isNotBlank() }?.let { return it }
        if (channel.provider != AiProviderType.POLLINATIONS_FREE) {
            channelKeyOverrides[channel.provider.id]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return channel.keyResolver(ctx)
    }

    fun buildConversationTurns(
        systemPrompt: String,
        history: List<ChatMessage>?,
        currentPrompt: String,
        currentImageBase64: String? = null,
        maxPreviousTurns: Int = 12
    ): List<ChatTurn> {
        val turns = mutableListOf<ChatTurn>()
        if (systemPrompt.isNotBlank()) turns.add(ChatTurn(role = "system", content = systemPrompt))
        if (!history.isNullOrEmpty()) {
            history.filter { it.content.isNotBlank() && (it.role == "user" || it.role == "assistant") }
                .takeLast(maxPreviousTurns)
                .forEach { msg ->
                    turns.add(ChatTurn(role = if (msg.role == "assistant") "assistant" else "user", content = msg.content))
                }
        }
        turns.add(ChatTurn(role = "user", content = currentPrompt, imageBase64 = currentImageBase64))
        return turns
    }

    fun callChannel(channel: AiModelChannel, turns: List<ChatTurn>, withSearchGrounding: Boolean = true): String {
        val key = resolveChannelKey(channel, context)
        return when (channel.provider) {
            AiProviderType.GEMINI -> ProviderGeminiTransport.callGemini(
                httpClient = httpClient,
                baseUrlOverride = baseUrlOverrides[AiProviderType.GEMINI],
                turns = turns,
                model = channel.modelId,
                apiKey = key,
                withSearchGrounding = withSearchGrounding,
                hasCustomClient = customHttpClient != null
            )
            AiProviderType.ANTHROPIC -> ProviderAnthropicAndFreeTransport.callAnthropic(
                httpClient = httpClient,
                baseUrlOverrides = baseUrlOverrides,
                turns = turns,
                model = channel.modelId,
                apiKey = key,
                hasCustomClient = customHttpClient != null
            )
            AiProviderType.POLLINATIONS_FREE -> {
                val polKey = key.takeIf { it.isNotBlank() && !it.equals("free", ignoreCase = true) }
                    ?: context?.let { AyraPreferences.getPollinationsKey(it) }.orEmpty()
                ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                    httpClient = httpClient,
                    baseUrlOverrides = baseUrlOverrides,
                    turns = turns,
                    preferredModel = channel.modelId,
                    apiKey = polKey
                )
            }
            AiProviderType.OMNI_ROUTE -> {
                val gKey = channelKeyOverrides[AiProviderType.GEMINI.id]
                    ?: context?.let { AyraPreferences.getEffectiveGeminiKey(it) } ?: ""
                if (gKey.isNotBlank()) {
                    ProviderGeminiTransport.callGemini(
                        httpClient = httpClient,
                        baseUrlOverride = baseUrlOverrides[AiProviderType.GEMINI],
                        turns = turns,
                        model = GEMINI_FLASH_MODEL,
                        apiKey = gKey,
                        withSearchGrounding = withSearchGrounding,
                        hasCustomClient = customHttpClient != null
                    )
                } else throw ProviderError.NoKeyConfigured(AiProviderType.GEMINI)
            }
            else -> ProviderOpenAiTransport.callOpenAiCompatible(
                resolveClientForProvider(channel.provider), context, channel.provider, baseUrlOverrides,
                customHttpClient != null, turns, channel.modelId, key, channel.maxTokens
            )
        }
    }

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
        val cacheKey = "${prompt.trim()}|${systemPrompt.take(120)}|${preferredProvider?.id}|$preferredModelId"
        val allowResponseCache = imageBase64 == null && history.isNullOrEmpty() && !ProviderPool.isTimeSensitiveQuery(prompt)
        if (allowResponseCache) {
            circuitBreaker.getCachedResponse(cacheKey)?.let { cached ->
                return@withContext ProviderResponse(
                    text = cached,
                    modelUsed = "Ayra AI Instant Cache ⚡",
                    provider = preferredProvider ?: getBestAvailableProvider(),
                    latencyMs = 0L
                )
            }
        }

        if (preferredProvider != AiProviderType.LOCAL_OLLAMA &&
            preferredProvider != AiProviderType.OMNI_ROUTE &&
            SecureAiGatewayClient.shouldUseBackendGatewayProxy(context, isTestHarnessMode)
        ) {
            val ctx = context
            if (ctx != null) {
                val proxyProvider = preferredProvider ?: AiProviderType.GEMINI
                val proxyModel = preferredModelId
                    ?: ModelCatalog.defaultModelFor(proxyProvider)?.id
                    ?: getDefaultModelForProvider(proxyProvider)
                val proxyResult = SecureAiGatewayClient.callAuthenticatedBackendAiProxy(
                    context = ctx,
                    backendBaseUrl = AyraPreferences.getBackendGatewayUrl(ctx),
                    prompt = prompt,
                    model = proxyModel,
                    provider = proxyProvider.id,
                    systemPrompt = systemPrompt
                )
                val proxyText = proxyResult.getOrNull()?.trim().orEmpty()
                if (proxyText.isNotBlank()) {
                    val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    AiUsageTracker.recordCall(ctx, proxyProvider, true, latency)
                    circuitBreaker.recordSuccess(proxyProvider)
                    if (allowResponseCache) {
                        circuitBreaker.putCachedResponse(cacheKey, proxyText)
                    }
                    return@withContext ProviderResponse(
                        text = proxyText,
                        modelUsed = "Ayra Gateway Route B ($proxyModel)",
                        provider = proxyProvider,
                        latencyMs = latency
                    )
                }
            }
        }

        var lastCriticalError: ProviderError? = null
        if (preferredProvider == AiProviderType.OMNI_ROUTE) {
            if (!isTestHarnessMode) {
                val gate = SecureAiGatewayClient.evaluateProviderAccess(context, AiProviderType.GEMINI)
                if (!gate.allowed) {
                    throw ProviderError.NetworkFailure(gate.denialReason ?: "Provider blocked by Ayra AI Zero-Trust policy.")
                }
            }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ExternalAiProviders", "OmniRoute execution fallback: ${e.message}")
            }
        } else if (preferredProvider != null) {
            if (!isTestHarnessMode) {
                val gate = SecureAiGatewayClient.evaluateProviderAccess(context, preferredProvider)
                if (!gate.allowed) {
                    throw ProviderError.NetworkFailure(gate.denialReason ?: "Provider blocked by Ayra AI Zero-Trust policy.")
                }
            }
            val initialModel = preferredModelId ?: (ModelCatalog.defaultModelFor(preferredProvider)?.id ?: getDefaultModelForProvider(preferredProvider))
            val key = getKeyForProviderAndModel(preferredProvider, initialModel)
            if ((key.isNotBlank() || preferredProvider == AiProviderType.LOCAL_OLLAMA || preferredProvider == AiProviderType.POLLINATIONS_FREE) &&
                circuitBreaker.canExecute(preferredProvider)
            ) {
                val triedModels = mutableSetOf<String>()
                var candidateModel: String? = initialModel
                var failedModelNotice: String? = null
                var rateLimitRetries = 0
                while (candidateModel != null && triedModels.size < 3) {
                    val activeModel = candidateModel ?: break
                    triedModels.add(activeModel)
                    try {
                        val callStart = System.currentTimeMillis()
                        val resultText = callSpecificProvider(preferredProvider, activeModel, turns, key, withSearchGrounding)
                        val callLatency = (System.currentTimeMillis() - callStart).coerceAtLeast(1L)
                        if (resultText.isNotBlank()) {
                            AiUsageTracker.recordCall(context, preferredProvider, true, callLatency)
                            circuitBreaker.recordSuccess(preferredProvider)
                            val finalText = if (failedModelNotice != null && failedModelNotice != activeModel) {
                                "[Model Notice: '$failedModelNotice' was unavailable/retired; switched to '$activeModel'.]\n\n$resultText"
                            } else {
                                resultText
                            }
                            if (allowResponseCache) circuitBreaker.putCachedResponse(cacheKey, finalText)
                            return@withContext ProviderResponse(
                                text = finalText,
                                modelUsed = "${preferredProvider.displayName} ($activeModel)",
                                provider = preferredProvider,
                                latencyMs = System.currentTimeMillis() - startTime
                            )
                        }
                        candidateModel = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: BlockedByPolicyException) {
                        AiUsageTracker.recordCall(context, preferredProvider, false, System.currentTimeMillis() - startTime)
                        throw e
                    } catch (e: SecurityException) {
                        AiUsageTracker.recordCall(context, preferredProvider, false, System.currentTimeMillis() - startTime)
                        throw e
                    } catch (e: ProviderError.ModelNotFound) {
                        AiUsageTracker.recordCall(context, preferredProvider, false, System.currentTimeMillis() - startTime)
                        if (e.httpStatus == 410) {
                            ModelCatalog.markModelRetired(preferredProvider, activeModel, e.displayMessage)
                        } else {
                            ModelCatalog.markModelUnavailable(preferredProvider, activeModel, e.httpStatus, e.displayMessage)
                        }
                        failedModelNotice = activeModel
                        candidateModel = ModelCatalog.nextFallbackModel(preferredProvider, activeModel)?.takeIf { it !in triedModels }
                        if (candidateModel == null) lastCriticalError = e
                    } catch (e: ProviderError.RateLimited) {
                        AiUsageTracker.recordCall(context, preferredProvider, false, System.currentTimeMillis() - startTime)
                        lastCriticalError = e
                        if (rateLimitRetries < 2) {
                            rateLimitRetries++
                            delay(if (customHttpClient != null) 25L * rateLimitRetries else 300L * rateLimitRetries)
                            triedModels.remove(activeModel)
                        } else {
                            circuitBreaker.recordFailure(preferredProvider)
                            candidateModel = null
                        }
                    } catch (e: ProviderError.Unauthorized) {
                        AiUsageTracker.recordCall(context, preferredProvider, false, System.currentTimeMillis() - startTime)
                        circuitBreaker.recordFailure(preferredProvider)
                        lastCriticalError = e
                        candidateModel = null
                    } catch (e: Exception) {
                        AiUsageTracker.recordCall(context, preferredProvider, false, System.currentTimeMillis() - startTime)
                        if (preferredModelId == null) circuitBreaker.recordFailure(preferredProvider)
                        if (lastCriticalError == null && e is ProviderError) lastCriticalError = e
                        candidateModel = null
                    }
                }
            }
        }

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
                    if (allowResponseCache) circuitBreaker.putCachedResponse(cacheKey, resultText)
                    return@withContext ProviderResponse(
                        text = resultText,
                        modelUsed = channel.displayName,
                        provider = channel.provider,
                        latencyMs = System.currentTimeMillis() - startTime
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BlockedByPolicyException) {
                AiUsageTracker.recordCall(context, channel.provider, false, System.currentTimeMillis() - chStart)
                throw e
            } catch (e: SecurityException) {
                AiUsageTracker.recordCall(context, channel.provider, false, System.currentTimeMillis() - chStart)
                throw e
            } catch (e: ProviderError.ModelNotFound) {
                AiUsageTracker.recordCall(context, channel.provider, false, System.currentTimeMillis() - chStart)
                if (e.httpStatus == 410) {
                    ModelCatalog.markModelRetired(channel.provider, channel.modelId, e.displayMessage)
                } else {
                    ModelCatalog.markModelUnavailable(channel.provider, channel.modelId, e.httpStatus, e.displayMessage)
                }
                val fallbackId = ModelCatalog.nextFallbackModel(channel.provider, channel.modelId)
                if (fallbackId != null) {
                    runCatching {
                        val fbText = callSpecificProvider(channel.provider, fallbackId, turns, resolveChannelKey(channel, context), withSearchGrounding)
                        if (fbText.isNotBlank()) {
                            val noticedText = "[Model Notice: '${channel.modelId}' was unavailable/retired; switched to '$fallbackId'.]\n\n$fbText"
                            return@withContext ProviderResponse(
                                text = noticedText,
                                modelUsed = "${channel.provider.displayName} ($fallbackId)",
                                provider = channel.provider,
                                latencyMs = System.currentTimeMillis() - startTime
                            )
                        }
                    }
                }
            } catch (e: ProviderError.Unauthorized) {
                AiUsageTracker.recordCall(context, channel.provider, false, System.currentTimeMillis() - chStart)
                circuitBreaker.recordChannelFailure(channel.id)
                if (lastCriticalError == null) lastCriticalError = e
            } catch (e: ProviderError.RateLimited) {
                AiUsageTracker.recordCall(context, channel.provider, false, System.currentTimeMillis() - chStart)
                circuitBreaker.recordChannelFailure(channel.id)
                if (lastCriticalError == null) lastCriticalError = e
            } catch (e: Exception) {
                AiUsageTracker.recordCall(context, channel.provider, false, System.currentTimeMillis() - chStart)
                circuitBreaker.recordChannelFailure(channel.id)
                continue
            }
        }

        val freeGate = if (!isTestHarnessMode) {
            SecureAiGatewayClient.evaluateProviderAccess(context, AiProviderType.POLLINATIONS_FREE).allowed
        } else {
            context?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: false
        }
        if (freeGate) {
            try {
                val polKey = context?.let { AyraPreferences.getPollinationsKey(it) }.orEmpty()
                val fallbackText = ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                    httpClient = httpClient,
                    baseUrlOverrides = baseUrlOverrides,
                    turns = turns,
                    apiKey = polKey
                )
                if (fallbackText.isNotBlank()) {
                    return@withContext ProviderResponse(
                        text = fallbackText,
                        modelUsed = "Global Free AI Pool 🌐",
                        provider = AiProviderType.POLLINATIONS_FREE,
                        latencyMs = System.currentTimeMillis() - startTime
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BlockedByPolicyException) {
                throw e
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                if (lastCriticalError == null && e is ProviderError) lastCriticalError = e
            }
        }

        if (lastCriticalError != null) throw lastCriticalError
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
            AiProviderType.DEEPSEEK to DEEPSEEK_REASONER_MODEL,
            AiProviderType.SAMBANOVA to SAMBANOVA_DEEPSEEK_MODEL,
            AiProviderType.OPENROUTER to OPENROUTER_QWEN_FREE_MODEL,
            AiProviderType.GROQ to GROQ_QWEN_MODEL,
            AiProviderType.CEREBRAS to CEREBRAS_QWEN_MODEL,
            AiProviderType.POLLINATIONS_FREE to ModelCatalog.POLLINATIONS_DEEPSEEK_MODEL
        )
        reasoningFallbacks.forEach { pair ->
            if (candidates.none { it.first == pair.first && it.second == pair.second } &&
                isProviderConfigured(pair.first) && circuitBreaker.canExecute(pair.first)
            ) candidates.add(pair)
        }

        for ((provider, model) in candidates) {
            val key = getKeyForProviderAndModel(provider, model)
            if (key.isBlank() && provider != AiProviderType.LOCAL_OLLAMA && provider != AiProviderType.POLLINATIONS_FREE) continue
            val callStart = System.currentTimeMillis()
            try {
                val text = when (provider) {
                    AiProviderType.POLLINATIONS_FREE -> {
                        val polKey = key.takeIf { it.isNotBlank() && !it.equals("free", ignoreCase = true) }
                            ?: context?.let { AyraPreferences.getPollinationsKey(it) }.orEmpty()
                        ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                            httpClient = httpClient,
                            baseUrlOverrides = baseUrlOverrides,
                            turns = turns,
                            preferredModel = model,
                            preserveReasoning = true,
                            apiKey = polKey
                        )
                    }
                    AiProviderType.GEMINI, AiProviderType.ANTHROPIC, AiProviderType.OMNI_ROUTE ->
                        callSpecificProviderPublic(provider, model, turns, key, false)
                    else -> ProviderOpenAiTransport.callOpenAiCompatible(
                        resolveClientForProvider(provider), context, provider, baseUrlOverrides,
                        customHttpClient != null, turns, model, key, maxTokens = 8192, preserveReasoning = true
                    )
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: BlockedByPolicyException) {
                AiUsageTracker.recordCall(context, provider, false, System.currentTimeMillis() - callStart)
                throw e
            } catch (e: SecurityException) {
                AiUsageTracker.recordCall(context, provider, false, System.currentTimeMillis() - callStart)
                throw e
            } catch (e: Exception) {
                AiUsageTracker.recordCall(context, provider, false, System.currentTimeMillis() - callStart)
            }
        }

        return@withContext generateChatResponse(
            prompt = prompt, systemPrompt = systemPrompt, history = history,
            preferredProvider = preferredProvider, preferredModelId = preferredModelId, withSearchGrounding = false
        )
    }

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
        if (!isTestHarnessMode) {
            val gate = SecureAiGatewayClient.evaluateProviderAccess(context, provider)
            if (!gate.allowed) throw ProviderError.NetworkFailure(gate.denialReason ?: "Provider blocked by Zero-Trust policy.")
        }
        val initialModel = preferredModelId ?: (ModelCatalog.defaultModelFor(provider)?.id ?: getDefaultModelForProvider(provider))

        if (provider != AiProviderType.LOCAL_OLLAMA &&
            provider != AiProviderType.OMNI_ROUTE &&
            imageBase64 == null &&
            SecureAiGatewayClient.shouldUseBackendGatewayProxy(context, isTestHarnessMode)
        ) {
            val ctx = context
            if (ctx != null) {
                val startTime = System.currentTimeMillis()
                val proxyResult = SecureAiGatewayClient.callAuthenticatedBackendAiProxy(
                    context = ctx,
                    backendBaseUrl = AyraPreferences.getBackendGatewayUrl(ctx),
                    prompt = prompt,
                    model = initialModel,
                    provider = provider.id,
                    systemPrompt = systemPrompt
                )
                val proxyText = proxyResult.getOrNull()?.trim().orEmpty()
                if (proxyText.isNotBlank()) {
                    val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    AiUsageTracker.recordCall(ctx, provider, true, latency)
                    ModelCatalog.recordModelSuccess(provider, initialModel)
                    circuitBreaker.recordSuccess(provider)
                    emit(proxyText)
                    return@flow
                }
            }
        }

        val outerCollector: FlowCollector<String> = this

        suspend fun streamSingleAttempt(activeModel: String): Boolean {
            val activeKey = getKeyForProviderAndModel(provider, activeModel)
            var emitted = false
            val capturingCollector = FlowCollector<String> { chunk ->
                if (chunk.isNotEmpty()) {
                    emitted = true
                    outerCollector.emit(chunk)
                }
            }
            when (provider) {
                AiProviderType.GEMINI -> ProviderGeminiTransport.streamGemini(
                    httpClient = httpClient,
                    baseUrlOverride = baseUrlOverrides[AiProviderType.GEMINI],
                    turns = turns,
                    model = activeModel,
                    apiKey = activeKey,
                    collector = capturingCollector,
                    hasCustomClient = customHttpClient != null
                )
                AiProviderType.ANTHROPIC -> ProviderAnthropicAndFreeTransport.streamAnthropic(
                    httpClient = httpClient,
                    baseUrlOverrides = baseUrlOverrides,
                    turns = turns,
                    model = activeModel,
                    apiKey = activeKey,
                    collector = capturingCollector,
                    hasCustomClient = customHttpClient != null
                )
                AiProviderType.POLLINATIONS_FREE -> {
                    val polKey = activeKey.takeIf { it.isNotBlank() && !it.equals("free", ignoreCase = true) }
                        ?: context?.let { AyraPreferences.getPollinationsKey(it) }.orEmpty()
                    val text = ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                        httpClient = httpClient,
                        baseUrlOverrides = baseUrlOverrides,
                        turns = turns,
                        preferredModel = activeModel.ifBlank { ModelCatalog.POLLINATIONS_OPENAI_MODEL },
                        apiKey = polKey
                    )
                    if (text.isBlank()) throw ProviderError.NetworkFailure("Free AI Pool unreachable")
                    capturingCollector.emit(text)
                }
                AiProviderType.OMNI_ROUTE -> ProviderGeminiTransport.streamGemini(
                    httpClient = httpClient,
                    baseUrlOverride = baseUrlOverrides[AiProviderType.GEMINI],
                    turns = turns,
                    model = GEMINI_FLASH_MODEL,
                    apiKey = activeKey,
                    collector = capturingCollector,
                    hasCustomClient = customHttpClient != null
                )
                else -> {
                    val maxTok = if (provider == AiProviderType.NVIDIA_NIM && activeModel.contains("minimax", true)) 8192 else 4096
                    ProviderOpenAiTransport.streamOpenAiCompatible(
                        resolveClientForProvider(provider), context, provider, baseUrlOverrides,
                        customHttpClient != null, turns, activeModel, activeKey, maxTok, capturingCollector
                    )
                }
            }
            return emitted
        }

        val triedModels = mutableSetOf<String>()
        var currentModel: String? = initialModel
        var lastError: Throwable? = null
        while (triedModels.size < 3 && currentModel != null) {
            val activeModel = currentModel
            if (activeModel.isBlank() || !triedModels.add(activeModel)) break
            var rateLimitRetries = 0
            var moveToNextModel = false
            while (!moveToNextModel) {
                try {
                    val emitted = streamSingleAttempt(activeModel)
                    if (emitted) {
                        ModelCatalog.recordModelSuccess(provider, activeModel)
                        circuitBreaker.recordSuccess(provider)
                    }
                    return@flow
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProviderError.RateLimited) {
                    lastError = e
                    if (rateLimitRetries == 0) {
                        rateLimitRetries++
                        delay(if (customHttpClient != null) 25L else 450L)
                    } else {
                        currentModel = ModelCatalog.nextFallbackModel(provider, activeModel)?.takeIf { it !in triedModels }
                        moveToNextModel = true
                    }
                } catch (e: ProviderError.ModelNotFound) {
                    lastError = e
                    if (e.httpStatus == 410) {
                        ModelCatalog.markModelRetired(provider, activeModel, e.displayMessage)
                    } else {
                        ModelCatalog.markModelUnavailable(provider, activeModel, e.httpStatus, e.displayMessage)
                    }
                    val fallback = ModelCatalog.nextFallbackModel(provider, activeModel)
                    if (fallback.isNullOrBlank() || fallback in triedModels) {
                        throw e
                    }
                    if (triedModels.size == 1) {
                        outerCollector.emit("⚠️ [Model Notice: '$activeModel' unavailable; switched to '$fallback']\n\n")
                    }
                    currentModel = fallback
                    moveToNextModel = true
                } catch (e: Throwable) {
                    throw e
                }
            }
        }
        if (lastError != null) throw lastError
    }.flowOn(Dispatchers.IO)

    fun callSpecificProviderPublic(
        provider: AiProviderType,
        model: String,
        turns: List<ChatTurn>,
        apiKey: String,
        withSearchGrounding: Boolean
    ): String = when (provider) {
        AiProviderType.GEMINI -> ProviderGeminiTransport.callGemini(
            httpClient = httpClient,
            baseUrlOverride = baseUrlOverrides[AiProviderType.GEMINI],
            turns = turns,
            model = model,
            apiKey = apiKey,
            withSearchGrounding = withSearchGrounding,
            hasCustomClient = customHttpClient != null
        )
        AiProviderType.ANTHROPIC -> ProviderAnthropicAndFreeTransport.callAnthropic(
            httpClient = httpClient,
            baseUrlOverrides = baseUrlOverrides,
            turns = turns,
            model = model,
            apiKey = apiKey,
            hasCustomClient = customHttpClient != null
        )
        AiProviderType.POLLINATIONS_FREE -> {
            val polKey = apiKey.takeIf { it.isNotBlank() && !it.equals("free", ignoreCase = true) }
                ?: context?.let { AyraPreferences.getPollinationsKey(it) }.orEmpty()
            val res = ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                httpClient = httpClient,
                baseUrlOverrides = baseUrlOverrides,
                turns = turns,
                preferredModel = model.ifBlank { ModelCatalog.POLLINATIONS_OPENAI_MODEL },
                apiKey = polKey
            )
            if (res.isBlank()) throw ProviderError.NetworkFailure("Free AI Pool returned empty response")
            res
        }
        AiProviderType.OMNI_ROUTE -> {
            val geminiKey = context?.let { AyraPreferences.getEffectiveGeminiKey(it) } ?: ""
            if (geminiKey.isNotBlank()) {
                ProviderGeminiTransport.callGemini(
                    httpClient = httpClient,
                    baseUrlOverride = baseUrlOverrides[AiProviderType.GEMINI],
                    turns = turns,
                    model = GEMINI_FLASH_MODEL,
                    apiKey = geminiKey,
                    withSearchGrounding = withSearchGrounding,
                    hasCustomClient = customHttpClient != null
                )
            } else throw ProviderError.NoKeyConfigured(AiProviderType.GEMINI)
        }
        else -> {
            val maxTok = if (provider == AiProviderType.NVIDIA_NIM && model.contains("minimax", true)) 8192 else 4096
            ProviderOpenAiTransport.callOpenAiCompatible(
                resolveClientForProvider(provider), context, provider, baseUrlOverrides,
                customHttpClient != null, turns, model, apiKey, maxTok
            )
        }
    }

    fun callSpecificProvider(
        provider: AiProviderType,
        model: String,
        turns: List<ChatTurn>,
        apiKey: String,
        withSearchGrounding: Boolean = true
    ): String = callSpecificProviderPublic(provider, model, turns, apiKey, withSearchGrounding)

    suspend fun callOpenAiWithTools(
        provider: AiProviderType,
        turns: List<ChatTurn>,
        model: String,
        toolsArray: JSONArray,
        maxTokens: Int = 4096
    ): OpenAiToolTurnResponse = withContext(Dispatchers.IO) {
        ProviderOpenAiTransport.callOpenAiWithTools(
            client = resolveClientForProvider(provider),
            context = context,
            provider = provider,
            baseUrlOverrides = baseUrlOverrides,
            hasCustomClient = customHttpClient != null,
            turns = turns,
            model = model,
            key = getKeyForProviderAndModel(provider, model),
            toolsArray = toolsArray,
            maxTokens = maxTokens
        )
    }

    internal fun resolveGeminiModel(model: String): String = Companion.resolveGeminiModel(model)

    internal fun getBaseUrlForProvider(provider: AiProviderType): String =
        ProviderOpenAiTransport.getBaseUrlForProvider(provider, context, baseUrlOverrides)

    fun getKeyForProviderAndModel(provider: AiProviderType, modelId: String): String {
        if (provider == AiProviderType.NVIDIA_NIM) {
            val m = modelId.lowercase()
            val channelId = when {
                m.contains("llama") -> "nvidia_llama_3_3_70b"
                m.contains("minimax") -> "nvidia_minimax_m3_1"
                m.contains("glm") -> "nvidia_glm_5_3"
                m.contains("muse") -> "nvidia_muse_30b"
                m.contains("gemma") -> "nvidia_gemma_4_31b"
                else -> ""
            }
            if (channelId.isNotEmpty()) channelKeyOverrides[channelId]?.takeIf { it.isNotBlank() }?.let { return it }
            val ctx = context
            if (ctx != null) {
                AyraApplication.ensureActiveAiKeysSeeded(ctx)
                val resolved = when {
                    m.contains("llama") -> AyraPreferences.getNvidiaKey(ctx)
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
            val overriddenNim = channelKeyOverrides["nvidia_llama_3_3_70b"]
                ?: channelKeyOverrides["nvidia_minimax_m3_1"]
                ?: channelKeyOverrides["nvidia_muse_30b"]
                ?: channelKeyOverrides["nvidia_glm_5_3"]
                ?: channelKeyOverrides["nvidia_gemma_4_31b"]
            if (!overriddenNim.isNullOrBlank()) return overriddenNim
        }
        val ctx = context ?: return ""
        AyraApplication.ensureActiveAiKeysSeeded(ctx)
        return when (provider) {
            AiProviderType.GEMINI -> AyraPreferences.getEffectiveGeminiKey(ctx)
            AiProviderType.NVIDIA_NIM -> AyraPreferences.getNvidiaKey(ctx)
                .ifBlank { AyraPreferences.getNvidiaMinimaxKey(ctx) }
                .ifBlank { AyraPreferences.getNvidiaMuseKey(ctx) }
                .ifBlank { AyraPreferences.getNvidiaGlmKey(ctx) }
                .ifBlank { AyraPreferences.getNvidiaGemmaKey(ctx) }
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
                val hasOllama = AyraPreferences.hasExplicitOllamaHost(ctx)
                if (url.isNotBlank() || hasOllama) {
                    AyraPreferences.getCustomEndpointKey(ctx).ifBlank {
                        url.ifBlank { AyraPreferences.getOllamaHost(ctx) }
                    }
                } else ""
            }
            AiProviderType.POLLINATIONS_FREE -> if (AyraPreferences.isFreeAiPoolEnabled(ctx)) "free" else ""
            AiProviderType.OMNI_ROUTE -> AyraPreferences.getEffectiveGeminiKey(ctx)
        }
    }

    fun isProviderConfigured(provider: AiProviderType): Boolean {
        if (!isTestHarnessMode) {
            if (!SecureAiGatewayClient.evaluateProviderAccess(context, provider).allowed) return false
        }
        if (provider == AiProviderType.GITHUB_MODELS) {
            // Azure GitHub Models inference endpoint shut down 2026-07-30; remain inactive unless test override is supplied.
            if (!baseUrlOverrides.containsKey(AiProviderType.GITHUB_MODELS)) return false
        }
        if (provider == AiProviderType.OMNI_ROUTE) return !(context != null && AyraPreferences.isLocalOnlyAiModeEnabled(context))
        if (provider == AiProviderType.LOCAL_OLLAMA) {
            return (channelKeyOverrides[AiProviderType.LOCAL_OLLAMA.id]?.isNotBlank() == true) ||
                (context?.let {
                    AyraPreferences.getCustomEndpointUrl(it).isNotBlank() || AyraPreferences.hasExplicitOllamaHost(it)
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

    fun getActiveConfiguredProviders(): List<AiProviderType> =
        listOf(
            AiProviderType.GEMINI, AiProviderType.GROQ, AiProviderType.CEREBRAS,
            AiProviderType.SAMBANOVA, AiProviderType.NVIDIA_NIM, AiProviderType.GITHUB_MODELS,
            AiProviderType.HUGGINGFACE, AiProviderType.OPENAI, AiProviderType.ANTHROPIC,
            AiProviderType.DEEPSEEK, AiProviderType.PERPLEXITY, AiProviderType.XAI,
            AiProviderType.MISTRAL, AiProviderType.OPENROUTER, AiProviderType.LOCAL_OLLAMA,
            AiProviderType.POLLINATIONS_FREE
        ).filter { isProviderConfigured(it) }

    fun hasAnyConfiguredProvider(): Boolean = getActiveConfiguredProviders().isNotEmpty()

    fun getBestAvailableProvider(): AiProviderType = getActiveConfiguredProviders().firstOrNull() ?: AiProviderType.GEMINI

    fun getSavedKeyProvidersIgnoringConsent(): List<AiProviderType> =
        listOf(
            AiProviderType.GEMINI, AiProviderType.GROQ, AiProviderType.CEREBRAS,
            AiProviderType.SAMBANOVA, AiProviderType.NVIDIA_NIM, AiProviderType.HUGGINGFACE,
            AiProviderType.OPENAI, AiProviderType.ANTHROPIC, AiProviderType.DEEPSEEK,
            AiProviderType.PERPLEXITY, AiProviderType.XAI, AiProviderType.MISTRAL,
            AiProviderType.OPENROUTER, AiProviderType.LOCAL_OLLAMA, AiProviderType.POLLINATIONS_FREE
        ).filter { provider ->
            when (provider) {
                AiProviderType.LOCAL_OLLAMA -> (channelKeyOverrides[AiProviderType.LOCAL_OLLAMA.id]?.isNotBlank() == true) ||
                    (context?.let {
                        AyraPreferences.getCustomEndpointUrl(it).isNotBlank() || AyraPreferences.hasExplicitOllamaHost(it)
                    } ?: false)
                AiProviderType.POLLINATIONS_FREE -> context?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: false
                else -> getProviderKey(provider).isNotBlank()
            }
        }

    fun getDefaultModelForProvider(provider: AiProviderType): String = when (provider) {
        AiProviderType.GEMINI -> GEMINI_FLASH_MODEL
        AiProviderType.NVIDIA_NIM -> NVIDIA_LLAMA_MODEL
        AiProviderType.GROQ -> GROQ_DEFAULT_MODEL
        AiProviderType.CEREBRAS -> CEREBRAS_LLAMA_MODEL
        AiProviderType.SAMBANOVA -> SAMBANOVA_DEEPSEEK_MODEL
        AiProviderType.HUGGINGFACE -> HF_QWEN_MODEL
        AiProviderType.GITHUB_MODELS -> GITHUB_RETIRED_MODEL
        AiProviderType.OPENAI -> OPENAI_DEFAULT_MODEL
        AiProviderType.ANTHROPIC -> ANTHROPIC_SONNET_MODEL
        AiProviderType.DEEPSEEK -> DEEPSEEK_REASONER_MODEL
        AiProviderType.XAI -> XAI_DEFAULT_MODEL
        AiProviderType.MISTRAL -> MISTRAL_LARGE_MODEL
        AiProviderType.PERPLEXITY -> PERPLEXITY_REASONING_MODEL
        AiProviderType.OPENROUTER -> OPENROUTER_DEFAULT_MODEL
        AiProviderType.LOCAL_OLLAMA -> ModelCatalog.OLLAMA_LLAMA_MODEL
        AiProviderType.POLLINATIONS_FREE -> ModelCatalog.POLLINATIONS_OPENAI_MODEL
        AiProviderType.OMNI_ROUTE -> GEMINI_FLASH_MODEL
    }

    suspend fun testProviderConnection(
        provider: AiProviderType,
        customKey: String? = null,
        customModel: String? = null
    ): Long = withContext(Dispatchers.IO) {
        if (provider == AiProviderType.GITHUB_MODELS && !baseUrlOverrides.containsKey(AiProviderType.GITHUB_MODELS)) {
            throw ProviderError.NetworkFailure("GitHub Models inference endpoint shut down on 2026-07-30.")
        }
        val model = customModel ?: getDefaultModelForProvider(provider)
        val key = customKey ?: getKeyForProviderAndModel(provider, model)
        if (key.isBlank() &&
            provider != AiProviderType.LOCAL_OLLAMA &&
            provider != AiProviderType.OMNI_ROUTE &&
            provider != AiProviderType.POLLINATIONS_FREE
        ) {
            throw ProviderError.NoKeyConfigured(provider)
        }
        circuitBreaker.resetProvider(provider)
        val start = System.currentTimeMillis()
        try {
            val testTurns = listOf(ChatTurn("user", "Reply with just the single word: READY"))
            val response = callSpecificProvider(provider, model, testTurns, key, withSearchGrounding = false)
            if (response.isBlank()) throw ProviderError.NetworkFailure("Empty response received from ${provider.displayName}")
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            ModelCatalog.recordModelSuccess(provider, model)
            circuitBreaker.recordSuccess(provider)
            AiUsageTracker.recordCall(context, provider, true, elapsed)
            elapsed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AiUsageTracker.recordCall(context, provider, false, System.currentTimeMillis() - start)
            throw e
        }
    }

    fun getOptimalModelForRoutingMode(mode: AiRoutingMode): Pair<AiProviderType, String> =
        ProviderAnthropicAndFreeTransport.getOptimalModelForRoutingMode(
            mode = mode,
            configured = getActiveConfiguredProviders(),
            bestAvailable = getBestAvailableProvider(),
            defaultModelForProvider = ::getDefaultModelForProvider
        )

    suspend fun discoverDynamicModels(provider: AiProviderType): List<AiModelInfo> = withContext(Dispatchers.IO) {
        dynamicModelCache[provider]?.let { if (it.isNotEmpty()) return@withContext it }
        if (!isTestHarnessMode &&
            !SecureAiGatewayClient.evaluateProviderAccess(context, provider).allowed
        ) {
            return@withContext CURATED_MODELS.filter { it.provider == provider }
        }
        val key = getProviderKey(provider)
        val discovered = mutableListOf<AiModelInfo>()
        val hasCustomClient = customHttpClient != null
        try {
            when (provider) {
                AiProviderType.GEMINI -> ProviderGeminiTransport.discoverModels(httpClient, key, discovered, hasCustomClient)
                AiProviderType.ANTHROPIC -> ProviderAnthropicAndFreeTransport.discoverAnthropicModels(httpClient, key, discovered, hasCustomClient)
                AiProviderType.NVIDIA_NIM, AiProviderType.GROQ, AiProviderType.CEREBRAS, AiProviderType.SAMBANOVA,
                AiProviderType.HUGGINGFACE, AiProviderType.GITHUB_MODELS, AiProviderType.OPENAI, AiProviderType.DEEPSEEK,
                AiProviderType.XAI, AiProviderType.MISTRAL, AiProviderType.PERPLEXITY, AiProviderType.OPENROUTER,
                AiProviderType.LOCAL_OLLAMA ->
                    ProviderOpenAiTransport.discoverModels(httpClient, context, provider, baseUrlOverrides, key, discovered, hasCustomClient)
                else -> {}
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ExternalAiProviders", "Dynamic model discovery failed for ${provider.id}: ${e.message}")
        }

        if (discovered.isNotEmpty()) {
            dynamicModelCache[provider] = discovered
            ModelCatalog.registerLiveDiscoveredModels(provider, discovered)
            discovered
        } else {
            CURATED_MODELS.filter { it.provider == provider }
        }
    }

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
            if (customHttpClient == null && !SecureAiGatewayClient.evaluateProviderAccess(context, provider).allowed) continue
            val start = System.currentTimeMillis()
            try {
                val baseUrl = getBaseUrlForProvider(provider)
                val endpoint = "${baseUrl}audio/transcriptions"
                val mime = if (audioFile.name.endsWith(".wav", true)) "audio/wav" else "audio/mp4"
                val bodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mime.toMediaType()))
                    .addFormDataPart("model", model)
                if (promptHint.isNotBlank()) bodyBuilder.addFormDataPart("prompt", promptHint)
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $key")
                    .post(bodyBuilder.build())
                    .build()

                val callResponse = if (customHttpClient == null && endpoint.startsWith("https://", ignoreCase = true)) {
                    NetworkSecurityHelper.executePublicHttpsWithValidatedRedirects(
                        client = httpClient,
                        initialRequest = req,
                        urlValidator = { NetworkSecurityHelper.validatePublicHttpsSyntaxOnly(it) }
                    )
                } else {
                    httpClient.newCall(req).execute()
                }
                callResponse.use { resp ->
                    val raw = resp.readBounded()
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ExternalAiProviders", "Whisper transcription failed for ${provider.id}: ${e.message}")
                AiUsageTracker.recordCall(context, provider, false, System.currentTimeMillis() - start)
            }
        }
        ""
    }
}
