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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
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
        const val GEMINI_FLASH_35_MODEL = ModelCatalog.GEMINI_FLASH_35_MODEL
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

        private val dynamicModelCache = ConcurrentHashMap<AiProviderType, List<AiModelInfo>>()

        @JvmStatic
        fun clearDynamicModelCache(provider: AiProviderType? = null) {
            if (provider == null) {
                dynamicModelCache.clear()
            } else {
                dynamicModelCache.remove(provider)
            }
        }

        fun resolveGeminiModel(model: String): String = ExternalAiCatalog.resolveGeminiModel(model)

        fun sanitizeModelOutput(raw: String): String = ExternalAiCatalog.sanitizeModelOutput(raw)

        val CURATED_MODELS: List<AiModelInfo> get() = ExternalAiCatalog.curatedModels
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
            val key = ExternalAiKeyAndDiscovery.resolveChannelKey(channel, ctx, channelKeyOverrides)
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
                .thenByDescending { if (it.id == "gemini_3_6_flash" || it.id == "nvidia_llama_3_3_70b") 3 else 0 }
                .thenByDescending { if (it.provider == AiProviderType.POLLINATIONS_FREE) -5 else 1 }
        )
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
        val key = ExternalAiKeyAndDiscovery.resolveChannelKey(channel, context, channelKeyOverrides)
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
                    httpClient, baseUrlOverrides, usr, sys, channel.modelId, conversationTurns = turns, apiKey = key
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
        val cacheEligible = imageBase64 == null && history.isNullOrEmpty() && !ProviderPool.isTimeSensitiveQuery(prompt)
        if (cacheEligible) {
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
                val validatedGatewayUrl = SecureAiGatewayClient.getValidatedBackendGatewayUrl(ctx)
                val proxyResult = if (validatedGatewayUrl != null) {
                    SecureAiGatewayClient.callAuthenticatedBackendAiProxy(
                        context = ctx,
                        backendBaseUrl = validatedGatewayUrl,
                        prompt = prompt,
                        model = proxyModel,
                        provider = proxyProvider.id,
                        systemPrompt = systemPrompt
                    )
                } else {
                    Result.failure(IllegalStateException("Route B gateway URL is not initialized or failed HTTPS integrity check."))
                }
                val proxyText = proxyResult.getOrNull()?.trim().orEmpty()
                if (proxyText.isNotBlank()) {
                    val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    AiUsageTracker.recordCall(ctx, proxyProvider, true, latency)
                    circuitBreaker.recordSuccess(proxyProvider)
                    if (cacheEligible) {
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
                android.util.Log.w("ExternalAiProviders", "OmniRoute execution warning: ${e.message}")
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
                            if (cacheEligible) circuitBreaker.putCachedResponse(cacheKey, finalText)
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
                        android.util.Log.w("ExternalAiProviders", "Preferred provider ${preferredProvider.id} failed: ${e.message}")
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
                    if (cacheEligible) circuitBreaker.putCachedResponse(cacheKey, resultText)
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
                if (e.httpStatus == 410) {
                    ModelCatalog.markModelRetired(channel.provider, channel.modelId, e.displayMessage)
                } else {
                    ModelCatalog.markModelUnavailable(channel.provider, channel.modelId, e.httpStatus, e.displayMessage)
                }
                val fallbackId = ModelCatalog.nextFallbackModel(channel.provider, channel.modelId)
                if (fallbackId != null) {
                    runCatching {
                        val fbText = callSpecificProvider(
                            channel.provider,
                            fallbackId,
                            turns,
                            ExternalAiKeyAndDiscovery.resolveChannelKey(channel, context, channelKeyOverrides),
                            withSearchGrounding
                        )
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
                android.util.Log.w("ExternalAiProviders", "Channel ${channel.id} failed: ${e.message}")
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
                val pollKey = context?.let { AyraPreferences.getPollinationsKey(it) }.orEmpty()
                val fallbackText = ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                    httpClient, baseUrlOverrides, prompt, systemPrompt, apiKey = pollKey
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
            } catch (e: Exception) {
                android.util.Log.w("ExternalAiProviders", "Pollinations free fallback failed: ${e.message}")
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
    ): ProviderResponse = ExternalAiStreamingSupport.generateChatResponseWithReasoning(
        owner = this,
        context = context,
        httpClient = httpClient,
        customHttpClient = customHttpClient,
        baseUrlOverrides = baseUrlOverrides,
        prompt = prompt,
        systemPrompt = systemPrompt,
        history = history,
        preferredProvider = preferredProvider,
        preferredModelId = preferredModelId,
        resolveClientForProvider = ::resolveClientForProvider
    )

    fun streamChatResponse(
        prompt: String,
        systemPrompt: String,
        history: List<ChatMessage>? = null,
        imageBase64: String? = null,
        preferredProvider: AiProviderType? = null,
        preferredModelId: String? = null
    ): Flow<String> = ExternalAiStreamingSupport.streamChatResponse(
        owner = this,
        context = context,
        httpClient = httpClient,
        customHttpClient = customHttpClient,
        baseUrlOverrides = baseUrlOverrides,
        prompt = prompt,
        systemPrompt = systemPrompt,
        history = history,
        imageBase64 = imageBase64,
        preferredProvider = preferredProvider,
        preferredModelId = preferredModelId,
        resolveClientForProvider = ::resolveClientForProvider
    )

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
                httpClient, baseUrlOverrides, usr, sys, model.ifBlank { ModelCatalog.POLLINATIONS_OPENAI_MODEL }, conversationTurns = turns, apiKey = apiKey
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

    fun getKeyForProviderAndModel(provider: AiProviderType, modelId: String): String =
        ExternalAiKeyAndDiscovery.getKeyForProviderAndModel(provider, modelId, context, channelKeyOverrides)

    fun getProviderKey(provider: AiProviderType): String =
        ExternalAiKeyAndDiscovery.getProviderKey(provider, context, channelKeyOverrides)

    fun isProviderConfigured(provider: AiProviderType): Boolean =
        ExternalAiKeyAndDiscovery.isProviderConfigured(provider, context, isTestHarnessMode, baseUrlOverrides, channelKeyOverrides)

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
        ExternalAiKeyAndDiscovery.getSavedKeyProvidersIgnoringConsent(context, channelKeyOverrides)

    fun getDefaultModelForProvider(provider: AiProviderType): String = ExternalAiCatalog.defaultModelForProvider(provider)

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

    suspend fun discoverDynamicModels(provider: AiProviderType): List<AiModelInfo> =
        ExternalAiKeyAndDiscovery.discoverDynamicModels(
            provider = provider,
            context = context,
            httpClient = httpClient,
            baseUrlOverrides = baseUrlOverrides,
            channelKeyOverrides = channelKeyOverrides,
            isTestHarnessMode = isTestHarnessMode,
            dynamicModelCache = dynamicModelCache
        )

    suspend fun transcribeAudioOpenAiCompatible(
        audioFile: File,
        promptHint: String = ""
    ): String = ExternalAiKeyAndDiscovery.transcribeAudioOpenAiCompatible(
        audioFile = audioFile,
        promptHint = promptHint,
        context = context,
        httpClient = httpClient,
        customHttpClient = customHttpClient,
        baseUrlOverrides = baseUrlOverrides,
        channelKeyOverrides = channelKeyOverrides
    )
}
