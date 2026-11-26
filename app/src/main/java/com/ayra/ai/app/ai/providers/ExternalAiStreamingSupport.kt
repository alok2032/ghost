package com.ayra.ai.app.ai.providers

import android.content.Context
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.security.SecureAiGatewayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal object ExternalAiStreamingSupport {

    suspend fun generateChatResponseWithReasoning(
        owner: ExternalAiProviders,
        context: Context?,
        httpClient: OkHttpClient,
        customHttpClient: OkHttpClient?,
        baseUrlOverrides: Map<AiProviderType, String>,
        prompt: String,
        systemPrompt: String,
        history: List<ChatMessage>?,
        preferredProvider: AiProviderType?,
        preferredModelId: String?,
        resolveClientForProvider: (AiProviderType?) -> OkHttpClient
    ): ProviderResponse = withContext(Dispatchers.IO) {
        val turns = owner.buildConversationTurns(systemPrompt, history, prompt, null)
        val startTime = System.currentTimeMillis()

        val candidates = mutableListOf<Pair<AiProviderType, String>>()
        if (preferredProvider != null && owner.isProviderConfigured(preferredProvider) && owner.circuitBreaker.canExecute(preferredProvider)) {
            candidates.add(preferredProvider to (preferredModelId ?: owner.getDefaultModelForProvider(preferredProvider)))
        }
        val reasoningFallbacks = listOf(
            AiProviderType.DEEPSEEK to ModelCatalog.DEEPSEEK_REASONER_MODEL,
            AiProviderType.SAMBANOVA to ModelCatalog.SAMBANOVA_DEEPSEEK_MODEL,
            AiProviderType.OPENROUTER to ModelCatalog.OPENROUTER_QWEN_FREE_MODEL,
            AiProviderType.GROQ to ModelCatalog.GROQ_QWEN_MODEL,
            AiProviderType.CEREBRAS to ModelCatalog.CEREBRAS_QWEN_MODEL,
            AiProviderType.POLLINATIONS_FREE to ModelCatalog.POLLINATIONS_DEEPSEEK_MODEL
        )
        reasoningFallbacks.forEach { pair ->
            if (candidates.none { it.first == pair.first && it.second == pair.second } &&
                owner.isProviderConfigured(pair.first) && owner.circuitBreaker.canExecute(pair.first)
            ) candidates.add(pair)
        }

        for ((provider, model) in candidates) {
            val key = owner.getKeyForProviderAndModel(provider, model)
            if (key.isBlank() && provider != AiProviderType.LOCAL_OLLAMA && provider != AiProviderType.POLLINATIONS_FREE) continue
            val callStart = System.currentTimeMillis()
            try {
                val text = when (provider) {
                    AiProviderType.POLLINATIONS_FREE -> ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                        httpClient, baseUrlOverrides, prompt, systemPrompt, model, preserveReasoning = true, conversationTurns = turns, apiKey = key
                    )
                    AiProviderType.GEMINI, AiProviderType.ANTHROPIC, AiProviderType.OMNI_ROUTE ->
                        owner.callSpecificProviderPublic(provider, model, turns, key, false)
                    else -> ProviderOpenAiTransport.callOpenAiCompatible(
                        resolveClientForProvider(provider), context, provider, baseUrlOverrides,
                        customHttpClient != null, turns, model, key, maxTokens = 8192, preserveReasoning = true
                    )
                }
                val latency = (System.currentTimeMillis() - callStart).coerceAtLeast(1L)
                if (text.isNotBlank()) {
                    AiUsageTracker.recordCall(context, provider, true, latency)
                    owner.circuitBreaker.recordSuccess(provider)
                    return@withContext ProviderResponse(
                        text = text,
                        modelUsed = "${provider.displayName} ($model)",
                        provider = provider,
                        latencyMs = System.currentTimeMillis() - startTime
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ExternalAiStreaming", "Reasoning fallback failed for ${provider.id}: ${e.message}")
                AiUsageTracker.recordCall(context, provider, false, System.currentTimeMillis() - callStart)
            }
        }

        return@withContext owner.generateChatResponse(
            prompt = prompt, systemPrompt = systemPrompt, history = history,
            preferredProvider = preferredProvider, preferredModelId = preferredModelId, withSearchGrounding = false
        )
    }

    fun streamChatResponse(
        owner: ExternalAiProviders,
        context: Context?,
        httpClient: OkHttpClient,
        customHttpClient: OkHttpClient?,
        baseUrlOverrides: Map<AiProviderType, String>,
        prompt: String,
        systemPrompt: String,
        history: List<ChatMessage>?,
        imageBase64: String?,
        preferredProvider: AiProviderType?,
        preferredModelId: String?,
        resolveClientForProvider: (AiProviderType?) -> OkHttpClient
    ): Flow<String> = flow {
        val turns = owner.buildConversationTurns(systemPrompt, history, prompt, imageBase64)
        val provider = preferredProvider ?: owner.getBestAvailableProvider()
        if (!owner.isTestHarnessMode) {
            val gate = SecureAiGatewayClient.evaluateProviderAccess(context, provider)
            if (!gate.allowed) throw ProviderError.NetworkFailure(gate.denialReason ?: "Provider blocked by Zero-Trust policy.")
        }
        val initialModel = preferredModelId ?: (ModelCatalog.defaultModelFor(provider)?.id ?: owner.getDefaultModelForProvider(provider))
        val outerCollector: FlowCollector<String> = this

        suspend fun streamSingleAttempt(activeModel: String): Boolean {
            val activeKey = owner.getKeyForProviderAndModel(provider, activeModel)
            var emitted = false
            val capturingCollector = FlowCollector<String> { chunk ->
                if (chunk.isNotEmpty()) {
                    emitted = true
                    outerCollector.emit(chunk)
                }
            }
            when (provider) {
                AiProviderType.GEMINI -> ProviderGeminiTransport.streamGemini(
                    httpClient, baseUrlOverrides[AiProviderType.GEMINI], turns, activeModel, activeKey, capturingCollector
                )
                AiProviderType.ANTHROPIC -> ProviderAnthropicAndFreeTransport.streamAnthropic(
                    httpClient, baseUrlOverrides, turns, activeModel, activeKey, capturingCollector
                )
                AiProviderType.POLLINATIONS_FREE -> {
                    val text = ProviderAnthropicAndFreeTransport.queryPollinationsFallback(
                        httpClient, baseUrlOverrides, prompt, systemPrompt, activeModel.ifBlank { ModelCatalog.POLLINATIONS_OPENAI_MODEL }, conversationTurns = turns, apiKey = activeKey
                    )
                    if (text.isBlank()) throw ProviderError.NetworkFailure("Free AI Pool unreachable")
                    capturingCollector.emit(text)
                }
                AiProviderType.OMNI_ROUTE -> ProviderGeminiTransport.streamGemini(
                    httpClient, baseUrlOverrides[AiProviderType.GEMINI], turns, ModelCatalog.GEMINI_FLASH_MODEL, activeKey, capturingCollector
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
                        owner.circuitBreaker.recordSuccess(provider)
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
}
