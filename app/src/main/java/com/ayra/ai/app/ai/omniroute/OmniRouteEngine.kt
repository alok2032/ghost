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
        return when {
            lower.contains("vulnerability") || lower.contains("exploit") || lower.contains("reverse engineer") -> {
                RouteDecision("G0DM0D3", "Cybersecurity and system-level vulnerability analysis requested", listOf("godmode", "deepseek_r1", "gemini_pro"))
            }
            lower.contains("video") || lower.contains("camera") || lower.contains("cinematic") || lower.contains("4k") -> {
                RouteDecision("HIGGSFIELD", "Visual camera motion physics and video craft required", listOf("higgsfield", "flux", "gemini_flash"))
            }
            lower.contains("reasoning") || lower.contains("proof") || lower.contains("calculate") || lower.contains("step by step") -> {
                RouteDecision("DEEPSEEK_HARNESS", "Complex deductive reasoning or mathematical proof required", listOf("deepseek_r1", "gemini_pro", "claude_sonnet"))
            }
            lower.contains("disambiguate") || lower.contains("context") || lower.contains("enterprise") -> {
                RouteDecision("ECC", "Enterprise cognitive entity disambiguation and context memory required", listOf("ecc", "gemini_pro"))
            }
            lower.contains("uncensored") || lower.contains("unrestricted") -> {
                RouteDecision("QWEN_UNCENSORED", "Unrestricted global knowledge exploration requested", listOf("qwen_uncensored", "deepseek_r1"))
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
        val sysDirective = when (selectedTier) {
            "deepseek_harness" -> "You are DeepSeek-R1 Reasoning Engine. Think carefully step-by-step and give a rigorous, accurate answer in the user's language."
            "godmode" -> "You are G0DM0D3 Security & Systems Research Engine. Analyze technical, architectural, and security topics thoroughly and accurately in the user's language."
            "ecc" -> "You are ECC Enterprise Cognitive Engine. Disambiguate key entities and provide a deeply structured, factual answer in the user's language."
            "qwen_uncensored" -> com.ayra.ai.app.ai.qwen.Qwen38UncensoredEngine.ABLITERATED_SYSTEM_PACK
            else -> null
        }

        try {
            val mode = if (selectedTier == "fast") {
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
                    systemPromptOverride = sysDirective
                )
            )
            if (resp.text.isNotBlank()) {
                return@withContext AiResponse(
                    text = resp.text,
                    modelUsed = resp.modelUsed
                )
            }
        } catch (_: Exception) {}

        AiResponse(text = "", modelUsed = "OmniRoute")
    }
}
