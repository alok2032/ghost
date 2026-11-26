package com.ayra.ai.app.ai.providers

import android.content.Context
import android.util.Log
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.agents.AgentDomain
import com.ayra.ai.app.ai.agents.AyraAgentOrchestrator
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.BlockedByPolicyException
import com.ayra.ai.app.security.SecureAiGatewayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue

internal object ProviderEnsembleExecutor {

    /**
     * Executes Quorum-Accelerated Multi-AI Parallel Ensemble across specialist channels in [supervisorScope].
     * Emits drafts live via [Channel], finishes as soon as all models respond OR quorum soft-deadline fires,
     * and streams the consensus synthesis live to the UI.
     */
    suspend fun executeMultiAiEnsemble(
        context: Context?,
        externalProviders: ExternalAiProviders,
        firebaseAiEngine: FirebaseAiEngine,
        circuitBreaker: ProviderCircuitBreaker,
        request: ChatRequest,
        ensembleChannels: List<AiModelChannel>,
        allChannels: List<AiModelChannel>,
        domain: AgentDomain,
        wikiDeferred: Deferred<String>?,
        collector: FlowCollector<AyraStreamEvent>,
        onCacheStore: (ProviderResponse) -> Unit,
        onFallbackToSequential: suspend (channels: List<AiModelChannel>, priorError: AyraError?) -> Unit
    ) = supervisorScope {
        val namesPreview = ensembleChannels.take(4).joinToString(", ") { it.displayName }
        collector.emit(AyraStreamEvent.Thinking("${domain.title} • Live Search & Ensemble ($namesPreview)"))

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

        val isBestMode = request.mode == ProviderPoolMode.BEST
        val perChannelTimeoutMs = if (isBestMode) 22_000L else 16_000L

        val wrappedChannel = Channel<Any>(Channel.UNLIMITED)
        val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val firebaseEnsembleActive = externalProviders.allowExternalWikiGrounding &&
            FirebaseAiEngine.isAvailable(context)
        val totalWorkers = ensembleChannels.size + if (firebaseEnsembleActive) 1 else 0
        val targetQuorum = when {
            isBestMode -> minOf(4, totalWorkers)
            totalWorkers >= 4 -> 3
            else -> minOf(2, totalWorkers)
        }
        val quorumGraceMs = if (isBestMode) 2_200L else 1_200L

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
                    displayName = "Gemini 3.6 / 3.1 Firebase",
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
                                enableSearchGrounding = !ProviderPoolQueryClassifier.isTrivialGreeting(safePrompt)
                            )
                            if (fbRes.text.isNotBlank()) {
                                Pair(fbChannel, fbRes.text)
                            } else {
                                fbRes.error?.let { err ->
                                    workerErrors.add(
                                        ProviderRoutingSupport.mapExceptionToAyraError(
                                            context = context,
                                            provider = AiProviderType.GEMINI,
                                            e = err,
                                            isFirebaseKeyless = !externalProviders.isProviderConfigured(AiProviderType.GEMINI)
                                        )
                                    )
                                }
                                Unit
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("ProviderPool", "Firebase ensemble worker failed: ${e.message}")
                            workerErrors.add(
                                ProviderRoutingSupport.mapExceptionToAyraError(
                                    context = context,
                                    provider = AiProviderType.GEMINI,
                                    e = e,
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
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("ProviderPool", "Ensemble worker ${channel.id} failed: ${e.message}")
                            if (e !is ProviderError.ModelNotFound &&
                                e !is BlockedByPolicyException &&
                                e !is SecurityException
                            ) {
                                circuitBreaker.recordChannelFailure(channel.id)
                            }
                            workerErrors.add(ProviderRoutingSupport.mapExceptionToAyraError(context, channel.provider, e))
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
            onFallbackToSequential(remaining.ifEmpty { allChannels }, workerErrors.firstOrNull())
            return@supervisorScope
        }

        val rankedDrafts = BestAnswerSelector.rankDrafts(
            prompt = safePrompt,
            domain = domain,
            drafts = successfulDrafts,
            wikiContext = wikiContext
        )
        val validRankedDrafts = rankedDrafts
            .filterNot { BestAnswerSelector.isLowValueOrRefusal(it.text) }
            .ifEmpty { rankedDrafts }

        if (validRankedDrafts.size == 1 && wikiContext.isBlank()) {
            val single = validRankedDrafts.first()
            val resp = ProviderResponse(
                text = single.text,
                modelUsed = "${single.channel.displayName} (Best • ${single.score.toInt()}%) ⚡",
                provider = single.channel.provider
            )
            onCacheStore(resp)
            collector.emit(AyraStreamEvent.Complete(resp))
            return@supervisorScope
        }

        val synthesizer = validRankedDrafts
            .firstOrNull { it.channel.provider == AiProviderType.GEMINI }?.channel
            ?: validRankedDrafts.firstOrNull { it.channel.provider != AiProviderType.POLLINATIONS_FREE }?.channel
            ?: validRankedDrafts.first().channel

        val isBengali = request.prompt.any { it in '\u0980'..'\u09FF' }
        val isBanglish = AyraAgentOrchestrator.isBanglish(request.prompt)
        val langNote = when {
            isBengali -> "natural, fluent Bengali (বাংলা)"
            isBanglish -> "natural Bengali (বাংলা) or fluent Banglish matching the user's style"
            else -> "clear, authoritative natural English"
        }

        val synthesisPrompt = buildString {
            append("You are Ayra AI's Principal Consensus Synthesizer. ${validRankedDrafts.size} specialist AI models independently analyzed the user's request.\n\n")
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
            validRankedDrafts.forEachIndexed { idx, scored ->
                append("--- SPECIALIST ${idx + 1}: ${scored.channel.displayName} [Quality Score ${scored.score.toInt()}/100] ---\n")
                append(scored.text.take(4500))
                append("\n\n")
            }
            append("Instructions:\n")
            append("1. Compare all specialist answers, verify against live Wiki/Plugin facts, select the most accurate solution, and synthesize ONE cohesive, detailed, authoritative response in $langNote.\n")
            append("2. Resolve any disagreements accurately and preserve complete runnable code blocks without truncation.\n")
            append("3. Do NOT mention 'Specialist 1/2', quality scores, or meta-commentary about combining drafts. Output ONLY the final polished response.")
        }

        collector.emit(AyraStreamEvent.Thinking("Selecting & synthesizing best answer (${validRankedDrafts.size} AIs) with ${synthesizer.displayName}..."))

        val streamedSynth = StringBuilder()
        var synthProviderUsed = synthesizer.provider

        suspend fun tryStreamSynthesizer(target: AiModelChannel): Boolean {
            streamedSynth.clear()
            return withTimeoutOrNull(22_000L) {
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
                } catch (e: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    Log.w("ProviderPool", "Synthesizer ${target.displayName} inner timeout")
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ProviderPool", "Synthesizer ${target.displayName} failed: ${e.message}")
                    false
                }
            } ?: false
        }

        var streamSucceeded = tryStreamSynthesizer(synthesizer)
        if (!streamSucceeded || ExternalAiProviders.sanitizeModelOutput(streamedSynth.toString()).isBlank()) {
            val backupSynth = validRankedDrafts
                .firstOrNull { it.channel.id != synthesizer.id && it.channel.provider != AiProviderType.POLLINATIONS_FREE }
                ?.channel
                ?: validRankedDrafts.firstOrNull { it.channel.id != synthesizer.id }?.channel
            if (backupSynth != null) {
                collector.emit(AyraStreamEvent.Thinking("Switching synthesizer to ${backupSynth.displayName}..."))
                synthProviderUsed = backupSynth.provider
                streamSucceeded = tryStreamSynthesizer(backupSynth)
            }
        }

        val synthText = ExternalAiProviders.sanitizeModelOutput(streamedSynth.toString())
        val participatingNames = validRankedDrafts.joinToString(" + ") { it.channel.displayName }

        if (streamSucceeded && synthText.isNotBlank()) {
            val wikiSuffix = if (wikiContext.isNotBlank()) " + Multi-Wiki 🌐" else ""
            val pluginSuffix = if (request.activePluginNames.isNotEmpty()) " + Plugins(${request.activePluginNames.size}) 🔌" else ""
            val resp = ProviderResponse(
                text = synthText,
                modelUsed = "Multi-AI Ensemble • Best of ${validRankedDrafts.size} ($participatingNames)$wikiSuffix$pluginSuffix ⚡",
                provider = synthProviderUsed
            )
            onCacheStore(resp)
            collector.emit(AyraStreamEvent.Complete(resp))
            return@supervisorScope
        }

        val bestDraft = validRankedDrafts.first()
        val resp = ProviderResponse(
            text = bestDraft.text,
            modelUsed = "${bestDraft.channel.displayName} (Best Answer • ${bestDraft.score.toInt()}%) ⚡",
            provider = bestDraft.channel.provider
        )
        onCacheStore(resp)
        collector.emit(AyraStreamEvent.Complete(resp))
    }
}
