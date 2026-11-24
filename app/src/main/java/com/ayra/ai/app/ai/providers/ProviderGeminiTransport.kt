package com.ayra.ai.app.ai.providers

import com.ayra.ai.app.security.NetworkSecurityHelper
import com.ayra.ai.app.security.NetworkSecurityHelper.readBounded
import kotlinx.coroutines.flow.FlowCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Gemini REST & SSE Streaming transport helpers extracted from [ExternalAiProviders].
 */
internal object ProviderGeminiTransport {

    private fun nextGeminiFallback(currentModel: String): String? {
        return when (currentModel) {
            ExternalAiProviders.GEMINI_FLASH_MODEL -> ExternalAiProviders.GEMINI_FLASH_LITE_MODEL
            ExternalAiProviders.GEMINI_FLASH_LITE_MODEL -> null
            else -> ExternalAiProviders.GEMINI_FLASH_MODEL
        }
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

    private fun throwIfGeminiInlineErrorOrBlocked(json: JSONObject, model: String) {
        val errObj = json.optJSONObject("error")
        if (errObj != null) {
            val code = errObj.optInt("code", 500)
            val status = errObj.optString("status", "")
            val msg = errObj.optString("message", status.ifBlank { "Gemini API error" })
            when {
                code == 401 || code == 403 || status == "UNAUTHENTICATED" || status == "PERMISSION_DENIED" ->
                    throw ProviderError.Unauthorized(AiProviderType.GEMINI)
                code == 429 || status == "RESOURCE_EXHAUSTED" ->
                    throw ProviderError.RateLimited(msg)
                code == 404 || code == 410 || status == "NOT_FOUND" ->
                    throw ProviderError.ModelNotFound(AiProviderType.GEMINI, model, msg, httpStatus = code)
                else ->
                    throw ProviderError.ServerError(code, msg)
            }
        }
        val promptFeedback = json.optJSONObject("promptFeedback")
        val blockReason = promptFeedback?.optString("blockReason", "").orEmpty().trim()
        if (blockReason.isNotBlank()) {
            throw ProviderError.ContentBlocked(blockReason)
        }
    }

    fun buildGeminiUrl(
        baseUrlOverride: String?,
        model: String,
        @Suppress("UNUSED_PARAMETER") apiKey: String = "",
        stream: Boolean
    ): String {
        val base = baseUrlOverride?.let {
            if (it.endsWith("/")) it else "$it/"
        } ?: "https://generativelanguage.googleapis.com/v1beta/"
        val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        return "${base}models/$model:$action"
    }

    private fun buildRequestJson(
        turns: List<ChatTurn>,
        actualModel: String,
        allowGrounding: Boolean
    ): JSONObject = JSONObject().apply {
        val systemTurn = turns.firstOrNull { it.role == "system" }
        if (systemTurn != null && systemTurn.content.isNotBlank() && !actualModel.startsWith("gemma")) {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemTurn.content)))
            })
        }

        val contentsArray = JSONArray()
        val conversationTurns = turns.filter { it.role != "system" }
        val prefixSystem = if (actualModel.startsWith("gemma") && systemTurn != null && systemTurn.content.isNotBlank()) {
            "${systemTurn.content}\n\n"
        } else ""

        conversationTurns.forEachIndexed { index, turn ->
            val geminiRole = if (turn.role == "assistant") "model" else "user"
            val partsArray = JSONArray()
            val textContent = if (index == 0 && geminiRole == "user" && prefixSystem.isNotEmpty()) {
                "$prefixSystem${turn.content}"
            } else turn.content
            partsArray.put(JSONObject().put("text", textContent))

            if (!turn.imageBase64.isNullOrBlank()) {
                partsArray.put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", turn.imageBase64)
                    })
                })
            }

            contentsArray.put(JSONObject().apply {
                put("role", geminiRole)
                put("parts", partsArray)
            })
        }

        put("contents", contentsArray)
        if (allowGrounding) {
            put("tools", JSONArray().put(JSONObject().apply {
                put("googleSearch", JSONObject())
            }))
        }
    }

    fun callGemini(
        httpClient: OkHttpClient,
        baseUrlOverride: String?,
        turns: List<ChatTurn>,
        model: String,
        apiKey: String,
        withSearchGrounding: Boolean,
        visitedModels: Set<String> = emptySet(),
        hasCustomClient: Boolean = false
    ): String {
        val actualModel = ExternalAiProviders.resolveGeminiModel(model)
        val allowGrounding = withSearchGrounding && !actualModel.startsWith("gemma")
        val url = buildGeminiUrl(baseUrlOverride, actualModel, apiKey, stream = false)
        val requestJson = buildRequestJson(turns, actualModel, allowGrounding)

        val reqBuilder = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("x-goog-api-key", apiKey)
        }

        return executeHttpsAware(httpClient, baseUrlOverride, hasCustomClient, reqBuilder.build()).use { response ->
            if (response.code == 400 && allowGrounding) {
                return callGemini(httpClient, baseUrlOverride, turns, actualModel, apiKey, false, visitedModels, hasCustomClient)
            }
            if (response.code == 404 || response.code == 410) {
                if (response.code == 410) {
                    ModelCatalog.markModelRetired(AiProviderType.GEMINI, actualModel, "HTTP 410")
                }
                val nextModel = nextGeminiFallback(actualModel)
                if (nextModel != null && nextModel !in visitedModels) {
                    return callGemini(httpClient, baseUrlOverride, turns, nextModel, apiKey, withSearchGrounding, visitedModels + actualModel, hasCustomClient)
                }
                throw ProviderError.ModelNotFound(
                    AiProviderType.GEMINI,
                    actualModel,
                    response.readBounded(truncateOnOverflow = true).ifBlank { response.message },
                    httpStatus = response.code
                )
            }
            if (response.code == 429) throw ProviderError.RateLimited("Gemini quota exceeded.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(AiProviderType.GEMINI)
            if (!response.isSuccessful) {
                val errBody = response.readBounded(truncateOnOverflow = true)
                throw ProviderError.ServerError(response.code, errBody.ifBlank { response.message })
            }

            val json = JSONObject(response.readBounded())
            throwIfGeminiInlineErrorOrBlocked(json, actualModel)
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val cand0 = candidates.getJSONObject(0)
                val parts = cand0.optJSONObject("content")?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        val partObj = parts.optJSONObject(i) ?: continue
                        if (!partObj.isNull("text")) {
                            sb.append(partObj.optString("text", ""))
                        }
                    }
                    return ExternalAiProviders.sanitizeModelOutput(sb.toString())
                }
                val finishReason = cand0.optString("finishReason", "")
                if (finishReason in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")) {
                    throw ProviderError.ContentBlocked(finishReason)
                }
            }
            ""
        }
    }

    suspend fun streamGemini(
        httpClient: OkHttpClient,
        baseUrlOverride: String?,
        turns: List<ChatTurn>,
        model: String,
        apiKey: String,
        collector: FlowCollector<String>,
        visitedModels: Set<String> = emptySet(),
        withSearchGrounding: Boolean = true,
        hasCustomClient: Boolean = false
    ) {
        val actualModel = ExternalAiProviders.resolveGeminiModel(model)
        val allowGrounding = withSearchGrounding && !actualModel.startsWith("gemma")
        val url = buildGeminiUrl(baseUrlOverride, actualModel, apiKey, stream = true)
        val requestJson = buildRequestJson(turns, actualModel, allowGrounding = allowGrounding)

        val reqBuilder = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("x-goog-api-key", apiKey)
        }

        executeHttpsAware(httpClient, baseUrlOverride, hasCustomClient, reqBuilder.build()).use { response ->
            if (response.code == 400 && allowGrounding) {
                return streamGemini(
                    httpClient = httpClient,
                    baseUrlOverride = baseUrlOverride,
                    turns = turns,
                    model = actualModel,
                    apiKey = apiKey,
                    collector = collector,
                    visitedModels = visitedModels,
                    withSearchGrounding = false,
                    hasCustomClient = hasCustomClient
                )
            }
            if (response.code == 404 || response.code == 410) {
                if (response.code == 410) {
                    ModelCatalog.markModelRetired(AiProviderType.GEMINI, actualModel, "HTTP 410")
                }
                val nextModel = nextGeminiFallback(actualModel)
                if (nextModel != null && nextModel !in visitedModels) {
                    return streamGemini(httpClient, baseUrlOverride, turns, nextModel, apiKey, collector, visitedModels + actualModel, withSearchGrounding, hasCustomClient)
                }
                throw ProviderError.ModelNotFound(
                    AiProviderType.GEMINI,
                    actualModel,
                    response.readBounded(truncateOnOverflow = true).ifBlank { response.message },
                    httpStatus = response.code
                )
            }
            if (response.code == 429) throw ProviderError.RateLimited("Gemini quota exceeded.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(AiProviderType.GEMINI)
            if (!response.isSuccessful) {
                val errBody = response.readBounded(truncateOnOverflow = true)
                throw ProviderError.ServerError(response.code, errBody.ifBlank { response.message })
            }

            val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return, StandardCharsets.UTF_8))
            var line: String?
            var emittedAnySse = false
            val rawFallbackBuffer = StringBuilder()
            while (reader.readLine().also { line = it } != null) {
                val rawLine = line ?: continue
                val l = rawLine.trim()
                if (l.startsWith("data:")) {
                    val jsonStr = l.substring(5).trim()
                    if (jsonStr.isNotBlank() && jsonStr != "[DONE]") {
                        try {
                            val json = JSONObject(jsonStr)
                            throwIfGeminiInlineErrorOrBlocked(json, actualModel)
                            val candidates = json.optJSONArray("candidates")
                            if (candidates != null && candidates.length() > 0) {
                                val cand0 = candidates.getJSONObject(0)
                                val parts = cand0.optJSONObject("content")?.optJSONArray("parts")
                                if (parts != null && parts.length() > 0) {
                                    for (i in 0 until parts.length()) {
                                        val part = parts.optJSONObject(i)
                                        val token = if (part != null && !part.isNull("text")) part.optString("text", "") else ""
                                        if (token.isNotEmpty()) {
                                            emittedAnySse = true
                                            collector.emit(token)
                                        }
                                    }
                                } else if (!emittedAnySse) {
                                    val finishReason = cand0.optString("finishReason", "")
                                    if (finishReason in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")) {
                                        throw ProviderError.ContentBlocked(finishReason)
                                    }
                                }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: ProviderError) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("ProviderGeminiTransport", "Malformed SSE JSON chunk skipped: ${e.javaClass.simpleName}")
                        }
                    }
                } else if (!emittedAnySse && l.isNotEmpty()) {
                    rawFallbackBuffer.append(rawLine).append('\n')
                }
            }

            if (!emittedAnySse && rawFallbackBuffer.isNotBlank()) {
                try {
                    val json = JSONObject(rawFallbackBuffer.toString().trim())
                    throwIfGeminiInlineErrorOrBlocked(json, actualModel)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val cand0 = candidates.getJSONObject(0)
                        val parts = cand0.optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val sb = StringBuilder()
                            for (i in 0 until parts.length()) {
                                val partObj = parts.optJSONObject(i) ?: continue
                                if (!partObj.isNull("text")) {
                                    sb.append(partObj.optString("text", ""))
                                }
                            }
                            val cleaned = ExternalAiProviders.sanitizeModelOutput(sb.toString())
                            if (cleaned.isNotEmpty()) collector.emit(cleaned)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: ProviderError) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ProviderGeminiTransport", "Non-SSE fallback body parse skipped: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    fun discoverModels(
        httpClient: OkHttpClient,
        key: String,
        out: MutableList<AiModelInfo>,
        hasCustomClient: Boolean = false
    ) {
        if (key.isBlank()) return
        val url = "https://generativelanguage.googleapis.com/v1beta/models"
        val req = Request.Builder().url(url).header("x-goog-api-key", key).get().build()
        executeHttpsAware(httpClient, null, hasCustomClient, req).use { resp ->
            if (!resp.isSuccessful) return
            val root = JSONObject(resp.readBounded())
            val arr = root.optJSONArray("models") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "").removePrefix("models/")
                val displayName = obj.optString("displayName", name)
                val desc = obj.optString("description", "")
                val inputLimit = obj.optInt("inputTokenLimit", 0)
                if (name.contains("gemini") || name.contains("gemma")) {
                    out.add(
                        AiModelInfo(
                            id = name,
                            displayName = displayName,
                            provider = AiProviderType.GEMINI,
                            description = desc.ifBlank { "Google official model" },
                            contextWindow = if (inputLimit > 0) "${inputLimit / 1000}k tokens" else "1M tokens",
                            supportsVision = true,
                            isReasoning = name.contains("pro") || name.contains("gemma"),
                            isDefault = name == ExternalAiProviders.GEMINI_FLASH_MODEL
                        )
                    )
                }
            }
        }
    }
}
