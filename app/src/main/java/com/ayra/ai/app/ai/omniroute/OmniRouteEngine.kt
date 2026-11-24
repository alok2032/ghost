package com.ayra.ai.app.ai.omniroute

import android.content.Context
import com.ayra.ai.app.ai.AiResponse
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.data.AyraPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class RouteDecision(
    val selectedEngine: String,
    val rationale: String,
    val candidateModels: List<String>
)

/**
 * OmniRoute Multi-Architecture Ensemble Engine.
 * Intelligently fans out inference across all active AI models in parallel,
 * compares candidate drafts, and synthesizes the best consensus answer.
 */
class OmniRouteEngine(private val context: Context? = null) {

    private val externalProviders = ExternalAiProviders(context)

    fun determineRoute(prompt: String, selectedTier: String): RouteDecision {
        val lower = prompt.lowercase()
        val tier = com.ayra.ai.app.ai.providers.ModelTier.fromId(selectedTier)
        return when {
            tier == com.ayra.ai.app.ai.providers.ModelTier.SECURITY_RESEARCH || lower.contains("vulnerability") || lower.contains("cve-") || lower.contains("owasp") -> {
                RouteDecision("SECURITY_RESEARCH", "Defensive cybersecurity and vulnerability mitigation analysis requested", listOf("security_research", "deepseek_r1", "gemini_pro"))
            }
            tier == com.ayra.ai.app.ai.providers.ModelTier.OPEN_HIGGSFIELD || lower.contains("video") || lower.contains("camera") || lower.contains("cinematic") || lower.contains("4k") -> {
                RouteDecision("HIGGSFIELD", "Visual camera motion physics and video craft required", listOf("higgsfield", "flux", "gemini_flash"))
            }
            tier == com.ayra.ai.app.ai.providers.ModelTier.DEEPSEEK_HARNESS || tier == com.ayra.ai.app.ai.providers.ModelTier.OPENROUTER_DEEPSEEK || lower.contains("reasoning") || lower.contains("proof") || lower.contains("calculate") || lower.contains("step by step") -> {
                RouteDecision("DEEPSEEK_HARNESS", "Complex deductive reasoning or mathematical proof required", listOf("deepseek_r1", "gemini_pro", "claude_sonnet"))
            }
            tier == com.ayra.ai.app.ai.providers.ModelTier.ECC_COGNITIVE || lower.contains("disambiguate") || lower.contains("context") || lower.contains("enterprise") -> {
                RouteDecision("ECC", "Enterprise cognitive entity disambiguation and context memory required", listOf("ecc", "gemini_pro"))
            }
            tier == com.ayra.ai.app.ai.providers.ModelTier.OPEN_WEIGHTS || lower.contains("open weights") || lower.contains("local ollama") -> {
                RouteDecision("OPEN_WEIGHTS", "Objective open-weights local or cloud knowledge analysis requested", listOf("open_weights", "deepseek_r1"))
            }
            tier == com.ayra.ai.app.ai.providers.ModelTier.OPENROUTER_LLAMA -> {
                RouteDecision("LLAMA_3_3", "High-speed Llama 3.3 70B literature and synthesis routing", listOf("llama_3_3", "groq", "cerebras"))
            }
            tier == com.ayra.ai.app.ai.providers.ModelTier.SUPERPOWERS -> {
                RouteDecision("SUPERPOWERS", "Structured Superpowers methodology workflow execution", listOf("superpowers", "gemini_pro", "deepseek_r1"))
            }
            else -> {
                RouteDecision("OMNI_ENSEMBLE", "Multi-model consensus synergy across active providers", listOf("gemini_flash", "gemini_pro", "openrouter"))
            }
        }
    }

    suspend fun executeRoute(
        prompt: String,
        selectedTier: String = "auto",
        attachedImageBase64: String? = null,
        attachedDocumentText: String? = null
    ): AiResponse = withContext(Dispatchers.IO) {
        val pool = com.ayra.ai.app.ai.providers.ProviderPool(context, externalProviders)
        val tier = com.ayra.ai.app.ai.providers.ModelTier.fromId(selectedTier)
        val (prefProvider, prefModel) = tier.resolvePreferredProviderAndModel(externalProviders)
        val sysDirective = tier.buildSpecialistSystemDirective(prompt, context, baseSystemPrompt = null)

        try {
            val mode = if (tier == com.ayra.ai.app.ai.providers.ModelTier.GEMINI_FLASH &&
                selectedTier.equals("fast", ignoreCase = true)
            ) {
                com.ayra.ai.app.ai.providers.ProviderPoolMode.FAST
            } else {
                com.ayra.ai.app.ai.providers.ProviderPoolMode.BEST
            }
            val resp = pool.executeSync(
                com.ayra.ai.app.ai.providers.ChatRequest(
                    prompt = prompt,
                    mode = mode,
                    imageBase64 = attachedImageBase64,
                    documentText = attachedDocumentText,
                    preferredProvider = prefProvider,
                    preferredModelId = prefModel,
                    specialistDirective = sysDirective
                )
            )
            if (resp.text.isNotBlank()) {
                return@withContext AiResponse(
                    text = resp.text,
                    modelUsed = resp.modelUsed
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("OmniRouteEngine", "executeRoute failed: ${e.message}")
        }

        AiResponse(text = "", modelUsed = "OmniRoute")
    }
}
