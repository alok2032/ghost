package com.ayra.ai.app.ai.providers

import android.content.Context
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.NetworkSecurityHelper
import com.ayra.ai.app.security.NetworkSecurityHelper.readBounded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.FlowCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * OpenAI-compatible REST, SSE streaming, tool-calling, and /models discovery transport
 * covering NVIDIA NIM, Groq, Cerebras, SambaNova, HuggingFace, GitHub Models,
 * OpenAI, DeepSeek, xAI, Mistral, Perplexity, OpenRouter, and Local Ollama.
 */
internal object ProviderOpenAiTransport {

    fun getBaseUrlForProvider(
        provider: AiProviderType,
        context: Context?,
        baseUrlOverrides: Map<AiProviderType, String>
    ): String {
        baseUrlOverrides[provider]?.let { custom ->
            return if (custom.endsWith("/")) custom else "$custom/"
        }
        return when (provider) {
            AiProviderType.NVIDIA_NIM -> "https://integrate.api.nvidia.com/v1/"
            AiProviderType.GROQ -> "https://api.groq.com/openai/v1/"
            AiProviderType.CEREBRAS -> "https://api.cerebras.ai/v1/"
            AiProviderType.SAMBANOVA -> "https://api.sambanova.ai/v1/"
            AiProviderType.HUGGINGFACE -> "https://router.huggingface.co/v1/"
            AiProviderType.GITHUB_MODELS -> throw ProviderError.ModelNotFound(
                provider,
                "github-models",
                "GitHub Models Azure inference endpoint was retired on 2026-07-30.",
                httpStatus = 410
            )
            AiProviderType.OPENAI -> "https://api.openai.com/v1/"
            AiProviderType.DEEPSEEK -> "https://api.deepseek.com/"
            AiProviderType.XAI -> "https://api.x.ai/v1/"
            AiProviderType.MISTRAL -> "https://api.mistral.ai/v1/"
            AiProviderType.PERPLEXITY -> "https://api.perplexity.ai/"
            AiProviderType.OPENROUTER -> "https://openrouter.ai/api/v1/"
            AiProviderType.POLLINATIONS_FREE -> "https://text.pollinations.ai/"
            AiProviderType.LOCAL_OLLAMA -> {
                val custom = context?.let { AyraPreferences.getCustomEndpointUrl(it).trim() } ?: ""
                if (custom.isNotBlank()) {
                    val normalized = custom.removeSuffix("/chat/completions").trimEnd('/')
                    "$normalized/"
                } else {
                    val host = context?.let { AyraPreferences.getOllamaHost(it).trim().trimEnd('/') } ?: "http://10.0.2.2:11434"
                    if (host.endsWith("/v1")) "$host/" else "$host/v1/"
                }
            }
            else -> "https://api.openai.com/v1/"
        }
    }

    private fun supportsMultimodalImageUrl(provider: AiProviderType, model: String): Boolean {
        if (provider == AiProviderType.OPENAI || provider == AiProviderType.OPENROUTER) return true
        if (provider == AiProviderType.NVIDIA_NIM) {
            return model.contains("minimax", ignoreCase = true) ||
                model.contains("gemma", ignoreCase = true) ||
                model.contains("vision", ignoreCase = true)
        }
        return false
    }

    private fun buildOpenAiMessages(provider: AiProviderType, model: String, turns: List<ChatTurn>): JSONArray {
        val messagesArray = JSONArray()
        val canAttachImage = supportsMultimodalImageUrl(provider, model)
        for (turn in turns) {
            messagesArray.put(JSONObject().apply {
                put("role", turn.role)
                if (!turn.imageBase64.isNullOrBlank() && canAttachImage) {
                    val contentParts = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", turn.content)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,${turn.imageBase64}")
                            })
                        })
                    }
                    put("content", contentParts)
                } else {
                    put("content", turn.content)
                }
            })
        }
        return messagesArray
    }

    private fun validateEndpointIfLocal(
        provider: AiProviderType,
        context: Context?,
        baseUrl: String,
        hasOverrides: Boolean
    ) {
        if (provider == AiProviderType.LOCAL_OLLAMA && !hasOverrides) {
            val check = NetworkSecurityHelper.validateLocalAiEndpointUrl(context, baseUrl)
            if (!check.allowed) {
                throw ProviderError.NetworkFailure(check.reason ?: "Blocked unsafe local endpoint URL.")
            }
        }
    }

    private fun shouldAttachBearerHeader(provider: AiProviderType, context: Context?, apiKey: String): Boolean {
        val clean = apiKey.trim()
        if (clean.isBlank()) return false
        if (provider != AiProviderType.LOCAL_OLLAMA) return true
        if (clean == "local") return false
        if (context != null) {
            val url = AyraPreferences.getCustomEndpointUrl(context).trim()
            val host = AyraPreferences.getOllamaHost(context).trim()
            if (clean == url || clean == host) return false
        }
        return true
    }

    fun callOpenAiCompatible(
        client: OkHttpClient,
        context: Context?,
        provider: AiProviderType,
        baseUrlOverrides: Map<AiProviderType, String>,
        hasCustomClient: Boolean,
        turns: List<ChatTurn>,
        model: String,
        apiKey: String,
        maxTokens: Int = 4096,
        preserveReasoning: Boolean = false
    ): String {
        val baseUrl = getBaseUrlForProvider(provider, context, baseUrlOverrides)
        validateEndpointIfLocal(provider, context, baseUrl, hasCustomClient || baseUrlOverrides.isNotEmpty())
        val endpoint = "${baseUrl}chat/completions"

        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", buildOpenAiMessages(provider, model, turns))
            put("max_tokens", maxTokens)
            if (!model.contains("reasoner") && !model.contains("astra") && !model.contains("v4-pro") && !model.contains("o1") && !model.contains("o3")) {
                put("temperature", 0.65)
                put("top_p", 0.95)
            }
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
        if (shouldAttachBearerHeader(provider, context, apiKey)) reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        if (provider == AiProviderType.OPENROUTER) {
            reqBuilder.addHeader("HTTP-Referer", "https://ayra.ai")
            reqBuilder.addHeader("X-Title", "Ayra AI")
        }

        return client.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 429) throw ProviderError.RateLimited("${provider.displayName} rate limit reached.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(provider)
            if (response.code == 404 || response.code == 410) {
                throw ProviderError.ModelNotFound(provider, model, response.readBounded(truncateOnOverflow = true), httpStatus = response.code)
            }
            if (!response.isSuccessful) {
                val errBody = response.readBounded(truncateOnOverflow = true)
                if (errBody.contains("model_not_found", ignoreCase = true) ||
                    errBody.contains("does not exist", ignoreCase = true)
                ) {
                    throw ProviderError.ModelNotFound(provider, model, errBody)
                }
                throw ProviderError.ServerError(response.code, errBody.ifBlank { response.message })
            }

            val json = JSONObject(response.readBounded())
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                val rawContent = if (message != null && !message.isNull("content")) message.optString("content", "") else ""
                val reasoningContent = if (message != null && !message.isNull("reasoning_content")) message.optString("reasoning_content", "").trim() else ""
                if (preserveReasoning) {
                    if (reasoningContent.isNotBlank() && rawContent.isNotBlank() && !rawContent.contains("<think>", ignoreCase = true)) {
                        return "<think>\n$reasoningContent\n</think>\n\n${rawContent.trim()}"
                    }
                    if (rawContent.isNotBlank()) return rawContent.trim()
                    if (reasoningContent.isNotBlank()) return "<think>\n$reasoningContent\n</think>"
                }
                val cleaned = ExternalAiProviders.sanitizeModelOutput(rawContent)
                if (cleaned.isNotBlank()) return cleaned
                if (reasoningContent.isNotBlank()) return ExternalAiProviders.sanitizeModelOutput(reasoningContent)
            }
            ""
        }
    }

    fun callOpenAiWithTools(
        client: OkHttpClient,
        context: Context?,
        provider: AiProviderType,
        baseUrlOverrides: Map<AiProviderType, String>,
        hasCustomClient: Boolean,
        turns: List<ChatTurn>,
        model: String,
        key: String,
        toolsArray: JSONArray,
        maxTokens: Int = 4096
    ): ExternalAiProviders.OpenAiToolTurnResponse {
        val baseUrl = getBaseUrlForProvider(provider, context, baseUrlOverrides)
        validateEndpointIfLocal(provider, context, baseUrl, hasCustomClient || baseUrlOverrides.isNotEmpty())
        val endpoint = "${baseUrl}chat/completions"

        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", buildOpenAiMessages(provider, model, turns))
            put("max_tokens", maxTokens)
            if (toolsArray.length() > 0) {
                put("tools", toolsArray)
                put("tool_choice", "auto")
            }
            if (!model.contains("reasoner") && !model.contains("astra") && !model.contains("v4-pro") && !model.contains("o1") && !model.contains("o3")) {
                put("temperature", 0.3)
            }
        }
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
        if (shouldAttachBearerHeader(provider, context, key)) reqBuilder.addHeader("Authorization", "Bearer $key")
        if (provider == AiProviderType.OPENROUTER) {
            reqBuilder.addHeader("HTTP-Referer", "https://ayra.ai")
            reqBuilder.addHeader("X-Title", "Ayra AI")
        }

        return client.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 429) throw ProviderError.RateLimited("${provider.displayName} rate limit reached.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(provider)
            if (response.code == 404 || response.code == 410) {
                throw ProviderError.ModelNotFound(provider, model, response.readBounded(truncateOnOverflow = true), httpStatus = response.code)
            }
            if (!response.isSuccessful) {
                throw ProviderError.ServerError(response.code, response.readBounded(truncateOnOverflow = true).ifBlank { response.message })
            }
            val json = JSONObject(response.readBounded())
            val choices = json.optJSONArray("choices") ?: return ExternalAiProviders.OpenAiToolTurnResponse()
            if (choices.length() == 0) return ExternalAiProviders.OpenAiToolTurnResponse()
            val message = choices.getJSONObject(0).optJSONObject("message") ?: return ExternalAiProviders.OpenAiToolTurnResponse()
            val text = if (!message.isNull("content")) ExternalAiProviders.sanitizeModelOutput(message.optString("content", "")) else ""
            val calls = mutableListOf<ExternalAiProviders.OpenAiToolCallSpec>()
            val toolCallsArr = message.optJSONArray("tool_calls")
            if (toolCallsArr != null) {
                for (i in 0 until toolCallsArr.length()) {
                    val item = toolCallsArr.optJSONObject(i) ?: continue
                    val fn = item.optJSONObject("function") ?: continue
                    val name = fn.optString("name", "").trim()
                    val args = fn.optString("arguments", "{}").ifBlank { "{}" }
                    if (name.isNotBlank()) {
                        calls.add(ExternalAiProviders.OpenAiToolCallSpec(item.optString("id", "call_$i"), name, args))
                    }
                }
            }
            ExternalAiProviders.OpenAiToolTurnResponse(content = text, toolCalls = calls)
        }
    }

    suspend fun streamOpenAiCompatible(
        client: OkHttpClient,
        context: Context?,
        provider: AiProviderType,
        baseUrlOverrides: Map<AiProviderType, String>,
        hasCustomClient: Boolean,
        turns: List<ChatTurn>,
        model: String,
        apiKey: String,
        maxTokens: Int = 4096,
        collector: FlowCollector<String>
    ) {
        val baseUrl = getBaseUrlForProvider(provider, context, baseUrlOverrides)
        validateEndpointIfLocal(provider, context, baseUrl, hasCustomClient || baseUrlOverrides.isNotEmpty())
        val endpoint = "${baseUrl}chat/completions"

        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", buildOpenAiMessages(provider, model, turns))
            put("max_tokens", maxTokens)
            put("stream", true)
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .header("Accept", "text/event-stream")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
        if (shouldAttachBearerHeader(provider, context, apiKey)) reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        if (provider == AiProviderType.OPENROUTER) {
            reqBuilder.addHeader("HTTP-Referer", "https://ayra.ai")
            reqBuilder.addHeader("X-Title", "Ayra AI")
        }

        client.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 429) throw ProviderError.RateLimited("${provider.displayName} rate limit reached.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(provider)
            if (response.code == 404 || response.code == 410) {
                throw ProviderError.ModelNotFound(provider, model, response.readBounded(truncateOnOverflow = true).ifBlank { response.message }, httpStatus = response.code)
            }
            if (!response.isSuccessful) {
                val err = response.readBounded(truncateOnOverflow = true)
                throw ProviderError.ServerError(response.code, err.ifBlank { response.message })
            }
            val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return, StandardCharsets.UTF_8))
            var line: String?
            var inThinkBlock = false
            var emittedAnySse = false
            val rawFallbackBuffer = StringBuilder()
            while (reader.readLine().also { line = it } != null) {
                val rawLine = line ?: continue
                val l = rawLine.trim()
                if (l.startsWith("data:")) {
                    val data = l.substring(5).trim()
                    if (data == "[DONE]") break
                    if (data.isNotBlank()) {
                        try {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                val rawText = if (delta != null && !delta.isNull("content")) delta.optString("content", "") else ""
                                if (rawText.isNotEmpty()) {
                                    var chunk = rawText
                                    if (chunk.contains("<think>", ignoreCase = true)) {
                                        inThinkBlock = true
                                        chunk = chunk.substringBefore("<think>")
                                    }
                                    if (inThinkBlock) {
                                        if (chunk.contains("</think>", ignoreCase = true)) {
                                            inThinkBlock = false
                                            chunk = chunk.substringAfter("</think>")
                                        } else {
                                            chunk = ""
                                        }
                                    }
                                    if (chunk.isNotEmpty()) {
                                        emittedAnySse = true
                                        collector.emit(chunk)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {}
                    }
                } else if (!emittedAnySse && l.isNotEmpty()) {
                    rawFallbackBuffer.append(rawLine).append('\n')
                }
            }

            if (!emittedAnySse && rawFallbackBuffer.isNotBlank()) {
                try {
                    val json = JSONObject(rawFallbackBuffer.toString().trim())
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val message = choices.getJSONObject(0).optJSONObject("message")
                        val rawContent = if (message != null && !message.isNull("content")) message.optString("content", "") else ""
                        val cleaned = ExternalAiProviders.sanitizeModelOutput(rawContent).ifBlank {
                            val r = if (message != null && !message.isNull("reasoning_content")) message.optString("reasoning_content", "") else ""
                            ExternalAiProviders.sanitizeModelOutput(r)
                        }
                        if (cleaned.isNotEmpty()) collector.emit(cleaned)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
        }
    }

    fun discoverModels(
        client: OkHttpClient,
        context: Context?,
        provider: AiProviderType,
        baseUrlOverrides: Map<AiProviderType, String>,
        key: String,
        out: MutableList<AiModelInfo>
    ) {
        val baseUrl = getBaseUrlForProvider(provider, context, baseUrlOverrides)
        validateEndpointIfLocal(provider, context, baseUrl, baseUrlOverrides.isNotEmpty())
        val reqBuilder = Request.Builder().url("${baseUrl}models")
        if (shouldAttachBearerHeader(provider, context, key)) {
            reqBuilder.addHeader("Authorization", "Bearer $key")
        }
        client.newCall(reqBuilder.get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return
            val bodyStr = resp.readBounded().trim()
            if (bodyStr.isBlank()) return
            val root = runCatching { JSONObject(bodyStr) }.getOrNull() ?: return
            val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val modelId = obj.optString("id", obj.optString("name", "")).trim()
                if (modelId.isNotBlank() && !shouldSkipDiscoveredModel(modelId)) {
                    val lower = modelId.lowercase()
                    val isVision = lower.contains("vision") || lower.contains("4o") || lower.contains("pixtral") || lower.contains("minimax") || lower.contains("gpt-6") || lower.contains("gemma")
                    val isReasoning = lower.contains("r1") || lower.contains("o1") || lower.contains("o3") || lower.contains("reasoner") || lower.contains("glm") || lower.contains("gemma") || lower.contains("astra") || lower.contains("v4-pro")
                    out.add(
                        AiModelInfo(
                            id = modelId,
                            displayName = modelId,
                            provider = provider,
                            description = "${provider.displayName} live model",
                            contextWindow = "Adaptive",
                            supportsVision = isVision,
                            isReasoning = isReasoning,
                            isDefault = false
                        )
                    )
                }
            }
        }
    }

    internal fun shouldSkipDiscoveredModel(modelId: String): Boolean {
        val lower = modelId.trim().lowercase()
        if (lower.isBlank()) return true
        val nonChatPrefixes = listOf(
            "whisper", "tts-", "dall-e", "text-embedding", "embedding-",
            "omni-moderation", "text-moderation", "babbage-", "davinci-",
            "sora-", "canary-"
        )
        if (nonChatPrefixes.any { lower.startsWith(it) || lower.contains("/$it") }) return true
        if (lower.contains("realtime") || lower.contains("transcribe") || lower.contains("audio-preview")) return true
        return false
    }
}
