package com.ayra.ai.app.ai

import com.ayra.ai.app.ai.providers.ExternalAiProviders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AiMediaPromptSynthesizer {

    private val httpClient: OkHttpClient by lazy {
        ExternalAiProviders.sharedFastWebClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(2800, TimeUnit.MILLISECONDS)
            .build()
    }

    private const val MAX_PROMPT_CACHE = 200
    private val promptCache = ConcurrentHashMap<String, Pair<String, String>>()

    private val bengaliToEnglishSubjectMap = mapOf(
        "বিড়াল" to "cute fluffy domestic cat",
        "বিড়ালের" to "cute fluffy domestic cat",
        "কুকুর" to "playful golden retriever dog",
        "রাজকন্যা" to "fairytale royal princess with ornate tiara and gown",
        "রাজকন্যার" to "fairytale royal princess with ornate tiara and gown",
        "গাড়ি" to "futuristic hypercar sleek sports car",
        "গাড়ির" to "futuristic hypercar sleek sports car",
        "মহাকাশ" to "deep space with luminous nebulae and galaxies",
        "চাঁদ" to "realistic glowing moon in dark cosmic night sky",
        "চাঁদে" to "astronaut on the lunar surface with earth in background",
        "সূর্য" to "blazing golden sun with solar flares in space",
        "বাঘ" to "majestic royal bengal tiger in lush green jungle",
        "সিংহ" to "noble male lion with golden mane in savannah",
        "ফুল" to "vibrant blooming flowers in a sunlit garden",
        "গোলাপ" to "ultra-detailed red roses with delicate dewdrops",
        "সমুদ্র" to "tropical azure ocean with gentle waves and coral reef",
        "সৈকত" to "peaceful sandy tropical beach at sunset",
        "পাহাড়" to "snow-capped majestic alpine mountain peaks",
        "সূর্যাস্ত" to "dramatic vibrant golden sunset over horizon",
        "মেয়ে" to "beautiful cinematic portrait of an elegant woman",
        "ছেলে" to "portrait of a handsome young man",
        "রোবট" to "sentient humanoid cyber android with neon circuitry",
        "বাড়ি" to "luxurious modern architectural glass villa with infinity pool",
        "পাখি" to "vivid colorful exotic bird with iridescent feathers",
        "শহর" to "futuristic cyberpunk neon metropolis skyline",
        "নদী" to "crystal clear winding river reflecting starlight",
        "অ্যানিমে" to "stunning Makoto Shinkai style anime character landscape",
        "কার্টুন" to "delightful Pixar 3D animated character",
        "ড্রাগন" to "majestic fire-breathing dragon over ancient castle",
        "ঘোড়া" to "powerful wild black stallion galloping on beach",
        "বৃষ্টি" to "romantic rainy city street with neon reflections",
        "বাচ্চা" to "happy adorable baby with sparkling eyes",
        "মা" to "loving affectionate mother holding child portrait"
    )

    /**
     * Translates and refines any user prompt into a high-fidelity Flux English prompt with <1ms repeat caching.
     */
    fun synthesizeVisualPrompt(rawPrompt: String, type: String = "IMAGE", context: android.content.Context? = null): Pair<String, String> {
        val cleanSubject = extractCleanSubject(rawPrompt)
        val cacheKey = "${type.uppercase(Locale.ROOT)}:${cleanSubject.lowercase(Locale.ROOT)}"
        promptCache[cacheKey]?.let { return it }

        fun cacheAndReturn(expanded: String): Pair<String, String> {
            val pair = Pair(cleanSubject, expanded)
            if (promptCache.size >= MAX_PROMPT_CACHE) promptCache.clear()
            promptCache[cacheKey] = pair
            return pair
        }

        if (context != null) {
            val geminiKey = com.ayra.ai.app.data.AyraPreferences.getEffectiveGeminiKey(context)
            if (geminiKey.isNotBlank()) {
                val expanded = expandViaGemini(cleanSubject, geminiKey, type)
                if (expanded.isNotBlank()) {
                    return cacheAndReturn(expanded)
                }
            }

            // NVIDIA NIM visual prompt expansion fallback
            val minimaxKey = com.ayra.ai.app.data.AyraPreferences.getNvidiaMinimaxKey(context)
            val nvidiaKey = minimaxKey.ifBlank { com.ayra.ai.app.data.AyraPreferences.getNvidiaMuseKey(context) }
            if (nvidiaKey.isNotBlank()) {
                val nvidiaModel = if (minimaxKey.isNotBlank()) {
                    "minimaxai/minimax-m3.1-flash"
                } else {
                    "meta/muse-glimmer-30b"
                }
                val expanded = expandViaOpenAiEndpoint(
                    subject = cleanSubject,
                    endpointUrl = "${com.ayra.ai.app.ai.providers.AiProviderType.NVIDIA_NIM.defaultBaseUrl}chat/completions",
                    apiKey = nvidiaKey,
                    modelId = nvidiaModel,
                    type = type
                )
                if (expanded.isNotBlank()) {
                    return cacheAndReturn(expanded)
                }
            }
        }

        // Intelligent Offline Bilingual Translation & Photographic Expansion
        val translatedSubject = translateBengaliVisualTerms(cleanSubject)
        val styleTags = if (type == "VIDEO") {
            "4k 60fps cinematic tracking motion shot, dynamic perspective, dramatic volumetric studio lighting, hyper-realistic photorealism, Unreal Engine 5 render style"
        } else {
            "masterpiece, photorealistic 8K, award-winning photography, shot on 35mm lens, f/1.8, ray traced reflections, dramatic volumetric lighting, cinematic color grading"
        }

        val expanded = "$translatedSubject, $styleTags"
        return cacheAndReturn(expanded)
    }

    @JvmOverloads
    fun buildImageUrl(
        prompt: String,
        seed: Long = (100000..999999).random().toLong(),
        context: android.content.Context? = null,
        preExpandedPrompt: String? = null
    ): String {
        val expandedPrompt = preExpandedPrompt?.takeIf { it.isNotBlank() }
            ?: synthesizeVisualPrompt(prompt, "IMAGE", context).second
        val encoded = URLEncoder.encode(expandedPrompt, "UTF-8")
        return "https://image.pollinations.ai/prompt/$encoded?model=flux&width=768&height=768&seed=$seed&nologo=true"
    }

    @JvmOverloads
    fun buildVideoPayload(
        prompt: String,
        seed: Long = (100000..999999).random().toLong(),
        context: android.content.Context? = null,
        aspectRatio: String = "16:9"
    ): JSONObject {
        val (cleanSubject, expandedPrompt) = synthesizeVisualPrompt(prompt, "VIDEO", context)
        val isPortrait = aspectRatio.trim() == "9:16"
        val width = if (isPortrait) 576 else 1024
        val height = if (isPortrait) 1024 else 576
        val encodedOpening = URLEncoder.encode("$expandedPrompt, opening wide establishing shot", "UTF-8")
        val encodedAction = URLEncoder.encode("$expandedPrompt, dynamic tracking close-up progression", "UTF-8")
        val previewUrl = "https://image.pollinations.ai/prompt/$encodedOpening?model=flux&width=$width&height=$height&seed=$seed&nologo=true"
        val secondFrameUrl = "https://image.pollinations.ai/prompt/$encodedAction?model=flux&width=$width&height=$height&seed=${seed + 17}&nologo=true"

        return JSONObject().apply {
            put("title", cleanSubject.replaceFirstChar { it.uppercase() })
            put("prompt", cleanSubject)
            put("previewUrl", previewUrl)
            put("keyframes", JSONArray().put(previewUrl).put(secondFrameUrl))
            put("aspectRatio", if (isPortrait) "9:16" else "16:9")
            put("duration", "0:15")
        }
    }

    private fun extractCleanSubject(prompt: String): String {
        var clean = prompt
            .replace(Regex("(?i)^(please\\s+)?(can\\s+you\\s+)?(make|create|generate|draw|paint|render|show\\s+me|give\\s+me)\\s*(a|an|the)?\\s*"), "")
            .replace(Regex("(?i)^(photo|image|picture|pic|wallpaper|video|animation|clip|chobi|ছবি|ফটো|পিক|ভিডিও)\\s*(of|about|for|er|এর)?\\s*"), "")
            .replace(Regex("(?i)(photo|image|picture|pic|video|animation|chobi|ছবি|ফটো|পিক|ভিডিও)\\s*(banao|toiri\\s+koro|akho|enke\\s+dao|banai\\s+dao|banie\\s+dao|বানাও|আঁকো|তৈরি\\s+করো|এঁকে\\s+দাও|বানিয়ে\\s+দাও)"), "")
            .replace(Regex("(?i)^(ekta|akta|একটি|একটা|একটি\\s+সুন্দর|একটা\\s+সুন্দর)\\s*"), "")
            .replace(Regex("^(ছবি:|ফটো:|পিক:|ভিডিও:|image:|photo:|pic:|video:)"), "")
            .trim()

        if (clean.isBlank()) {
            clean = "Stunning futuristic cyberpunk neon city 8k"
        }
        return clean
    }

    private fun translateBengaliVisualTerms(text: String): String {
        var result = text
        for ((bn, en) in bengaliToEnglishSubjectMap) {
            if (result.contains(bn)) {
                result = result.replace(bn, en)
            }
        }
        return result
    }

    private fun expandViaGemini(subject: String, apiKey: String, type: String): String {
        return try {
            val systemInstruction = if (type == "VIDEO") {
                "You are an expert prompt engineer for cinematic AI video synthesis. Convert this visual request into a single sentence vivid 4k video motion prompt describing dynamic movement, lighting, and camera tracking. Output ONLY the English prompt in one line, no markdown, no quotes."
            } else {
                "You are an expert prompt engineer for Flux.1 and photorealistic diffusion. Convert this visual request into a single sentence master photographic English prompt with subject details, lens, lighting, and textures. Output ONLY the English prompt in one line, no markdown, no quotes."
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"
            val requestJson = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", "$systemInstruction\nUser request: $subject")
                    }))
                }))
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                        val answer = parts?.optJSONObject(0)?.optString("text", "")?.trim() ?: ""
                        if (answer.isNotBlank()) return answer
                    }
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun expandViaFastOpenAiProviders(
        subject: String,
        context: android.content.Context,
        type: String
    ): String {
        val candidates = mutableListOf<Triple<String, String, String>>()
        val minimaxKey = com.ayra.ai.app.data.AyraPreferences.getNvidiaMinimaxKey(context)
        val museKey = com.ayra.ai.app.data.AyraPreferences.getNvidiaMuseKey(context)
        if (minimaxKey.isNotBlank()) {
            candidates.add(Triple("https://integrate.api.nvidia.com/v1/chat/completions", minimaxKey, ExternalAiProviders.NVIDIA_MINIMAX_MODEL))
        }
        if (museKey.isNotBlank()) {
            candidates.add(Triple("https://integrate.api.nvidia.com/v1/chat/completions", museKey, ExternalAiProviders.NVIDIA_MUSE_MODEL))
        }
        val groqKey = com.ayra.ai.app.data.AyraPreferences.getGroqKey(context)
        if (groqKey.isNotBlank()) {
            candidates.add(Triple("${com.ayra.ai.app.ai.providers.AiProviderType.GROQ.defaultBaseUrl}chat/completions", groqKey, ExternalAiProviders.GROQ_LLAMA_MODEL))
        }
        val cerebrasKey = com.ayra.ai.app.data.AyraPreferences.getCerebrasKey(context)
        if (cerebrasKey.isNotBlank()) {
            candidates.add(Triple("${com.ayra.ai.app.ai.providers.AiProviderType.CEREBRAS.defaultBaseUrl}chat/completions", cerebrasKey, ExternalAiProviders.CEREBRAS_LLAMA_MODEL))
        }
        val openRouterKey = com.ayra.ai.app.data.AyraPreferences.getEffectiveOpenRouterKey(context)
        if (openRouterKey.isNotBlank()) {
            candidates.add(Triple("${com.ayra.ai.app.ai.providers.AiProviderType.OPENROUTER.defaultBaseUrl}chat/completions", openRouterKey, "meta-llama/llama-3.3-70b-instruct:free"))
        }
        val githubKey = com.ayra.ai.app.data.AyraPreferences.getGitHubModelsKey(context)
        if (githubKey.isNotBlank()) {
            candidates.add(Triple("${com.ayra.ai.app.ai.providers.AiProviderType.GITHUB_MODELS.defaultBaseUrl}chat/completions", githubKey, ExternalAiProviders.GITHUB_GPT4O_MODEL))
        }

        for ((endpoint, key, modelId) in candidates) {
            val expanded = expandViaOpenAiEndpoint(subject, endpoint, key, modelId, type)
            if (expanded.isNotBlank()) return expanded
        }
        return ""
    }

    internal fun expandViaOpenAiEndpoint(
        subject: String,
        endpointUrl: String,
        apiKey: String,
        modelId: String,
        type: String,
        clientOverride: OkHttpClient? = null
    ): String {
        return try {
            val systemInstruction = if (type == "VIDEO") {
                "Convert this visual request into a vivid 1-sentence 4k cinematic video prompt in English describing motion, lighting, and camera angle. Output ONLY the prompt."
            } else {
                "Convert this visual request into a vivid 1-sentence photorealistic 8K Flux image prompt in English describing subject, lighting, lens, and texture. Output ONLY the prompt."
            }

            val bodyJson = JSONObject().apply {
                put("model", modelId)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemInstruction))
                    put(JSONObject().put("role", "user").put("content", subject))
                })
                put("max_tokens", 256)
                put("temperature", 0.7)
            }

            val request = Request.Builder()
                .url(endpointUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            (clientOverride ?: httpClient).newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    val raw = if (msg != null && !msg.isNull("content")) msg.optString("content", "") else ""
                    val cleaned = ExternalAiProviders.sanitizeModelOutput(raw)
                    if (cleaned.isNotBlank()) return cleaned.lineSequence().first().trim()
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }
}
