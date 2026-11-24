package com.ayra.ai.app.ai.gemini

import android.content.Context
import com.ayra.ai.app.ai.AiResponse
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Direct Gemini CLI & Firebase Engine.
 * Executes direct Gemini REST or Firebase AI Logic commands with system directives,
 * search grounding, multimodal image attachments, and ProviderPool failover.
 */
class GeminiCliEngine(private val context: Context? = null) {

    private val externalProviders = ExternalAiProviders(context)

    suspend fun executeCliQuery(
        prompt: String,
        systemDirective: String? = null,
        imageBase64: String? = null
    ): AiResponse = withContext(Dispatchers.IO) {
        val sys = systemDirective ?: "You are Gemini CLI Engine. Provide direct, highly accurate, and helpful answers in the user's language."

        if (externalProviders.isProviderConfigured(AiProviderType.GEMINI)) {
            try {
                val resp = externalProviders.generateChatResponse(
                    prompt = prompt,
                    systemPrompt = sys,
                    imageBase64 = imageBase64,
                    preferredProvider = AiProviderType.GEMINI,
                    preferredModelId = ExternalAiProviders.GEMINI_FLASH_MODEL,
                    withSearchGrounding = externalProviders.allowExternalWikiGrounding
                )
                if (resp.text.isNotBlank()) {
                    return@withContext AiResponse(
                        text = resp.text,
                        modelUsed = resp.modelUsed.ifBlank { "Gemini 3.6 Flash (Direct CLI) ⚡" }
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        if (FirebaseAiEngine.isAvailable(context)) {
            val fbRes = try {
                FirebaseAiEngine(context).generateText(
                    prompt = prompt,
                    systemPrompt = sys,
                    imageBase64 = imageBase64,
                    modelName = FirebaseAiEngine.MODEL_FLASH,
                    enableSearchGrounding = true
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                ""
            }
            if (fbRes.isNotBlank()) {
                return@withContext AiResponse(
                    text = fbRes,
                    modelUsed = "Gemini 3.6 Flash (Firebase AI CLI) ⚡"
                )
            }
        }

        val poolResp = ProviderPool(context, externalProviders).executeSync(
            ChatRequest(
                prompt = prompt,
                mode = ProviderPoolMode.BEST,
                imageBase64 = imageBase64,
                systemPromptOverride = sys
            )
        )
        AiResponse(
            text = poolResp.text,
            modelUsed = poolResp.modelUsed.ifBlank { "Gemini CLI (${poolResp.provider.displayName}) ⚡" }
        )
    }
}
