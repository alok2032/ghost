package com.ayra.ai.app.ai

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import com.ayra.ai.app.ai.higgsfield.OpenHiggsfieldEngine
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.ai.studios.ComfyUiClient
import com.ayra.ai.app.ai.studios.ComfyUiResult
import com.ayra.ai.app.data.AyraPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Multimodal Generative Craft Studio executors (Image, Video Storyboard/MP4, Song/Lyrics, 3D Wavefront OBJ)
 * extracted cleanly from [UnifiedAiEngine].
 */
internal class AiCraftGenerators(
    private val context: Context?,
    private val externalAiProviders: ExternalAiProviders,
    private val higgsfieldEngine: OpenHiggsfieldEngine,
    private val firebaseAiEngine: FirebaseAiEngine
) {

    private fun hasCloudOrFirebaseAi(): Boolean =
        context != null && (
            externalAiProviders.hasAnyConfiguredProvider() ||
                (externalAiProviders.allowExternalWikiGrounding && FirebaseAiEngine.isAvailable(context))
            )

    fun extractSubject(prompt: String, type: String): String {
        var clean = prompt
            .replace(Regex("(?i)^(please\\s+)?(can\\s+you\\s+)?(make|create|generate|draw|paint|render|show\\s+me|give\\s+me)\\s*(a|an|the)?\\s*"), "")
            .replace(Regex("(?i)^(photo|image|picture|pic|wallpaper|video|animation|clip|chobi|ছবি|ফটো|পিক|ভিডিও)\\s*(of|about|for|er|এর)?\\s*"), "")
            .replace(Regex("(?i)(photo|image|picture|pic|video|animation|chobi|ছবি|ফটো|পিক|ভিডিও)\\s*(banao|toiri\\s+koro|akho|enke\\s+dao|banai\\s+dao|banie\\s+dao|বানাও|আঁকো|তৈরি\\s+করো|এঁকে\\s+দাও|বানিয়ে\\s+দাও)"), "")
            .replace(Regex("(?i)^(ekta|akta|একটি|একটা|একটি\\s+সুন্দর|একটা\\s+সুন্দর)\\s*"), "")
            .replace(Regex("^(ছবি:|ফটো:|পিক:|ভিডিও:|image:|photo:|pic:|video:)"), "")
            .trim()

        if (clean.isBlank()) {
            clean = if (type == "VIDEO") "Cinematic futuristic cosmic motion in 4k" else "Ultra detailed futuristic cyberpunk neon city 8k"
        }
        return clean
    }

    private fun readVideoDurationText(file: File): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val totalSecs = (ms / 1000L).coerceAtLeast(1L)
            val mins = totalSecs / 60L
            val secs = totalSecs % 60L
            String.format(Locale.US, "%d:%02d", mins, secs)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun generateVideoCraft(
        prompt: String,
        currentPersona: String,
        attachedImageBase64: String? = null
    ): AiResponse {
        val higgsfieldPayload = higgsfieldEngine.synthesizeVideoCraftWithAi(prompt)
        val cleanSubject = extractSubject(prompt, "VIDEO")
        var finalJsonPayload = higgsfieldPayload.jsonPayload
        var modelLabel = "Open-Higgsfield-AI Cinematic Motion Storyboard 🎬"
        var hasRenderedVideo = false
        var hasStoryKeyframes = false

        val comfyUrl = context?.let { AyraPreferences.getComfyUiUrl(it) }.orEmpty()
        if (context != null && comfyUrl.isNotBlank()) {
            val outDir = File(context.filesDir, "generated_images")
            val comfyVid = ComfyUiClient().generateVideo(
                baseUrl = comfyUrl,
                prompt = cleanSubject,
                outputDir = outDir
            )
            if (comfyVid is ComfyUiResult.VideoSuccess) {
                val measuredDuration = readVideoDurationText(comfyVid.file)
                finalJsonPayload = runCatching {
                    JSONObject(higgsfieldPayload.jsonPayload).apply {
                        put("localVideoPath", comfyVid.file.absolutePath)
                        put("isStoryboardOnly", false)
                        if (measuredDuration != null) put("duration", measuredDuration) else remove("duration")
                        put("cameraMotion", "Wan 2.1 ComfyUI MP4")
                    }.toString()
                }.getOrDefault(higgsfieldPayload.jsonPayload)
                modelLabel = "ComfyUI Wan 2.1 Neural Video MP4 🎬"
                hasRenderedVideo = true
            }
        }

        val canUseFirebaseImage = context != null &&
            !AyraPreferences.isLocalOnlyAiModeEnabled(context) &&
            AyraPreferences.hasExternalAiConsent(context) &&
            FirebaseAiEngine.isAvailable(context)

        if (!hasRenderedVideo && canUseFirebaseImage) {
            val framePrompt = if (!attachedImageBase64.isNullOrBlank()) {
                "Transform this attached image into an ultra-cinematic 16:9 movie keyframe depicting motion for: $cleanSubject"
            } else {
                "Ultra-cinematic 16:9 widescreen 4K movie film keyframe of: $cleanSubject, ${higgsfieldPayload.lighting}, ${higgsfieldPayload.lensType}"
            }
            val kf1 = firebaseAiEngine.generateOrEditImage(framePrompt, attachedImageBase64, "16:9")
            if (kf1 is FirebaseImageResult.Success) {
                val frameUri = Uri.fromFile(kf1.file).toString()
                val kf2 = firebaseAiEngine.generateOrEditImage(
                    "Cinematic next-moment continuation shot (${higgsfieldPayload.cameraMotion}): $cleanSubject",
                    null,
                    "16:9"
                )
                val framesArray = JSONArray().apply {
                    put(frameUri)
                    if (kf2 is FirebaseImageResult.Success) {
                        put(Uri.fromFile(kf2.file).toString())
                    }
                }
                finalJsonPayload = runCatching {
                    JSONObject(higgsfieldPayload.jsonPayload).apply {
                        put("previewUrl", frameUri)
                        put("keyframes", framesArray)
                        put("isStoryboardOnly", true)
                        put("frameCount", framesArray.length())
                    }.toString()
                }.getOrDefault(higgsfieldPayload.jsonPayload)
                modelLabel = "Nano Banana 2.1 + Open-Higgsfield 4K Storyboard 🎬"
                hasStoryKeyframes = true
            }
        }

        val allowFreePool = context != null &&
            !AyraPreferences.isLocalOnlyAiModeEnabled(context) &&
            AyraPreferences.hasExternalAiConsent(context) &&
            AyraPreferences.isFreeAiPoolEnabled(context)
        if (!hasRenderedVideo && !hasStoryKeyframes && !allowFreePool) {
            finalJsonPayload = runCatching {
                JSONObject(finalJsonPayload).apply {
                    remove("previewUrl")
                    put("isStoryboardOnly", true)
                }.toString()
            }.getOrDefault(finalJsonPayload)
        }

        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"

        val replyText = if (isBengali) {
            when {
                hasRenderedVideo -> "🎬 ComfyUI Wan 2.1 থেকে রেন্ডার করা আসল MP4 ভিডিও তৈরি হয়েছে:\n**$cleanSubject**\nনিচে ভিডিও প্লেয়ারে প্লে করুন।"
                isGfMode -> "🎬 সোনা, সিনেমাটিক কী-ফ্রেম ও ক্যামেরা মোশন স্টোরিবোর্ড তৈরি করেছি:\n**$cleanSubject**\nক্যামেরা: ${higgsfieldPayload.cameraMotion} | লেন্স: ${higgsfieldPayload.lensType} (পূর্ণ MP4 ভিডিওর জন্য Tools-এ ComfyUI URL সেট করুন) ❤️"
                else -> "🎬 সিনেমাটিক মোশন স্টোরিবোর্ড ও কী-ফ্রেম ডিরেকশন প্রস্তুত:\n**$cleanSubject**\nক্যামেরা: ${higgsfieldPayload.cameraMotion} | লেন্স: ${higgsfieldPayload.lensType} (পূর্ণ MP4 ভিডিও রেন্ডার করতে ComfyUI সার্ভার সংযুক্ত করুন)।"
            }
        } else {
            when {
                hasRenderedVideo -> "🎬 Real MP4 video rendered via ComfyUI Wan 2.1 for:\n**$cleanSubject**"
                isGfMode -> "🎬 Sweetheart, synthesized your cinematic motion storyboard:\n**$cleanSubject**\nCamera: ${higgsfieldPayload.cameraMotion} | Lens: ${higgsfieldPayload.lensType} (configure ComfyUI in Tools for direct MP4 video) ❤️"
                else -> "🎬 Cinematic motion storyboard synthesized for:\n**$cleanSubject**\nCamera: ${higgsfieldPayload.cameraMotion} | Optics: ${higgsfieldPayload.lensType} (connect ComfyUI in Tools for live MP4 video rendering)"
            }
        }

        return AiResponse(
            text = replyText,
            craftType = "VIDEO",
            craftPayload = finalJsonPayload,
            modelUsed = modelLabel
        )
    }

    suspend fun generateImageCraft(
        prompt: String,
        currentPersona: String,
        attachedImageBase64: String? = null
    ): AiResponse {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"
        val isEditMode = !attachedImageBase64.isNullOrBlank()
        val aspectRatio = when {
            prompt.contains("16:9") || prompt.contains("landscape", ignoreCase = true) || prompt.contains("wallpaper", ignoreCase = true) -> "16:9"
            prompt.contains("9:16") || prompt.contains("portrait", ignoreCase = true) || prompt.contains("vertical", ignoreCase = true) -> "9:16"
            else -> "1:1"
        }

        var firebaseFailureReason: String? = null
        if (context != null && FirebaseAiEngine.isAvailable(context) && externalAiProviders.allowExternalWikiGrounding) {
            when (val fbRes = firebaseAiEngine.generateOrEditImage(prompt, attachedImageBase64, aspectRatio)) {
                is FirebaseImageResult.Success -> {
                    val fileUri = Uri.fromFile(fbRes.file).toString()
                    val cleanSubject = extractSubject(prompt, "IMAGE")
                    val captionNote = if (fbRes.caption.isNotBlank()) "\n\n${fbRes.caption}" else ""
                    val replyText = if (isBengali) {
                        if (isGfMode) {
                            "🎨 সোনা, **Gemini Nano Banana 2.1** দিয়ে তোমার ${if (isEditMode) "এডিট করা" else "তৈরি"} ছবিটি রেডি:\n**$cleanSubject** ❤️$captionNote"
                        } else {
                            "🎨 **Gemini Nano Banana 2.1** দিয়ে ${if (isEditMode) "এডিট করা" else "তৈরি"} ছবি:\n**$cleanSubject**$captionNote"
                        }
                    } else {
                        if (isGfMode) {
                            "🎨 Here is your ${if (isEditMode) "edited" else "generated"} image from **Gemini Nano Banana 2.1**, sweetie:\n**$cleanSubject** ❤️$captionNote"
                        } else {
                            "🎨 Image ${if (isEditMode) "edited" else "generated"} via **Gemini Nano Banana 2.1**:\n**$cleanSubject**$captionNote"
                        }
                    }
                    return AiResponse(
                        text = replyText,
                        craftType = "IMAGE",
                        craftPayload = fileUri,
                        modelUsed = fbRes.modelUsed
                    )
                }
                is FirebaseImageResult.Failure -> {
                    firebaseFailureReason = fbRes.userMessage
                    val hasComfy = !AyraPreferences.getComfyUiUrl(context).isBlank()
                    if (isEditMode && !fbRes.isQuotaOrBilling && !hasComfy) {
                        val errMsg = if (isBengali) {
                            "⚠️ **ছবি এডিট করা সম্ভব হয়নি (Gemini Nano Banana 2.1)**\n\nকারণ: ${fbRes.userMessage}"
                        } else {
                            "⚠️ **Could Not Edit Image (Gemini Nano Banana 2.1)**\n\nReason: ${fbRes.userMessage}"
                        }
                        return AiResponse(text = errMsg, modelUsed = "Gemini Nano Banana 2.1")
                    }
                }
            }
        }

        val comfyUrl = context?.let { AyraPreferences.getComfyUiUrl(it) }.orEmpty()
        if (context != null && comfyUrl.isNotBlank()) {
            val outDir = File(context.filesDir, "generated_images")
            val comfyClient = ComfyUiClient()
            val comfyRes = if (!attachedImageBase64.isNullOrBlank()) {
                val refBytes = runCatching { Base64.decode(attachedImageBase64, Base64.DEFAULT) }.getOrNull()
                if (refBytes != null && refBytes.isNotEmpty()) {
                    comfyClient.generateImageToImage(comfyUrl, prompt, refBytes, outDir)
                } else ComfyUiResult.Failure("Invalid reference image bytes")
            } else {
                val (w, h) = when (aspectRatio) {
                    "16:9" -> 1024 to 576
                    "9:16" -> 576 to 1024
                    else -> 768 to 768
                }
                comfyClient.generateImage(comfyUrl, prompt, outDir, width = w, height = h)
            }
            if (comfyRes is ComfyUiResult.ImageSuccess) {
                val fileUri = Uri.fromFile(comfyRes.file).toString()
                val cleanSubject = extractSubject(prompt, "IMAGE")
                val replyText = if (isBengali) {
                    "🎨 **ComfyUI** দিয়ে ${if (isEditMode) "এডিট করা" else "তৈরি"} ছবি:\n**$cleanSubject**"
                } else {
                    "🎨 Image ${if (isEditMode) "edited via ComfyUI Img2Img" else "generated via ComfyUI"}:\n**$cleanSubject**"
                }
                return AiResponse(
                    text = replyText,
                    craftType = "IMAGE",
                    craftPayload = fileUri,
                    modelUsed = if (isEditMode) "ComfyUI Img2Img 🎨" else "ComfyUI Flux.1 🎨"
                )
            }
        }

        val canUseFreePool = context != null &&
            !AyraPreferences.isLocalOnlyAiModeEnabled(context) &&
            AyraPreferences.hasExternalAiConsent(context) &&
            AyraPreferences.isFreeAiPoolEnabled(context)

        if (!canUseFreePool) {
            val reasonNote = firebaseFailureReason?.let { "\n\nNano Banana status: $it" }.orEmpty()
            val errText = if (isBengali) {
                "⚠️ **ছবি তৈরি করা সম্ভব হয়নি**\n\nবর্তমানে Local-Only মোড চালু আছে অথবা Global Free AI Pool (Pollinations) বন্ধ রয়েছে। Tools & Keys থেকে **Global Free AI Pool** অন করুন অথবা **ComfyUI Server URL** যুক্ত করুন।$reasonNote"
            } else {
                "⚠️ **Image Generation Unavailable**\n\nPrimary cloud image generation did not succeed and the public Global Free AI Pool is disabled or blocked by Local-Only mode. Enable **Global Free AI Pool** or configure a **ComfyUI Server URL** in Tools & Keys.$reasonNote"
            }
            return AiResponse(text = errText, modelUsed = "Offline Status")
        }

        var effectivePrompt = prompt
        if (isEditMode && externalAiProviders.hasAnyConfiguredProvider()) {
            try {
                val visionRes = ProviderPool(context, externalAiProviders).executeSync(
                    ChatRequest(
                        prompt = "Inspect this attached photo and write a concise 1-sentence Flux image generation prompt that preserves the key subject while applying this user edit request: \"$prompt\". Output ONLY the single English prompt.",
                        persona = "PROFESSIONAL",
                        mode = ProviderPoolMode.FAST,
                        imageBase64 = attachedImageBase64
                    )
                )
                if (visionRes.text.isNotBlank()) {
                    effectivePrompt = visionRes.text.lineSequence().first().trim()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        val (cleanSubject, expandedPrompt) = AiMediaPromptSynthesizer.synthesizeVisualPrompt(effectivePrompt, "IMAGE", context)
        val imageUrl = AiMediaPromptSynthesizer.buildImageUrl(
            prompt = effectivePrompt,
            context = context,
            preExpandedPrompt = expandedPrompt,
            aspectRatio = aspectRatio
        )

        val fallbackNote = if (!firebaseFailureReason.isNullOrBlank()) {
            if (isBengali) "\n*(Nano Banana নোট: $firebaseFailureReason — Flux.1 ইঞ্জিন ব্যবহার করা হয়েছে)*"
            else "\n*(Nano Banana note: $firebaseFailureReason — rendered via Flux.1)*"
        } else ""

        val replyText = if (isBengali) {
            if (isGfMode) {
                "🎨 সোনা, Flux.1 AI দিয়ে তোমার জন্য ছবিটি তৈরি করেছি:\n**$cleanSubject**\nনিচে দেখো! ❤️$fallbackNote"
            } else {
                "🎨 Flux.1 AI মডেলে তৈরি ছবি:\n**$cleanSubject**$fallbackNote"
            }
        } else {
            if (isGfMode) {
                "🎨 Here is your image generated via Flux.1 AI, sweetie:\n**$cleanSubject** ❤️$fallbackNote"
            } else {
                "🎨 Image generated via Flux.1 AI for:\n**$cleanSubject**$fallbackNote"
            }
        }

        return AiResponse(
            text = replyText,
            craftType = "IMAGE",
            craftPayload = imageUrl,
            modelUsed = "Flux.1 Neural Image Studio 🎨"
        )
    }

    suspend fun generateSongCraft(prompt: String, currentPersona: String): AiResponse {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"
        val subject = prompt
            .replace(Regex("(?i)^(please\\s+)?(compose|write|make|gaan\\s+banao|গান\\s+বানাও|গান\\s+লিখ|song|music|গান)\\s*(a\\s+song|song|gaan)?\\s*(about|for)?\\s*"), "")
            .trim().ifEmpty { "Original Composition" }

        var jsonPayload = ""
        if (hasCloudOrFirebaseAi() && context != null) {
            try {
                val songPrompt = """
                    Compose an original, soulful song about: "$prompt".
                    Language: ${if (isBengali) "Bengali (বাংলা)" else "English"}.
                    Output ONLY valid raw JSON (no markdown fences) with these exact string fields:
                    {"title":"...", "genre":"...", "tempo":"...", "key":"...", "chords":"...", "lyrics":"[Verse 1]\n...\n\n[Chorus]\n...\n\n[Verse 2]\n..."}
                """.trimIndent()
                val res = ProviderPool(context, externalAiProviders).executeSync(
                    ChatRequest(
                        prompt = songPrompt,
                        persona = currentPersona,
                        mode = ProviderPoolMode.FAST
                    )
                )
                val raw = res.text.trim()
                val startIdx = raw.indexOf('{')
                val endIdx = raw.lastIndexOf('}')
                if (startIdx >= 0 && endIdx > startIdx) {
                    val parsed = JSONObject(raw.substring(startIdx, endIdx + 1))
                    if (parsed.optString("lyrics").isNotBlank()) {
                        parsed.put("artist", "Ayra AI Music Studio")
                        if (parsed.optString("chords").isBlank()) {
                            parsed.put("chords", "Am - F - C - G")
                        }
                        jsonPayload = parsed.toString()
                    }
                } else if (raw.length > 30) {
                    jsonPayload = JSONObject().apply {
                        put("title", subject.replaceFirstChar { it.uppercase() })
                        put("artist", "Ayra AI Music Studio")
                        put("genre", if (isBengali) "Adhunik / Acoustic" else "Modern Acoustic Pop")
                        put("tempo", "105 BPM")
                        put("key", "C Major")
                        put("chords", "C - G - Am - F")
                        put("lyrics", raw)
                    }.toString()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        if (jsonPayload.isBlank()) {
            val offlineMsg = if (isBengali) {
                "⚠️ **গান ও লিরিক্স তৈরি করা যায়নি**\n\nমৌলিক গানের লিরিক্স ও কর্ড তৈরি করতে বর্তমানে কোনো AI প্রোভাইডার সংযুক্ত নেই। অনুগ্রহ করে ইন্টারনেট সংযোগ ও API কী পরীক্ষা করে আবার চেষ্টা করুন।"
            } else {
                "⚠️ **Song Composition Unavailable**\n\nNo active AI provider could be reached to compose original lyrics and chord progressions. Check your connection or API keys in Tools & Keys and try again."
            }
            return AiResponse(
                text = offlineMsg,
                craftType = null,
                craftPayload = null,
                modelUsed = "Offline Status"
            )
        }

        val text = if (isBengali) {
            if (isGfMode) {
                "🎵 সোনা, তোমার অনুরোধ অনুযায়ী গানের লিরিক্স ও সুরের কাঠামো তৈরি করেছি:\n**$subject** ❤️"
            } else {
                "🎵 আপনার অনুরোধে গানের লিরিক্স ও কর্ড তৈরি সম্পন্ন হয়েছে:\n**$subject**"
            }
        } else {
            if (isGfMode) {
                "🎵 Sweetheart, here is the song composition for:\n**$subject** ❤️"
            } else {
                "🎵 AI Song Composition generated:\n**$subject**"
            }
        }

        return AiResponse(
            text = text,
            craftType = "SONG",
            craftPayload = jsonPayload,
            modelUsed = "Sonic Melodic Studio"
        )
    }

    suspend fun generate3dModelCraft(prompt: String, currentPersona: String): AiResponse {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"
        val lower = prompt.lowercase(Locale.ROOT)
        val (modelKind, baseTitle) = when {
            lower.contains("car") || lower.contains("গাড়ি") || lower.contains("sports") -> Pair("CAR", "Cyber Sports Car 3D")
            lower.contains("ship") || lower.contains("space") || lower.contains("rocket") || lower.contains("মহাকাশ") -> Pair("SPACESHIP", "Interstellar Fighter 3D")
            lower.contains("robot") || lower.contains("রোবট") || lower.contains("droid") -> Pair("ROBOT", "Cyber Android Mech 3D")
            lower.contains("diamond") || lower.contains("crystal") || lower.contains("হীরা") -> Pair("DIAMOND", "Holographic Diamond 3D")
            lower.contains("sphere") || lower.contains("planet") || lower.contains("ball") || lower.contains("গোলক") -> Pair("SPHERE", "Geodesic Sphere 3D")
            else -> {
                val cleaned = prompt.replace(Regex("(?i)^(please\\s+)?(make|create|generate|3d\\s+model|3d\\s+বানাও|3d)\\s*(of|a|an)?\\s*"), "").trim()
                Pair("CUSTOM_AI", if (cleaned.isNotBlank()) "3D ${cleaned.replaceFirstChar { it.uppercase() }}" else "Holographic Cyber Core 3D")
            }
        }

        var mesh = if (modelKind == "CUSTOM_AI") Model3dMeshCatalog.getMesh("CYBER_CORE") else Model3dMeshCatalog.getMesh(modelKind)
        var effectiveKind = if (modelKind == "CUSTOM_AI") "CYBER_CORE" else modelKind
        var usedFallbackPreset = false

        if (modelKind == "CUSTOM_AI" && context != null && hasCloudOrFirebaseAi()) {
            val aiObj = withTimeoutOrNull(15_000L) {
                try {
                    val objPrompt = """
                        Generate a clean, low-poly 3D geometric mesh in Wavefront .OBJ format representing: "$baseTitle".
                        Output ONLY valid `v x y z` vertex lines (12-36 vertices, coordinates in -1.5..1.5) followed by `f i1 i2 i3` (or quad `f i1 i2 i3 i4`) 1-based face lines (at least 6 faces). No markdown, no explanations.
                    """.trimIndent()
                    ProviderPool(context, externalAiProviders).executeSync(
                        ChatRequest(
                            prompt = objPrompt,
                            mode = ProviderPoolMode.FAST,
                            systemPromptOverride = "You are a 3D CAD geometry generator outputting strictly raw Wavefront .OBJ `v` and `f` lines."
                        )
                    ).text
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ""
                }
            }.orEmpty()
            val parsedMesh = Model3dMeshCatalog.parseWavefrontObj(aiObj, "CUSTOM_AI")
            if (parsedMesh != null) {
                mesh = parsedMesh
                effectiveKind = "CUSTOM_AI"
            } else {
                usedFallbackPreset = true
            }
        } else if (modelKind == "CUSTOM_AI") {
            usedFallbackPreset = true
        }

        val displayTitle = if (usedFallbackPreset) {
            "Cyber Core 3D (Fallback Preset for \"$baseTitle\")"
        } else {
            baseTitle
        }

        val objText = Model3dMeshCatalog.buildWavefrontObj(mesh, displayTitle)
        val jsonPayload = JSONObject().apply {
            put("title", displayTitle)
            put("kind", effectiveKind)
            put("isFallback", usedFallbackPreset)
            put("vertices", mesh.vertexCount)
            put("edges", mesh.edgeCount)
            put("faces", mesh.faceCount)
            put("format", "Wavefront .OBJ / Holographic WebGL")
            put("objContent", objText)
        }.toString()

        val fallbackBanner = if (usedFallbackPreset) {
            if (isBengali) "\n*(নোট: কাস্টম AI মেশ তৈরি না হওয়ায় ডিফল্ট ৩ডি Cyber Core প্রিসেট প্রদর্শিত হচ্ছে)*"
            else "\n*(Note: AI custom mesh synthesis unavailable or returned invalid geometry; displaying Cyber Core 3D preset.)*"
        } else ""

        val text = if (isBengali) {
            if (isGfMode) {
                "🌐 সোনা, তোমার ত্রিমাত্রিক (3D) মডেল প্রস্তুত:\n**$displayTitle**\nনিচে 3D ভিউপোর্টে হাত দিয়ে ঘুরিয়ে ও জুম করে দেখো তো কেমন হয়েছে! ❤️$fallbackBanner"
            } else {
                "🌐 ত্রিমাত্রিক (3D) মডেল লোড করা হয়েছে:\n**$displayTitle**\nনিচের ইন্টারঅ্যাক্টিভ ভিউপোর্টে স্পর্শ করে ৩৬০° কোণে পর্যবেক্ষণ করুন।$fallbackBanner"
            }
        } else {
            if (isGfMode) {
                "🌐 Darling, here is your 3D holographic viewport:\n**$displayTitle**\nRotate and explore it below in 360°! ❤️$fallbackBanner"
            } else {
                "🌐 3D CAD holographic viewport ready:\n**$displayTitle**\nInteract with the 360° spatial geometry below.$fallbackBanner"
            }
        }

        return AiResponse(
            text = text,
            craftType = "3D",
            craftPayload = jsonPayload,
            modelUsed = when {
                effectiveKind == "CUSTOM_AI" -> "Ayra AI 3D Mesh Architect"
                usedFallbackPreset -> "Ayra AI 3D Preset Viewport (Offline Fallback)"
                else -> "Holographic 3D Spatial Engine"
            }
        )
    }
}
