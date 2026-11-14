package com.ayra.ai.app.ai.higgsfield

import android.content.Context
import com.ayra.ai.app.ai.AiMediaPromptSynthesizer
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class HiggsfieldVideoPayload(
    val subject: String,
    val cameraMotion: String,
    val lensType: String,
    val lighting: String,
    val jsonPayload: String
)

/**
 * Open-Higgsfield-AI Motion Storyboard Engine.
 * Synthesizes cinematic camera trajectories, photometric lens characteristics,
 * lighting dynamics, and multi-keyframe motion storyboards.
 */
class OpenHiggsfieldEngine(private val context: Context? = null) {

    @JvmOverloads
    fun synthesizeVideoCraft(prompt: String, aspectRatio: String = "16:9"): HiggsfieldVideoPayload {
        val lower = prompt.lowercase()

        val cameraMotion = when {
            lower.contains("drone") || lower.contains("aerial") -> "Aerial FPV 360 Sweep"
            lower.contains("orbit") || lower.contains("circle") -> "Smooth 360 Orbit Dolly"
            lower.contains("zoom") || lower.contains("close") -> "Macro Push-In Focus Rack"
            lower.contains("fast") || lower.contains("action") -> "Dynamic Whip Pan & Tracking"
            else -> "Smooth Steadicam Tracking"
        }

        val lensType = when {
            lower.contains("wide") || lower.contains("landscape") -> "18mm Ultra-Wide Anamorphic f/2.0"
            lower.contains("portrait") || lower.contains("face") -> "85mm Prime Cine f/1.4"
            else -> "35mm Master Anamorphic T1.9"
        }

        val lighting = when {
            lower.contains("night") || lower.contains("neon") -> "Cyberpunk Volumetric Neon Glow"
            lower.contains("sunset") || lower.contains("golden") -> "Golden Hour Warm Backlight"
            else -> "Natural High-Key Diffused Daylight"
        }

        val effectiveAspect = if (aspectRatio == "9:16" || lower.contains("9:16") || lower.contains("reels") || lower.contains("shorts")) "9:16" else "16:9"
        val videoBase = AiMediaPromptSynthesizer.buildVideoPayload(
            prompt = prompt,
            context = context,
            aspectRatio = effectiveAspect
        )
        val cleanSubject = videoBase.optString("prompt", "")
            .ifBlank {
                prompt
                    .replace(Regex("(?i)(video|craft|generate|cinematic|camera|motion|4k|hd|movie|film|ভিডিও|বানাও)\\s*"), "")
                    .trim()
                    .ifBlank { "Futuristic cinematic sequence" }
            }

        val json = JSONObject().apply {
            put("title", videoBase.optString("title", cleanSubject.replaceFirstChar { it.uppercase() }))
            put("prompt", cleanSubject)
            put("previewUrl", videoBase.optString("previewUrl", ""))
            videoBase.optJSONArray("keyframes")?.let { put("keyframes", it) }
            put("duration", videoBase.optString("duration", "0:15"))
            put("subject", cleanSubject)
            put("cameraMotion", cameraMotion)
            put("lensType", lensType)
            put("lighting", lighting)
            put("aspectRatio", effectiveAspect)
            put("resolution", "Motion Storyboard Preview")
        }.toString()

        return HiggsfieldVideoPayload(
            subject = cleanSubject,
            cameraMotion = cameraMotion,
            lensType = lensType,
            lighting = lighting,
            jsonPayload = json
        )
    }

    /**
     * Uses an active AI cinematographer (Firebase Gemini / ProviderPool) to craft tailored
     * camera trajectory, optical lens choice, lighting, and shot description, cleanly falling
     * back to deterministic optical physics if offline or if parsing fails.
     */
    @JvmOverloads
    suspend fun synthesizeVideoCraftWithAi(
        prompt: String,
        aspectRatio: String = "16:9"
    ): HiggsfieldVideoPayload = withContext(Dispatchers.IO) {
        val baseline = synthesizeVideoCraft(prompt, aspectRatio)
        val providers = ExternalAiProviders(context)
        val hasOnlineAi = providers.allowExternalWikiGrounding &&
            (com.ayra.ai.app.ai.FirebaseAiEngine.isAvailable(context) || providers.hasAnyConfiguredProvider())
        if (!hasOnlineAi) return@withContext baseline

        val directorJson = withTimeoutOrNull(4500L) {
            try {
                val resp = ProviderPool(context, providers).executeSync(
                    ChatRequest(
                        prompt = """
                            Design bespoke cinematography metadata for this video prompt: "$prompt" ($aspectRatio).
                            Return ONLY raw JSON (no markdown) with exact keys:
                            {"cameraMotion":"...","lensType":"...","lighting":"...","colorGrade":"...","visualPrompt":"..."}
                        """.trimIndent(),
                        mode = ProviderPoolMode.FAST,
                        systemPromptOverride = "You are a Hollywood Director of Photography. Respond strictly with valid compact JSON only."
                    )
                )
                resp.text
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }.orEmpty()

        val startIdx = directorJson.indexOf('{')
        val endIdx = directorJson.lastIndexOf('}')
        if (startIdx < 0 || endIdx <= startIdx) return@withContext baseline

        val parsed = runCatching { JSONObject(directorJson.substring(startIdx, endIdx + 1)) }.getOrNull()
            ?: return@withContext baseline

        val cameraMotion = parsed.optString("cameraMotion", baseline.cameraMotion).ifBlank { baseline.cameraMotion }
        val lensType = parsed.optString("lensType", baseline.lensType).ifBlank { baseline.lensType }
        val lighting = parsed.optString("lighting", baseline.lighting).ifBlank { baseline.lighting }
        val colorGrade = parsed.optString("colorGrade", "").trim()
        val visualPrompt = parsed.optString("visualPrompt", "").trim()

        val effectiveAspect = if (aspectRatio == "9:16" || prompt.contains("9:16", true) || prompt.contains("reels", true)) "9:16" else "16:9"
        val upgradedBase = if (visualPrompt.isNotBlank()) {
            AiMediaPromptSynthesizer.buildVideoPayload(
                prompt = "$visualPrompt, $cameraMotion, $lensType, $lighting",
                context = context,
                aspectRatio = effectiveAspect
            )
        } else null

        val mergedJson = runCatching {
            JSONObject(baseline.jsonPayload).apply {
                put("cameraMotion", cameraMotion)
                put("lensType", lensType)
                put("lighting", lighting)
                if (colorGrade.isNotBlank()) put("colorGrade", colorGrade)
                if (upgradedBase != null) {
                    put("previewUrl", upgradedBase.optString("previewUrl", optString("previewUrl")))
                    upgradedBase.optJSONArray("keyframes")?.let { put("keyframes", it) }
                }
            }.toString()
        }.getOrDefault(baseline.jsonPayload)

        HiggsfieldVideoPayload(
            subject = baseline.subject,
            cameraMotion = cameraMotion,
            lensType = lensType,
            lighting = lighting,
            jsonPayload = mergedJson
        )
    }
}
