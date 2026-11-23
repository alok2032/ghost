package com.ayra.ai.app.ai.providers

import android.content.Context
import android.util.Log
import com.ayra.ai.app.ai.AutonomousKnowledgeEngine
import com.ayra.ai.app.ai.agents.AgentDomain
import com.ayra.ai.app.ai.agents.AyraAgentOrchestrator
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

enum class ProviderPoolMode {
    FAST,
    BEST,
    AUTO
}

sealed class AyraError(val userFriendlyMessage: String) : Exception(userFriendlyMessage) {
    data class AuthError(val provider: AiProviderType, val details: String = "") :
        AyraError("API key rejected for ${provider.displayName}. Please verify your key in Settings -> Keys.")
    data class RateLimited(val provider: AiProviderType, val retryAfterSeconds: Long? = null) :
        AyraError("Rate limit reached (429) for ${provider.displayName}.${if (retryAfterSeconds != null) " Retry in ${retryAfterSeconds}s." else " Please wait a moment."}")
    data class Timeout(val provider: AiProviderType, val details: String = "Inference timed out.") :
        AyraError("Request to ${provider.displayName} timed out. Please try again.")
    data class NoNetwork(val details: String = "No internet connection.") :
        AyraError("No internet connection. Please check your network and try again.")
    data class ProviderDown(val provider: AiProviderType, val statusCode: Int, val details: String = "") :
        AyraError("${provider.displayName} is currently unavailable (HTTP $statusCode).${if (details.isNotBlank()) " $details" else ""}")
    data class ContentBlocked(val reason: String = "Safety filter triggered.") :
        AyraError("The request was blocked: $reason")
    object NoProviderConfigured :
        AyraError("No AI provider is configured. Please add an API key in Settings -> Keys to start chatting.")
}

data class ChatRequest(
    val prompt: String,
    val persona: String = "AI_ASSISTANT",
    val mode: ProviderPoolMode = ProviderPoolMode.AUTO,
    val history: List<ChatMessage> = emptyList(),
    val imageBase64: String? = null,
    val documentText: String? = null,
    val systemPromptOverride: String? = null,
    val preferredProvider: AiProviderType? = null,
    val preferredModelId: String? = null,
    val activePluginContext: String? = null,
    val activePluginNames: List<String> = emptyList(),
    val thinkingMode: String? = null,
    val workspaceContext: String? = null,
    val routingMode: AiRoutingMode = AiRoutingMode.AUTO
)

sealed class AyraStreamEvent {
    data class Thinking(val model: String) : AyraStreamEvent()
    data class Token(val text: String) : AyraStreamEvent()
    data class Draft(val model: String, val text: String) : AyraStreamEvent()
    data class Complete(val response: ProviderResponse) : AyraStreamEvent()
    data class Error(val error: AyraError) : AyraStreamEvent()
}

/**
 * Unified Multi-AI Orchestration Pool for Ayra AI.
 * - Question-aware specialist ranking using [AyraAgentOrchestrator].
 * - Parallel Multi-Layer Wiki Grounding (Surface Wiki + Deep Web Academic + Dark Web Technical Wiki).
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
        val httpClient: okhttp3.OkHttpClient
    )

    internal var routeBTestOverride: RouteBTestConfig? = null

    val circuitBreaker: ProviderCircuitBreaker get() = externalProviders.circuitBreaker

    private val firebaseAiEngine by lazy {
        com.ayra.ai.app.ai.FirebaseAiEngine(context)
    }

    // LRU Cache: Max 100 entries, 10-minute TTL
    private data class CacheEntry(val response: ProviderResponse, val timestamp: Long)
    private val memoryLruCache: MutableMap<String, CacheEntry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CacheEntry>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
                return size > 100
            }
        }
    )

    companion object {
        private val LATIN_TIME_SENSITIVE_REGEX = Regex(
            "\\b(today|yesterday|tomorrow|now|latest|recent|current|news|price|weather|version|release|update|winner|champion|ceo|president|prime minister|score|rate|stock|2025|2026|2027|ajke|kal|notun|dam|koto|khobor|ekhon|ke)\\b",
            RegexOption.IGNORE_CASE
        )
        private val INDIC_TIME_SENSITIVE_CUES = listOf(
            "বর্তমান", "আজ", "খবর", "নতুন", "সাম্প্রতিক", "আপডেট", "দাম", "কত", "সময়", "সময়", "কে এখন"
        )

        fun isTimeSensitiveQuery(prompt: String): Boolean {
            val clean = prompt.trim()
            if (clean.isBlank()) return false
            if (LATIN_TIME_SENSITIVE_REGEX.containsMatchIn(clean)) return true
            return INDIC_TIME_SENSITIVE_CUES.any { clean.contains(it) }
        }
    }

    private val casualGreetings = setOf(
        "hi", "hello", "hey", "hii", "hiii", "yo", "salam", "assalamualaikum",
        "good morning", "good night", "kemon acho", "kemon achis", "ki obostha",
        "কেমন আছো", "হ্যালো", "হাই", "ধন্যবাদ", "thanks", "thank you", "ok", "okay"
    )

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

    private fun computeCacheKey(request: ChatRequest): String? {
        val prompt = request.prompt.trim()
        if (isTimeSensitiveQuery(prompt)) return null
        if (request.history.isNotEmpty()) return null
        if (request.imageBase64 != null || request.documentText != null) return null

        if (!request.activePluginContext.isNullOrBlank()) return null

        val lang = context?.let { AyraPreferences.getPreferredLanguage(it) } ?: "Auto"
        val localOnly = context?.let { AyraPreferences.isLocalOnlyAiModeEnabled(it) } ?: false
        val privacyOn = context?.let { AyraPreferences.isPrivacyModeEnabled(it) } ?: true
        val thinking = request.thinkingMode ?: context?.let { AyraPreferences.getThinkingMode(it) } ?: "balanced"
        val raw = "$prompt|${request.persona}|${request.mode}|${request.routingMode.id}|$thinking|${request.preferredProvider?.id}|${request.preferredModelId}|$lang|$localOnly|$privacyOn|${request.systemPromptOverride.orEmpty()}|${request.workspaceContext.orEmpty()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    internal fun isTrivialGreeting(prompt: String): Boolean {
        val clean = prompt.lowercase().trim().trimEnd('!', '.', '।')
        if (clean in casualGreetings) return true
        if (clean.contains("?") || clean.any { it.isDigit() }) return false
        val greetingRegex = Regex("^(hi+|hello+|hey+|hii+|yo+|salam|namaste|good morning|good afternoon|good evening|good night|kemon acho|ki khobor|হ্যালো|হাই|ধন্যবাদ|thanks|thank you)(\\s+[a-z0-9]{1,12})?$")
        return greetingRegex.matches(clean)
    }

    enum class TaskComplexity { TRIVIAL, STANDARD, COMPLEX }

    internal fun classifyTaskComplexity(request: ChatRequest): TaskComplexity {
        if (isTrivialGreeting(request.prompt)) return TaskComplexity.TRIVIAL
        val lower = request.prompt.lowercase()
        val hasMediaOrDoc = !request.imageBase64.isNullOrBlank() || !request.documentText.isNullOrBlank()
        val isComplexSignal = hasMediaOrDoc ||
            request.prompt.length > 260 ||
            lower.contains("architecture") || lower.contains("refactor") ||
            lower.contains("algorithm") || lower.contains("optimize") ||
            lower.contains("compare") || lower.contains("analyze") ||
            lower.contains("debug") || lower.contains("sql") ||
            lower.contains("kotlin") || lower.contains("python") ||
            lower.contains("proof") || lower.contains("research")
        return when {
            isComplexSignal -> TaskComplexity.COMPLEX
            request.prompt.length <= 75 && !lower.contains("code") && !lower.contains("write") -> TaskComplexity.STANDARD
            else -> TaskComplexity.STANDARD
        }
    }

    /**
     * Convenience synchronous/suspend execution using the exact same unified pipeline.
     */
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

    /**
     * Main streaming entry point. Emits specialist thinking status, parallel drafts, tokens, and final answer.
     */
    fun streamChat(request: ChatRequest): Flow<AyraStreamEvent> = flow {
        // 1. Check LRU Cache (<1ms hit)
        val cacheKey = computeCacheKey(request)
        if (cacheKey != null) {
            val cached = memoryLruCache[cacheKey]
            if (cached != null) {
                val age = System.currentTimeMillis() - cached.timestamp
                if (age < 10 * 60 * 1000L) {
                    emit(AyraStreamEvent.Complete(cached.response))
                    return@flow
                } else {
                    memoryLruCache.remove(cacheKey)
                }
            }
        }

        // 2. Domain Classification & Specialist Channel Ranking (filtered by Zero-Trust route policy)
        val domain = AyraAgentOrchestrator.classifyIntent(request.prompt)
        val isLocalOnlyMode = context?.let { AyraPreferences.isLocalOnlyAiModeEnabled(it) } == true
        val channels = externalProviders.getActiveChannels(
            domainName = domain.name,
            hasImage = !request.imageBase64.isNullOrBlank()
        ).filter { ch ->
            externalProviders.isTestHarnessMode ||
                com.ayra.ai.app.security.SecureAiGatewayClient.evaluateProviderAccess(context, ch.provider).allowed
        }
        val availableProviders = getAvailableProviders().filter { p ->
            externalProviders.isTestHarnessMode ||
                com.ayra.ai.app.security.SecureAiGatewayClient.evaluateProviderAccess(context, p).allowed
        }

        val isStrictLocalRouting = request.routingMode == AiRoutingMode.PRIVACY_LOCAL &&
            channels.any { it.provider == AiProviderType.LOCAL_OLLAMA }
        val routedChannels = prioritizeChannelsForRoutingMode(channels, request.routingMode)
        val routedProviders = if (isStrictLocalRouting) {
            availableProviders.filter { it == AiProviderType.LOCAL_OLLAMA }.ifEmpty { availableProviders }
        } else {
            availableProviders
        }

        val isFirebaseAiReady = !isLocalOnlyMode &&
            !isStrictLocalRouting &&
            com.ayra.ai.app.ai.FirebaseAiEngine.isAvailable(context)

        val isRouteBReady = !isLocalOnlyMode &&
            !isStrictLocalRouting &&
            ((routeBTestOverride != null &&
                context != null &&
                AyraPreferences.hasExternalAiConsent(context) &&
                !AyraPreferences.isByokPreferred(context)) ||
                com.ayra.ai.app.security.SecureAiGatewayClient.shouldUseBackendGatewayProxy(
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
                (hasTavilyKey && request.prompt.trim().length >= 8))

        supervisorScope {
            val wikiDeferred: Deferred<String>? = if (shouldGroundWiki) {
                async(Dispatchers.IO) {
                    withTimeoutOrNull(3_000L) {
                        val wikiJob = async {
                            runCatching {
                                AutonomousKnowledgeEngine.queryMultiLayerWiki(
                                    prompt = request.prompt,
                                    isBengali = isBengali
                                )
                            }.getOrDefault("")
                        }
                        val tavilyJob = if (hasTavilyKey && context != null) {
                            async {
                                runCatching {
                                    com.ayra.ai.app.search.TavilySearchEngine(context)
                                        .search(request.prompt, maxResults = 3, searchDepth = "basic")
                                        .formattedSummary
                                }.getOrDefault("")
                            }
                        } else null
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

            // Preserve Route B Backend Gateway routing when explicitly configured without multi-provider ensemble or under test override
            if (routeBTestOverride != null || (isRouteBReady && routedChannels.size < 2 && !isFirebaseAiReady)) {
                executeChannelFallback(effectiveRequest, routedChannels, routedProviders, cacheKey, domain, wikiDeferred, this@flow)
                return@supervisorScope
            }

            // 3. Explicit provider/model override uses focused single-channel execution with fallback
            if (effectiveRequest.preferredProvider != null && effectiveRequest.preferredProvider != AiProviderType.OMNI_ROUTE) {
                val filtered = routedChannels.filter { it.provider == effectiveRequest.preferredProvider } +
                    routedChannels.filter { it.provider != effectiveRequest.preferredProvider }
                executeChannelFallback(effectiveRequest, filtered, routedProviders, cacheKey, domain, wikiDeferred, this@flow)
                return@supervisorScope
            }

            // 4. Trivial greeting, explicit FAST mode, or strict local Ollama privacy mode -> focused streaming specialist
            if (effectiveRequest.mode == ProviderPoolMode.FAST ||
                isStrictLocalRouting ||
                (effectiveRequest.mode == ProviderPoolMode.AUTO && isTrivialGreeting(effectiveRequest.prompt))
            ) {
                executeChannelFallback(effectiveRequest, routedChannels, routedProviders, cacheKey, domain, wikiDeferred, this@flow)
                return@supervisorScope
            }

            // 5. Real Question in AUTO or BEST -> Quorum-Accelerated Multi-AI Parallel Ensemble!
            val targetMax = if (effectiveRequest.mode == ProviderPoolMode.BEST) 8 else 6
            val ensembleChannels = selectDiverseEnsembleChannels(routedChannels, maxCount = targetMax)
            val totalEnsembleWorkers = ensembleChannels.size + if (isFirebaseAiReady) 1 else 0
            if (totalEnsembleWorkers >= 2 && (ensembleChannels.isNotEmpty() || isFirebaseAiReady)) {
                executeMultiAiEnsemble(effectiveRequest, ensembleChannels, routedChannels, routedProviders, cacheKey, domain, wikiDeferred, this@flow)
            } else {
                executeChannelFallback(effectiveRequest, routedChannels, routedProviders, cacheKey, domain, wikiDeferred, this@flow)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun prioritizeChannelsForRoutingMode(
        channels: List<AiModelChannel>,
        routingMode: AiRoutingMode
    ): List<AiModelChannel> {
        if (channels.isEmpty() || routingMode == AiRoutingMode.AUTO) return channels
        return when (routingMode) {
            AiRoutingMode.AUTO -> channels
            AiRoutingMode.PRIVACY_LOCAL -> {
                val localOnly = channels.filter { it.provider == AiProviderType.LOCAL_OLLAMA }
                localOnly.ifEmpty {
                    channels.sortedByDescending { ch ->
                        when (ch.provider) {
                            AiProviderType.LOCAL_OLLAMA -> 10
                            AiProviderType.DEEPSEEK, AiProviderType.OPENROUTER -> 4
                            else -> 0
                        }
                    }
                }
            }
            AiRoutingMode.FAST -> channels.sortedByDescending { ch ->
                var score = if (ch.specialties.contains("fast")) 6 else 0
                score += when (ch.provider) {
                    AiProviderType.GROQ, AiProviderType.CEREBRAS -> 8
                    AiProviderType.GEMINI -> if (ch.id.contains("flash")) 7 else 2
                    AiProviderType.NVIDIA_NIM, AiProviderType.SAMBANOVA -> 5
                    AiProviderType.OPENAI -> if (ch.modelId.contains("luna") || ch.modelId.contains("mini")) 4 else 1
                    else -> 0
                }
                score
            }
            AiRoutingMode.BEST_QUALITY -> channels.sortedByDescending { ch ->
                var score = if (ch.isReasoning) 5 else 0
                score += when (ch.provider) {
                    AiProviderType.GEMINI -> if (ch.id.contains("argon") || ch.id.contains("pro")) 9 else 5
                    AiProviderType.ANTHROPIC, AiProviderType.OPENAI, AiProviderType.DEEPSEEK -> 8
                    AiProviderType.SAMBANOVA, AiProviderType.NVIDIA_NIM -> 6
                    AiProviderType.POLLINATIONS_FREE -> -4
                    else -> 3
                }
                score
            }
            AiRoutingMode.REASONING -> channels.sortedByDescending { ch ->
                var score = 0
                if (ch.isReasoning) score += 8
                if (ch.specialties.contains("reasoning")) score += 5
                if (ch.specialties.contains("math")) score += 3
                if (ch.provider == AiProviderType.DEEPSEEK || ch.provider == AiProviderType.SAMBANOVA) score += 4
                score
            }
            AiRoutingMode.CODING -> channels.sortedByDescending { ch ->
                var score = if (ch.specialties.contains("coding")) 10 else 0
                if (ch.provider == AiProviderType.ANTHROPIC || ch.provider == AiProviderType.DEEPSEEK || ch.provider == AiProviderType.MISTRAL) score += 3
                score
            }
            AiRoutingMode.COST_EFFICIENT -> channels.sortedByDescending { ch ->
                when (ch.provider) {
                    AiProviderType.GROQ, AiProviderType.CEREBRAS, AiProviderType.SAMBANOVA,
                    AiProviderType.HUGGINGFACE, AiProviderType.OPENROUTER -> 8
                    AiProviderType.GEMINI -> if (ch.id.contains("flash")) 9 else 4
                    AiProviderType.POLLINATIONS_FREE -> 7
                    AiProviderType.NVIDIA_NIM -> 6
                    AiProviderType.OPENAI, AiProviderType.ANTHROPIC, AiProviderType.XAI, AiProviderType.PERPLEXITY -> -2
                    else -> 2
                }
            }
            AiRoutingMode.OPEN_WEIGHTS -> channels.sortedByDescending { ch ->
                val modelLower = ch.modelId.lowercase()
                val isOpenWeightsFamily = ch.provider in setOf(
                    AiProviderType.LOCAL_OLLAMA,
                    AiProviderType.OPENROUTER,
                    AiProviderType.HUGGINGFACE,
                    AiProviderType.GROQ,
                    AiProviderType.CEREBRAS,
                    AiProviderType.SAMBANOVA,
                    AiProviderType.DEEPSEEK
                ) || modelLower.contains("gemma") ||
                    modelLower.contains("qwen") ||
                    modelLower.contains("llama") ||
                    modelLower.contains("deepseek") ||
                    modelLower.contains("gpt-oss") ||
                    modelLower.contains("glm") ||
                    modelLower.contains("minimax") ||
                    modelLower.contains("muse")
                when {
                    ch.provider == AiProviderType.LOCAL_OLLAMA -> 12
                    isOpenWeightsFamily -> 8
                    else -> 0
                }
            }
        }
    }

    /**
     * Selects up to [maxCount] diverse specialist channels for the question so every active
     * AI provider participates in parallel round-robin before allocating secondary models.
     */
    internal fun selectDiverseEnsembleChannels(channels: List<AiModelChannel>, maxCount: Int): List<AiModelChannel> {
        if (channels.isEmpty() || maxCount <= 0) return emptyList()
        val selected = mutableListOf<AiModelChannel>()
        val selectedIds = mutableSetOf<String>()
        val seenModelFamilies = mutableSetOf<String>()
        var pickedLocalOllama = false

        // Pass 1: First select the top-ranked channel from each distinct provider for true multi-AI diversity
        val seenProviders = mutableSetOf<AiProviderType>()
        for (ch in channels) {
            if (ch.provider in seenProviders) continue
            val familyKey = ch.modelId.lowercase()
            seenProviders.add(ch.provider)
            seenModelFamilies.add(familyKey)
            selected.add(ch)
            selectedIds.add(ch.id)
            if (ch.provider == AiProviderType.LOCAL_OLLAMA) pickedLocalOllama = true
            if (selected.size >= maxCount) return selected
        }

        // Pass 2: Fill any remaining ensemble slots with distinct secondary model families
        for (ch in channels) {
            if (ch.id in selectedIds) continue
            if (ch.provider == AiProviderType.LOCAL_OLLAMA) {
                if (pickedLocalOllama) continue
                pickedLocalOllama = true
            }
            val familyKey = ch.modelId.lowercase()
            if (seenModelFamilies.add(familyKey)) {
                selected.add(ch)
                selectedIds.add(ch.id)
                if (selected.size >= maxCount) break
            }
        }
        return selected
    }

    /**
     * Executes sequential streaming with fail-fast channel-by-channel failover on error.
     */
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
            withTimeoutOrNull(2_200L) { wikiDeferred.await() } ?: ""
        } else ""

        val sysPrompt = resolveSystemPrompt(request, domain, wikiContext)
        val privacyRedactionOn = context?.let { AyraPreferences.isPrivacyModeEnabled(it) } == true
        val safePrompt = if (privacyRedactionOn) {
            com.ayra.ai.app.security.SecureAiGatewayClient.redactSecretsForOutboundSafety(request.prompt)
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
                    cacheKey?.let { memoryLruCache[it] = CacheEntry(resp, System.currentTimeMillis()) }
                    collector.emit(AyraStreamEvent.Complete(resp))
                    true
                } else {
                    lastError = AyraError.ProviderDown(
                        AiProviderType.GEMINI,
                        502,
                        "Firebase AI returned an empty response."
                    )
                    false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = mapExceptionToAyraError(
                    provider = AiProviderType.GEMINI,
                    e = e,
                    isFirebaseKeyless = !externalProviders.isProviderConfigured(AiProviderType.GEMINI)
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
                com.ayra.ai.app.security.SecureAiGatewayClient.shouldUseBackendGatewayProxy(
                    context = appCtx,
                    hasExplicitTestOverrides = externalProviders.isTestHarnessMode
                )
            }
            if (!routeBAllowed || !request.imageBase64.isNullOrBlank()) return false
            val gatewayUrl = testCfg?.baseUrl ?: AyraPreferences.getBackendGatewayUrl(appCtx)
            if (gatewayUrl.isBlank()) return false

            val mappedProvider = request.preferredProvider?.takeIf {
                it in setOf(
                    AiProviderType.GEMINI,
                    AiProviderType.OPENROUTER,
                    AiProviderType.NVIDIA_NIM,
                    AiProviderType.GROQ
                )
            } ?: AiProviderType.GEMINI
            val gatewaySlug = com.ayra.ai.app.security.SecureAiGatewayClient.mapProviderToGatewaySlug(mappedProvider)
            val gatewayModel = request.preferredModelId?.takeIf { it.isNotBlank() } ?: when (gatewaySlug) {
                "groq" -> ExternalAiProviders.GROQ_QWEN_MODEL
                "openrouter" -> ExternalAiProviders.OPENROUTER_DEFAULT_MODEL
                "nvidia" -> ExternalAiProviders.NVIDIA_LLAMA_MODEL
                else -> if (request.mode == ProviderPoolMode.BEST ||
                    request.routingMode == AiRoutingMode.REASONING ||
                    request.routingMode == AiRoutingMode.BEST_QUALITY
                ) {
                    com.ayra.ai.app.ai.FirebaseAiEngine.MODEL_PRO_REASONING
                } else {
                    com.ayra.ai.app.ai.FirebaseAiEngine.MODEL_FLASH
                }
            }

            collector.emit(AyraStreamEvent.Thinking("Ayra Zero-Trust Gateway ($gatewaySlug) 🛡️"))
            val proxyResult = com.ayra.ai.app.security.SecureAiGatewayClient.callAuthenticatedBackendAiProxy(
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
                val resp = ProviderResponse(
                    text = cleanText,
                    modelUsed = "$gatewayModel (Route B Gateway) 🛡️",
                    provider = mappedProvider
                )
                cacheKey?.let { memoryLruCache[it] = CacheEntry(resp, System.currentTimeMillis()) }
                collector.emit(AyraStreamEvent.Complete(resp))
                return true
            }
            val err = proxyResult.exceptionOrNull()
            if (err != null) {
                android.util.Log.w("ProviderPool", "Route B backend gateway failed (${err.message}); falling back")
                lastError = AyraError.ProviderDown(mappedProvider, 502, err.message ?: "Route B gateway unavailable")
            }
            return false
        }

        if (request.routingMode != AiRoutingMode.PRIVACY_LOCAL && tryRouteBBackendGateway()) {
            return
        }

        val firebaseCanHandlePrimary = request.routingMode != AiRoutingMode.PRIVACY_LOCAL &&
            com.ayra.ai.app.ai.FirebaseAiEngine.isAvailable(context) &&
            (request.preferredProvider == null || request.preferredProvider == AiProviderType.GEMINI || candidateList.isEmpty())

        if (firebaseCanHandlePrimary && tryFirebaseAiLogic()) {
            return
        }

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
                            // Only fall back to synchronous call if streaming returned empty cleanly without throwing
                            val overrideChannel = if (modelId != channel.modelId) channel.copy(modelId = modelId) else channel
                            externalProviders.callChannel(overrideChannel, turns, withSearchGrounding = true)
                        }

                        if (rawText.isNotBlank()) {
                            circuitBreaker.recordSuccess(channel.provider)
                            circuitBreaker.recordChannelSuccess(channel.id)
                            val finalResponse = ProviderResponse(
                                text = rawText,
                                modelUsed = channel.displayName,
                                provider = channel.provider
                            )
                            cacheKey?.let { keyHash ->
                                memoryLruCache[keyHash] = CacheEntry(finalResponse, System.currentTimeMillis())
                            }
                            collector.emit(AyraStreamEvent.Complete(finalResponse))
                            return
                        }
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val mappedError = mapExceptionToAyraError(channel.provider, e)
                        lastError = mappedError
                        // Fail fast on auth, 4xx, 5xx, content blocks, or network failure — do NOT double-call the same broken channel!
                        if (attempt < 2 && mappedError is AyraError.RateLimited) {
                            delay(350L + Random.nextLong(50, 150))
                        } else {
                            circuitBreaker.recordChannelFailure(channel.id)
                            break
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                circuitBreaker.recordChannelFailure(channel.id)
                lastError = mapExceptionToAyraError(channel.provider, e)
            }
        }

        if (!triedFirebaseAi &&
            request.routingMode != AiRoutingMode.PRIVACY_LOCAL &&
            com.ayra.ai.app.ai.FirebaseAiEngine.isAvailable(context) &&
            tryFirebaseAiLogic()
        ) {
            return
        }

        val savedWithoutConsent = if (!externalProviders.isTestHarnessMode &&
            context != null &&
            !AyraPreferences.hasExternalAiConsent(context)
        ) {
            externalProviders.getSavedKeyProvidersIgnoringConsent()
        } else {
            emptyList()
        }
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

    /**
     * Executes Quorum-Accelerated Multi-AI Parallel Ensemble across specialist channels in [supervisorScope].
     * Emits drafts live via [Channel], finishes as soon as all models respond OR quorum soft-deadline fires,
     * and streams the consensus synthesis live to the UI.
     */
    private suspend fun executeMultiAiEnsemble(
        request: ChatRequest,
        ensembleChannels: List<AiModelChannel>,
        allChannels: List<AiModelChannel>,
        fallbackProviders: List<AiProviderType>,
        cacheKey: String?,
        domain: AgentDomain,
        wikiDeferred: Deferred<String>?,
        collector: FlowCollector<AyraStreamEvent>
    ) = supervisorScope {
        val namesPreview = ensembleChannels.take(4).joinToString(", ") { it.displayName }
        collector.emit(AyraStreamEvent.Thinking("${domain.title} • Live Search & Ensemble ($namesPreview)"))

        // Inject live web & multi-layer wiki context into EVERY parallel AI worker upfront
        val wikiContext = if (wikiDeferred != null) {
            withTimeoutOrNull(2_200L) { wikiDeferred.await() } ?: ""
        } else ""

        val sysPrompt = resolveSystemPrompt(request, domain, wikiContext)
        val privacyRedactionOn = context?.let { AyraPreferences.isPrivacyModeEnabled(it) } == true
        val safePrompt = if (privacyRedactionOn) {
            com.ayra.ai.app.security.SecureAiGatewayClient.redactSecretsForOutboundSafety(request.prompt)
        } else request.prompt
        val turns = externalProviders.buildConversationTurns(
            systemPrompt = sysPrompt,
            history = request.history,
            currentPrompt = safePrompt,
            currentImageBase64 = request.imageBase64
        )

        val isBestMode = request.mode == ProviderPoolMode.BEST
        val perChannelTimeoutMs = if (isBestMode) 14_000L else 11_000L

        val wrappedChannel = Channel<Any>(Channel.UNLIMITED)
        val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val firebaseEnsembleActive = externalProviders.allowExternalWikiGrounding &&
            com.ayra.ai.app.ai.FirebaseAiEngine.isAvailable(context)
        val totalWorkers = ensembleChannels.size + if (firebaseEnsembleActive) 1 else 0
        val targetQuorum = if (isBestMode) minOf(4, totalWorkers) else minOf(3, totalWorkers)
        val quorumGraceMs = if (isBestMode) 2_500L else 1_800L

        val successfulDrafts = mutableListOf<Pair<AiModelChannel, String>>()
        val workerErrors = ConcurrentLinkedQueue<AyraError>()
        try {
            if (firebaseEnsembleActive) {
                val fbModelName = firebaseAiEngine.selectModelForTask(
                    modelTier = if (request.routingMode == AiRoutingMode.FAST) "fast" else "auto",
                    prompt = safePrompt,
                    preferReasoning = isBestMode ||
                        request.routingMode == AiRoutingMode.BEST_QUALITY ||
                        request.routingMode == AiRoutingMode.REASONING
                )
                val fbChannel = AiModelChannel(
                    id = "firebase_ai_$fbModelName",
                    provider = AiProviderType.GEMINI,
                    modelId = fbModelName,
                    displayName = "Gemini 3.8 / 4 Firebase",
                    specialties = setOf("general", "coding", "reasoning", "research"),
                    supportsVision = true,
                    isReasoning = true,
                    keyResolver = { "" }
                )
                draftScope.launch {
                    val outcome: Any = withTimeoutOrNull(perChannelTimeoutMs) {
                        try {
                            val fbRes = firebaseAiEngine.generateTextDetailed(
                                prompt = safePrompt,
                                systemPrompt = sysPrompt,
                                history = request.history,
                                imageBase64 = request.imageBase64,
                                modelName = fbModelName,
                                enableSearchGrounding = !isTrivialGreeting(safePrompt)
                            )
                            if (fbRes.text.isNotBlank()) {
                                Pair(fbChannel, fbRes.text)
                            } else {
                                fbRes.error?.let { err ->
                                    workerErrors.add(
                                        mapExceptionToAyraError(
                                            AiProviderType.GEMINI,
                                            err,
                                            isFirebaseKeyless = !externalProviders.isProviderConfigured(AiProviderType.GEMINI)
                                        )
                                    )
                                }
                                Unit
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("ProviderPool", "Firebase ensemble worker failed: ${e.message}")
                            workerErrors.add(
                                mapExceptionToAyraError(
                                    AiProviderType.GEMINI,
                                    e,
                                    isFirebaseKeyless = !externalProviders.isProviderConfigured(AiProviderType.GEMINI)
                                )
                            )
                            Unit
                        }
                    } ?: Unit
                    wrappedChannel.trySend(outcome)
                }
            }

            ensembleChannels.forEach { channel ->
                draftScope.launch {
                    val outcome: Any = withTimeoutOrNull(perChannelTimeoutMs) {
                        try {
                            val text = externalProviders.callChannel(channel, turns, withSearchGrounding = true)
                            if (text.isNotBlank()) {
                                circuitBreaker.recordSuccess(channel.provider)
                                circuitBreaker.recordChannelSuccess(channel.id)
                                Pair(channel, text)
                            } else Unit
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("ProviderPool", "Ensemble worker ${channel.id} failed: ${e.message}")
                            circuitBreaker.recordChannelFailure(channel.id)
                            workerErrors.add(mapExceptionToAyraError(channel.provider, e))
                            Unit
                        }
                    } ?: Unit
                    wrappedChannel.trySend(outcome)
                }
            }

            val startMs = System.currentTimeMillis()
            var hardDeadlineMs = startMs + perChannelTimeoutMs
            var finishedCount = 0

            while (finishedCount < totalWorkers) {
                val remainingMs = hardDeadlineMs - System.currentTimeMillis()
                if (remainingMs <= 0L) break

                val received = withTimeoutOrNull(remainingMs) {
                    wrappedChannel.receive()
                } ?: break

                finishedCount++
                if (received is Pair<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val draft = received as Pair<AiModelChannel, String>
                    successfulDrafts.add(draft)
                    collector.emit(AyraStreamEvent.Draft(draft.first.displayName, draft.second))

                    // Once quorum drafts are in hand, shrink deadline to a short grace window so fast models aren't stalled by slow stragglers
                    if (successfulDrafts.size >= targetQuorum && finishedCount < totalWorkers) {
                        val softStop = System.currentTimeMillis() + quorumGraceMs
                        if (softStop < hardDeadlineMs) {
                            hardDeadlineMs = softStop
                        }
                    }
                }
            }
        } finally {
            draftScope.cancel()
            wrappedChannel.close()
        }

        if (successfulDrafts.isEmpty()) {
            val remaining = allChannels.filter { ch -> ensembleChannels.none { it.id == ch.id } }
            executeChannelFallback(
                request = request,
                channels = remaining.ifEmpty { allChannels },
                fallbackProviders = fallbackProviders,
                cacheKey = cacheKey,
                domain = domain,
                wikiDeferred = wikiDeferred,
                collector = collector,
                priorError = workerErrors.firstOrNull()
            )
            return@supervisorScope
        }

        val rankedDrafts = BestAnswerSelector.rankDrafts(
            prompt = safePrompt,
            domain = domain,
            drafts = successfulDrafts,
            wikiContext = wikiContext
        )

        val validDrafts = rankedDrafts
            .filterNot { BestAnswerSelector.isLowValueOrRefusal(it.text) }
            .ifEmpty { rankedDrafts }

        if (validDrafts.size == 1 && wikiContext.isBlank()) {
            val single = validDrafts.first()
            val resp = ProviderResponse(
                text = single.text,
                modelUsed = "${single.channel.displayName} (Best Answer • ${single.score.toInt()}%) ⚡",
                provider = single.channel.provider
            )
            cacheKey?.let { memoryLruCache[it] = CacheEntry(resp, System.currentTimeMillis()) }
            collector.emit(AyraStreamEvent.Complete(resp))
            return@supervisorScope
        }

        // Prioritize Gemini, then highest-scored non-free flagship, then highest-scored overall draft
        val synthesizer = validDrafts
            .firstOrNull { it.channel.provider == AiProviderType.GEMINI }?.channel
            ?: validDrafts.firstOrNull { it.channel.provider != AiProviderType.POLLINATIONS_FREE }?.channel
            ?: validDrafts.first().channel

        val isBengali = request.prompt.any { it in '\u0980'..'\u09FF' }
        val isBanglish = AyraAgentOrchestrator.isBanglish(request.prompt)
        val langNote = when {
            isBengali -> "natural, fluent Bengali (বাংলা)"
            isBanglish -> "natural Bengali (বাংলা) or fluent Banglish matching the user's style"
            else -> "clear, authoritative natural English"
        }

        val todayDate = java.time.LocalDate.now()
        val synthesisPrompt = buildString {
            append("You are Ayra AI's Principal Consensus Synthesizer. Today's Current Date: $todayDate (Year ${todayDate.year}). ${validDrafts.size} specialist AI models independently analyzed the user's request.\n\n")
            append("User Query:\n\"$safePrompt\"\n\n")
            if (!request.activePluginContext.isNullOrBlank()) {
                append("=== INSTALLED ACTIVE GITHUB PLUGINS ===\n")
                append(request.activePluginContext.take(3000))
                append("\n\n")
            }
            if (wikiContext.isNotBlank()) {
                append("=== LIVE MULTI-LAYER WIKI & TECHNICAL GROUNDING ===\n")
                append(wikiContext.take(2200))
                append("\n\n")
            }
            validDrafts.forEachIndexed { idx, scored ->
                append("--- SPECIALIST ${idx + 1}: ${scored.channel.displayName} [Quality Score ${scored.score.toInt()}/100] ---\n")
                append(scored.text.take(4500))
                append("\n\n")
            }
            append("Instructions:\n")
            append("1. Compare all specialist answers, verify against Today's Current Date ($todayDate) and live Wiki/Plugin facts (preferring fresh up-to-date evidence over outdated training cutoffs), select the most accurate solution, and synthesize ONE cohesive, detailed, authoritative response in $langNote.\n")
            append("2. Resolve any disagreements accurately and preserve complete runnable code blocks without truncation.\n")
            append("3. Do NOT mention 'Specialist 1/2', quality scores, or meta-commentary about combining drafts. Output ONLY the final polished response.")
        }

        collector.emit(AyraStreamEvent.Thinking("Selecting & synthesizing best answer (${validDrafts.size} AIs) with ${synthesizer.displayName}..."))

        val streamedSynth = StringBuilder()
        var synthProviderUsed = synthesizer.provider

        suspend fun tryStreamSynthesizer(target: AiModelChannel): Boolean {
            streamedSynth.clear()
            return withTimeoutOrNull(14_000L) {
                try {
                    if (target.id.startsWith("firebase_ai_") && firebaseEnsembleActive) {
                        firebaseAiEngine.streamText(
                            prompt = synthesisPrompt,
                            systemPrompt = "",
                            history = emptyList(),
                            imageBase64 = null,
                            modelName = target.modelId,
                            enableSearchGrounding = false
                        ).collect { token ->
                            streamedSynth.append(token)
                            collector.emit(AyraStreamEvent.Token(token))
                        }
                    } else {
                        externalProviders.streamChatResponse(
                            prompt = synthesisPrompt,
                            systemPrompt = "",
                            history = emptyList(),
                            imageBase64 = null,
                            preferredProvider = target.provider,
                            preferredModelId = target.modelId
                        ).collect { token ->
                            streamedSynth.append(token)
                            collector.emit(AyraStreamEvent.Token(token))
                        }
                    }
                    streamedSynth.isNotBlank()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ProviderPool", "Synthesizer ${target.displayName} failed: ${e.message}")
                    false
                }
            } ?: false
        }

        var streamSucceeded = tryStreamSynthesizer(synthesizer)
        if (!streamSucceeded || ExternalAiProviders.sanitizeModelOutput(streamedSynth.toString()).isBlank()) {
            val backupSynth = validDrafts
                .firstOrNull { it.channel.id != synthesizer.id && it.channel.provider != AiProviderType.POLLINATIONS_FREE }
                ?.channel
                ?: validDrafts.firstOrNull { it.channel.id != synthesizer.id }?.channel
            if (backupSynth != null) {
                collector.emit(AyraStreamEvent.Thinking("Switching synthesizer to ${backupSynth.displayName}..."))
                synthProviderUsed = backupSynth.provider
                streamSucceeded = tryStreamSynthesizer(backupSynth)
            }
        }

        val synthText = ExternalAiProviders.sanitizeModelOutput(streamedSynth.toString())
        val participatingNames = validDrafts.joinToString(" + ") { it.channel.displayName }

        if (streamSucceeded && synthText.isNotBlank()) {
            val wikiSuffix = if (wikiContext.isNotBlank()) " + Multi-Wiki 🌐" else ""
            val pluginSuffix = if (request.activePluginNames.isNotEmpty()) " + Plugins(${request.activePluginNames.size}) 🔌" else ""
            val resp = ProviderResponse(
                text = synthText,
                modelUsed = "Multi-AI Ensemble • Best of ${validDrafts.size} ($participatingNames)$wikiSuffix$pluginSuffix ⚡",
                provider = synthProviderUsed
            )
            cacheKey?.let { memoryLruCache[it] = CacheEntry(resp, System.currentTimeMillis()) }
            collector.emit(AyraStreamEvent.Complete(resp))
            return@supervisorScope
        }

        // Fallback: return the objectively highest-scored valid specialist draft (never naive length)
        val bestDraft = validDrafts.first()
        val resp = ProviderResponse(
            text = bestDraft.text,
            modelUsed = "${bestDraft.channel.displayName} (Best Answer • ${bestDraft.score.toInt()}%) ⚡",
            provider = bestDraft.channel.provider
        )
        cacheKey?.let { memoryLruCache[it] = CacheEntry(resp, System.currentTimeMillis()) }
        collector.emit(AyraStreamEvent.Complete(resp))
    }

    private suspend fun resolveSystemPrompt(
        request: ChatRequest,
        domain: AgentDomain,
        wikiContext: String = ""
    ): String {
        val base = if (!request.systemPromptOverride.isNullOrBlank()) {
            request.systemPromptOverride
        } else {
            val isBengali = request.prompt.any { it in '\u0980'..'\u09FF' }
            val isBanglish = AyraAgentOrchestrator.isBanglish(request.prompt)
            val appContext = context
            val safePersona = AyraPreferences.resolveSafePersona(appContext, request.persona)
            val isGfMode = safePersona == "GF_COMPANION"
            val userInstructions = appContext?.let { AyraPreferences.buildCombinedPersonalizationBlock(it) } ?: ""
            val isMemoryOn = appContext?.let { AyraPreferences.isMemoryEnabled(it) } ?: false
            val roomFacts = appContext?.takeIf { isMemoryOn }?.let { ctx ->
                runCatching {
                    com.ayra.ai.app.ai.memory.LongTermMemoryManager.retrieveRelevantMemories(ctx, request.prompt, topK = 6)
                        .map { "[${it.category}] ${it.content}" }
                }.getOrDefault(emptyList())
            } ?: emptyList()
            val legacyFacts = appContext?.takeIf { isMemoryOn }?.let { AyraPreferences.getRememberedFacts(it) } ?: emptySet()
            val rememberedFacts = (roomFacts + legacyFacts)
                .filter { !com.ayra.ai.app.ai.memory.LongTermMemoryManager.containsSensitiveCredentialOrPii(it) }
                .toSet()
            val effectiveThinking = request.thinkingMode ?: context?.let { AyraPreferences.getThinkingMode(it) } ?: "balanced"

            val preferredLang = context?.let { AyraPreferences.getPreferredLanguage(it) } ?: "Auto"
            val langOverride = when (preferredLang) {
                "bn" -> "\nLanguage Preference: Always reply in fluent Bengali (বাংলা)."
                "hi" -> "\nLanguage Preference: Always reply in fluent Hindi (हिन्दी)."
                "en" -> "\nLanguage Preference: Always reply in clear English."
                else -> ""
            }

            AyraAgentOrchestrator.buildSystemPrompt(
                domain = domain,
                isBengali = isBengali,
                isGfMode = isGfMode,
                userCustomInstructions = userInstructions,
                rememberedFacts = rememberedFacts,
                isBanglish = isBanglish,
                personaStyle = request.persona,
                thinkingMode = effectiveThinking
            ) + langOverride
        }

        return buildString {
            append(base)
            if (!request.workspaceContext.isNullOrBlank()) {
                append("\n\n")
                append(request.workspaceContext.take(6500))
            }
            if (!request.activePluginContext.isNullOrBlank()) {
                append("\n\n")
                append(request.activePluginContext.take(6500))
            }
            if (wikiContext.isNotBlank()) {
                append("\n\n[LIVE MULTI-LAYER WIKI & TECHNICAL KNOWLEDGE CONTEXT]:\n")
                append(wikiContext.take(2400))
            }
            if (!request.documentText.isNullOrBlank()) {
                append("\n\n[ATTACHED DOCUMENT CONTEXT (${request.documentText.length} chars)]:\n")
                append(request.documentText.take(40_000))
            }
        }
    }

    private fun mapExceptionToAyraError(
        provider: AiProviderType,
        e: Exception,
        isFirebaseKeyless: Boolean = false
    ): AyraError {
        val rawMsg = (e.localizedMessage ?: e.message ?: e::class.java.simpleName).trim()
        val safeDetail = com.ayra.ai.app.security.SecurityAuditLogger
            .redactSensitiveText(rawMsg)
            .take(180)
        val msg = rawMsg.lowercase()
        return when {
            isFirebaseKeyless && (msg.contains("403") || msg.contains("appcheck") || msg.contains("app check") || msg.contains("attestation") || msg.contains("permission")) ->
                AyraError.ProviderDown(provider, 403, safeDetail)
            e is ProviderError.Unauthorized || msg.contains("401") || (!isFirebaseKeyless && msg.contains("403")) || msg.contains("unauthorized") || msg.contains("invalid api key") ->
                AyraError.AuthError(provider, safeDetail)
            e is ProviderError.RateLimited || msg.contains("429") || msg.contains("quota") || msg.contains("rate limit") ->
                AyraError.RateLimited(provider)
            e is ProviderError.Timeout || e is java.net.SocketTimeoutException || e is kotlinx.coroutines.TimeoutCancellationException || msg.contains("timeout") || msg.contains("timed out") ->
                AyraError.Timeout(provider, safeDetail)
            e is ProviderError.NetworkFailure || e is java.net.UnknownHostException || e is java.net.ConnectException || msg.contains("unable to resolve host") || msg.contains("no internet") ->
                AyraError.NoNetwork(safeDetail)
            e is ProviderError.ContentBlocked ->
                AyraError.ContentBlocked(e.reason)
            e is ProviderError.ModelNotFound ->
                AyraError.ProviderDown(provider, e.httpStatus, safeDetail)
            e is ProviderError.ServerError ->
                AyraError.ProviderDown(provider, Regex("HTTP\\s*(\\d+)").find(e.displayMessage)?.groupValues?.get(1)?.toIntOrNull() ?: 500, safeDetail)
            msg.contains("404") || msg.contains("not found") ->
                AyraError.ProviderDown(provider, 404, safeDetail)
            msg.contains("400") || msg.contains("bad request") ->
                AyraError.ProviderDown(provider, 400, safeDetail)
            msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504") ->
                AyraError.ProviderDown(provider, 500, safeDetail)
            msg.contains("safety") || msg.contains("blocked") || msg.contains("content policy") ->
                AyraError.ContentBlocked(safeDetail)
            else -> AyraError.ProviderDown(provider, 500, safeDetail)
        }
    }
}
