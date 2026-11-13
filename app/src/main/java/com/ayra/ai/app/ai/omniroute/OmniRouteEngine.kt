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
            selectedTier == "godmode" || lower.contains("vulnerability") || lower.contains("exploit") || lower.contains("reverse engineer") || lower.contains("cve-") -> {
                RouteDecision("G0DM0D3", "Cybersecurity and system-level vulnerability analysis requested", listOf("godmode", "deepseek_r1", "gemini_pro"))
            }
            selectedTier == "higgsfield" || lower.contains("video") || lower.contains("camera") || lower.contains("cinematic") || lower.contains("4k") -> {
                RouteDecision("HIGGSFIELD", "Visual camera motion physics and video craft required", listOf("higgsfield", "flux", "gemini_flash"))
            }
            selectedTier == "deepseek_harness" || selectedTier == "deepseek_r1" || lower.contains("reasoning") || lower.contains("proof") || lower.contains("calculate") || lower.contains("step by step") -> {
                RouteDecision("DEEPSEEK_HARNESS", "Complex deductive reasoning or mathematical proof required", listOf("deepseek_r1", "gemini_pro", "claude_sonnet"))
            }
            selectedTier == "ecc" || lower.contains("disambiguate") || lower.contains("context") || lower.contains("enterprise") -> {
                RouteDecision("ECC", "Enterprise cognitive entity disambiguation and context memory required", listOf("ecc", "gemini_pro"))
            }
            selectedTier == "qwen_uncensored" || lower.contains("uncensored") || lower.contains("unrestricted") -> {
                RouteDecision("QWEN_UNCENSORED", "Unrestricted global knowledge exploration requested", listOf("qwen_uncensored", "deepseek_r1"))
            }
            selectedTier == "llama_3_3" -> {
                RouteDecision("LLAMA_3_3", "High-speed Llama 3.3 70B literature and synthesis routing", listOf("llama_3_3", "groq", "cerebras"))
            }
            selectedTier == "superpowers" -> {
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

        val prefProvider: AiProviderType?
        val prefModel: String?
        when (selectedTier) {
            "deepseek_harness", "deepseek_r1" -> {
                val dsChoice = listOf(
                    AiProviderType.DEEPSEEK to "deepseek-reasoner",
                    AiProviderType.SAMBANOVA to ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL,
                    AiProviderType.OPENROUTER to "deepseek/deepseek-r1:free"
                ).firstOrNull { externalProviders.isProviderConfigured(it.first) }
                prefProvider = dsChoice?.first
                prefModel = dsChoice?.second
            }
            "llama_3_3" -> {
                val llamaChoice = listOf(
                    AiProviderType.GROQ to ExternalAiProviders.GROQ_LLAMA_MODEL,
                    AiProviderType.CEREBRAS to ExternalAiProviders.CEREBRAS_LLAMA_MODEL,
                    AiProviderType.SAMBANOVA to ExternalAiProviders.SAMBANOVA_LLAMA_MODEL,
                    AiProviderType.OPENROUTER to "meta-llama/llama-3.3-70b-instruct:free"
                ).firstOrNull { externalProviders.isProviderConfigured(it.first) }
                prefProvider = llamaChoice?.first
                prefModel = llamaChoice?.second
            }
            "qwen_uncensored" -> {
                val qwenChoice = listOf(
                    AiProviderType.LOCAL_OLLAMA to com.ayra.ai.app.ai.qwen.Qwen38UncensoredEngine.OLLAMA_DEFAULT_SERVING,
                    AiProviderType.OPENROUTER to "qwen/qwen-2.5-72b-instruct",
                    AiProviderType.HUGGINGFACE to ExternalAiProviders.HF_QWEN_MODEL,
                    AiProviderType.GROQ to ExternalAiProviders.GROQ_QWEN_MODEL,
                    AiProviderType.CEREBRAS to ExternalAiProviders.CEREBRAS_QWEN_MODEL
                ).firstOrNull { externalProviders.isProviderConfigured(it.first) }
                prefProvider = qwenChoice?.first
                prefModel = qwenChoice?.second
            }
            else -> {
                prefProvider = null
                prefModel = null
            }
        }

        val securityGrounding = if (selectedTier == "godmode") {
            com.ayra.ai.app.ai.godmode.GodModePliniusEngine(context).buildSecurityGrounding(prompt)
        } else ""

        val sysDirective = when (selectedTier) {
            "deepseek_harness", "deepseek_r1" ->
                "You are DeepSeek-R1 Reasoning Engine. Think carefully step-by-step and give a rigorous, verified answer in the user's language."
            "godmode" -> buildString {
                append("You are G0DM0D3 Security & Systems Research Engine. Analyze technical, architectural, and security topics thoroughly and accurately in the user's language.")
                if (securityGrounding.isNotBlank()) append("\n\n").append(securityGrounding)
            }
            "ecc" -> {
                val ctx = com.ayra.ai.app.ai.ecc.EnterpriseCognitiveEngine(context).extractCognitiveContext(prompt)
                "You are ECC Enterprise Cognitive Engine. Domain: ${ctx.domain}. Entities: ${ctx.detectedEntities.joinToString(", ").ifBlank { "General" }}. Disambiguate key entities and provide a deeply structured, factual answer in the user's language."
            }
            "qwen_uncensored" -> com.ayra.ai.app.ai.qwen.Qwen38UncensoredEngine.ABLITERATED_SYSTEM_PACK
            "higgsfield" -> "You are Open-Higgsfield Cinematography & Motion Physics Director. Design camera trajectories, optical lenses, lighting ratios, and shot lists precisely in the user's language."
            "superpowers" -> {
                val isBn = prompt.any { it in '\u0980'..'\u09FF' }
                val sp = com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.detectSuperpower(prompt)
                    ?: com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.SuperpowerType.SYSTEMATIC_DEBUGGING
                com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine.buildSuperpowerSystemPrompt(sp, isBn, context)
            }
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
                    systemPromptOverride = sysDirective,
                    preferredProvider = prefProvider,
                    preferredModelId = prefModel
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
