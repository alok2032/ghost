package com.ayra.ai.app.ai.providers

import android.content.Context
import com.ayra.ai.app.AyraApplication
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.security.NetworkSecurityHelper
import com.ayra.ai.app.security.SecureAiGatewayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
            get() = NetworkSecurityHelper.hardenedPooledClient

        val sharedFastWebClient: OkHttpClient by lazy {
            NetworkSecurityHelper.hardenedPooledClient.newBuilder()
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
            AiModelInfo(GROQ_LLAMA_MODEL, "Groq Llama 3.3 70B ⚡", AiProviderType.GROQ, "Ultra-fast LPU inference with generous free tier", "128k tokens", false, false, false),
            AiModelInfo(GROQ_QWEN_MODEL, "Groq Qwen3 32B 🧠", AiProviderType.GROQ, "High-speed STEM & multilingual reasoning on Groq LPU", "128k tokens", false, true, false),
            AiModelInfo(CEREBRAS_LLAMA_MODEL, "Cerebras Llama 3.3 70B 🚀", AiProviderType.CEREBRAS, "Wafer-scale high-speed inference with generous free tier", "128k tokens", false, false, false),
            AiModelInfo(CEREBRAS_QWEN_MODEL, "Cerebras Qwen 3 32B ⚡", AiProviderType.CEREBRAS, "Wafer-scale STEM, math & coding reasoning", "128k tokens", false, true, false),
            AiModelInfo(SAMBANOVA_DEEPSEEK_MODEL, "SambaNova DeepSeek V3.1 🔬", AiProviderType.SAMBANOVA, "Fast RDU inference for coding, math & deep reasoning", "128k tokens", false, true, false),
            AiModelInfo(SAMBANOVA_QWEN_MODEL, "SambaNova Qwen3 32B 🌐", AiProviderType.SAMBANOVA, "Fast RDU multilingual & STEM reasoning", "64k tokens", false, true, false),
            AiModelInfo(HF_QWEN_MODEL, "HF Qwen 2.5 72B Instruct 🤗", AiProviderType.HUGGINGFACE, "Open-weights Hugging Face Inference Router", "128k tokens", false, true, false),
            AiModelInfo(GITHUB_GPT4O_MODEL, "GitHub Models GPT-4o Mini 🐙", AiProviderType.GITHUB_MODELS, "Free-tier GitHub Models inference gateway", "128k tokens", true, false, false),
            AiModelInfo(NVIDIA_MINIMAX_MODEL, "MiniMax M3.1 Flash ⚡", AiProviderType.NVIDIA_NIM, "High-speed multimodal vision & coding intelligence via NVIDIA NIM", "128k tokens", true, true, true),
            AiModelInfo(NVIDIA_GLM_MODEL, "Z-AI GLM 5.3 Flash 🧠", AiProviderType.NVIDIA_NIM, "Fast STEM, mathematical reasoning & multilingual synthesis", "128k tokens", false, true, false),
            AiModelInfo(NVIDIA_MUSE_MODEL, "Meta Muse Glimmer 30B ✨", AiProviderType.NVIDIA_NIM, "Fluent creative writing, Bengali/Banglish & deep reasoning", "128k tokens", false, true, false),
            AiModelInfo(NVIDIA_GEMMA_MODEL, "Gemma 4 31B IT (NIM) 🔬", AiProviderType.NVIDIA_NIM, "Multilingual science, coding & analytical verification", "128k tokens", true, true, false),
            AiModelInfo("gpt-4o", "GPT-4o Omni 🧠", AiProviderType.OPENAI, "OpenAI flagship multimodal intelligence", "128k tokens", true, false, false),
            AiModelInfo("gpt-4o-mini", "GPT-4o Mini ⚡", AiProviderType.OPENAI, "Fast daily conversational model", "128k tokens", true, false, false),
            AiModelInfo("o3-mini", "o3-mini Reasoning 🔬", AiProviderType.OPENAI, "STEM, math & coding chain-of-thought", "200k tokens", false, true, false),
            AiModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4 🎭", AiProviderType.ANTHROPIC, "Hybrid reasoning & software architecture benchmark", "200k tokens", true, true, false),
            AiModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5 ⚡", AiProviderType.ANTHROPIC, "Fast coding, analysis & concise synthesis", "200k tokens", true, false, false),
            AiModelInfo("deepseek-chat", "DeepSeek V3 🚀", AiProviderType.DEEPSEEK, "MoE general conversational & coding intelligence", "64k tokens", false, false, false),
            AiModelInfo("deepseek-reasoner", "DeepSeek R1 CoT 🔬", AiProviderType.DEEPSEEK, "Open mathematical proof & step-by-step CoT", "64k tokens", false, true, false),
            AiModelInfo("grok-2-latest", "Grok 2 Latest 🤖", AiProviderType.XAI, "Frontier reasoning by xAI", "128k tokens", true, false, false),
            AiModelInfo("mistral-large-latest", "Mistral Large 🌐", AiProviderType.MISTRAL, "European multilingual intelligence", "128k tokens", false, false, false),
            AiModelInfo("codestral-latest", "Codestral 💻", AiProviderType.MISTRAL, "Dedicated programming & refactoring", "256k tokens", false, false, false),
            AiModelInfo("sonar-reasoning", "Perplexity Sonar 🔎", AiProviderType.PERPLEXITY, "Web-grounded research with citations", "128k tokens", false, true, false),
            AiModelInfo("openrouter/auto", "OpenRouter Auto 🔀", AiProviderType.OPENROUTER, "Dynamic universal gateway", "128k tokens", true, false, false),
            AiModelInfo("qwen/qwen-2.5-72b-instruct", "Qwen 2.5 72B Instruct 🐉", AiProviderType.OPENROUTER, "Multilingual STEM & reasoning", "128k tokens", false, false, false),
            AiModelInfo("qwen/qwen-2.5-coder-32b-instruct", "Qwen 2.5 Coder 32B 💻", AiProviderType.OPENROUTER, "Coding and bug-fixing specialist", "128k tokens", false, false, false),
            AiModelInfo("qwen2.5:14b", "Local Ollama Qwen 2.5 14B 🌐", AiProviderType.LOCAL_OLLAMA, "Local/LAN open-weights Qwen model", "32k tokens", false, true, false),
            AiModelInfo("llama3.3", "Local Ollama / LM Studio 🏠", AiProviderType.LOCAL_OLLAMA, "Zero-cloud local LLM on your LAN", "32k tokens", false, false, true),
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
                httpClient, baseUrlOverrides[AiProviderType.GEMINI], turns, channel.modelId, key, withSearchGrounding
            )
            AiProviderType.ANTHROPIC -> ProviderAnthropicAndFreeTransport.callAnthropic(
                httpClient, baseUrlOverrides, turns, channel.modelId, key
            )
            AiProviderType.POLLINATIONS_FREE -> {
                val sys = turns.firstOrNull { it.role == "system" }?.content ?: ""
                val usr = turns.lastOrNull { it.role == "user" }?.content ?: ""
                ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                    httpClient, baseUrlOverrides, usr, sys, channel.modelId
                )
            }
            AiProviderType.OMNI_ROUTE -> {
                val gKey = channelKeyOverrides[AiProviderType.GEMINI.id]
                    ?: context?.let { AyraPreferences.getEffectiveGeminiKey(it) } ?: ""
                if (gKey.isNotBlank()) {
                    ProviderGeminiTransport.callGemini(
                        httpClient, baseUrlOverrides[AiProviderType.GEMINI], turns, GEMINI_FLASH_MODEL, gKey, withSearchGrounding
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
        if (imageBase64 == null && history.isNullOrEmpty()) {
            circuitBreaker.getCachedResponse(cacheKey)?.let { cached ->
                return@withContext ProviderResponse(
                    text = cached,
                    modelUsed = "Ayra AI Instant Cache ⚡",
                    provider = preferredProvider ?: getBestAvailableProvider(),
                    latencyMs = 0L
                )
            }
        }

        var lastCriticalError: ProviderError? = null
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        } else if (preferredProvider != null) {
            if (allowExternalWikiGrounding) {
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
                            if (imageBase64 == null && history.isNullOrEmpty()) circuitBreaker.putCachedResponse(cacheKey, resultText)
                            return@withContext ProviderResponse(
                                text = resultText,
                                modelUsed = "${preferredProvider.displayName} ($activeModel)",
                                provider = preferredProvider,
                                latencyMs = System.currentTimeMillis() - startTime
                            )
                        }
                        candidateModel = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ProviderError.ModelNotFound) {
                        AiUsageTracker.recordCall(context, preferredProvider, false, System.currentTimeMillis() - startTime)
                        ModelCatalog.markModelUnavailable(preferredProvider, activeModel, 404, e.displayMessage)
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
                    } catch (_: Exception) {
                        AiUsageTracker.recordCall(context, preferredProvider, false, System.currentTimeMillis() - startTime)
                        if (preferredModelId == null) circuitBreaker.recordFailure(preferredProvider)
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
                    if (imageBase64 == null && history.isNullOrEmpty()) circuitBreaker.putCachedResponse(cacheKey, resultText)
                    return@withContext ProviderResponse(
                        text = resultText,
                        modelUsed = channel.displayName,
                        provider = channel.provider,
                        latencyMs = System.currentTimeMillis() - startTime
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderError.ModelNotFound) {
                AiUsageTracker.recordCall(context, channel.provider, false, System.currentTimeMillis() - chStart)
                ModelCatalog.markModelUnavailable(channel.provider, channel.modelId, 404, e.displayMessage)
                circuitBreaker.recordChannelFailure(channel.id)
                val fallbackId = ModelCatalog.nextFallbackModel(channel.provider, channel.modelId)
                if (fallbackId != null) {
                    runCatching {
                        val fbText = callSpecificProvider(channel.provider, fallbackId, turns, resolveChannelKey(channel, context), withSearchGrounding)
                        if (fbText.isNotBlank()) {
                            return@withContext ProviderResponse(
                                text = fbText,
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
            } catch (_: Exception) {
                AiUsageTracker.recordCall(context, channel.provider, false, System.currentTimeMillis() - chStart)
                circuitBreaker.recordChannelFailure(channel.id)
                continue
            }
        }

        val freeGate = if (baseUrlOverrides.isEmpty() && channelKeyOverrides.isEmpty() && customHttpClient == null) {
            SecureAiGatewayClient.evaluateProviderAccess(context, AiProviderType.POLLINATIONS_FREE).allowed
        } else {
            context?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: true
        }
        if (freeGate) {
            try {
                val fallbackText = ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                    httpClient, baseUrlOverrides, prompt, systemPrompt
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
            } catch (_: Exception) {}
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
            ) candidates.add(pair)
        }

        for ((provider, model) in candidates) {
            val key = getKeyForProviderAndModel(provider, model)
            if (key.isBlank() && provider != AiProviderType.LOCAL_OLLAMA && provider != AiProviderType.POLLINATIONS_FREE) continue
            val callStart = System.currentTimeMillis()
            try {
                val text = when (provider) {
                    AiProviderType.POLLINATIONS_FREE -> ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                        httpClient, baseUrlOverrides, prompt, systemPrompt, model, preserveReasoning = true
                    )
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
            } catch (_: Exception) {
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
        if (baseUrlOverrides.isEmpty() && channelKeyOverrides.isEmpty() && customHttpClient == null) {
            val gate = SecureAiGatewayClient.evaluateProviderAccess(context, provider)
            if (!gate.allowed) throw ProviderError.NetworkFailure(gate.denialReason ?: "Provider blocked by Zero-Trust policy.")
        }
        val model = preferredModelId ?: getDefaultModelForProvider(provider)
        val key = getKeyForProviderAndModel(provider, model)

        when (provider) {
            AiProviderType.GEMINI -> ProviderGeminiTransport.streamGemini(
                httpClient, baseUrlOverrides[AiProviderType.GEMINI], turns, model, key, this
            )
            AiProviderType.ANTHROPIC -> ProviderAnthropicAndFreeTransport.streamAnthropic(
                httpClient, baseUrlOverrides, turns, model, key, this
            )
            AiProviderType.POLLINATIONS_FREE -> {
                val text = ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                    httpClient, baseUrlOverrides, prompt, systemPrompt, model.ifBlank { "openai" }
                )
                if (text.isBlank()) throw ProviderError.NetworkFailure("Free AI Pool unreachable")
                emit(text)
            }
            AiProviderType.OMNI_ROUTE -> ProviderGeminiTransport.streamGemini(
                httpClient, baseUrlOverrides[AiProviderType.GEMINI], turns, GEMINI_FLASH_MODEL, key, this
            )
            else -> {
                val maxTok = if (provider == AiProviderType.NVIDIA_NIM && model.contains("minimax", true)) 8192 else 4096
                ProviderOpenAiTransport.streamOpenAiCompatible(
                    resolveClientForProvider(provider), context, provider, baseUrlOverrides,
                    customHttpClient != null, turns, model, key, maxTok, this
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    fun callSpecificProviderPublic(
        provider: AiProviderType,
        model: String,
        turns: List<ChatTurn>,
        apiKey: String,
        withSearchGrounding: Boolean
    ): String = when (provider) {
        AiProviderType.GEMINI -> ProviderGeminiTransport.callGemini(
            httpClient, baseUrlOverrides[AiProviderType.GEMINI], turns, model, apiKey, withSearchGrounding
        )
        AiProviderType.ANTHROPIC -> ProviderAnthropicAndFreeTransport.callAnthropic(
            httpClient, baseUrlOverrides, turns, model, apiKey
        )
        AiProviderType.POLLINATIONS_FREE -> {
            val sys = turns.firstOrNull { it.role == "system" }?.content ?: ""
            val usr = turns.lastOrNull { it.role == "user" }?.content ?: ""
            val res = ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                httpClient, baseUrlOverrides, usr, sys, model.ifBlank { "openai" }
            )
            if (res.isBlank()) throw ProviderError.NetworkFailure("Free AI Pool returned empty response")
            res
        }
        AiProviderType.OMNI_ROUTE -> {
            val geminiKey = context?.let { AyraPreferences.getEffectiveGeminiKey(it) } ?: ""
            if (geminiKey.isNotBlank()) {
                ProviderGeminiTransport.callGemini(
                    httpClient, baseUrlOverrides[AiProviderType.GEMINI], turns, GEMINI_FLASH_MODEL, geminiKey, withSearchGrounding
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
            if (!SecureAiGatewayClient.evaluateProviderAccess(context, provider).allowed) return false
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

    fun getDefaultModelForProvider(provider: AiProviderType): String = when (provider) {
        AiProviderType.GEMINI -> GEMINI_FLASH_MODEL
        AiProviderType.NVIDIA_NIM -> NVIDIA_MINIMAX_MODEL
        AiProviderType.GROQ -> GROQ_LLAMA_MODEL
        AiProviderType.CEREBRAS -> CEREBRAS_LLAMA_MODEL
        AiProviderType.SAMBANOVA -> SAMBANOVA_DEEPSEEK_MODEL
        AiProviderType.HUGGINGFACE -> HF_QWEN_MODEL
        AiProviderType.GITHUB_MODELS -> GITHUB_GPT4O_MODEL
        AiProviderType.OPENAI -> "gpt-4o"
        AiProviderType.ANTHROPIC -> "claude-sonnet-4-20250514"
        AiProviderType.DEEPSEEK -> "deepseek-chat"
        AiProviderType.XAI -> "grok-2-latest"
        AiProviderType.MISTRAL -> "mistral-large-latest"
        AiProviderType.PERPLEXITY -> "sonar-reasoning"
        AiProviderType.OPENROUTER -> "openrouter/auto"
        AiProviderType.LOCAL_OLLAMA -> "llama3.3"
        AiProviderType.POLLINATIONS_FREE -> "openai"
        AiProviderType.OMNI_ROUTE -> GEMINI_FLASH_MODEL
    }

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
        if (baseUrlOverrides.isEmpty() &&
            channelKeyOverrides.isEmpty() &&
            customHttpClient == null &&
            provider != AiProviderType.LOCAL_OLLAMA &&
            !SecureAiGatewayClient.evaluateProviderAccess(context, provider).allowed
        ) {
            return@withContext CURATED_MODELS.filter { it.provider == provider }
        }
        val key = getProviderKey(provider)
        val discovered = mutableListOf<AiModelInfo>()
        try {
            when (provider) {
                AiProviderType.GEMINI -> ProviderGeminiTransport.discoverModels(httpClient, key, discovered)
                AiProviderType.ANTHROPIC -> ProviderAnthropicAndFreeTransport.discoverAnthropicModels(httpClient, key, discovered)
                AiProviderType.NVIDIA_NIM, AiProviderType.GROQ, AiProviderType.CEREBRAS, AiProviderType.SAMBANOVA,
                AiProviderType.HUGGINGFACE, AiProviderType.GITHUB_MODELS, AiProviderType.OPENAI, AiProviderType.DEEPSEEK,
                AiProviderType.XAI, AiProviderType.MISTRAL, AiProviderType.PERPLEXITY, AiProviderType.OPENROUTER ->
                    ProviderOpenAiTransport.discoverModels(httpClient, context, provider, baseUrlOverrides, key, discovered)
                else -> {}
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}

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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AiUsageTracker.recordCall(context, provider, false, System.currentTimeMillis() - start)
            }
        }
        ""
    }
}
