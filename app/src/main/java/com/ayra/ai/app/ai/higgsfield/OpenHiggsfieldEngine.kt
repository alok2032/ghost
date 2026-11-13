package com.ayra.ai.app.ai.higgsfield

import android.content.Context
import com.ayra.ai.app.ai.AiMediaPromptSynthesizer
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
}
