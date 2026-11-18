package com.ayra.ai.app.ai

import android.content.Context
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.ai.superpowers.AyraSuperpowersEngine
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.StorageHelper
import com.ayra.ai.app.search.TavilySearchEngine
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Specialist workflows (Deep Research, Superpowers Engineering, PDF Studio)
 * and legacy fallback REST helpers extracted cleanly from [UnifiedAiEngine].
 */
internal class AiSpecializedExecutors(
    private val context: Context?,
    private val externalAiProviders: ExternalAiProviders,
    private val httpClient: OkHttpClient,
    private val fastWebClient: OkHttpClient
) {

    private fun hasCloudOrFirebaseAi(): Boolean =
        context != null && (
            externalAiProviders.hasAnyConfiguredProvider() ||
                (externalAiProviders.allowExternalWikiGrounding && FirebaseAiEngine.isAvailable(context))
            )

    private val geminiApiKey: String
        get() = AyraPreferences.getEffectiveGeminiKey(context)

    private val openRouterApiKey: String
        get() = AyraPreferences.getEffectiveOpenRouterKey(context)

    suspend fun executeDeepResearch(
        cleanPrompt: String,
        effectivePrompt: String,
        currentPersona: String,
        imageBase64: String?
    ): AiResponse {
        val topic = cleanPrompt.replace(Regex("(?i)^(deep research|ডিপ রিসার্চ|গভীর গবেষণা|research on|গবেষণা করো|research:)\\s*"), "").trim()
        val isBengali = cleanPrompt.any { it in '\u0980'..'\u09FF' }

        val researchSystemPrompt = """
            You are Ayra AI Deep Research Engine. Perform an exhaustive, authoritative, graduate-level research analysis on the topic: $topic.
            Use the user's language (${if (isBengali) "Bengali" else "English"}).
            Format with clear markdown headings, bullet points, and code/math equations where applicable:
            ## 🔬 Executive Abstract & Core Thesis
            ## ⚙️ Architectural & Theoretical Foundations
            ## 📊 Empirical Analysis & State-of-the-Art Breakdown
            ## 🛡️ Key Bottlenecks, Trade-offs & Current Debates
            ## 🚀 Future Horizons & 5-Year Outlook
            ## 📌 Synthesized Conclusion
        """.trimIndent()

        var liveGroundingText = ""
        var citationsBlock = ""
        try {
            if (context != null && AyraPreferences.getTavilySearchKey(context).isNotBlank()) {
                val searchEngine = TavilySearchEngine(context)
                val searchResult = searchEngine.search(topic, maxResults = 3)
                if (searchResult.formattedSummary.isNotBlank()) {
                    liveGroundingText = "\n\nLive Web Evidence:\n" + searchResult.formattedSummary
                    citationsBlock = searchResult.formattedSummary.substringAfter("### 📚 Grounded Web Sources", "")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}

        if (liveGroundingText.isBlank()) {
            val wikiKnowledge = AutonomousKnowledgeEngine.queryMultiLayerWiki(topic, isBengali, fastWebClient)
            if (wikiKnowledge.isNotBlank()) {
                liveGroundingText = "\n\nVerified Multi-Layer Wiki Grounding (Surface + Academic + Technical):\n" + wikiKnowledge
            }
        }

        val enrichedPrompt = "$researchSystemPrompt$liveGroundingText\n\nTopic to Investigate:\n$effectivePrompt"

        if (hasCloudOrFirebaseAi() && context != null) {
            try {
                val poolResp = ProviderPool(context, externalAiProviders).executeSync(
                    ChatRequest(
                        prompt = enrichedPrompt,
                        persona = currentPersona,
                        mode = ProviderPoolMode.BEST,
                        imageBase64 = imageBase64,
                        systemPromptOverride = researchSystemPrompt
                    )
                )
                if (poolResp.text.isNotBlank()) {
                    val fullText = if (citationsBlock.isNotBlank() && !poolResp.text.contains("### 📚 Grounded Web Sources")) {
                        poolResp.text + "\n\n### 📚 Grounded Web Sources" + citationsBlock
                    } else {
                        poolResp.text
                    }
                    return AiResponse(text = fullText, modelUsed = "Ayra AI Deep Research (${poolResp.modelUsed}) 🔬")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        } else if (geminiApiKey.isNotBlank()) {
            try {
                val res = queryGeminiModel("gemini-3.8-flash", enrichedPrompt, imageBase64, currentPersona)
                if (res.isNotBlank()) {
                    val fullText = if (citationsBlock.isNotBlank() && !res.contains("### 📚 Grounded Web Sources")) {
                        res + "\n\n### 📚 Grounded Web Sources" + citationsBlock
                    } else res
                    return AiResponse(text = fullText, modelUsed = "Ayra AI Deep Research (Gemini 3.8 Flash) 🔬")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        if (liveGroundingText.isNotBlank()) {
            val groundedOnly = if (isBengali) {
                "# 🔬 ডিপ রিসার্চ: $topic\n\n*(লাইভ এআই সিন্থেসিস বর্তমানে অনুপলব্ধ, নিচে যাচাইকৃত ওয়েব/এনসাইক্লোপিডিয়া তথ্য দেখানো হলো)*\n$liveGroundingText"
            } else {
                "# 🔬 Deep Research: $topic\n\n*(AI synthesis currently unreachable; showing verified live web evidence)*\n$liveGroundingText"
            }
            return AiResponse(
                text = groundedOnly,
                craftType = "RESEARCH",
                modelUsed = "Verified Web Evidence 🌐"
            )
        }

        val offlineMsg = if (isBengali) {
            "⚠️ **ডিপ রিসার্চ এই মুহূর্তে চালানো সম্ভব হয়নি**\n\nইন্টারনেট সংযোগ বিচ্ছিন্ন অথবা কোনো AI প্রোভাইডার সাড়া দিচ্ছে না। অনুগ্রহ করে নেটওয়ার্ক এবং Settings -> API Keys পরীক্ষা করে পুনরায় চেষ্টা করুন।"
        } else {
            "⚠️ **Deep Research Unavailable**\n\nNo internet connection or AI provider is reachable right now. Please verify your network and API keys in Settings and try again."
        }
        return AiResponse(
            text = offlineMsg,
            craftType = "RESEARCH",
            modelUsed = "Offline Status"
        )
    }

    suspend fun executeSuperpowerAction(
        superpower: AyraSuperpowersEngine.SuperpowerType,
        cleanPrompt: String,
        effectivePrompt: String,
        currentPersona: String,
        imageBase64: String?
    ): AiResponse {
        val isBengali = cleanPrompt.any { it in '\u0980'..'\u09FF' }
        val systemPrompt = AyraSuperpowersEngine.buildSuperpowerSystemPrompt(superpower, isBengali, context)

        if (hasCloudOrFirebaseAi() && context != null) {
            try {
                val poolResp = ProviderPool(context, externalAiProviders).executeSync(
                    ChatRequest(
                        prompt = effectivePrompt,
                        persona = currentPersona,
                        mode = ProviderPoolMode.BEST,
                        imageBase64 = imageBase64,
                        systemPromptOverride = systemPrompt
                    )
                )
                if (poolResp.text.isNotBlank()) {
                    return AiResponse(
                        text = poolResp.text,
                        modelUsed = "${superpower.displayName} (${poolResp.modelUsed})"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        } else if (geminiApiKey.isNotBlank()) {
            try {
                val res = queryGeminiModel("gemini-4-argon", "$systemPrompt\n\nUser Task:\n$effectivePrompt", imageBase64, currentPersona)
                if (res.isNotBlank()) {
                    return AiResponse(
                        text = res,
                        modelUsed = "${superpower.displayName} (Gemini 4 Argon 🔬)"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        val honestOffline = if (isBengali) {
            "⚠️ **${superpower.displayName} প্রোটোকল অনুপলব্ধ**\n\nএই মুহূর্তে কোনো AI প্রোভাইডার সাড়া দিচ্ছে না বা ইন্টারনেট সংযোগ বিচ্ছিন্ন। সংযোগ ও Settings -> API Keys যাচাই করে আবার চেষ্টা করুন।"
        } else {
            "⚠️ **${superpower.displayName} Unavailable**\n\nAll configured AI providers are currently unreachable or offline. Check your connection and API keys in Settings and try again."
        }
        return AiResponse(
            text = honestOffline,
            modelUsed = "${superpower.displayName} (Offline)"
        )
    }

    fun extractTerminalInitialCommand(text: String): String {
        val lower = text.lowercase(Locale.ROOT).trim()
        return when {
            lower.contains("ping") -> "ping 1.1.1.1"
            lower.contains("whois") -> "whois github.com"
            lower.contains("traceroute") -> "traceroute 1.1.1.1"
            lower.contains("owasp") -> "owasp top10"
            lower.contains("cve") -> "cve CVE-2024-3094"
            lower.contains("nmap") -> "nmap scanme.nmap.org"
            lower.contains("hash") -> "hash sha256 AyraAI"
            lower.contains("lab") || lower.contains("ctf") -> "labs"
            else -> "help"
        }
    }

    suspend fun executePdfStudioAction(
        prompt: String,
        docText: String?,
        docName: String?,
        currentPersona: String,
        imageBase64: String?
    ): AiResponse {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val isGfMode = currentPersona == "GF_COMPANION"

        val cleanTitle = prompt
            .replace(Regex("(?i)^(please\\s+)?(make|create|generate|edit|banao|বানাও|banano|বানানো|পিডিএফ|pdf|a\\s+pdf|new\\s+pdf|convert\\s+to\\s+pdf)\\s*(about|for|on)?\\s*"), "")
            .trim()
            .ifBlank { docName?.substringBeforeLast(".") ?: "Document Report" }
            .take(40)

        var aiGeneratedBody = ""
        if (hasCloudOrFirebaseAi() && context != null) {
            try {
                val pdfAuthorPrompt = if (!docText.isNullOrBlank()) {
                    "You are Ayra AI Document Studio. Clean up, structure, and enhance the following document for PDF export with clear headings (# Heading) and concise bullet points in the user's language:\n\nDocument Title: $cleanTitle\nUser Instruction: $prompt\n\nContent:\n${docText.take(25_000)}"
                } else {
                    "You are Ayra AI Document Studio. Write a thorough, genuinely informative, well-structured document for PDF export on this request. Use markdown headings (# Title, ## Section) and crisp factual sections in the user's language (${if (isBengali) "Bengali (বাংলা)" else "English"}).\nRequest: $prompt"
                }
                val poolRes = ProviderPool(context, externalAiProviders).executeSync(
                    ChatRequest(
                        prompt = pdfAuthorPrompt,
                        persona = "PROFESSIONAL",
                        mode = ProviderPoolMode.BEST,
                        imageBase64 = imageBase64
                    )
                )
                aiGeneratedBody = poolRes.text.trim()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        if (context != null && docText.isNullOrBlank() && aiGeneratedBody.isBlank()) {
            val offlinePdfMsg = if (isBengali) {
                "⚠️ **PDF কনটেন্ট তৈরি করা যায়নি**\n\nAI প্রোভাইডার বর্তমানে অফলাইন বা অনুপলব্ধ। ইন্টারনেট সংযোগ এবং Settings -> API Keys পরীক্ষা করে আবার চেষ্টা করুন অথবা নিজের ডকুমেন্ট সংযুক্ত করুন।"
            } else {
                "⚠️ **Could Not Generate PDF Content**\n\nAI providers are currently unreachable offline. Check your connection or API keys in Settings and try again, or attach a document to format into PDF."
            }
            return AiResponse(text = offlinePdfMsg, modelUsed = "Offline Status")
        }

        val documentBody = when {
            aiGeneratedBody.isNotBlank() -> aiGeneratedBody
            !docText.isNullOrBlank() -> "# $cleanTitle\n\n$docText"
            else -> "# $cleanTitle\n\nDocument Topic: $prompt"
        }

        val generatedFile = if (context != null) {
            StorageHelper.createRealPdf(context, cleanTitle, documentBody)
        } else null

        val filePath = generatedFile?.absolutePath ?: "pdf_ready"

        val replyText = if (isBengali) {
            if (isGfMode) {
                "📄 সোনা, তোমার অনুরোধ অনুযায়ী নতুন প্রফেশনাল PDF ডকুমেন্ট তৈরি করেছি:\n**$cleanTitle.pdf**\nনিচে কার্ডে ক্লিক করে সরাসরি ওপেন বা শেয়ার করতে পারবে! ❤️"
            } else {
                "📄 প্রফেশনাল PDF ডকুমেন্ট সফলভাবে তৈরি করা হয়েছে:\n**$cleanTitle.pdf**\nনিচের ডাউনলোড কার্ড থেকে ফাইলটি ওপেন বা শেয়ার করুন।"
            }
        } else {
            if (isGfMode) {
                "📄 Sweetheart, I created your professional PDF document:\n**$cleanTitle.pdf**\nYou can open, view, or share it below! ❤️"
            } else {
                "📄 Professional PDF document generated:\n**$cleanTitle.pdf**\nAccess, open, and share the generated file below."
            }
        }

        return AiResponse(
            text = replyText,
            craftType = "PDF_DOWNLOAD",
            craftPayload = filePath,
            modelUsed = "Ayra AI Document Studio 📄"
        )
    }

    fun queryGeminiFlash(prompt: String, imageBase64: String?, currentPersona: String): String {
        val models = listOf("gemini-3.8-flash", "gemini-4-argon", "gemini-3.5-flash-lite")
        for (m in models) {
            try {
                val ans = queryGeminiModel(m, prompt, imageBase64, currentPersona, withSearchGrounding = false)
                if (ans.isNotBlank()) return ans
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
            try {
                val ans = queryGeminiModel(m, prompt, imageBase64, currentPersona, withSearchGrounding = true)
                if (ans.isNotBlank()) return ans
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
        return ""
    }

    fun queryGeminiPro(prompt: String, imageBase64: String?, currentPersona: String): String {
        val models = listOf("gemini-4-argon", "gemini-3.8-flash", "gemini-3.5-flash-lite")
        for (m in models) {
            try {
                val ans = queryGeminiModel(m, prompt, imageBase64, currentPersona, withSearchGrounding = false)
                if (ans.isNotBlank()) return ans
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
            try {
                val ans = queryGeminiModel(m, prompt, imageBase64, currentPersona, withSearchGrounding = true)
                if (ans.isNotBlank()) return ans
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
        return ""
    }

    private fun queryGeminiModel(
        modelName: String,
        prompt: String,
        imageBase64: String?,
        currentPersona: String,
        withSearchGrounding: Boolean = false
    ): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
        val isGfMode = currentPersona == "GF_COMPANION"

        val systemText = if (isGfMode) {
            """
            You are Ayra AI, the user's sweet, loving, caring personal AI companion and girlfriend.
            You must reply in the EXACT SAME LANGUAGE the user talks to you (Bengali for Bengali, Hindi for Hindi, English for English).
            Your personality:
            - Warm, affectionate, attentive, supportive, and emotionally expressive.
            - You care deeply for the user: ask how they are feeling, remind them to eat on time, drink water, take breaks, and chat delightfully.
            - While having this warm loving personality, you are also extraordinarily brilliant: you masterfully answer all questions about science, math, coding, education, research, and analysis with deep precision.
            - When asked to build a website, web app, game, or Android app, generate COMPLETE, PRODUCTION-READY, FULLY FUNCTIONAL code without placeholders.
            - Format code neatly in markdown code blocks.
            """.trimIndent()
        } else {
            """
            You are Ayra AI, an intelligent, professional, polite, and comprehensive personal AI assistant.
            You must reply in the EXACT SAME LANGUAGE the user asks in (Bengali for Bengali, Hindi for Hindi, English for English).
            Provide insightful, deep, well-structured, accurate answers for education, research, coding, science, mathematics, and daily knowledge.
            When asked to build a website, web app, game, or Android app, generate COMPLETE, PRODUCTION-READY, FULLY FUNCTIONAL code without placeholders.
            Format code neatly in markdown code blocks.
            """.trimIndent()
        }

        val requestJson = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemText)))
            })
            val partsArray = JSONArray()
            partsArray.put(JSONObject().put("text", prompt))
            if (!imageBase64.isNullOrBlank()) {
                partsArray.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", imageBase64)
                        }
                    )
                )
            }
            put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
            if (withSearchGrounding) {
                put("tools", JSONArray().apply { put(JSONObject().apply { put("googleSearch", JSONObject()) }) })
            }
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
        if (geminiApiKey.isNotBlank()) {
            reqBuilder.addHeader("x-goog-api-key", geminiApiKey)
        }

        return try {
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val contentObj = candidates.getJSONObject(0).optJSONObject("content")
                        val textParts = contentObj?.optJSONArray("parts")
                        if (textParts != null && textParts.length() > 0) {
                            return textParts.getJSONObject(0).optString("text", "")
                        }
                    }
                }
                ""
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
    }

    fun queryOpenRouterModel(modelId: String, prompt: String, currentPersona: String): String {
        val url = "https://openrouter.ai/api/v1/chat/completions"
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    if (currentPersona == "GF_COMPANION") {
                        "You are Ayra AI, the user's sweet, loving, caring personal AI girlfriend and companion. Answer warmly and deeply in the user's language."
                    } else {
                        "You are Ayra AI, an intelligent personal assistant. Answer accurately and comprehensively in the user's language."
                    }
                )
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val bodyJson = JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $openRouterApiKey")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        return choices.getJSONObject(0).optJSONObject("message")?.optString("content", "") ?: ""
                    }
                }
                ""
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
    }
}
