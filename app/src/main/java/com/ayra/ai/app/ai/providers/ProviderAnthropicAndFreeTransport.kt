package com.ayra.ai.app.ai.providers

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
import java.util.concurrent.TimeUnit

/**
 * Anthropic Claude, Global Free AI Pool (Pollinations), and routing-mode selection helpers
 * extracted from [ExternalAiProviders].
 */
internal object ProviderAnthropicAndFreeTransport {

    private fun getAnthropicEndpoint(baseUrlOverrides: Map<AiProviderType, String>): String {
        val base = baseUrlOverrides[AiProviderType.ANTHROPIC]?.let {
            if (it.endsWith("/")) it else "$it/"
        } ?: "https://api.anthropic.com/v1/"
        return "${base}messages"
    }

    fun callAnthropic(
        httpClient: OkHttpClient,
        baseUrlOverrides: Map<AiProviderType, String>,
        turns: List<ChatTurn>,
        model: String,
        apiKey: String
    ): String {
        val url = getAnthropicEndpoint(baseUrlOverrides)
        val systemTurn = turns.firstOrNull { it.role == "system" }

        val messagesArray = JSONArray()
        for (turn in turns.filter { it.role != "system" }) {
            messagesArray.put(JSONObject().apply {
                put("role", if (turn.role == "assistant") "assistant" else "user")
                if (turn.imageBase64 != null) {
                    val contentArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", turn.imageBase64)
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", turn.content)
                        })
                    }
                    put("content", contentArr)
                } else {
                    put("content", turn.content)
                }
            })
        }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            if (systemTurn != null) put("system", systemTurn.content)
            put("messages", messagesArray)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (response.code == 429) throw ProviderError.RateLimited("Anthropic Claude rate limit reached.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(AiProviderType.ANTHROPIC)
            if (response.code == 404 || response.code == 410) {
                throw ProviderError.ModelNotFound(AiProviderType.ANTHROPIC, model, response.readBounded(truncateOnOverflow = true).ifBlank { response.message }, httpStatus = response.code)
            }
            if (!response.isSuccessful) throw ProviderError.ServerError(response.code, response.message)

            val json = JSONObject(response.readBounded())
            val contentArr = json.optJSONArray("content")
            if (contentArr != null && contentArr.length() > 0) {
                val block = contentArr.getJSONObject(0)
                return if (!block.isNull("text")) block.optString("text", "") else ""
            }
            ""
        }
    }

    suspend fun streamAnthropic(
        httpClient: OkHttpClient,
        baseUrlOverrides: Map<AiProviderType, String>,
        turns: List<ChatTurn>,
        model: String,
        apiKey: String,
        collector: FlowCollector<String>
    ) {
        val url = getAnthropicEndpoint(baseUrlOverrides)
        val systemTurn = turns.firstOrNull { it.role == "system" }

        val messagesArray = JSONArray()
        for (turn in turns.filter { it.role != "system" }) {
            messagesArray.put(JSONObject().apply {
                put("role", if (turn.role == "assistant") "assistant" else "user")
                put("content", turn.content)
            })
        }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            put("stream", true)
            if (systemTurn != null) put("system", systemTurn.content)
            put("messages", messagesArray)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 429) throw ProviderError.RateLimited("Anthropic Claude rate limit reached.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(AiProviderType.ANTHROPIC)
            if (response.code == 404 || response.code == 410) {
                throw ProviderError.ModelNotFound(AiProviderType.ANTHROPIC, model, response.readBounded(truncateOnOverflow = true).ifBlank { response.message }, httpStatus = response.code)
            }
            if (!response.isSuccessful) {
                val err = response.readBounded(truncateOnOverflow = true)
                throw ProviderError.ServerError(response.code, err.ifBlank { response.message })
            }
            val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return, StandardCharsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.startsWith("data:")) {
                    val data = l.substring(5).trim()
                    try {
                        val json = JSONObject(data)
                        if (json.optString("type") == "content_block_delta") {
                            val delta = json.optJSONObject("delta")
                            val text = if (delta != null && !delta.isNull("text")) delta.optString("text", "") else ""
                            if (text.isNotEmpty()) collector.emit(text)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun discoverAnthropicModels(httpClient: OkHttpClient, key: String, out: MutableList<AiModelInfo>) {
        if (key.isBlank()) return
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/models")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            val bodyStr = resp.readBounded().trim()
            if (bodyStr.isBlank()) return
            val root = runCatching { JSONObject(bodyStr) }.getOrNull() ?: return
            val data = root.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val modelId = obj.optString("id", "")
                val displayName = obj.optString("display_name", modelId)
                if (modelId.isNotBlank()) {
                    out.add(
                        AiModelInfo(
                            id = modelId,
                            displayName = displayName,
                            provider = AiProviderType.ANTHROPIC,
                            description = "Anthropic Claude model",
                            contextWindow = "200k tokens",
                            supportsVision = true,
                            isReasoning = modelId.contains("5-5") || modelId.contains("opus") || modelId.contains("sonnet"),
                            isDefault = modelId.contains("sonnet-5-5")
                        )
                    )
                }
            }
        }
    }

    internal fun extractPollinationsText(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.startsWith("{")) {
            val extracted = runCatching {
                val json = JSONObject(trimmed)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val msg = choices.getJSONObject(0).optJSONObject("message")
                    val content = if (msg != null && !msg.isNull("content")) msg.optString("content", "") else ""
                    val reasoning = if (msg != null && !msg.isNull("reasoning_content")) msg.optString("reasoning_content", "") else ""
                    (content.ifBlank { reasoning }).trim()
                } else {
                    val directText = if (!json.isNull("text")) json.optString("text", "") else ""
                    val directContent = if (!json.isNull("content")) json.optString("content", "") else ""
                    directText.ifBlank { directContent }.trim()
                }
            }.getOrDefault("")
            if (extracted.isNotBlank()) return extracted
        }
        return trimmed
    }

    internal fun isPollinationsGatewayError(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.length < 2) return true
        return lower.startsWith("<!doctype") ||
            lower.startsWith("<html") ||
            lower.contains("502 bad gateway") ||
            lower.contains("504 gateway") ||
            lower.contains("cloudflare") ||
            lower.contains("error code: 5")
    }

    fun queryPollinationsFallback(
        httpClient: OkHttpClient,
        baseUrlOverrides: Map<AiProviderType, String>,
        prompt: String,
        systemPrompt: String,
        preferredModel: String = "openai",
        preserveReasoning: Boolean = false
    ): String {
        val url = baseUrlOverrides[AiProviderType.POLLINATIONS_FREE]?.let {
            if (it.endsWith("/")) it else "$it/"
        } ?: "https://text.pollinations.ai/"
        val fastFreeClient = httpClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .build()
        val models = listOf(preferredModel, "openai-large").distinct()
        for (m in models) {
            try {
                val jsonPayload = JSONObject().apply {
                    put("messages", JSONArray().apply {
                        if (systemPrompt.isNotBlank()) {
                            put(JSONObject().apply {
                                put("role", "system")
                                put("content", systemPrompt)
                            })
                        }
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                    put("model", m)
                }

                val req = Request.Builder()
                    .url(url)
                    .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", "AyraAi/3.1")
                    .build()

                val result = fastFreeClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val parsed = extractPollinationsText(resp.readBounded(truncateOnOverflow = true))
                        if (!isPollinationsGatewayError(parsed)) parsed else ""
                    } else ""
                }
                if (result.isNotBlank()) {
                    return if (preserveReasoning) result else ExternalAiProviders.sanitizeModelOutput(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("PollinationsTransport", "Free AI call failed ($m): ${e.message}")
            }
        }
        return ""
    }

    fun getOptimalModelForRoutingMode(
        mode: AiRoutingMode,
        configured: List<AiProviderType>,
        bestAvailable: AiProviderType,
        defaultModelForProvider: (AiProviderType) -> String
    ): Pair<AiProviderType, String> = when (mode) {
        AiRoutingMode.FAST -> when {
            configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, ExternalAiProviders.GROQ_LLAMA_MODEL)
            configured.contains(AiProviderType.CEREBRAS) -> Pair(AiProviderType.CEREBRAS, ExternalAiProviders.CEREBRAS_LLAMA_MODEL)
            configured.contains(AiProviderType.GEMINI) -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_FLASH_MODEL)
            configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, ModelCatalog.defaultModelFor(AiProviderType.NVIDIA_NIM)?.id ?: ExternalAiProviders.NVIDIA_LLAMA_MODEL)
            configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, ExternalAiProviders.SAMBANOVA_QWEN_MODEL)
            configured.contains(AiProviderType.OPENAI) -> Pair(AiProviderType.OPENAI, ExternalAiProviders.OPENAI_MINI_MODEL)
            configured.contains(AiProviderType.DEEPSEEK) -> Pair(AiProviderType.DEEPSEEK, ExternalAiProviders.DEEPSEEK_CHAT_MODEL)
            else -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_FLASH_MODEL)
        }
        AiRoutingMode.BEST_QUALITY -> when {
            configured.contains(AiProviderType.GEMINI) -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_PRO_MODEL)
            configured.contains(AiProviderType.ANTHROPIC) -> Pair(AiProviderType.ANTHROPIC, ExternalAiProviders.ANTHROPIC_OPUS_MODEL)
            configured.contains(AiProviderType.OPENAI) -> Pair(AiProviderType.OPENAI, ExternalAiProviders.OPENAI_DEFAULT_MODEL)
            configured.contains(AiProviderType.DEEPSEEK) -> Pair(AiProviderType.DEEPSEEK, ExternalAiProviders.DEEPSEEK_REASONER_MODEL)
            configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL)
            configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, ExternalAiProviders.NVIDIA_MUSE_MODEL)
            configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, ExternalAiProviders.GROQ_QWEN_MODEL)
            else -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_FLASH_MODEL)
        }
        AiRoutingMode.REASONING -> when {
            configured.contains(AiProviderType.DEEPSEEK) -> Pair(AiProviderType.DEEPSEEK, ExternalAiProviders.DEEPSEEK_REASONER_MODEL)
            configured.contains(AiProviderType.GEMINI) -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_PRO_MODEL)
            configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL)
            configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, ExternalAiProviders.GROQ_QWEN_MODEL)
            configured.contains(AiProviderType.CEREBRAS) -> Pair(AiProviderType.CEREBRAS, ExternalAiProviders.CEREBRAS_QWEN_MODEL)
            configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, ExternalAiProviders.NVIDIA_GLM_MODEL)
            configured.contains(AiProviderType.OPENAI) -> Pair(AiProviderType.OPENAI, ExternalAiProviders.OPENAI_REASONING_MODEL)
            configured.contains(AiProviderType.PERPLEXITY) -> Pair(AiProviderType.PERPLEXITY, ExternalAiProviders.PERPLEXITY_REASONING_MODEL)
            else -> Pair(AiProviderType.OPENROUTER, ExternalAiProviders.OPENROUTER_QWEN_FREE_MODEL)
        }
        AiRoutingMode.CODING -> when {
            configured.contains(AiProviderType.ANTHROPIC) -> Pair(AiProviderType.ANTHROPIC, ExternalAiProviders.ANTHROPIC_SONNET_MODEL)
            configured.contains(AiProviderType.MISTRAL) -> Pair(AiProviderType.MISTRAL, "codestral-latest")
            configured.contains(AiProviderType.DEEPSEEK) -> Pair(AiProviderType.DEEPSEEK, ExternalAiProviders.DEEPSEEK_CHAT_MODEL)
            configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL)
            configured.contains(AiProviderType.GEMINI) -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_PRO_MODEL)
            configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, ExternalAiProviders.NVIDIA_GLM_MODEL)
            configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, ExternalAiProviders.GROQ_QWEN_MODEL)
            configured.contains(AiProviderType.HUGGINGFACE) -> Pair(AiProviderType.HUGGINGFACE, ExternalAiProviders.HF_QWEN_MODEL)
            configured.contains(AiProviderType.OPENAI) -> Pair(AiProviderType.OPENAI, ExternalAiProviders.OPENAI_DEFAULT_MODEL)
            else -> Pair(AiProviderType.OPENROUTER, ExternalAiProviders.OPENROUTER_CODER_MODEL)
        }
        AiRoutingMode.PRIVACY_LOCAL -> when {
            configured.contains(AiProviderType.LOCAL_OLLAMA) -> Pair(AiProviderType.LOCAL_OLLAMA, "llama3.3")
            configured.contains(AiProviderType.DEEPSEEK) -> Pair(AiProviderType.DEEPSEEK, ExternalAiProviders.DEEPSEEK_CHAT_MODEL)
            else -> Pair(AiProviderType.OPENROUTER, ExternalAiProviders.OPENROUTER_DEFAULT_MODEL)
        }
        AiRoutingMode.COST_EFFICIENT -> when {
            configured.contains(AiProviderType.GEMINI) -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_FLASH_MODEL)
            configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, ExternalAiProviders.GROQ_QWEN_MODEL)
            configured.contains(AiProviderType.CEREBRAS) -> Pair(AiProviderType.CEREBRAS, ExternalAiProviders.CEREBRAS_LLAMA_MODEL)
            configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL)
            configured.contains(AiProviderType.POLLINATIONS_FREE) -> Pair(AiProviderType.POLLINATIONS_FREE, "openai")
            configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, ModelCatalog.defaultModelFor(AiProviderType.NVIDIA_NIM)?.id ?: ExternalAiProviders.NVIDIA_LLAMA_MODEL)
            else -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_FLASH_LITE_MODEL)
        }
        AiRoutingMode.OPEN_WEIGHTS -> when {
            configured.contains(AiProviderType.LOCAL_OLLAMA) -> Pair(AiProviderType.LOCAL_OLLAMA, ModelCatalog.defaultModelFor(AiProviderType.LOCAL_OLLAMA)?.id ?: "qwen2.5:14b")
            configured.contains(AiProviderType.OPENROUTER) -> Pair(AiProviderType.OPENROUTER, ExternalAiProviders.OPENROUTER_QWEN_FREE_MODEL)
            configured.contains(AiProviderType.HUGGINGFACE) -> Pair(AiProviderType.HUGGINGFACE, ExternalAiProviders.HF_QWEN_MODEL)
            configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, ExternalAiProviders.GROQ_QWEN_MODEL)
            configured.contains(AiProviderType.CEREBRAS) -> Pair(AiProviderType.CEREBRAS, ExternalAiProviders.CEREBRAS_QWEN_MODEL)
            configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, ExternalAiProviders.SAMBANOVA_QWEN_MODEL)
            else -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_FLASH_MODEL)
        }
        AiRoutingMode.AUTO -> Pair(bestAvailable, defaultModelForProvider(bestAvailable))
    }
}
