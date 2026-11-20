package com.ayra.ai.app.ai.openmodel

import android.content.Context
import com.ayra.ai.app.ai.AiResponse
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelCatalog
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.NetworkSecurityHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Open-Weights Local & Cloud AI Engine.
 * Routes technical, scientific, historical, and multilingual queries to user-configured
 * local Ollama / LM Studio instances or open-weights cloud models (Qwen 2.5, Llama 3.3, Qwen3)
 * while maintaining standard safety boundaries.
 */
class OpenModelEngine(private val context: Context? = null) {

    private val externalProviders by lazy { ExternalAiProviders(context) }

    companion object {
        const val OLLAMA_DEFAULT_SERVING = "qwen2.5:14b"
        const val SAFE_SYSTEM_PROMPT =
            "You are Ayra Open-Weights Engine. Provide objective, accurate, technical, scientific, and educational answers in the user's language. Do not assist with malware creation, actionable cyber exploits, weapons, self-harm instruction, or unauthorized access to private systems or data."
    }

    suspend fun queryOpenModel(prompt: String, systemInstruction: String? = null): AiResponse = withContext(Dispatchers.IO) {
        val sys = systemInstruction ?: SAFE_SYSTEM_PROMPT

        val candidates = listOf(
            AiProviderType.LOCAL_OLLAMA to (ModelCatalog.defaultModelFor(AiProviderType.LOCAL_OLLAMA)?.id ?: OLLAMA_DEFAULT_SERVING),
            AiProviderType.OPENROUTER to ExternalAiProviders.OPENROUTER_QWEN_FREE_MODEL,
            AiProviderType.HUGGINGFACE to ExternalAiProviders.HF_QWEN_MODEL,
            AiProviderType.GROQ to ExternalAiProviders.GROQ_QWEN_MODEL,
            AiProviderType.CEREBRAS to ExternalAiProviders.CEREBRAS_QWEN_MODEL,
            AiProviderType.SAMBANOVA to ExternalAiProviders.SAMBANOVA_QWEN_MODEL
        )
        val firstConfigured = candidates.firstOrNull { (p, _) -> externalProviders.isProviderConfigured(p) }

        val resp = try {
            externalProviders.generateChatResponse(
                prompt = prompt,
                systemPrompt = sys,
                preferredProvider = firstConfigured?.first,
                preferredModelId = firstConfigured?.second,
                withSearchGrounding = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            com.ayra.ai.app.ai.providers.ProviderPool(context, externalProviders).executeSync(
                com.ayra.ai.app.ai.providers.ChatRequest(
                    prompt = prompt,
                    mode = com.ayra.ai.app.ai.providers.ProviderPoolMode.BEST,
                    systemPromptOverride = sys
                )
            )
        }

        AiResponse(
            text = resp.text,
            modelUsed = "Open-Weights (${resp.modelUsed})"
        )
    }

    suspend fun testConnection(endpoint: String): Long = withContext(Dispatchers.IO) {
        val targetUrl = endpoint.trim().ifBlank {
            context?.let { AyraPreferences.getCustomEndpointUrl(it) } ?: "http://10.0.2.2:11434"
        }
        val check = NetworkSecurityHelper.validateLocalAiEndpointUrl(context, targetUrl)
        if (!check.allowed) {
            throw SecurityException(check.reason ?: "Blocked unsafe endpoint URL.")
        }
        val start = System.currentTimeMillis()
        val client = NetworkSecurityHelper.localLanClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()

        val base = targetUrl.trimEnd('/')
        val probeUrls = listOf("$base/api/tags", "$base/v1/models", "$base/")
        var lastError: Exception? = null
        var lastStatus: Int? = null

        for (probe in probeUrls) {
            val req = Request.Builder().url(probe).get().build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful || resp.code == 401 || resp.code == 403) {
                        return@withContext (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    }
                    lastStatus = resp.code
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (lastStatus != null) {
            throw IllegalStateException("Endpoint unreachable or unhealthy (HTTP $lastStatus)")
        }
        throw lastError ?: IllegalStateException("Unable to reach endpoint: $targetUrl")
    }
}
