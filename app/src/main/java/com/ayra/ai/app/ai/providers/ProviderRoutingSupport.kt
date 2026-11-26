package com.ayra.ai.app.ai.providers

import android.content.Context
import com.ayra.ai.app.ai.agents.AgentDomain
import com.ayra.ai.app.ai.agents.AyraAgentOrchestrator
import com.ayra.ai.app.ai.memory.LongTermMemoryManager
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.BlockedByPolicyException
import com.ayra.ai.app.security.NetworkConnectivityManager
import com.ayra.ai.app.security.SecurityAuditLogger
import kotlinx.coroutines.TimeoutCancellationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal object ProviderRoutingSupport {

    fun prioritizeChannelsForRoutingMode(
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
                    AiProviderType.GEMINI -> if (ch.id.contains("3_1_pro") || ch.id.contains("pro")) 9 else 5
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
    fun selectDiverseEnsembleChannels(channels: List<AiModelChannel>, maxCount: Int): List<AiModelChannel> {
        if (channels.isEmpty() || maxCount <= 0) return emptyList()
        val selected = mutableListOf<AiModelChannel>()
        val selectedIds = mutableSetOf<String>()
        val seenModelFamilies = mutableSetOf<String>()
        var pickedLocalOllama = false

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

    suspend fun resolveSystemPrompt(
        context: Context?,
        request: ChatRequest,
        domain: AgentDomain,
        wikiContext: String = ""
    ): String {
        val base = if (!request.systemPromptOverride.isNullOrBlank()) {
            request.systemPromptOverride
        } else {
            val isBengali = request.prompt.any { it in '\u0980'..'\u09FF' }
            val isBanglish = AyraAgentOrchestrator.isBanglish(request.prompt)
            val safePersona = AyraPreferences.resolveSafePersona(context, request.persona)
            val isGfMode = safePersona == "GF_COMPANION"
            val userInstructions = context?.let { AyraPreferences.buildCombinedPersonalizationBlock(it) } ?: ""
            val isMemoryOn = context?.let { AyraPreferences.isMemoryEnabled(it) } ?: false
            val roomFacts = context?.takeIf { isMemoryOn }?.let { ctx ->
                runCatching {
                    LongTermMemoryManager.retrieveRelevantMemories(ctx, request.prompt, topK = 6)
                        .map { "[${it.category}] ${it.content}" }
                }.getOrDefault(emptyList())
            } ?: emptyList()
            val legacyFacts = context?.takeIf { isMemoryOn }?.let { AyraPreferences.getRememberedFacts(it) } ?: emptySet()
            val rememberedFacts = (roomFacts + legacyFacts)
                .filter { !LongTermMemoryManager.containsSensitiveCredentialOrPii(it) }
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
            if (!request.specialistDirective.isNullOrBlank()) {
                append("\n\n")
                append(request.specialistDirective.take(4000))
            }
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

    fun mapExceptionToAyraError(
        context: Context?,
        provider: AiProviderType,
        e: Exception,
        isFirebaseKeyless: Boolean = false
    ): AyraError {
        val rawMsg = (e.localizedMessage ?: e.message ?: e::class.java.simpleName).trim()
        val safeDetail = SecurityAuditLogger.redactSensitiveText(rawMsg).take(180)
        val msg = rawMsg.lowercase()
        val policyException = e as? BlockedByPolicyException
            ?: (e.cause as? BlockedByPolicyException)
        val hasOsInternet = context != null &&
            NetworkConnectivityManager.isValidatedInternetAvailable(context)
        return when {
            policyException != null ->
                AyraError.SecurityPolicyBlocked(policyException.reasonCode, policyException.message ?: safeDetail)
            e is SecurityException || msg.contains("ssrf") || msg.contains("dns rebinding guard") || msg.contains("fake-ip") || msg.contains("blocked internal/metadata") ->
                AyraError.SecurityPolicyBlocked("POLICY_BLOCKED", safeDetail)
            isFirebaseKeyless && (msg.contains("403") || msg.contains("appcheck") || msg.contains("app check") || msg.contains("attestation") || msg.contains("permission")) ->
                AyraError.ProviderDown(provider, 403, safeDetail)
            e is ProviderError.Unauthorized || msg.contains("401") || (!isFirebaseKeyless && msg.contains("403")) || msg.contains("unauthorized") || msg.contains("invalid api key") ->
                AyraError.AuthError(provider, safeDetail)
            e is ProviderError.RateLimited || msg.contains("429") || msg.contains("quota") || msg.contains("rate limit") ->
                AyraError.RateLimited(provider)
            e is ProviderError.Timeout || e is SocketTimeoutException || e is TimeoutCancellationException || msg.contains("timeout") || msg.contains("timed out") ->
                AyraError.Timeout(provider, safeDetail)
            e is ProviderError.ModelNotFound || msg.contains("404") || msg.contains("not found") ->
                AyraError.ProviderDown(provider, 404, safeDetail)
            e is ProviderError.NetworkFailure || e is UnknownHostException || e is ConnectException || msg.contains("unable to resolve host") || msg.contains("no internet") ->
                if (hasOsInternet && !msg.contains("no internet")) {
                    AyraError.DnsError(provider, safeDetail)
                } else {
                    AyraError.NoNetwork(safeDetail)
                }
            e is ProviderError.ServerError ->
                AyraError.ProviderDown(
                    provider,
                    Regex("HTTP\\s*(\\d+)").find(e.displayMessage)?.groupValues?.get(1)?.toIntOrNull() ?: 500,
                    safeDetail
                )
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
