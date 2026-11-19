package com.ayra.ai.app.ai.providers

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
        visitedModels: Set<String> = emptySet()
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

        return httpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 400 && allowGrounding) {
                return callGemini(httpClient, baseUrlOverride, turns, actualModel, apiKey, false, visitedModels)
            }
            if (response.code == 404) {
                val nextModel = nextGeminiFallback(actualModel)
                if (nextModel != null && nextModel !in visitedModels) {
                    return callGemini(httpClient, baseUrlOverride, turns, nextModel, apiKey, withSearchGrounding, visitedModels + actualModel)
                }
            }
            if (response.code == 429) throw ProviderError.RateLimited("Gemini quota exceeded.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(AiProviderType.GEMINI)
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw ProviderError.ServerError(response.code, errBody.ifBlank { response.message })
            }

            val json = JSONObject(response.body?.string() ?: "")
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
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
        visitedModels: Set<String> = emptySet()
    ) {
        val actualModel = ExternalAiProviders.resolveGeminiModel(model)
        val url = buildGeminiUrl(baseUrlOverride, actualModel, apiKey, stream = true)
        val requestJson = buildRequestJson(turns, actualModel, allowGrounding = false)

        val reqBuilder = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("x-goog-api-key", apiKey)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 404) {
                val nextModel = nextGeminiFallback(actualModel)
                if (nextModel != null && nextModel !in visitedModels) {
                    return streamGemini(httpClient, baseUrlOverride, turns, nextModel, apiKey, collector, visitedModels + actualModel)
                }
            }
            if (response.code == 429) throw ProviderError.RateLimited("Gemini quota exceeded.")
            if (response.code == 401 || response.code == 403) throw ProviderError.Unauthorized(AiProviderType.GEMINI)
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
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
                            val candidates = json.optJSONArray("candidates")
                            if (candidates != null && candidates.length() > 0) {
                                val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                                if (parts != null && parts.length() > 0) {
                                    for (i in 0 until parts.length()) {
                                        val part = parts.optJSONObject(i)
                                        val token = if (part != null && !part.isNull("text")) part.optString("text", "") else ""
                                        if (token.isNotEmpty()) {
                                            emittedAnySse = true
                                            collector.emit(token)
                                        }
                                    }
                                }
                            }
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
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
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
                } catch (e: Exception) {
                    Log.w("ProviderGeminiTransport", "Non-SSE fallback body parse skipped: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    fun discoverModels(httpClient: OkHttpClient, key: String, out: MutableList<AiModelInfo>) {
        if (key.isBlank()) return
        val url = "https://generativelanguage.googleapis.com/v1beta/models"
        val req = Request.Builder().url(url).header("x-goog-api-key", key).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            val root = JSONObject(resp.body?.string() ?: "")
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
