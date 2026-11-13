package com.ayra.ai.app.ai.providers

import android.content.Context
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
    data class ProviderDown(val provider: AiProviderType, val statusCode: Int) :
        AyraError("${provider.displayName} is currently unavailable (HTTP $statusCode).")
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
    val activePluginNames: List<String> = emptyList()
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

    private val timeSensitiveKeywords = listOf(
        "today", "yesterday", "tomorrow", "now", "latest", "news", "price",
        "current", "weather", "ajke", "kal", "বর্তমান", "আজ", "খবর", "সময়", "সময়"
    )

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

    private fun computeCacheKey(request: ChatRequest): String? {
        val prompt = request.prompt.trim()
        val lower = prompt.lowercase()
        if (timeSensitiveKeywords.any { lower.contains(it) }) return null
        if (request.history.isNotEmpty()) return null
        if (request.imageBase64 != null || request.documentText != null) return null

        if (!request.activePluginContext.isNullOrBlank()) return null

        val raw = "$prompt|${request.persona}|${request.mode}|${request.preferredProvider?.id}|${request.preferredModelId}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isTrivialGreeting(prompt: String): Boolean {
        val clean = prompt.lowercase().trim().trimEnd('?', '!', '.')
        if (clean in casualGreetings) return true
        return clean.length <= 16 && !clean.contains("?") && !clean.contains("কি") &&
            !clean.contains("কী") && !clean.contains("how") && !clean.contains("what") &&
            !clean.contains("why") && !clean.contains("code") && !clean.contains("write")
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

        // 2. Domain Classification & Specialist Channel Ranking
        val domain = AyraAgentOrchestrator.classifyIntent(request.prompt)
        val channels = externalProviders.getActiveChannels(
            domainName = domain.name,
            hasImage = !request.imageBase64.isNullOrBlank()
        )
        val availableProviders = getAvailableProviders()

        val isFirebaseAiReady = externalProviders.allowExternalWikiGrounding &&
            com.ayra.ai.app.ai.FirebaseAiEngine.isAvailable(context)

        if (channels.isEmpty() && availableProviders.isEmpty() && !isFirebaseAiReady) {
            emit(AyraStreamEvent.Error(AyraError.NoProviderConfigured))
            return@flow
        }

        val isBengali = request.prompt.any { it in '\u0980'..'\u09FF' }
        val shouldGroundWiki = externalProviders.allowExternalWikiGrounding &&
            request.imageBase64 == null &&
            request.documentText == null &&
            !isTrivialGreeting(request.prompt) &&
            AyraAgentOrchestrator.needsLiveWikiGrounding(request.prompt, domain)

        supervisorScope {
            val wikiDeferred: Deferred<String>? = if (shouldGroundWiki) {
                async(Dispatchers.IO) {
                    withTimeoutOrNull(1800L) {
                        AutonomousKnowledgeEngine.queryMultiLayerWiki(
                            prompt = request.prompt,
                            isBengali = isBengali
                        )
                    } ?: ""
                }
            } else null

            // 3. Explicit provider/model override uses focused single-channel execution with fallback
            if (request.preferredProvider != null && request.preferredProvider != AiProviderType.OMNI_ROUTE) {
                val filtered = channels.filter { it.provider == request.preferredProvider } +
                    channels.filter { it.provider != request.preferredProvider }
                executeChannelFallback(request, filtered, availableProviders, cacheKey, domain, wikiDeferred, this@flow)
                return@supervisorScope
            }

            // 4. Trivial greeting or explicit FAST mode -> ultra-fast streaming single specialist
            if (request.mode == ProviderPoolMode.FAST || (request.mode == ProviderPoolMode.AUTO && isTrivialGreeting(request.prompt))) {
                executeChannelFallback(request, channels, availableProviders, cacheKey, domain, wikiDeferred, this@flow)
                return@supervisorScope
            }

            // 5. Real Question in AUTO or BEST -> Quorum-Accelerated Multi-AI Parallel Ensemble!
            val targetMax = if (request.mode == ProviderPoolMode.BEST) 5 else 3
            val ensembleChannels = selectDiverseEnsembleChannels(channels, maxCount = targetMax)
            if (ensembleChannels.size >= 2) {
                executeMultiAiEnsemble(request, ensembleChannels, channels, availableProviders, cacheKey, domain, wikiDeferred, this@flow)
            } else {
                executeChannelFallback(request, channels, availableProviders, cacheKey, domain, wikiDeferred, this@flow)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Selects up to [maxCount] diverse specialist channels for the question so we get
     * genuine multi-model intelligence without redundant calls to identical weights.
     */
    private fun selectDiverseEnsembleChannels(channels: List<AiModelChannel>, maxCount: Int): List<AiModelChannel> {
        val selected = mutableListOf<AiModelChannel>()
        val seenModelFamilies = mutableSetOf<String>()

        for (ch in channels) {
            if (ch.provider == AiProviderType.POLLINATIONS_FREE && selected.size >= 3) continue
            val familyKey = ch.modelId.lowercase()
            if (seenModelFamilies.add(familyKey)) {
                selected.add(ch)
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
        collector: FlowCollector<AyraStreamEvent>
    ) {
        val wikiContext = if (wikiDeferred != null) {
            withTimeoutOrNull(1100L) { wikiDeferred.await() } ?: ""
        } else ""

        val sysPrompt = resolveSystemPrompt(request, domain, wikiContext)
        val turns = externalProviders.buildConversationTurns(
            systemPrompt = sysPrompt,
            history = request.history,
            currentPrompt = request.prompt,
            currentImageBase64 = request.imageBase64
        )
        var lastError: AyraError? = null

        val candidateList: List<AiModelChannel> = if (channels.isNotEmpty()) {
            channels
        } else {
            fallbackProviders.map { p ->
                val defaultModel = request.preferredModelId ?: externalProviders.getDefaultModelForProvider(p)
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

        if (externalProviders.allowExternalWikiGrounding &&
            com.ayra.ai.app.ai.FirebaseAiEngine.isAvailable(context) &&
            (request.preferredProvider == null || request.preferredProvider == AiProviderType.GEMINI)
        ) {
            val fbModel = firebaseAiEngine.selectModelForTask(
                modelTier = request.mode.name.lowercase(),
                prompt = request.prompt,
                preferReasoning = request.mode == ProviderPoolMode.BEST
            )
            collector.emit(AyraStreamEvent.Thinking("Firebase Gemini ($fbModel) + Search 🌐"))
            val fbStreamed = StringBuilder()
            try {
                firebaseAiEngine.streamText(
                    prompt = request.prompt,
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
                        modelUsed = "$fbModel (Firebase AI + Search) ⚡",
                        provider = AiProviderType.GEMINI
                    )
                    cacheKey?.let { memoryLruCache[it] = CacheEntry(resp, System.currentTimeMillis()) }
                    collector.emit(AyraStreamEvent.Complete(resp))
                    return
                }
            } catch (_: Exception) {
                // Gracefully fall through to external REST provider channels
            }
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
                            prompt = request.prompt,
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
            } catch (e: Exception) {
                circuitBreaker.recordChannelFailure(channel.id)
                lastError = mapExceptionToAyraError(channel.provider, e)
            }
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
        collector.emit(AyraStreamEvent.Thinking("${domain.title} Ensemble ($namesPreview)"))

        val sysPrompt = resolveSystemPrompt(request, domain)
        val turns = externalProviders.buildConversationTurns(
            systemPrompt = sysPrompt,
            history = request.history,
            currentPrompt = request.prompt,
            currentImageBase64 = request.imageBase64
        )

        val isBestMode = request.mode == ProviderPoolMode.BEST
        val perChannelTimeoutMs = if (isBestMode) 8_500L else 6_000L
        val targetQuorum = if (isBestMode) minOf(3, ensembleChannels.size) else minOf(2, ensembleChannels.size)
        val quorumGraceMs = if (isBestMode) 1_500L else 1_000L

        val wrappedChannel = Channel<Any>(Channel.UNLIMITED)
        val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val firebaseEnsembleActive = externalProviders.allowExternalWikiGrounding &&
            com.ayra.ai.app.ai.FirebaseAiEngine.isAvailable(context)
        val totalWorkers = ensembleChannels.size + if (firebaseEnsembleActive) 1 else 0

        val successfulDrafts = mutableListOf<Pair<AiModelChannel, String>>()
        try {
            if (firebaseEnsembleActive) {
                val fbModelName = firebaseAiEngine.selectModelForTask(
                    modelTier = if (isBestMode) "gemini_pro" else "auto",
                    prompt = request.prompt,
                    preferReasoning = isBestMode
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
                            val fbText = firebaseAiEngine.generateText(
                                prompt = request.prompt,
                                systemPrompt = sysPrompt,
                                history = request.history,
                                imageBase64 = request.imageBase64,
                                modelName = fbModelName,
                                enableSearchGrounding = true
                            )
                            if (fbText.isNotBlank()) Pair(fbChannel, fbText) else Unit
                        } catch (_: Exception) {
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
                        } catch (_: Exception) {
                            circuitBreaker.recordChannelFailure(channel.id)
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
                    if (successfulDrafts.size >= targetQuorum && finishedCount < ensembleChannels.size) {
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
            executeChannelFallback(request, remaining.ifEmpty { allChannels }, fallbackProviders, cacheKey, domain, wikiDeferred, collector)
            return@supervisorScope
        }

        // Grab whatever Multi-Layer Wiki evidence is already ready (max 400ms wait since it launched in parallel)
        val wikiContext = if (wikiDeferred != null) {
            withTimeoutOrNull(450L) { wikiDeferred.await() } ?: ""
        } else ""

        if (successfulDrafts.size == 1 && wikiContext.isBlank()) {
            val single = successfulDrafts.first()
            val resp = ProviderResponse(
                text = single.second,
                modelUsed = "${single.first.displayName} ⚡",
                provider = single.first.provider
            )
            cacheKey?.let { memoryLruCache[it] = CacheEntry(resp, System.currentTimeMillis()) }
            collector.emit(AyraStreamEvent.Complete(resp))
            return@supervisorScope
        }

        // Prioritize Gemini or a healthy flagship model for live-streamed Consensus Synthesis
        val synthesizer = successfulDrafts
            .firstOrNull { it.first.provider == AiProviderType.GEMINI }?.first
            ?: successfulDrafts.first().first

        val isBengali = request.prompt.any { it in '\u0980'..'\u09FF' }
        val isBanglish = AyraAgentOrchestrator.isBanglish(request.prompt)
        val langNote = when {
            isBengali -> "natural, fluent Bengali (বাংলা)"
            isBanglish -> "natural Bengali (বাংলা) or fluent Banglish matching the user's style"
            else -> "clear, authoritative natural English"
        }

        val synthesisPrompt = buildString {
            append("You are Ayra AI's Principal Consensus Synthesizer. ${successfulDrafts.size} specialist AI models independently analyzed the user's request.\n\n")
            append("User Query:\n\"${request.prompt}\"\n\n")
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
            successfulDrafts.forEachIndexed { idx, (ch, draft) ->
                append("--- SPECIALIST ${idx + 1}: ${ch.displayName} ---\n")
                append(draft.take(4500))
                append("\n\n")
            }
            append("Instructions:\n")
            append("1. Merge the strongest verified facts, Multi-Layer Wiki details, active GitHub plugin capabilities/APIs (if relevant), complete runnable code, equations, and practical explanations into ONE cohesive, detailed, authoritative response in $langNote.\n")
            append("2. Resolve any disagreements accurately and preserve complete markdown code blocks without truncation.\n")
            append("3. Do NOT mention 'Specialist 1/2' or meta-commentary about combining drafts. Output ONLY the final polished response.")
        }

        collector.emit(AyraStreamEvent.Thinking("Synthesizing with ${synthesizer.displayName}..."))

        val streamedSynth = StringBuilder()
        val streamSucceeded = withTimeoutOrNull(14_000L) {
            try {
                externalProviders.streamChatResponse(
                    prompt = synthesisPrompt,
                    systemPrompt = "",
                    history = emptyList(),
                    imageBase64 = null,
                    preferredProvider = synthesizer.provider,
                    preferredModelId = synthesizer.modelId
                ).collect { token ->
                    streamedSynth.append(token)
                    collector.emit(AyraStreamEvent.Token(token))
                }
                true
            } catch (_: Exception) {
                false
            }
        } ?: false

        val synthText = ExternalAiProviders.sanitizeModelOutput(streamedSynth.toString())
        val participatingNames = successfulDrafts.joinToString(" + ") { it.first.displayName }

        if (streamSucceeded && synthText.isNotBlank()) {
            val wikiSuffix = if (wikiContext.isNotBlank()) " + Multi-Wiki 🌐" else ""
            val pluginSuffix = if (request.activePluginNames.isNotEmpty()) " + Plugins(${request.activePluginNames.size}) 🔌" else ""
            val resp = ProviderResponse(
                text = synthText,
                modelUsed = "Multi-AI Ensemble ($participatingNames)$wikiSuffix$pluginSuffix ⚡",
                provider = synthesizer.provider
            )
            cacheKey?.let { memoryLruCache[it] = CacheEntry(resp, System.currentTimeMillis()) }
            collector.emit(AyraStreamEvent.Complete(resp))
            return@supervisorScope
        }

        // Fallback: return the richest responding specialist draft directly (no second blocking synthesis timeout)
        val bestDraft = successfulDrafts.maxByOrNull { it.second.length } ?: successfulDrafts.first()
        val resp = ProviderResponse(
            text = bestDraft.second,
            modelUsed = "${bestDraft.first.displayName} (Ensemble Best) ⚡",
            provider = bestDraft.first.provider
        )
        cacheKey?.let { memoryLruCache[it] = CacheEntry(resp, System.currentTimeMillis()) }
        collector.emit(AyraStreamEvent.Complete(resp))
    }

    private fun resolveSystemPrompt(
        request: ChatRequest,
        domain: AgentDomain,
        wikiContext: String = ""
    ): String {
        val base = if (!request.systemPromptOverride.isNullOrBlank()) {
            request.systemPromptOverride
        } else {
            val isBengali = request.prompt.any { it in '\u0980'..'\u09FF' }
            val isBanglish = AyraAgentOrchestrator.isBanglish(request.prompt)
            val isGfMode = request.persona == "GF_COMPANION"
            val userInstructions = context?.let { AyraPreferences.getUserCustomInstructions(it) } ?: ""
            val isMemoryOn = context?.let { AyraPreferences.isMemoryEnabled(it) } ?: false
            val rememberedFacts = context?.takeIf { isMemoryOn }?.let { AyraPreferences.getRememberedFacts(it) } ?: emptySet()

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
                isBanglish = isBanglish
            ) + langOverride
        }

        return buildString {
            append(base)
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

    private fun mapExceptionToAyraError(provider: AiProviderType, e: Exception): AyraError {
        val msg = e.message ?: ""
        return when {
            e is ProviderError.Unauthorized || msg.contains("401") || msg.contains("403") || msg.contains("Unauthorized") || msg.contains("Invalid API key") ->
                AyraError.AuthError(provider, msg)
            e is ProviderError.RateLimited || msg.contains("429") || msg.contains("quota") || msg.contains("rate limit") ->
                AyraError.RateLimited(provider)
            e is ProviderError.Timeout || e is java.net.SocketTimeoutException || msg.contains("timeout") || msg.contains("timed out") ->
                AyraError.Timeout(provider, msg)
            e is ProviderError.NetworkFailure || e is java.net.UnknownHostException || msg.contains("Unable to resolve host") || msg.contains("No internet") ->
                AyraError.NoNetwork(msg)
            e is ProviderError.ServerError ->
                AyraError.ProviderDown(provider, Regex("HTTP\\s*(\\d+)").find(e.displayMessage)?.groupValues?.get(1)?.toIntOrNull() ?: 500)
            msg.contains("404") || msg.contains("Not Found") ->
                AyraError.ProviderDown(provider, 404)
            msg.contains("400") || msg.contains("Bad Request") ->
                AyraError.ProviderDown(provider, 400)
            msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504") ->
                AyraError.ProviderDown(provider, 500)
            msg.contains("safety") || msg.contains("blocked") || msg.contains("content policy") ->
                AyraError.ContentBlocked(msg)
            else -> AyraError.ProviderDown(provider, 500)
        }
    }
}
