package com.ayra.ai.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.AiUsageTracker
import com.ayra.ai.app.ai.providers.ModelCatalog
import com.ayra.ai.app.data.ChatMessage
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.ContentBlockedException
import com.google.firebase.ai.type.FirebaseAIException
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ImageConfig
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ImageSize
import com.google.firebase.ai.type.InvalidAPIKeyException
import com.google.firebase.ai.type.InvalidLocationException
import com.google.firebase.ai.type.PermissionMissingException
import com.google.firebase.ai.type.PromptBlockedException
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.RequestTimeoutException
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.ResponseStoppedException
import com.google.firebase.ai.type.ServiceDisabledException
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.UnsupportedUserLocationException
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream

data class FirebaseTextResult(
    val text: String,
    val error: Exception? = null
)

sealed class FirebaseImageResult {
    data class Success(
        val file: File,
        val caption: String,
        val modelUsed: String
    ) : FirebaseImageResult()

    data class Failure(
        val userMessage: String,
        val isQuotaOrBilling: Boolean = false
    ) : FirebaseImageResult()
}

enum class FirebaseSelfTestOutcome {
    PASS,
    AUTH_FAILED,
    API_DISABLED,
    APP_CHECK_FAILED,
    QUOTA_EXCEEDED,
    TIMEOUT,
    NETWORK_ERROR,
    OFFLINE,
    UNAVAILABLE
}

data class FirebaseSelfTestResult(
    val outcome: FirebaseSelfTestOutcome,
    val latencyMs: Long,
    val detail: String
)

/**
 * Unified Firebase AI Logic engine providing zero-key Gemini frontier models
 * (text, streaming, Google Search grounding, multimodal vision, audio transcription,
 * and Nano Banana 2.1 image generation & editing).
 */
class FirebaseAiEngine(private val context: Context?) {

    companion object {
        private const val TAG = "FirebaseAiEngine"

        const val MODEL_FLASH = ModelCatalog.GEMINI_FLASH_MODEL
        const val MODEL_FAST_LITE = ModelCatalog.GEMINI_FAST_LITE_MODEL
        const val MODEL_PRO_REASONING = ModelCatalog.GEMINI_PRO_MODEL
        const val MODEL_IMAGE_NANO_BANANA = ModelCatalog.GEMINI_IMAGE_NANO_BANANA_MODEL
        const val MODEL_TTS = ModelCatalog.GEMINI_TTS_MODEL

        private const val STREAM_SEARCH_TIMEOUT_MS = 18_000L
        private const val UNARY_SEARCH_TIMEOUT_MS = 15_000L
        private const val TEXT_TIMEOUT_MS = 22_000L
        private const val IMAGE_TIMEOUT_MS = 90_000L

        fun isAvailable(context: Context?): Boolean {
            if (context == null) return false
            if (com.ayra.ai.app.data.AyraPreferences.isLocalOnlyAiModeEnabled(context)) return false
            return runCatching {
                FirebaseApp.getApps(context.applicationContext).isNotEmpty()
            }.getOrDefault(false)
        }

        fun decodeBase64ToBitmap(base64: String?): Bitmap? {
            if (base64.isNullOrBlank()) return null
            return runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }

        internal fun isFatalNonRecoverableError(e: Throwable): Boolean {
            if (e is InvalidAPIKeyException ||
                e is PermissionMissingException ||
                e is ServiceDisabledException ||
                e is UnsupportedUserLocationException ||
                e is InvalidLocationException ||
                e is PromptBlockedException ||
                e is ContentBlockedException ||
                e is ResponseStoppedException
            ) {
                return true
            }
            if (e is java.net.UnknownHostException || e is java.net.ConnectException) return true
            val msg = (e.message ?: e.localizedMessage ?: "").lowercase()
            return msg.contains("403") ||
                msg.contains("401") ||
                msg.contains("appcheck") ||
                msg.contains("app check") ||
                msg.contains("attestation") ||
                msg.contains("api key not valid") ||
                msg.contains("billing") ||
                msg.contains("permission denied") ||
                msg.contains("service_disabled") ||
                msg.contains("firebaseai.googleapis.com") ||
                msg.contains("unable to resolve host")
        }

        internal fun classifyFirebaseException(e: Throwable): FirebaseSelfTestOutcome {
            return when {
                e is InvalidAPIKeyException -> FirebaseSelfTestOutcome.AUTH_FAILED
                e is ServiceDisabledException -> FirebaseSelfTestOutcome.API_DISABLED
                e is PermissionMissingException -> {
                    val msg = (e.message ?: "").lowercase()
                    if (msg.contains("appcheck") || msg.contains("app check") || msg.contains("attestation")) {
                        FirebaseSelfTestOutcome.APP_CHECK_FAILED
                    } else {
                        FirebaseSelfTestOutcome.AUTH_FAILED
                    }
                }
                e is QuotaExceededException -> FirebaseSelfTestOutcome.QUOTA_EXCEEDED
                e is RequestTimeoutException || e is TimeoutCancellationException || e is java.net.SocketTimeoutException ->
                    FirebaseSelfTestOutcome.TIMEOUT
                e is com.ayra.ai.app.security.BlockedByPolicyException -> FirebaseSelfTestOutcome.NETWORK_ERROR
                e is java.net.UnknownHostException || e is java.net.ConnectException -> FirebaseSelfTestOutcome.NETWORK_ERROR
                else -> {
                    val msg = (e.message ?: e.localizedMessage ?: "").lowercase()
                    when {
                        msg.contains("appcheck") || msg.contains("app check") || msg.contains("attestation") ->
                            FirebaseSelfTestOutcome.APP_CHECK_FAILED
                        msg.contains("service_disabled") || msg.contains("api has not been used") ->
                            FirebaseSelfTestOutcome.API_DISABLED
                        msg.contains("401") || msg.contains("api key") ->
                            FirebaseSelfTestOutcome.AUTH_FAILED
                        msg.contains("429") || msg.contains("quota") ->
                            FirebaseSelfTestOutcome.QUOTA_EXCEEDED
                        msg.contains("403") || msg.contains("permission") ->
                            FirebaseSelfTestOutcome.APP_CHECK_FAILED
                        msg.contains("timeout") || msg.contains("timed out") ->
                            FirebaseSelfTestOutcome.TIMEOUT
                        else -> FirebaseSelfTestOutcome.UNAVAILABLE
                    }
                }
            }
        }
    }

    suspend fun runStartupSelfTest(ctx: Context? = context): FirebaseSelfTestResult = withContext(Dispatchers.IO) {
        val targetCtx = ctx ?: context
        if (!isAvailable(targetCtx)) {
            return@withContext FirebaseSelfTestResult(
                outcome = FirebaseSelfTestOutcome.UNAVAILABLE,
                latencyMs = 0L,
                detail = "FirebaseApp not initialized or Local-Only Privacy Mode active."
            )
        }
        val startMs = System.currentTimeMillis()
        try {
            val text = withTimeout(10_000L) {
                aiClient.generativeModel(modelName = MODEL_FAST_LITE)
                    .generateContent("Reply with the single word: READY")
                    .text?.trim().orEmpty()
            }
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            if (text.isNotBlank()) {
                FirebaseSelfTestResult(
                    outcome = FirebaseSelfTestOutcome.PASS,
                    latencyMs = elapsed,
                    detail = "Live $MODEL_FAST_LITE response verified (${elapsed}ms)"
                )
            } else {
                FirebaseSelfTestResult(
                    outcome = FirebaseSelfTestOutcome.UNAVAILABLE,
                    latencyMs = elapsed,
                    detail = "Empty response from $MODEL_FAST_LITE"
                )
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                FirebaseSelfTestResult(
                    outcome = FirebaseSelfTestOutcome.TIMEOUT,
                    latencyMs = elapsed,
                    detail = "Firebase AI startup probe timed out after ${elapsed}ms"
                )
            } else {
                throw e
            }
        } catch (e: Exception) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val outcome = classifyFirebaseException(e)
            val safeDetail = com.ayra.ai.app.security.SecurityAuditLogger
                .redactSensitiveText(e.message ?: e::class.java.simpleName)
                .take(180)
            Log.w(TAG, "runStartupSelfTest [$outcome] failed: $safeDetail")
            FirebaseSelfTestResult(
                outcome = outcome,
                latencyMs = elapsed,
                detail = safeDetail
            )
        }
    }

    private val aiClient by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI())
    }

    fun selectModelForTask(
        modelTier: String,
        prompt: String,
        preferReasoning: Boolean = false
    ): String {
        val tierLower = modelTier.trim().lowercase()
        return when {
            tierLower in setOf("gemini_pro", "reasoning", "quality", "best_quality", "deep_analysis") -> MODEL_PRO_REASONING
            tierLower in setOf("fast", "flash_lite", "lite") -> MODEL_FAST_LITE
            else -> MODEL_FLASH
        }
    }

    internal fun modelFallbackChain(requestedModel: String): List<String> {
        val clean = requestedModel.trim().ifBlank { MODEL_FLASH }
        return when (clean) {
            MODEL_PRO_REASONING -> listOf(MODEL_PRO_REASONING, MODEL_FLASH, MODEL_FAST_LITE)
            MODEL_FLASH -> listOf(MODEL_FLASH, MODEL_FAST_LITE)
            MODEL_FAST_LITE -> listOf(MODEL_FAST_LITE, MODEL_FLASH)
            else -> listOf(clean, MODEL_FLASH, MODEL_FAST_LITE).distinct()
        }
    }

    suspend fun generateText(
        prompt: String,
        systemPrompt: String = "",
        history: List<ChatMessage> = emptyList(),
        imageBase64: String? = null,
        modelName: String = MODEL_FLASH,
        enableSearchGrounding: Boolean = false
    ): String = generateTextDetailed(
        prompt = prompt,
        systemPrompt = systemPrompt,
        history = history,
        imageBase64 = imageBase64,
        modelName = modelName,
        enableSearchGrounding = enableSearchGrounding
    ).text

    suspend fun generateTextDetailed(
        prompt: String,
        systemPrompt: String = "",
        history: List<ChatMessage> = emptyList(),
        imageBase64: String? = null,
        modelName: String = MODEL_FLASH,
        enableSearchGrounding: Boolean = false
    ): FirebaseTextResult = withContext(Dispatchers.IO) {
        if (!isAvailable(context)) {
            return@withContext FirebaseTextResult(
                text = "",
                error = IllegalStateException("Firebase AI Logic is not available in this session.")
            )
        }
        val bitmap = decodeBase64ToBitmap(imageBase64)
        val fullPrompt = buildHistoryAugmentedPrompt(prompt, history)
        val candidates = modelFallbackChain(modelName)
        var lastError: Exception? = null

        candidateLoop@ for (candidateModel in candidates) {
            val groundingAttempts = if (enableSearchGrounding) listOf(true, false) else listOf(false)
            for (useSearch in groundingAttempts) {
                val startMs = System.currentTimeMillis()
                val attemptTimeout = if (useSearch) UNARY_SEARCH_TIMEOUT_MS else TEXT_TIMEOUT_MS
                try {
                    val text = withTimeout(attemptTimeout) {
                        val tools = if (useSearch) listOf(Tool.googleSearch()) else null
                        val model = aiClient.generativeModel(
                            modelName = candidateModel,
                            systemInstruction = if (systemPrompt.isNotBlank()) content { text(systemPrompt) } else null,
                            tools = tools
                        )
                        val inputContent = content {
                            text(fullPrompt)
                            if (bitmap != null) {
                                image(bitmap)
                            }
                        }
                        model.generateContent(inputContent).text?.trim().orEmpty()
                    }
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    if (text.isNotBlank()) {
                        AiUsageTracker.recordCall(context, AiProviderType.GEMINI, true, elapsed)
                        return@withContext FirebaseTextResult(text = text, error = null)
                    }
                } catch (e: TimeoutCancellationException) {
                    lastError = e
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    AiUsageTracker.recordCall(context, AiProviderType.GEMINI, false, elapsed)
                    Log.w(TAG, "generateText timed out for $candidateModel (search=$useSearch): ${e.message}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    AiUsageTracker.recordCall(context, AiProviderType.GEMINI, false, elapsed)
                    Log.w(TAG, "generateText failed for $candidateModel (search=$useSearch): ${e.message}")
                    if (isFatalNonRecoverableError(e)) {
                        break@candidateLoop
                    }
                }
            }
        }
        FirebaseTextResult(text = "", error = lastError)
    }

    fun streamText(
        prompt: String,
        systemPrompt: String = "",
        history: List<ChatMessage> = emptyList(),
        imageBase64: String? = null,
        modelName: String = MODEL_FLASH,
        enableSearchGrounding: Boolean = false
    ): Flow<String> = flow {
        if (!isAvailable(context)) return@flow
        val bitmap = decodeBase64ToBitmap(imageBase64)
        val fullPrompt = buildHistoryAugmentedPrompt(prompt, history)
        val candidates = modelFallbackChain(modelName)
        var lastError: Exception? = null
        var hitFatalError = false
        var emittedAnyToken = false

        suspend fun collectStream(candidateModel: String, useSearch: Boolean): Boolean {
            val startMs = System.currentTimeMillis()
            val tools = if (useSearch) listOf(Tool.googleSearch()) else null
            val model = aiClient.generativeModel(
                modelName = candidateModel,
                systemInstruction = if (systemPrompt.isNotBlank()) content { text(systemPrompt) } else null,
                tools = tools
            )
            val inputContent = content {
                text(fullPrompt)
                if (bitmap != null) {
                    image(bitmap)
                }
            }
            var emittedThisAttempt = false
            val attemptTimeout = if (useSearch) STREAM_SEARCH_TIMEOUT_MS else TEXT_TIMEOUT_MS
            withTimeout(attemptTimeout) {
                model.generateContentStream(inputContent).collect { chunk ->
                    val txt = chunk.text
                    if (!txt.isNullOrEmpty()) {
                        emittedAnyToken = true
                        emittedThisAttempt = true
                        emit(txt)
                    }
                }
            }
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            if (emittedThisAttempt) {
                AiUsageTracker.recordCall(context, AiProviderType.GEMINI, true, elapsed)
            }
            return emittedThisAttempt
        }

        candidateLoop@ for (candidateModel in candidates) {
            val groundingAttempts = if (enableSearchGrounding) listOf(true, false) else listOf(false)
            for (useSearch in groundingAttempts) {
                val attemptStart = System.currentTimeMillis()
                try {
                    if (collectStream(candidateModel, useSearch)) {
                        return@flow
                    }
                } catch (e: TimeoutCancellationException) {
                    lastError = e
                    val elapsed = (System.currentTimeMillis() - attemptStart).coerceAtLeast(1L)
                    AiUsageTracker.recordCall(context, AiProviderType.GEMINI, false, elapsed)
                    Log.w(TAG, "streamText timed out for $candidateModel (search=$useSearch): ${e.message}")
                    if (emittedAnyToken) throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    val elapsed = (System.currentTimeMillis() - attemptStart).coerceAtLeast(1L)
                    AiUsageTracker.recordCall(context, AiProviderType.GEMINI, false, elapsed)
                    Log.e(TAG, "streamText failed for $candidateModel (search=$useSearch): ${e.message}", e)
                    if (emittedAnyToken) throw e
                    if (isFatalNonRecoverableError(e)) {
                        hitFatalError = true
                        break@candidateLoop
                    }
                }
            }
        }

        // Unary fallback on default free-tier Flash without search only when zero tokens have streamed
        if (!hitFatalError && !emittedAnyToken) {
            val unaryStart = System.currentTimeMillis()
            try {
                val fallbackModel = aiClient.generativeModel(
                    modelName = MODEL_FLASH,
                    systemInstruction = if (systemPrompt.isNotBlank()) content { text(systemPrompt) } else null
                )
                val unaryInput = content {
                    text(fullPrompt)
                    if (bitmap != null) {
                        image(bitmap)
                    }
                }
                val unaryText = withTimeout(TEXT_TIMEOUT_MS) {
                    fallbackModel.generateContent(unaryInput).text?.trim().orEmpty()
                }
                if (unaryText.isNotBlank()) {
                    val elapsed = (System.currentTimeMillis() - unaryStart).coerceAtLeast(1L)
                    AiUsageTracker.recordCall(context, AiProviderType.GEMINI, true, elapsed)
                    emit(unaryText)
                    return@flow
                }
            } catch (e: TimeoutCancellationException) {
                lastError = e
                val elapsed = (System.currentTimeMillis() - unaryStart).coerceAtLeast(1L)
                AiUsageTracker.recordCall(context, AiProviderType.GEMINI, false, elapsed)
                Log.w(TAG, "unary fallback timed out for $MODEL_FLASH: ${e.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val elapsed = (System.currentTimeMillis() - unaryStart).coerceAtLeast(1L)
                AiUsageTracker.recordCall(context, AiProviderType.GEMINI, false, elapsed)
                Log.e(TAG, "unary fallback failed for $MODEL_FLASH: ${e.message}", e)
            }
        }

        if (lastError != null && !emittedAnyToken) {
            throw lastError
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Generates a brand-new image or edits an attached photo using Gemini Nano Banana 2.1.
     * Saves genuine PNG output into app internal files/generated_images for immediate local rendering.
     */
    suspend fun generateOrEditImage(
        prompt: String,
        attachedImageBase64: String? = null,
        aspectRatio: String = "1:1"
    ): FirebaseImageResult = withContext(Dispatchers.IO) {
        val appContext = context?.applicationContext
            ?: return@withContext FirebaseImageResult.Failure("Context unavailable for Firebase AI image generation.")

        if (!isAvailable(appContext)) {
            return@withContext FirebaseImageResult.Failure("Firebase AI Logic is not initialized.")
        }

        val resolvedAspect = when (aspectRatio.trim()) {
            "16:9" -> AspectRatio.LANDSCAPE_16x9
            "9:16" -> AspectRatio.PORTRAIT_9x16
            else -> AspectRatio.SQUARE_1x1
        }

        val startMs = System.currentTimeMillis()
        try {
            withTimeout(IMAGE_TIMEOUT_MS) {
                val model = aiClient.generativeModel(
                    modelName = MODEL_IMAGE_NANO_BANANA,
                    generationConfig = generationConfig {
                        responseModalities = listOf(ResponseModality.TEXT, ResponseModality.IMAGE)
                        imageConfig = ImageConfig.builder()
                            .setAspectRatio(resolvedAspect)
                            .setImageSize(ImageSize.SIZE_1K)
                            .build()
                    }
                )

                val sourceBitmap = decodeBase64ToBitmap(attachedImageBase64)
                val inputContent = content {
                    text(
                        if (sourceBitmap != null) {
                            "Edit and transform the provided image according to this instruction cleanly and photorealistically: $prompt"
                        } else {
                            prompt
                        }
                    )
                    if (sourceBitmap != null) {
                        image(sourceBitmap)
                    }
                }

                val response = model.generateContent(inputContent)
                val imagePart = response.candidates.firstOrNull()
                    ?.content?.parts
                    ?.filterIsInstance<ImagePart>()
                    ?.firstOrNull()

                val bitmap: Bitmap? = imagePart?.image
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                if (bitmap != null) {
                    val outDir = File(appContext.filesDir, "generated_images").apply {
                        if (!exists()) mkdirs()
                    }
                    val outFile = File(outDir, "ayra_img_${System.currentTimeMillis()}.png")
                    FileOutputStream(outFile).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 95, fos)
                    }
                    AiUsageTracker.recordCall(appContext, AiProviderType.GEMINI, true, elapsed)
                    FirebaseImageResult.Success(
                        file = outFile,
                        caption = response.text?.trim().orEmpty(),
                        modelUsed = "Gemini Nano Banana 2.1 🎨"
                    )
                } else {
                    AiUsageTracker.recordCall(appContext, AiProviderType.GEMINI, false, elapsed)
                    val textReason = response.text?.trim().orEmpty()
                    FirebaseImageResult.Failure(
                        userMessage = textReason.ifBlank {
                            "Image model did not return an image frame for this prompt."
                        }
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            AiUsageTracker.recordCall(appContext, AiProviderType.GEMINI, false, elapsed)
            FirebaseImageResult.Failure("Image generation timed out. Please try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            AiUsageTracker.recordCall(appContext, AiProviderType.GEMINI, false, elapsed)
            val msg = e.localizedMessage ?: e.message ?: "Image generation failed"
            val isQuotaOrBilling = msg.contains("quota", ignoreCase = true) ||
                msg.contains("billing", ignoreCase = true) ||
                msg.contains("429") ||
                msg.contains("403") ||
                msg.contains("AppCheck", ignoreCase = true)
            Log.w(TAG, "generateOrEditImage error: $msg", e)
            FirebaseImageResult.Failure(
                userMessage = msg,
                isQuotaOrBilling = isQuotaOrBilling
            )
        }
    }

    /**
     * Transcribes or analyzes recorded audio via Gemini 3.8 Flash multimodal inlineData.
     */
    suspend fun transcribeAudioFile(
        audioFile: File,
        mimeType: String = "audio/mp4",
        instruction: String = "Transcribe this audio accurately in its original spoken language (Bengali, Banglish, Hindi, or English). Output ONLY the clean transcription text without extra commentary."
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isAvailable(context)) {
            return@withContext Result.failure(IllegalStateException("Firebase AI Logic is not initialized."))
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("Recorded audio clip is empty."))
        }
        if (audioFile.length() > 19L * 1024L * 1024L) {
            return@withContext Result.failure(IllegalArgumentException("Audio recording exceeds 19 MB limit."))
        }

        val startMs = System.currentTimeMillis()
        try {
            withTimeout(TEXT_TIMEOUT_MS) {
                val bytes = audioFile.readBytes()
                val model = aiClient.generativeModel(modelName = MODEL_FLASH)
                val input = content {
                    inlineData(bytes, mimeType)
                    text(instruction)
                }
                val response = model.generateContent(input)
                val transcript = response.text?.trim().orEmpty()
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                if (transcript.isBlank()) {
                    AiUsageTracker.recordCall(context, AiProviderType.GEMINI, false, elapsed)
                    Result.failure(IllegalStateException("No speech detected in audio clip."))
                } else {
                    AiUsageTracker.recordCall(context, AiProviderType.GEMINI, true, elapsed)
                    Result.success(transcript)
                }
            }
        } catch (e: TimeoutCancellationException) {
            AiUsageTracker.recordCall(context, AiProviderType.GEMINI, false, System.currentTimeMillis() - startMs)
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AiUsageTracker.recordCall(context, AiProviderType.GEMINI, false, System.currentTimeMillis() - startMs)
            Log.e(TAG, "transcribeAudioFile error", e)
            Result.failure(e)
        }
    }

    private fun buildHistoryAugmentedPrompt(prompt: String, history: List<ChatMessage>): String {
        if (history.isEmpty()) return prompt
        val recent = history.takeLast(8)
        return buildString {
            append("=== CONVERSATION CONTEXT ===\n")
            recent.forEach { msg ->
                val speaker = if (msg.role == "user") "User" else "Ayra AI"
                append("$speaker: ${msg.content.take(900)}\n")
            }
            append("=== CURRENT REQUEST ===\n")
            append(prompt)
        }
    }
}
