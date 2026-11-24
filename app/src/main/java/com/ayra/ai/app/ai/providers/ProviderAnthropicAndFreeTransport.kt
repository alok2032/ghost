package com.ayra.ai.app.ai.providers

import com.ayra.ai.app.security.BlockedByPolicyException
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

    private fun executeHttpsAware(
        httpClient: OkHttpClient,
        baseUrlOverride: String?,
        hasCustomClient: Boolean,
        request: Request
    ): okhttp3.Response {
        val url = request.url.toString()
        return if (baseUrlOverride == null && !hasCustomClient && url.startsWith("https://", ignoreCase = true)) {
            NetworkSecurityHelper.executePublicHttpsWithValidatedRedirects(
                client = httpClient,
                initialRequest = request,
                urlValidator = { NetworkSecurityHelper.validatePublicHttpsSyntaxOnly(it) }
            )
        } else {
            httpClient.newCall(request).execute()
        }
    }

    private fun throwIfAnthropicInlineError(json: JSONObject, model: String) {
        val errObj = json.optJSONObject("error")
        val type = json.optString("type", "")
        if (errObj == null && type != "error") return
        val errType = errObj?.optString("type", "").orEmpty().lowercase()
        val msg = errObj?.optString("message", "").orEmpty()
            .ifBlank { json.optString("message", "Anthropic API error") }
        when {
            errType.contains("authentication") || errType.contains("permission") ->
                throw ProviderError.Unauthorized(AiProviderType.ANTHROPIC)
            errType.contains("rate_limit") ->
                throw ProviderError.RateLimited(msg)
            errType.contains("not_found") ||
                (errType.contains("invalid_request") && msg.contains("model", ignoreCase = true)) ->
                throw ProviderError.ModelNotFound(AiProviderType.ANTHROPIC, model, msg, httpStatus = 404)
            else -> throw ProviderError.ServerError(500, msg)
        }
    }

    fun callAnthropic(
        httpClient: OkHttpClient,
        baseUrlOverrides: Map<AiProviderType, String>,
        turns: List<ChatTurn>,
        model: String,
        apiKey: String,
        hasCustomClient: Boolean = false
    ): String {
        val baseOverride = baseUrlOverrides[AiProviderType.ANTHROPIC]
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

        return executeHttpsAware(httpClient, baseOverride, hasCustomClient, request).use { response ->
            if (response.code == 429) throw ProviderError.RateLimited("Anthropic Claude rate limit reached.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(AiProviderType.ANTHROPIC)
            if (response.code == 404 || response.code == 410) {
                throw ProviderError.ModelNotFound(AiProviderType.ANTHROPIC, model, response.readBounded(truncateOnOverflow = true).ifBlank { response.message }, httpStatus = response.code)
            }
            if (!response.isSuccessful) {
                val errBody = response.readBounded(truncateOnOverflow = true).ifBlank { response.message }
                if (response.code == 400 && (
                        errBody.contains("not_found_error", ignoreCase = true) ||
                            (errBody.contains("model", ignoreCase = true) &&
                                (errBody.contains("invalid", ignoreCase = true) || errBody.contains("not found", ignoreCase = true) || errBody.contains("does not exist", ignoreCase = true)))
                        )
                ) {
                    throw ProviderError.ModelNotFound(AiProviderType.ANTHROPIC, model, errBody, httpStatus = 400)
                }
                throw ProviderError.ServerError(response.code, errBody)
            }

            val json = JSONObject(response.readBounded())
            throwIfAnthropicInlineError(json, model)
            val contentArr = json.optJSONArray("content")
            if (contentArr != null && contentArr.length() > 0) {
                val sb = StringBuilder()
                for (i in 0 until contentArr.length()) {
                    val block = contentArr.optJSONObject(i) ?: continue
                    val blockType = block.optString("type", "")
                    if (blockType == "thinking" || blockType == "redacted_thinking") continue
                    if (!block.isNull("text")) {
                        val txt = block.optString("text", "")
                        if (txt.isNotEmpty()) sb.append(txt)
                    }
                }
                return sb.toString()
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
        collector: FlowCollector<String>,
        hasCustomClient: Boolean = false
    ) {
        val baseOverride = baseUrlOverrides[AiProviderType.ANTHROPIC]
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

        executeHttpsAware(httpClient, baseOverride, hasCustomClient, request).use { response ->
            if (response.code == 429) throw ProviderError.RateLimited("Anthropic Claude rate limit reached.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(AiProviderType.ANTHROPIC)
            if (response.code == 404 || response.code == 410) {
                throw ProviderError.ModelNotFound(AiProviderType.ANTHROPIC, model, response.readBounded(truncateOnOverflow = true).ifBlank { response.message }, httpStatus = response.code)
            }
            if (!response.isSuccessful) {
                val err = response.readBounded(truncateOnOverflow = true).ifBlank { response.message }
                if (response.code == 400 && (
                        err.contains("not_found_error", ignoreCase = true) ||
                            (err.contains("model", ignoreCase = true) &&
                                (err.contains("invalid", ignoreCase = true) || err.contains("not found", ignoreCase = true) || err.contains("does not exist", ignoreCase = true)))
                        )
                ) {
                    throw ProviderError.ModelNotFound(AiProviderType.ANTHROPIC, model, err, httpStatus = 400)
                }
                throw ProviderError.ServerError(response.code, err)
            }
            val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return, StandardCharsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.startsWith("data:")) {
                    val data = l.substring(5).trim()
                    if (data.isBlank() || data == "[DONE]") continue
                    try {
                        val json = JSONObject(data)
                        throwIfAnthropicInlineError(json, model)
                        if (json.optString("type") == "content_block_delta") {
                            val delta = json.optJSONObject("delta")
                            val deltaType = delta?.optString("type", "") ?: ""
                            if (deltaType != "thinking_delta" && delta != null && !delta.isNull("text")) {
                                val text = delta.optString("text", "")
                                if (text.isNotEmpty()) collector.emit(text)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ProviderError) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("AnthropicTransport", "Malformed SSE JSON chunk skipped: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    fun discoverAnthropicModels(
        httpClient: OkHttpClient,
        key: String,
        out: MutableList<AiModelInfo>,
        hasCustomClient: Boolean = false
    ) {
        if (key.isBlank()) return
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/models")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .get()
            .build()
        executeHttpsAware(httpClient, null, hasCustomClient, req).use { resp ->
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
        preferredModel: String = ModelCatalog.POLLINATIONS_OPENAI_MODEL,
        preserveReasoning: Boolean = false,
        apiKey: String = ""
    ): String {
        val turns = buildList {
            if (systemPrompt.isNotBlank()) add(ChatTurn(role = "system", content = systemPrompt))
            add(ChatTurn(role = "user", content = prompt))
        }
        return queryPollinationsFallback(
            httpClient = httpClient,
            baseUrlOverrides = baseUrlOverrides,
            turns = turns,
            preferredModel = preferredModel,
            preserveReasoning = preserveReasoning,
            apiKey = apiKey
        )
    }

    fun queryPollinationsFallback(
        httpClient: OkHttpClient,
        baseUrlOverrides: Map<AiProviderType, String>,
        turns: List<ChatTurn>,
        preferredModel: String = ModelCatalog.POLLINATIONS_OPENAI_MODEL,
        preserveReasoning: Boolean = false,
        apiKey: String = ""
    ): String {
        val url = baseUrlOverrides[AiProviderType.POLLINATIONS_FREE]?.let { overrideUrl ->
            if (overrideUrl.endsWith("/")) overrideUrl else "$overrideUrl/"
        } ?: "https://gen.pollinations.ai/v1/chat/completions"
        val isTestOverride = baseUrlOverrides.containsKey(AiProviderType.POLLINATIONS_FREE)
        val fastFreeClient = httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .build()
        val candidateModel = preferredModel.ifBlank { ModelCatalog.POLLINATIONS_OPENAI_MODEL }
        val models = if (candidateModel == ModelCatalog.POLLINATIONS_OPENAI_MODEL) {
            listOf(ModelCatalog.POLLINATIONS_OPENAI_MODEL)
        } else {
            listOf(candidateModel, ModelCatalog.POLLINATIONS_OPENAI_MODEL)
        }
        val cleanKey = apiKey.trim().takeUnless { it.equals("free", ignoreCase = true) }.orEmpty()
        val filteredTurns = turns.filter { it.content.isNotBlank() }.map { t ->
            if (t.role == "system" && t.content.length > 2400) {
                val head = t.content.take(900)
                val tail = t.content.takeLast(1400)
                t.copy(content = "$head\n...\n$tail")
            } else t
        }
        for (m in models) {
            try {
                val jsonPayload = JSONObject().apply {
                    put("messages", JSONArray().apply {
                        filteredTurns.forEach { t ->
                            put(JSONObject().apply {
                                put("role", if (t.role in setOf("system", "assistant", "user")) t.role else "user")
                                put("content", t.content)
                            })
                        }
                    })
                    put("model", m)
                }

                val reqBuilder = Request.Builder()
                    .url(url)
                    .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", "AyraAi/3.1")
                if (cleanKey.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer $cleanKey")
                }
                val req = reqBuilder.build()

                val callResponse = if (!isTestOverride && url.startsWith("https://", ignoreCase = true)) {
                    NetworkSecurityHelper.executePublicHttpsWithValidatedRedirects(
                        client = fastFreeClient,
                        initialRequest = req,
                        urlValidator = { NetworkSecurityHelper.validatePublicHttpsSyntaxOnly(it) }
                    )
                } else {
                    fastFreeClient.newCall(req).execute()
                }
                val result = callResponse.use { resp ->
                    when (resp.code) {
                        401, 403 -> throw ProviderError.Unauthorized(AiProviderType.POLLINATIONS_FREE)
                        402 -> throw ProviderError.ServerError(
                            402,
                            "Pollinations balance/quota required (HTTP 402). Add a key from enter.pollinations.ai in Settings -> Keys."
                        )
                        else -> if (resp.isSuccessful) {
                            val parsed = extractPollinationsText(resp.readBounded(truncateOnOverflow = true))
                            if (!isPollinationsGatewayError(parsed)) parsed else ""
                        } else {
                            android.util.Log.w("PollinationsTransport", "Free AI HTTP ${resp.code} ($m)")
                            ""
                        }
                    }
                }
                if (result.isNotBlank()) {
                    return if (preserveReasoning) result else ExternalAiProviders.sanitizeModelOutput(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BlockedByPolicyException) {
                throw e
            } catch (e: SecurityException) {
                throw e
            } catch (e: ProviderError) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("PollinationsTransport", "Free AI call failed ($m): ${e.message}")
            }
        }
        return ""
    }

    private fun verifiedNimOrDefault(preferredCandidate: String): String {
        return if (ModelCatalog.isVerifiedOrConfigured(AiProviderType.NVIDIA_NIM, preferredCandidate)) {
            preferredCandidate
        } else {
            ModelCatalog.defaultModelFor(AiProviderType.NVIDIA_NIM)?.id ?: ExternalAiProviders.NVIDIA_LLAMA_MODEL
        }
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
            configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, verifiedNimOrDefault(ExternalAiProviders.NVIDIA_MUSE_MODEL))
            configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, ExternalAiProviders.GROQ_QWEN_MODEL)
            else -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_FLASH_MODEL)
        }
        AiRoutingMode.REASONING -> when {
            configured.contains(AiProviderType.DEEPSEEK) -> Pair(AiProviderType.DEEPSEEK, ExternalAiProviders.DEEPSEEK_REASONER_MODEL)
            configured.contains(AiProviderType.GEMINI) -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_PRO_MODEL)
            configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL)
            configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, ExternalAiProviders.GROQ_QWEN_MODEL)
            configured.contains(AiProviderType.CEREBRAS) -> Pair(AiProviderType.CEREBRAS, ExternalAiProviders.CEREBRAS_QWEN_MODEL)
            configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, verifiedNimOrDefault(ExternalAiProviders.NVIDIA_GLM_MODEL))
            configured.contains(AiProviderType.OPENAI) -> Pair(AiProviderType.OPENAI, ExternalAiProviders.OPENAI_REASONING_MODEL)
            configured.contains(AiProviderType.PERPLEXITY) -> Pair(AiProviderType.PERPLEXITY, ExternalAiProviders.PERPLEXITY_REASONING_MODEL)
            else -> Pair(AiProviderType.OPENROUTER, ExternalAiProviders.OPENROUTER_QWEN_FREE_MODEL)
        }
        AiRoutingMode.CODING -> when {
            configured.contains(AiProviderType.ANTHROPIC) -> Pair(AiProviderType.ANTHROPIC, ExternalAiProviders.ANTHROPIC_SONNET_MODEL)
            configured.contains(AiProviderType.MISTRAL) -> Pair(AiProviderType.MISTRAL, ModelCatalog.MISTRAL_CODESTRAL_MODEL)
            configured.contains(AiProviderType.DEEPSEEK) -> Pair(AiProviderType.DEEPSEEK, ExternalAiProviders.DEEPSEEK_CHAT_MODEL)
            configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL)
            configured.contains(AiProviderType.GEMINI) -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_PRO_MODEL)
            configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, verifiedNimOrDefault(ExternalAiProviders.NVIDIA_GLM_MODEL))
            configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, ExternalAiProviders.GROQ_QWEN_MODEL)
            configured.contains(AiProviderType.HUGGINGFACE) -> Pair(AiProviderType.HUGGINGFACE, ExternalAiProviders.HF_QWEN_MODEL)
            configured.contains(AiProviderType.OPENAI) -> Pair(AiProviderType.OPENAI, ExternalAiProviders.OPENAI_DEFAULT_MODEL)
            else -> Pair(AiProviderType.OPENROUTER, ExternalAiProviders.OPENROUTER_CODER_MODEL)
        }
        AiRoutingMode.PRIVACY_LOCAL -> when {
            configured.contains(AiProviderType.LOCAL_OLLAMA) -> Pair(AiProviderType.LOCAL_OLLAMA, ModelCatalog.OLLAMA_LLAMA_MODEL)
            configured.contains(AiProviderType.DEEPSEEK) -> Pair(AiProviderType.DEEPSEEK, ExternalAiProviders.DEEPSEEK_CHAT_MODEL)
            else -> Pair(AiProviderType.OPENROUTER, ExternalAiProviders.OPENROUTER_DEFAULT_MODEL)
        }
        AiRoutingMode.COST_EFFICIENT -> when {
            configured.contains(AiProviderType.GEMINI) -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_FLASH_MODEL)
            configured.contains(AiProviderType.GROQ) -> Pair(AiProviderType.GROQ, ExternalAiProviders.GROQ_QWEN_MODEL)
            configured.contains(AiProviderType.CEREBRAS) -> Pair(AiProviderType.CEREBRAS, ExternalAiProviders.CEREBRAS_LLAMA_MODEL)
            configured.contains(AiProviderType.SAMBANOVA) -> Pair(AiProviderType.SAMBANOVA, ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL)
            configured.contains(AiProviderType.POLLINATIONS_FREE) -> Pair(AiProviderType.POLLINATIONS_FREE, ModelCatalog.POLLINATIONS_OPENAI_MODEL)
            configured.contains(AiProviderType.NVIDIA_NIM) -> Pair(AiProviderType.NVIDIA_NIM, ModelCatalog.defaultModelFor(AiProviderType.NVIDIA_NIM)?.id ?: ExternalAiProviders.NVIDIA_LLAMA_MODEL)
            else -> Pair(AiProviderType.GEMINI, ExternalAiProviders.GEMINI_FLASH_LITE_MODEL)
        }
        AiRoutingMode.OPEN_WEIGHTS -> when {
            configured.contains(AiProviderType.LOCAL_OLLAMA) -> Pair(AiProviderType.LOCAL_OLLAMA, ModelCatalog.defaultModelFor(AiProviderType.LOCAL_OLLAMA)?.id ?: ModelCatalog.OLLAMA_QWEN_MODEL)
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
