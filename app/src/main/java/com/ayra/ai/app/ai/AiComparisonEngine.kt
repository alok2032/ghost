package com.ayra.ai.app.ai

import android.content.Context
import com.ayra.ai.app.ai.providers.AiModelChannel
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ChatTurn
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ArenaCandidateResult(
    val candidateId: String,
    val displayName: String,
    val providerName: String,
    val responseText: String,
    val latencyMs: Long,
    val success: Boolean,
    val errorMessage: String? = null
)

data class ComparisonArenaReport(
    val prompt: String,
    val results: List<ArenaCandidateResult>,
    val synthesisSummary: String,
    val totalElapsedMs: Long
)

/**
 * Multi-Model Comparison Arena & Independent Verification Engine.
 * Executes user prompts across multiple genuinely configured AI backends in parallel,
 * measures real latency, and verifies agreements vs contradictions.
 */
class AiComparisonEngine(
    private val context: Context? = null,
    private val externalProviders: ExternalAiProviders = ExternalAiProviders(context),
    private val firebaseEngine: FirebaseAiEngine? = if (context != null) FirebaseAiEngine(context) else null
) {

    data class ArenaTarget(
        val id: String,
        val displayName: String,
        val providerName: String,
        val isFirebase: Boolean = false,
        val firebaseModelId: String = FirebaseAiEngine.MODEL_FLASH,
        val channel: AiModelChannel? = null
    )

    fun getAvailableTargets(): List<ArenaTarget> {
        val list = mutableListOf<ArenaTarget>()
        if (firebaseEngine != null && context != null && FirebaseAiEngine.isAvailable(context)) {
            list.add(
                ArenaTarget(
                    id = "fb_gemini_3_8_flash",
                    displayName = "Gemini 3.8 Flash",
                    providerName = "Firebase AI Logic",
                    isFirebase = true,
                    firebaseModelId = FirebaseAiEngine.MODEL_FLASH
                )
            )
            list.add(
                ArenaTarget(
                    id = "fb_gemini_3_5_lite",
                    displayName = "Gemini 3.5 Flash-Lite",
                    providerName = "Firebase AI Logic",
                    isFirebase = true,
                    firebaseModelId = FirebaseAiEngine.MODEL_FAST_LITE
                )
            )
        }
        externalProviders.getActiveChannels().forEach { ch ->
            list.add(
                ArenaTarget(
                    id = ch.id,
                    displayName = ch.displayName,
                    providerName = ch.provider.displayName,
                    isFirebase = false,
                    channel = ch
                )
            )
        }
        return list.distinctBy { it.id }
    }

    suspend fun runComparisonArena(
        prompt: String,
        selectedTargetIds: Set<String> = emptySet(),
        runDebateVerification: Boolean = true,
        timeoutPerModelMs: Long = 25_000L
    ): ComparisonArenaReport = withContext(Dispatchers.IO) {
        val overallStart = System.currentTimeMillis()
        val allTargets = getAvailableTargets()
        val chosenTargets = if (selectedTargetIds.isNotEmpty()) {
            allTargets.filter { it.id in selectedTargetIds }
        } else {
            allTargets.take(3)
        }

        if (chosenTargets.isEmpty()) {
            return@withContext ComparisonArenaReport(
                prompt = prompt,
                results = emptyList(),
                synthesisSummary = "No AI models are currently configured. Add an API key in Tools & Keys or enable Firebase AI Logic / Free AI Pool.",
                totalElapsedMs = System.currentTimeMillis() - overallStart
            )
        }

        val results = coroutineScope {
            chosenTargets.map { target ->
                async(Dispatchers.IO) {
                    val start = System.currentTimeMillis()
                    try {
                        val text = withTimeout(timeoutPerModelMs) {
                            if (target.isFirebase) {
                                firebaseEngine?.generateText(
                                    prompt = prompt,
                                    systemPrompt = "Provide a direct, factual, well-structured answer.",
                                    modelName = target.firebaseModelId
                                ).orEmpty()
                            } else {
                                val ch = target.channel!!
                                val turns = listOf(
                                    ChatTurn("system", "Provide a direct, accurate, concise answer."),
                                    ChatTurn("user", prompt)
                                )
                                externalProviders.callChannel(ch, turns, withSearchGrounding = false)
                            }
                        }
                        val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                        if (text.isBlank()) {
                            ArenaCandidateResult(
                                candidateId = target.id,
                                displayName = target.displayName,
                                providerName = target.providerName,
                                responseText = "",
                                latencyMs = latency,
                                success = false,
                                errorMessage = "Model returned an empty response."
                            )
                        } else {
                            ArenaCandidateResult(
                                candidateId = target.id,
                                displayName = target.displayName,
                                providerName = target.providerName,
                                responseText = text,
                                latencyMs = latency,
                                success = true
                            )
                        }
                    } catch (e: Exception) {
                        ArenaCandidateResult(
                            candidateId = target.id,
                            displayName = target.displayName,
                            providerName = target.providerName,
                            responseText = "",
                            latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                            success = false,
                            errorMessage = e.message ?: "Request failed"
                        )
                    }
                }
            }.awaitAll()
        }

        val succeeded = results.filter { it.success }
        val synthesis = if (!runDebateVerification) {
            "${succeeded.size}/${results.size} models responded."
        } else {
            synthesizeVerification(prompt, succeeded, results.size)
        }

        ComparisonArenaReport(
            prompt = prompt,
            results = results,
            synthesisSummary = synthesis,
            totalElapsedMs = System.currentTimeMillis() - overallStart
        )
    }

    private suspend fun synthesizeVerification(
        prompt: String,
        succeeded: List<ArenaCandidateResult>,
        totalAttempted: Int
    ): String {
        if (succeeded.isEmpty()) {
            return "All $totalAttempted selected models failed to respond. Inspect individual model errors below."
        }
        if (succeeded.size == 1) {
            return "Single verified response from ${succeeded.first().displayName} (${succeeded.first().latencyMs}ms). Configure a second model for cross-model verification."
        }

        val debatePrompt = buildString {
            append("Original Question: ").append(prompt).append("\n\n")
            append("Compare these ${succeeded.size} independent model answers. Identify:\n")
            append("1. Consensus Facts (where models agree)\n")
            append("2. Discrepancies or Gaps (where models differ or omit detail)\n")
            append("3. Final Verified Answer (concise, accurate)\n\n")
            succeeded.forEachIndexed { idx, res ->
                append("--- Model ${idx + 1}: ${res.displayName} (${res.latencyMs}ms) ---\n")
                append(res.responseText.take(1800)).append("\n\n")
            }
        }

        // Try Firebase AI Logic first for zero-key synthesis, else first working external channel
        val fbSynthesis = runCatching {
            if (firebaseEngine != null && context != null && FirebaseAiEngine.isAvailable(context)) {
                firebaseEngine.generateText(
                    prompt = debatePrompt,
                    systemPrompt = "You are an impartial multi-model verification arbiter.",
                    modelName = FirebaseAiEngine.MODEL_FLASH
                ).orEmpty()
            } else ""
        }.getOrDefault("")
        if (fbSynthesis.isNotBlank()) return fbSynthesis

        val firstChannel = externalProviders.getActiveChannels().firstOrNull()
        if (firstChannel != null) {
            val synthesized = runCatching {
                externalProviders.callChannel(
                    firstChannel,
                    listOf(
                        ChatTurn("system", "You are an impartial multi-model verification arbiter."),
                        ChatTurn("user", debatePrompt)
                    ),
                    withSearchGrounding = false
                )
            }.getOrDefault("")
            if (synthesized.isNotBlank()) return synthesized
        }

        return "Cross-model run complete (${succeeded.size}/$totalAttempted succeeded). Fastest: ${succeeded.minByOrNull { it.latencyMs }?.displayName}."
    }
}
