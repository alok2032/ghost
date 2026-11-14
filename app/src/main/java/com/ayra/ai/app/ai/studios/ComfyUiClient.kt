package com.ayra.ai.app.ai.studios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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
class ComfyUiClient @JvmOverloads constructor(
    private val httpClient: OkHttpClient = com.ayra.ai.app.security.NetworkSecurityHelper.localLanClient,
    private val context: android.content.Context? = null
) {

    private fun validateEndpoint(cleanUrl: String): com.ayra.ai.app.security.UrlValidationResult {
        return com.ayra.ai.app.security.NetworkSecurityHelper.validateLocalAiEndpointUrl(context, cleanUrl)
    }

    private fun resolveConfinedOutputFile(outputDir: File, prefix: String, remoteFilename: String): File {
        if (!outputDir.exists()) outputDir.mkdirs()
        val leaf = File(remoteFilename).name
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trimStart('.')
            .ifBlank { "output.bin" }
        val outFile = File(outputDir, "${prefix}_${System.currentTimeMillis()}_$leaf")
        val dirCanonical = outputDir.canonicalPath
        val outCanonical = outFile.canonicalPath
        if (outCanonical != dirCanonical && !outCanonical.startsWith("$dirCanonical${File.separator}")) {
            throw SecurityException("Path traversal blocked for ComfyUI filename '$remoteFilename'")
        }
        return outFile
    }

    suspend fun isServerReachable(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val clean = baseUrl.trim().trimEnd('/')
        if (!validateEndpoint(clean).allowed) return@withContext false
        runCatching {
            val req = Request.Builder().url("$clean/system_stats").get().build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun buildTextToImageWorkflow(
        prompt: String,
        width: Int = 768,
        height: Int = 768,
        seed: Long = System.currentTimeMillis() % 1_000_000L,
        negativePrompt: String = "blurry, low quality, distorted, watermark"
    ): JSONObject {
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
                    put("text", negativePrompt.ifBlank { "blurry, low quality, distorted, watermark" })
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

    fun buildImageToImageWorkflow(
        prompt: String,
        imageName: String,
        negativePrompt: String = "blurry, low quality, distorted",
        denoise: Double = 0.65,
        seed: Long = System.currentTimeMillis() % 1_000_000L
    ): JSONObject {
        return JSONObject().apply {
            put("1", JSONObject().apply {
                put("class_type", "LoadImage")
                put("inputs", JSONObject().put("image", imageName))
            })
            put("2", JSONObject().apply {
                put("class_type", "CheckpointLoaderSimple")
                put("inputs", JSONObject().put("ckpt_name", "flux1-dev-fp8.safetensors"))
            })
            put("3", JSONObject().apply {
                put("class_type", "VAEEncode")
                put("inputs", JSONObject().apply {
                    put("pixels", org.json.JSONArray(listOf("1", 0)))
                    put("vae", org.json.JSONArray(listOf("2", 2)))
                })
            })
            put("4", JSONObject().apply {
                put("class_type", "CLIPTextEncode")
                put("inputs", JSONObject().apply {
                    put("text", prompt)
                    put("clip", org.json.JSONArray(listOf("2", 1)))
                })
            })
            put("5", JSONObject().apply {
                put("class_type", "CLIPTextEncode")
                put("inputs", JSONObject().apply {
                    put("text", negativePrompt)
                    put("clip", org.json.JSONArray(listOf("2", 1)))
                })
            })
            put("6", JSONObject().apply {
                put("class_type", "KSampler")
                put("inputs", JSONObject().apply {
                    put("seed", seed)
                    put("steps", 20)
                    put("cfg", 7.0)
                    put("sampler_name", "euler")
                    put("scheduler", "normal")
                    put("denoise", denoise.coerceIn(0.1, 1.0))
                    put("model", org.json.JSONArray(listOf("2", 0)))
                    put("positive", org.json.JSONArray(listOf("4", 0)))
                    put("negative", org.json.JSONArray(listOf("5", 0)))
                    put("latent_image", org.json.JSONArray(listOf("3", 0)))
                })
            })
            put("7", JSONObject().apply {
                put("class_type", "VAEDecode")
                put("inputs", JSONObject().apply {
                    put("samples", org.json.JSONArray(listOf("6", 0)))
                    put("vae", org.json.JSONArray(listOf("2", 2)))
                })
            })
            put("8", JSONObject().apply {
                put("class_type", "SaveImage")
                put("inputs", JSONObject().apply {
                    put("filename_prefix", "AyraImg2Img")
                    put("images", org.json.JSONArray(listOf("7", 0)))
                })
            })
        }
    }

    fun buildVideoGenerationWorkflow(
        prompt: String,
        width: Int = 832,
        height: Int = 480,
        lengthFrames: Int = 33,
        seed: Long = System.currentTimeMillis() % 1_000_000L,
        negativePrompt: String = "static, blurry, distorted, watermark, low fps"
    ): JSONObject {
        return JSONObject().apply {
            put("1", JSONObject().apply {
                put("class_type", "UNETLoader")
                put("inputs", JSONObject().put("unet_name", "wan2.1_t2v_1.3B_bf16.safetensors").put("weight_dtype", "default"))
            })
            put("2", JSONObject().apply {
                put("class_type", "CLIPLoader")
                put("inputs", JSONObject().put("clip_name", "umt5_xxl_fp8_e4m3fn_scaled.safetensors").put("type", "wan"))
            })
            put("3", JSONObject().apply {
                put("class_type", "VAELoader")
                put("inputs", JSONObject().put("vae_name", "wan_2.1_vae.safetensors"))
            })
            put("4", JSONObject().apply {
                put("class_type", "CLIPTextEncode")
                put("inputs", JSONObject().apply {
                    put("text", prompt)
                    put("clip", org.json.JSONArray(listOf("2", 0)))
                })
            })
            put("5", JSONObject().apply {
                put("class_type", "CLIPTextEncode")
                put("inputs", JSONObject().apply {
                    put("text", negativePrompt)
                    put("clip", org.json.JSONArray(listOf("2", 0)))
                })
            })
            put("6", JSONObject().apply {
                put("class_type", "EmptyHunyuanLatentVideo")
                put("inputs", JSONObject().apply {
                    put("width", width)
                    put("height", height)
                    put("length", lengthFrames.coerceIn(17, 81))
                    put("batch_size", 1)
                })
            })
            put("7", JSONObject().apply {
                put("class_type", "KSampler")
                put("inputs", JSONObject().apply {
                    put("seed", seed)
                    put("steps", 24)
                    put("cfg", 6.0)
                    put("sampler_name", "uni_pc")
                    put("scheduler", "simple")
                    put("denoise", 1.0)
                    put("model", org.json.JSONArray(listOf("1", 0)))
                    put("positive", org.json.JSONArray(listOf("4", 0)))
                    put("negative", org.json.JSONArray(listOf("5", 0)))
                    put("latent_image", org.json.JSONArray(listOf("6", 0)))
                })
            })
            put("8", JSONObject().apply {
                put("class_type", "VAEDecode")
                put("inputs", JSONObject().apply {
                    put("samples", org.json.JSONArray(listOf("7", 0)))
                    put("vae", org.json.JSONArray(listOf("3", 0)))
                })
            })
            put("9", JSONObject().apply {
                put("class_type", "VHS_VideoCombine")
                put("inputs", JSONObject().apply {
                    put("images", org.json.JSONArray(listOf("8", 0)))
                    put("frame_rate", 16)
                    put("format", "video/h264-mp4")
                    put("filename_prefix", "AyraVideo")
                    put("save_output", true)
                })
            })
        }
    }

    suspend fun generateImage(
        baseUrl: String,
        prompt: String,
        outputDir: File,
        width: Int = 768,
        height: Int = 768,
        negativePrompt: String = "blurry, low quality, distorted, watermark"
    ): ComfyUiResult = withContext(Dispatchers.IO) {
        val cleanBase = baseUrl.trim().trimEnd('/')
        if (cleanBase.isBlank()) return@withContext ComfyUiResult.Failure("ComfyUI server URL is not configured.")
        val urlCheck = validateEndpoint(cleanBase)
        if (!urlCheck.allowed) return@withContext ComfyUiResult.Failure(urlCheck.reason ?: "Unsafe ComfyUI URL.")
        val startMs = System.currentTimeMillis()

        try {
            val workflow = buildTextToImageWorkflow(prompt, width, height, negativePrompt = negativePrompt)
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
            val outFile = resolveConfinedOutputFile(outputDir, "comfy", filename)
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ComfyUiResult.Failure("ComfyUI request failed: ${e.message ?: "connection error"}")
        }
    }

    suspend fun generateVideo(
        baseUrl: String,
        prompt: String,
        outputDir: File,
        width: Int = 832,
        height: Int = 480,
        lengthFrames: Int = 33,
        negativePrompt: String = "static, blurry, distorted, watermark, low fps"
    ): ComfyUiResult = withContext(Dispatchers.IO) {
        val cleanBase = baseUrl.trim().trimEnd('/')
        if (cleanBase.isBlank()) return@withContext ComfyUiResult.Failure("ComfyUI server URL is not configured.")
        val urlCheck = validateEndpoint(cleanBase)
        if (!urlCheck.allowed) return@withContext ComfyUiResult.Failure(urlCheck.reason ?: "Unsafe ComfyUI URL.")
        val startMs = System.currentTimeMillis()

        try {
            val workflow = buildVideoGenerationWorkflow(prompt, width, height, lengthFrames, negativePrompt = negativePrompt)
            val payload = JSONObject().put("prompt", workflow)
            val req = Request.Builder()
                .url("$cleanBase/prompt")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val promptId = httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext ComfyUiResult.Failure("ComfyUI video /prompt returned HTTP ${resp.code}: $raw")
                }
                JSONObject(raw).optString("prompt_id", "")
            }

            if (promptId.isBlank()) {
                return@withContext ComfyUiResult.Failure("ComfyUI did not return a prompt_id for video generation.")
            }

            var filename = ""
            var subfolder = ""
            var folderType = "output"
            for (attempt in 1..45) {
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
                        val arr = node.optJSONArray("gifs")
                            ?: node.optJSONArray("videos")
                            ?: node.optJSONArray("images")
                        if (arr != null && arr.length() > 0) {
                            val obj = arr.getJSONObject(0)
                            filename = obj.optString("filename", "")
                            subfolder = obj.optString("subfolder", "")
                            folderType = obj.optString("type", "output")
                            if (filename.isNotBlank()) return@use true
                        }
                    }
                    false
                }
                if (found) break
                delay(1500L)
            }

            if (filename.isBlank()) {
                return@withContext ComfyUiResult.Failure("ComfyUI video job $promptId timed out before producing an output clip.")
            }

            val viewUrl = "$cleanBase/view?filename=${java.net.URLEncoder.encode(filename, "UTF-8")}&subfolder=${java.net.URLEncoder.encode(subfolder, "UTF-8")}&type=$folderType"
            val viewReq = Request.Builder().url(viewUrl).get().build()
            val outFile = resolveConfinedOutputFile(outputDir, "comfy_vid", filename)
            httpClient.newCall(viewReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ComfyUiResult.Failure("Failed to download video $filename (HTTP ${resp.code})")
                }
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
            }

            ComfyUiResult.VideoSuccess(
                file = outFile,
                promptId = promptId,
                elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ComfyUiResult.Failure("ComfyUI video request failed: ${e.message ?: "connection error"}")
        }
    }

    suspend fun uploadImage(
        baseUrl: String,
        imageBytes: ByteArray,
        filename: String = "ayra_ref_${System.currentTimeMillis()}.png"
    ): String? = withContext(Dispatchers.IO) {
        val cleanBase = baseUrl.trim().trimEnd('/')
        if (cleanBase.isBlank() || imageBytes.isEmpty()) return@withContext null
        if (!validateEndpoint(cleanBase).allowed) return@withContext null

        runCatching {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    filename,
                    imageBytes.toRequestBody("image/png".toMediaType())
                )
                .addFormDataPart("overwrite", "true")
                .build()

            val req = Request.Builder()
                .url("$cleanBase/upload/image")
                .post(multipart)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                json.optString("name", filename).ifBlank { filename }
            }
        }.getOrNull()
    }

    suspend fun generateImageToImage(
        baseUrl: String,
        prompt: String,
        referenceImageBytes: ByteArray,
        outputDir: File,
        negativePrompt: String = "blurry, low quality, distorted, watermark",
        denoise: Double = 0.65
    ): ComfyUiResult = withContext(Dispatchers.IO) {
        val cleanBase = baseUrl.trim().trimEnd('/')
        if (cleanBase.isBlank()) return@withContext ComfyUiResult.Failure("ComfyUI server URL is not configured.")
        val urlCheck = validateEndpoint(cleanBase)
        if (!urlCheck.allowed) return@withContext ComfyUiResult.Failure(urlCheck.reason ?: "Unsafe ComfyUI URL.")
        val startMs = System.currentTimeMillis()

        val uploadedName = uploadImage(cleanBase, referenceImageBytes)
            ?: return@withContext ComfyUiResult.Failure("Failed to upload reference image to ComfyUI /upload/image.")

        try {
            val workflow = buildImageToImageWorkflow(
                prompt = prompt,
                imageName = uploadedName,
                negativePrompt = negativePrompt.ifBlank { "blurry, low quality, distorted, watermark" },
                denoise = denoise
            )
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
                return@withContext ComfyUiResult.Failure("ComfyUI did not return a prompt_id for img2img.")
            }

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
                return@withContext ComfyUiResult.Failure("ComfyUI img2img job $promptId timed out before producing an output image.")
            }

            val viewUrl = "$cleanBase/view?filename=${java.net.URLEncoder.encode(filename, "UTF-8")}&subfolder=${java.net.URLEncoder.encode(subfolder, "UTF-8")}&type=$folderType"
            val viewReq = Request.Builder().url(viewUrl).get().build()
            val outFile = resolveConfinedOutputFile(outputDir, "comfy_i2i", filename)
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ComfyUiResult.Failure("ComfyUI img2img request failed: ${e.message ?: "connection error"}")
        }
    }
}
