package com.ayra.ai.app.ai.studios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class ComfyUiResult {
    data class ImageSuccess(val file: File, val promptId: String, val elapsedMs: Long) : ComfyUiResult()
    data class VideoSuccess(val file: File, val promptId: String, val elapsedMs: Long) : ComfyUiResult()
    data class Failure(val reason: String) : ComfyUiResult()
}

/**
 * Genuine ComfyUI REST Client supporting user-configured ComfyUI endpoints (LAN / Cloud GPU).
 * Submits real graph JSON via POST /prompt, polls GET /history/{prompt_id}, and downloads generated PNG/MP4 files via GET /view.
 */
class ComfyUiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    suspend fun isServerReachable(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val clean = baseUrl.trim().trimEnd('/')
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return@withContext false
        runCatching {
            val req = Request.Builder().url("$clean/system_stats").get().build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun buildTextToImageWorkflow(prompt: String, width: Int = 768, height: Int = 768, seed: Long = System.currentTimeMillis() % 1_000_000L): JSONObject {
        return JSONObject().apply {
            put("3", JSONObject().apply {
                put("class_type", "KSampler")
                put("inputs", JSONObject().apply {
                    put("seed", seed)
                    put("steps", 20)
                    put("cfg", 7.0)
                    put("sampler_name", "euler")
                    put("scheduler", "normal")
                    put("denoise", 1.0)
                    put("model", org.json.JSONArray(listOf("4", 0)))
                    put("positive", org.json.JSONArray(listOf("6", 0)))
                    put("negative", org.json.JSONArray(listOf("7", 0)))
                    put("latent_image", org.json.JSONArray(listOf("5", 0)))
                })
            })
            put("4", JSONObject().apply {
                put("class_type", "CheckpointLoaderSimple")
                put("inputs", JSONObject().put("ckpt_name", "flux1-dev-fp8.safetensors"))
            })
            put("5", JSONObject().apply {
                put("class_type", "EmptyLatentImage")
                put("inputs", JSONObject().apply {
                    put("width", width)
                    put("height", height)
                    put("batch_size", 1)
                })
            })
            put("6", JSONObject().apply {
                put("class_type", "CLIPTextEncode")
                put("inputs", JSONObject().apply {
                    put("text", prompt)
                    put("clip", org.json.JSONArray(listOf("4", 1)))
                })
            })
            put("7", JSONObject().apply {
                put("class_type", "CLIPTextEncode")
                put("inputs", JSONObject().apply {
                    put("text", "blurry, low quality, distorted, watermark")
                    put("clip", org.json.JSONArray(listOf("4", 1)))
                })
            })
            put("8", JSONObject().apply {
                put("class_type", "VAEDecode")
                put("inputs", JSONObject().apply {
                    put("samples", org.json.JSONArray(listOf("3", 0)))
                    put("vae", org.json.JSONArray(listOf("4", 2)))
                })
            })
            put("9", JSONObject().apply {
                put("class_type", "SaveImage")
                put("inputs", JSONObject().apply {
                    put("filename_prefix", "AyraComfy")
                    put("images", org.json.JSONArray(listOf("8", 0)))
                })
            })
        }
    }

    suspend fun generateImage(
        baseUrl: String,
        prompt: String,
        outputDir: File,
        width: Int = 768,
        height: Int = 768
    ): ComfyUiResult = withContext(Dispatchers.IO) {
        val cleanBase = baseUrl.trim().trimEnd('/')
        if (cleanBase.isBlank()) return@withContext ComfyUiResult.Failure("ComfyUI server URL is not configured.")
        val startMs = System.currentTimeMillis()

        try {
            val workflow = buildTextToImageWorkflow(prompt, width, height)
            val payload = JSONObject().put("prompt", workflow)
            val req = Request.Builder()
                .url("$cleanBase/prompt")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val promptId = httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext ComfyUiResult.Failure("ComfyUI /prompt returned HTTP ${resp.code}: $raw")
                }
                JSONObject(raw).optString("prompt_id", "")
            }

            if (promptId.isBlank()) {
                return@withContext ComfyUiResult.Failure("ComfyUI did not return a prompt_id.")
            }

            // Poll /history/{promptId}
            var filename = ""
            var subfolder = ""
            var folderType = "output"
            for (attempt in 1..25) {
                val histReq = Request.Builder().url("$cleanBase/history/$promptId").get().build()
                val found = httpClient.newCall(histReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val body = resp.body?.string().orEmpty()
                    val root = JSONObject(body)
                    val entry = root.optJSONObject(promptId) ?: return@use false
                    val outputs = entry.optJSONObject("outputs") ?: return@use false
                    val keys = outputs.keys()
                    while (keys.hasNext()) {
                        val node = outputs.optJSONObject(keys.next()) ?: continue
                        val imgs = node.optJSONArray("images")
                        if (imgs != null && imgs.length() > 0) {
                            val imgObj = imgs.getJSONObject(0)
                            filename = imgObj.optString("filename", "")
                            subfolder = imgObj.optString("subfolder", "")
                            folderType = imgObj.optString("type", "output")
                            if (filename.isNotBlank()) return@use true
                        }
                    }
                    false
                }
                if (found) break
                delay(1000L)
            }

            if (filename.isBlank()) {
                return@withContext ComfyUiResult.Failure("ComfyUI job $promptId timed out before producing an output image.")
            }

            val viewUrl = "$cleanBase/view?filename=${java.net.URLEncoder.encode(filename, "UTF-8")}&subfolder=${java.net.URLEncoder.encode(subfolder, "UTF-8")}&type=$folderType"
            val viewReq = Request.Builder().url(viewUrl).get().build()
            if (!outputDir.exists()) outputDir.mkdirs()
            val outFile = File(outputDir, "comfy_${System.currentTimeMillis()}_$filename")
            httpClient.newCall(viewReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ComfyUiResult.Failure("Failed to download output file $filename (HTTP ${resp.code})")
                }
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
            }

            ComfyUiResult.ImageSuccess(
                file = outFile,
                promptId = promptId,
                elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        } catch (e: Exception) {
            ComfyUiResult.Failure("ComfyUI request failed: ${e.message ?: "connection error"}")
        }
    }
}
