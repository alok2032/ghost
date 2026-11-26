package com.ayra.ai.app.ai.providers

import android.content.Context
import android.util.Log
import com.ayra.ai.app.ai.AutonomousKnowledgeEngine
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.agents.AgentDomain
import com.ayra.ai.app.ai.agents.AyraAgentOrchestrator
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.search.TavilySearchEngine
import com.ayra.ai.app.security.BlockedByPolicyException
import com.ayra.ai.app.security.SecureAiGatewayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.random.Random

/**
 * Unified Multi-AI Orchestration Pool for Ayra AI.
 * - Question-aware specialist ranking using [AyraAgentOrchestrator].
 * - Parallel Multi-Layer Wiki Grounding + Tavily web grounding.
 * - Non-blocking Channel Quorum Ensemble with live token-streamed Consensus Synthesis.
 * - Zero fake echoes or canned templates; honest typed errors when offline.
 * - Per-channel circuit breaker isolation and bounded SHA-256 LRU cache.
 */
class ProviderPool @JvmOverloads constructor(
    private val context: Context? = null,
    private val externalProviders: ExternalAiProviders = ExternalAiProviders(context)
) {

    internal data class RouteBTestConfig(
        val baseUrl: String,
        val bearerToken: String,
        val httpClient: OkHttpClient
    )

    internal var routeBTestOverride: RouteBTestConfig? = null

    val circuitBreaker: ProviderCircuitBreaker get() = externalProviders.circuitBreaker

    private val firebaseAiEngine by lazy { FirebaseAiEngine(context) }

    // LRU Cache: Max 100 entries, 10-minute TTL
    private val memoryLruCache: MutableMap<String, ProviderPoolCacheEntry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ProviderPoolCacheEntry>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProviderPoolCacheEntry>?): Boolean {
                return size > 100
            }
        }
    )

    companion object {
        @JvmStatic
        fun isTimeSensitiveQuery(prompt: String): Boolean =
            ProviderPoolQueryClassifier.isTimeSensitiveQuery(prompt)
    }

    enum class TaskComplexity { TRIVIAL, STANDARD, COMPLEX }

    fun isProviderAvailable(provider: AiProviderType): Boolean {
        if (!circuitBreaker.canExecute(provider)) return false
        return externalProviders.isProviderConfigured(provider)
    }

    fun getAvailableProviders(): List<AiProviderType> {
        return externalProviders.getActiveConfiguredProviders().filter { circuitBreaker.canExecute(it) }
    }

    fun clearCache() {
        memoryLruCache.clear()
    }

    internal fun isTrivialGreeting(prompt: String): Boolean =
        ProviderPoolQueryClassifier.isTrivialGreeting(prompt)

    internal fun classifyTaskComplexity(request: ChatRequest): TaskComplexity =
        ProviderPoolQueryClassifier.classifyTaskComplexity(request)

    internal fun selectDiverseEnsembleChannels(channels: List<AiModelChannel>, maxCount: Int): List<AiModelChannel> =
        ProviderRoutingSupport.selectDiverseEnsembleChannels(channels, maxCount)

    suspend fun executeSync(request: ChatRequest): ProviderResponse = withContext(Dispatchers.IO) {
        var finalResp: ProviderResponse? = null
        var terminalError: AyraError? = null
        streamChat(request).collect { event ->
            when (event) {
                is AyraStreamEvent.Complete -> finalResp = event.response
                is AyraStreamEvent.Error -> terminalError = event.error
                else -> {}
            }
        }
        finalResp ?: throw (terminalError ?: AyraError.NoProviderConfigured)
    }

    fun streamChat(request: ChatRequest): Flow<AyraStreamEvent> = flow {
        val cacheKey = ProviderPoolQueryClassifier.computeCacheKey(context, request)
        if (cacheKey != null) {
            val cached = memoryLruCache[cacheKey]
            if (cached != null) {
                if (System.currentTimeMillis() - cached.timestamp < 10 * 60 * 1000L) {
                    emit(AyraStreamEvent.Complete(cached.response))
                    return@flow
                } else {
                    memoryLruCache.remove(cacheKey)
                }
            }
        }

        val domain = AyraAgentOrchestrator.classifyIntent(request.prompt)
        val isLocalOnlyMode = context?.let { AyraPreferences.isLocalOnlyAiModeEnabled(it) } == true
        val channels = externalProviders.getActiveChannels(
            domainName = domain.name,
            hasImage = !request.imageBase64.isNullOrBlank()
        ).filter { ch ->
            externalProviders.isTestHarnessMode ||
                SecureAiGatewayClient.evaluateProviderAccess(context, ch.provider).allowed
        }
        val availableProviders = getAvailableProviders().filter { p ->
            externalProviders.isTestHarnessMode ||
                SecureAiGatewayClient.evaluateProviderAccess(context, p).allowed
        }

        val isStrictLocalRouting = request.routingMode == AiRoutingMode.PRIVACY_LOCAL &&
            channels.any { it.provider == AiProviderType.LOCAL_OLLAMA }
        val routedChannels = ProviderRoutingSupport.prioritizeChannelsForRoutingMode(channels, request.routingMode)
        val routedProviders = if (isStrictLocalRouting) {
            availableProviders.filter { it == AiProviderType.LOCAL_OLLAMA }.ifEmpty { availableProviders }
        } else {
            availableProviders
        }

        val isFirebaseAiReady = !isLocalOnlyMode &&
            !isStrictLocalRouting &&
            FirebaseAiEngine.isAvailable(context)

        val isRouteBReady = !isLocalOnlyMode &&
            !isStrictLocalRouting &&
            ((routeBTestOverride != null &&
                context != null &&
                AyraPreferences.hasExternalAiConsent(context) &&
                !AyraPreferences.isByokPreferred(context)) ||
                SecureAiGatewayClient.shouldUseBackendGatewayProxy(
                    context = context,
                    hasExplicitTestOverrides = externalProviders.isTestHarnessMode
                ))

        if (routedChannels.isEmpty() && routedProviders.isEmpty() && !isFirebaseAiReady && !isRouteBReady) {
            val savedIgnoringConsent = if (!externalProviders.isTestHarnessMode && context != null) {
                externalProviders.getSavedKeyProvidersIgnoringConsent()
            } else {
                emptyList()
            }
            if (isLocalOnlyMode && savedIgnoringConsent.isNotEmpty() && savedIgnoringConsent.none { it == AiProviderType.LOCAL_OLLAMA }) {
                emit(
                    AyraStreamEvent.Error(
                        AyraError.ProviderDown(
                            savedIgnoringConsent.first(),
                            403,
                            "Local-Only Privacy Mode is active. Configure Local Ollama or disable Local-Only Mode in Security Center to use cloud AI."
                        )
                    )
                )
            } else if (context != null && !AyraPreferences.hasExternalAiConsent(context) && savedIgnoringConsent.isNotEmpty()) {
                val names = savedIgnoringConsent.take(3).joinToString(", ") { it.displayName }
                emit(
                    AyraStreamEvent.Error(
                        AyraError.ProviderDown(
                            savedIgnoringConsent.first(),
                            403,
                            "API keys are saved ($names), but External Cloud AI Consent is OFF. Enable External Cloud AI Consent in Settings -> AI Providers or Security Center."
                        )
                    )
                )
            } else {
                emit(AyraStreamEvent.Error(AyraError.NoProviderConfigured))
            }
            return@flow
        }

        val isBengali = request.prompt.any { it in '\u0980'..'\u09FF' }
        val hasTavilyKey = context != null && AyraPreferences.getTavilySearchKey(context).isNotBlank()
        val shouldGroundWiki = !isLocalOnlyMode &&
            !isStrictLocalRouting &&
            externalProviders.allowExternalWikiGrounding &&
            request.imageBase64 == null &&
            request.documentText == null &&
            !isTrivialGreeting(request.prompt) &&
            (AyraAgentOrchestrator.needsLiveWikiGrounding(request.prompt, domain) ||
                (request.prompt.trim().length >= 8 && AutonomousKnowledgeEngine.tryCalculateMath(request.prompt) == null))

        supervisorScope {
            val wikiDeferred: Deferred<String>? = if (shouldGroundWiki) {
                async(Dispatchers.IO) {
                    withTimeoutOrNull(4_000L) {
                        val wikiJob = async {
                            runCatching {
                                AutonomousKnowledgeEngine.queryMultiLayerWiki(
                                    prompt = request.prompt,
                                    isBengali = isBengali
                                )
                            }.getOrDefault("")
                        }
                        val tavilyJob = context?.takeIf { hasTavilyKey }?.let { ctx ->
                            async {
                                runCatching {
                                    TavilySearchEngine(ctx)
                                        .search(request.prompt, maxResults = 3, searchDepth = "basic")
                                        .formattedSummary
                                }.getOrDefault("")
                            }
                        }
                        val wikiRes = wikiJob.await()
                        val tavilyRes = tavilyJob?.await().orEmpty()
                        listOf(tavilyRes, wikiRes).filter { it.isNotBlank() }.joinToString("\n\n")
                    } ?: ""
                }
            } else null

            val effectiveThinkingMode = request.thinkingMode ?: context?.let { AyraPreferences.getThinkingMode(it) } ?: "balanced"
            val complexity = classifyTaskComplexity(request)
            val effectiveMode = when {
                request.mode != ProviderPoolMode.AUTO -> request.mode
                complexity == TaskComplexity.TRIVIAL -> ProviderPoolMode.FAST
                request.routingMode == AiRoutingMode.FAST -> ProviderPoolMode.FAST
                request.routingMode == AiRoutingMode.BEST_QUALITY ||
                    request.routingMode == AiRoutingMode.REASONING ||
                    request.routingMode == AiRoutingMode.CODING -> ProviderPoolMode.BEST
                effectiveThinkingMode == "fast" -> ProviderPoolMode.FAST
                effectiveThinkingMode in setOf("deep_analysis", "reasoning", "research") -> ProviderPoolMode.BEST
                complexity == TaskComplexity.COMPLEX -> ProviderPoolMode.BEST
                else -> ProviderPoolMode.AUTO
            }
            val effectiveRequest = if (effectiveMode != request.mode) request.copy(mode = effectiveMode) else request

            if (routeBTestOverride != null || (isRouteBReady && routedChannels.size < 2 && !isFirebaseAiReady)) {
                executeChannelFallback(effectiveRequest, routedChannels, routedProviders, cacheKey, domain, wikiDeferred, this@flow)
                return@supervisorScope
            }

            if (effectiveRequest.preferredProvider != null && effectiveRequest.preferredProvider != AiProviderType.OMNI_ROUTE) {
                val filtered = routedChannels.filter { it.provider == effectiveRequest.preferredProvider } +
                    routedChannels.filter { it.provider != effectiveRequest.preferredProvider }
                executeChannelFallback(effectiveRequest, filtered, routedProviders, cacheKey, domain, wikiDeferred, this@flow)
                return@supervisorScope
            }

            if (effectiveRequest.mode == ProviderPoolMode.FAST ||
                isStrictLocalRouting ||
                (effectiveRequest.mode == ProviderPoolMode.AUTO && isTrivialGreeting(effectiveRequest.prompt))
            ) {
                executeChannelFallback(effectiveRequest, routedChannels, routedProviders, cacheKey, domain, wikiDeferred, this@flow)
                return@supervisorScope
            }

            val targetMax = if (effectiveRequest.mode == ProviderPoolMode.BEST) 10 else 8
            val ensembleChannels = selectDiverseEnsembleChannels(routedChannels, maxCount = targetMax)
            val totalEnsembleWorkers = ensembleChannels.size + if (isFirebaseAiReady) 1 else 0
            if (totalEnsembleWorkers >= 2) {
                ProviderEnsembleExecutor.executeMultiAiEnsemble(
                    context = context,
                    externalProviders = externalProviders,
                    firebaseAiEngine = firebaseAiEngine,
                    circuitBreaker = circuitBreaker,
                    request = effectiveRequest,
                    ensembleChannels = ensembleChannels,
                    allChannels = routedChannels,
                    domain = domain,
                    wikiDeferred = wikiDeferred,
                    collector = this@flow,
                    onCacheStore = { resp ->
                        cacheKey?.let { memoryLruCache[it] = ProviderPoolCacheEntry(resp, System.currentTimeMillis()) }
                    },
                    onFallbackToSequential = { remainingChannels, priorErr ->
                        executeChannelFallback(
                            request = effectiveRequest,
                            channels = remainingChannels,
                            fallbackProviders = routedProviders,
                            cacheKey = cacheKey,
                            domain = domain,
                            wikiDeferred = wikiDeferred,
                            collector = this@flow,
                            priorError = priorErr
                        )
                    }
                )
            } else {
                executeChannelFallback(effectiveRequest, routedChannels, routedProviders, cacheKey, domain, wikiDeferred, this@flow)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeChannelFallback(
        request: ChatRequest,
        channels: List<AiModelChannel>,
        fallbackProviders: List<AiProviderType>,
        cacheKey: String?,
        domain: AgentDomain,
        wikiDeferred: Deferred<String>?,
        collector: FlowCollector<AyraStreamEvent>,
        priorError: AyraError? = null
    ) {
        val wikiContext = if (wikiDeferred != null) {
            withTimeoutOrNull(3_200L) { wikiDeferred.await() } ?: ""
        } else ""

        val sysPrompt = ProviderRoutingSupport.resolveSystemPrompt(context, request, domain, wikiContext)
        val privacyRedactionOn = context?.let { AyraPreferences.isPrivacyModeEnabled(it) } == true
        val safePrompt = if (privacyRedactionOn) {
            SecureAiGatewayClient.redactSecretsForOutboundSafety(request.prompt)
        } else request.prompt
        val turns = externalProviders.buildConversationTurns(
            systemPrompt = sysPrompt,
            history = request.history,
            currentPrompt = safePrompt,
            currentImageBase64 = request.imageBase64
        )
        var lastError: AyraError? = priorError

        val candidateList: List<AiModelChannel> = if (channels.isNotEmpty()) {
            channels
        } else {
            fallbackProviders.map { p ->
                val defaultModel = if (request.preferredProvider == p && !request.preferredModelId.isNullOrBlank()) {
                    request.preferredModelId
                } else {
                    externalProviders.getDefaultModelForProvider(p)
                }
                AiModelChannel(
                    id = "fallback_${p.id}",
                    provider = p,
                    modelId = defaultModel,
                    displayName = p.displayName,
                    specialties = setOf("general"),
                    supportsVision = p == AiProviderType.GEMINI || p == AiProviderType.OPENAI || p == AiProviderType.ANTHROPIC,
                    keyResolver = { externalProviders.getProviderKey(p) }
                )
            }
        }

        var triedFirebaseAi = false
        suspend fun tryFirebaseAiLogic(): Boolean {
            triedFirebaseAi = true
            val fbModel = firebaseAiEngine.selectModelForTask(
                modelTier = request.mode.name.lowercase(),
                prompt = request.prompt,
                preferReasoning = request.mode == ProviderPoolMode.BEST ||
                    request.routingMode == AiRoutingMode.REASONING ||
                    request.routingMode == AiRoutingMode.BEST_QUALITY
            )
            collector.emit(AyraStreamEvent.Thinking("Firebase Gemini ($fbModel) 🌐"))
            val fbStreamed = StringBuilder()
            return try {
                firebaseAiEngine.streamText(
                    prompt = safePrompt,
                    systemPrompt = sysPrompt,
                    history = request.history,
                    imageBase64 = request.imageBase64,
                    modelName = fbModel,
                    enableSearchGrounding = !isTrivialGreeting(request.prompt)
                ).collect { chunk ->
                    fbStreamed.append(chunk)
                    collector.emit(AyraStreamEvent.Token(chunk))
                }
                val finalFb = ExternalAiProviders.sanitizeModelOutput(fbStreamed.toString())
                if (finalFb.isNotBlank()) {
                    val resp = ProviderResponse(
                        text = finalFb,
                        modelUsed = "$fbModel (Firebase AI) ⚡",
                        provider = AiProviderType.GEMINI
                    )
                    cacheKey?.let { memoryLruCache[it] = ProviderPoolCacheEntry(resp, System.currentTimeMillis()) }
                    collector.emit(AyraStreamEvent.Complete(resp))
                    true
                } else {
                    lastError = AyraError.ProviderDown(AiProviderType.GEMINI, 502, "Firebase AI returned an empty response.")
                    false
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                lastError = ProviderRoutingSupport.mapExceptionToAyraError(
                    context, AiProviderType.GEMINI, e, isFirebaseKeyless = !externalProviders.isProviderConfigured(AiProviderType.GEMINI)
                )
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = ProviderRoutingSupport.mapExceptionToAyraError(
                    context, AiProviderType.GEMINI, e, isFirebaseKeyless = !externalProviders.isProviderConfigured(AiProviderType.GEMINI)
                )
                false
            }
        }

        suspend fun tryRouteBBackendGateway(): Boolean {
            val appCtx = context ?: return false
            val testCfg = routeBTestOverride
            val routeBAllowed = if (testCfg != null) {
                !AyraPreferences.isLocalOnlyAiModeEnabled(appCtx) &&
                    AyraPreferences.hasExternalAiConsent(appCtx) &&
                    !AyraPreferences.isByokPreferred(appCtx)
            } else {
                SecureAiGatewayClient.shouldUseBackendGatewayProxy(
                    context = appCtx,
                    hasExplicitTestOverrides = externalProviders.isTestHarnessMode
                )
            }
            if (!routeBAllowed || !request.imageBase64.isNullOrBlank()) return false
            val gatewayUrl = testCfg?.baseUrl
                ?: SecureAiGatewayClient.getValidatedBackendGatewayUrl(appCtx).orEmpty()
            if (gatewayUrl.isBlank()) return false

            val mappedProvider = request.preferredProvider?.takeIf {
                it in setOf(AiProviderType.GEMINI, AiProviderType.OPENROUTER, AiProviderType.NVIDIA_NIM, AiProviderType.GROQ)
            } ?: AiProviderType.GEMINI
            val gatewaySlug = SecureAiGatewayClient.mapProviderToGatewaySlug(mappedProvider)
            val appSavedTier = AyraPreferences.getSavedModelTier(appCtx).trim().lowercase()
            val gatewayModel = request.preferredModelId?.takeIf { it.isNotBlank() } ?: when (gatewaySlug) {
                "groq" -> ExternalAiProviders.GROQ_QWEN_MODEL
                "openrouter" -> ExternalAiProviders.OPENROUTER_DEFAULT_MODEL
                "nvidia" -> ExternalAiProviders.NVIDIA_LLAMA_MODEL
                else -> if (appSavedTier == "gemini_pro") FirebaseAiEngine.MODEL_PRO_REASONING else FirebaseAiEngine.MODEL_FLASH
            }

            collector.emit(AyraStreamEvent.Thinking("Ayra Zero-Trust Gateway ($gatewaySlug) 🛡️"))
            val proxyResult = SecureAiGatewayClient.callAuthenticatedBackendAiProxy(
                context = appCtx,
                backendBaseUrl = gatewayUrl,
                prompt = safePrompt,
                model = gatewayModel,
                provider = gatewaySlug,
                systemPrompt = sysPrompt,
                httpClient = testCfg?.httpClient,
                bearerTokenOverride = testCfg?.bearerToken,
                allowUnvalidatedUrlForTests = testCfg != null
            )
            val cleanText = ExternalAiProviders.sanitizeModelOutput(proxyResult.getOrNull().orEmpty())
            if (proxyResult.isSuccess && cleanText.isNotBlank()) {
                collector.emit(AyraStreamEvent.Token(cleanText))
                val resp = ProviderResponse(text = cleanText, modelUsed = "$gatewayModel (Route B Gateway) 🛡️", provider = mappedProvider)
                cacheKey?.let { memoryLruCache[it] = ProviderPoolCacheEntry(resp, System.currentTimeMillis()) }
                collector.emit(AyraStreamEvent.Complete(resp))
                return true
            }
            val err = proxyResult.exceptionOrNull()
            if (err != null) {
                Log.w("ProviderPool", "Route B backend gateway failed (${err.message}); falling back")
                lastError = AyraError.ProviderDown(mappedProvider, 502, err.message ?: "Route B gateway unavailable")
            }
            return false
        }

        if (request.routingMode != AiRoutingMode.PRIVACY_LOCAL && tryRouteBBackendGateway()) return

        val fastPrefersUltraLowLatency = request.routingMode == AiRoutingMode.FAST &&
            candidateList.firstOrNull()?.provider in setOf(AiProviderType.GROQ, AiProviderType.CEREBRAS, AiProviderType.LOCAL_OLLAMA)
        val firebaseCanHandlePrimary = request.routingMode != AiRoutingMode.PRIVACY_LOCAL &&
            !(externalProviders.isTestHarnessMode && request.preferredProvider != AiProviderType.GEMINI) &&
            !fastPrefersUltraLowLatency &&
            FirebaseAiEngine.isAvailable(context) &&
            (request.preferredProvider == null || request.preferredProvider == AiProviderType.GEMINI || candidateList.isEmpty())

        if (firebaseCanHandlePrimary && tryFirebaseAiLogic()) return

        for (channel in candidateList) {
            val modelId = if (request.preferredProvider == channel.provider && !request.preferredModelId.isNullOrBlank()) {
                request.preferredModelId
            } else channel.modelId

            collector.emit(AyraStreamEvent.Thinking("${channel.displayName} (${domain.title})"))
            val tokensAccumulator = StringBuilder()

            try {
                var attempt = 0
                while (attempt < 2) {
                    attempt++
                    tokensAccumulator.clear()
                    try {
                        externalProviders.streamChatResponse(
                            prompt = safePrompt,
                            systemPrompt = sysPrompt,
                            history = request.history,
                            imageBase64 = request.imageBase64,
                            preferredProvider = channel.provider,
                            preferredModelId = modelId
                        ).collect { token ->
                            tokensAccumulator.append(token)
                            collector.emit(AyraStreamEvent.Token(token))
                        }

                        val rawText = if (tokensAccumulator.isNotBlank()) {
                            ExternalAiProviders.sanitizeModelOutput(tokensAccumulator.toString())
                        } else {
                            val overrideChannel = if (modelId != channel.modelId) channel.copy(modelId = modelId) else channel
                            externalProviders.callChannel(overrideChannel, turns, withSearchGrounding = true)
                        }

                        if (rawText.isNotBlank()) {
                            circuitBreaker.recordSuccess(channel.provider)
                            circuitBreaker.recordChannelSuccess(channel.id)
                            val finalResponse = ProviderResponse(text = rawText, modelUsed = channel.displayName, provider = channel.provider)
                            cacheKey?.let { memoryLruCache[it] = ProviderPoolCacheEntry(finalResponse, System.currentTimeMillis()) }
                            collector.emit(AyraStreamEvent.Complete(finalResponse))
                            return
                        }
                        break
                    } catch (e: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        lastError = ProviderRoutingSupport.mapExceptionToAyraError(context, channel.provider, e)
                        circuitBreaker.recordChannelFailure(channel.id)
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val mappedError = ProviderRoutingSupport.mapExceptionToAyraError(context, channel.provider, e)
                        if (lastError !is AyraError.SecurityPolicyBlocked || mappedError is AyraError.SecurityPolicyBlocked) {
                            lastError = mappedError
                        }
                        val shouldRecordFailure = e !is ProviderError.ModelNotFound &&
                            e !is BlockedByPolicyException &&
                            e !is SecurityException
                        if (attempt < 2 && mappedError is AyraError.RateLimited) {
                            delay(350L + Random.nextLong(50, 150))
                        } else {
                            if (shouldRecordFailure) circuitBreaker.recordChannelFailure(channel.id)
                            break
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                circuitBreaker.recordChannelFailure(channel.id)
                if (lastError !is AyraError.SecurityPolicyBlocked) {
                    lastError = ProviderRoutingSupport.mapExceptionToAyraError(context, channel.provider, e)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is ProviderError.ModelNotFound && e !is BlockedByPolicyException && e !is SecurityException) {
                    circuitBreaker.recordChannelFailure(channel.id)
                }
                val mappedError = ProviderRoutingSupport.mapExceptionToAyraError(context, channel.provider, e)
                if (lastError !is AyraError.SecurityPolicyBlocked || mappedError is AyraError.SecurityPolicyBlocked) {
                    lastError = mappedError
                }
            }
        }

        if (!triedFirebaseAi &&
            request.routingMode != AiRoutingMode.PRIVACY_LOCAL &&
            !(externalProviders.isTestHarnessMode && request.preferredProvider != AiProviderType.GEMINI) &&
            FirebaseAiEngine.isAvailable(context) &&
            tryFirebaseAiLogic()
        ) {
            return
        }

        val savedWithoutConsent = if (!externalProviders.isTestHarnessMode &&
            context != null &&
            !AyraPreferences.hasExternalAiConsent(context)
        ) {
            externalProviders.getSavedKeyProvidersIgnoringConsent()
        } else emptyList()

        if (candidateList.isEmpty() && savedWithoutConsent.isNotEmpty()) {
            val names = savedWithoutConsent.take(3).joinToString(", ") { it.displayName }
            val fbNote = lastError?.userFriendlyMessage?.let { " (Built-in Firebase AI: $it)" }.orEmpty()
            collector.emit(
                AyraStreamEvent.Error(
                    AyraError.ProviderDown(
                        savedWithoutConsent.first(),
                        403,
                        "API keys are saved ($names), but External Cloud AI Consent is OFF. Enable External Cloud AI Consent in Settings -> AI Providers or Security Center.$fbNote"
                    )
                )
            )
            return
        }

        collector.emit(AyraStreamEvent.Error(lastError ?: AyraError.NoProviderConfigured))
    }
}
