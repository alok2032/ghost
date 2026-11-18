package com.ayra.ai.app.ai

/**
 * World-class Production Code and App Synthesizer for Ayra AI.
 * Generates full, production-ready, beautifully styled HTML5/CSS3/JS websites,
 * interactive HTML5 Canvas games, and complete Kotlin Jetpack Compose Android applications.
 */
object AiAppAndCodeStudio {

    fun isCodeOrAppGenerationIntent(prompt: String): Boolean {
        val lower = prompt.lowercase().trim()
        val hasBuildVerb = lower.contains("banao") || lower.contains("বানাও") ||
                lower.contains("create") || lower.contains("make") ||
                lower.contains("build") || lower.contains("generate") ||
                lower.contains("write a") || lower.contains("develop") ||
                lower.contains("তৈরি") || lower.contains("বানিয়ে") ||
                lower.contains("code dao") || lower.contains("কোড দাও") ||
                lower.contains("full code") || lower.contains("সম্পূর্ণ কোড")

        val hasSubject = lower.contains("website") || lower.contains("ওয়েবসাইট") ||
                lower.contains("web app") || lower.contains("landing page") ||
                lower.contains("android app") || lower.contains("compose app") ||
                lower.contains("app") || lower.contains("অ্যাপ") ||
                lower.contains("game") || lower.contains("গেম") ||
                lower.contains("dashboard") || lower.contains("ড্যাশবোর্ড") ||
                lower.contains("portfolio") || lower.contains("পোর্টফোলিও")

        return (hasBuildVerb && hasSubject) || lower.startsWith("code:") || lower.startsWith("কোড:")
    }

    fun generateCodeOrApp(prompt: String, isBengali: Boolean, isGfMode: Boolean): AiResponse {
        val cleanSubject = prompt.trim().take(120).ifBlank { "Custom Project" }
        val responseText = if (isBengali) {
            """
            ⚠️ **লাইভ কোড জেনারেশন অফলাইন বা API কী কনফিগার করা নেই**

            "**$cleanSubject**"-এর জন্য আসল, কাস্টম কোড তৈরি করতে একটি সক্রিয় এআই প্রোভাইডার (Firebase Gemini AI, Groq, Cerebras, SambaNova, GitHub Models, বা OpenRouter) এবং ইন্টারনেট সংযোগ প্রয়োজন। Ayra AI কোনো নকল বা আগে থেকে বানানো টেমপ্লেট কোড দেখায় না।

            **পরবর্তী পদক্ষেপ:**
            1. **Tools → AI Provider Keys**-এ গিয়ে ফ্রি-টিয়ার API কী যোগ করুন (অথবা অনলাইনে থাকুন)।
            2. অথবা **Studios → Code Builder** ব্যবহার করে মাল্টি-ফাইল প্রজেক্ট তৈরি ও ZIP ডাউনলোড করুন।
            """.trimIndent()
        } else {
            """
            ⚠️ **Live Code Generation Unavailable (Offline / No Provider Key Configured)**

            Generating real, custom source code for **"$cleanSubject"** requires an active network connection and at least one configured LLM provider (Firebase Gemini AI, Groq, Cerebras, SambaNova, GitHub Models, or OpenRouter). Ayra AI does not emit canned static templates in place of real AI synthesis.

            **Next Steps:**
            1. Add a free-tier API key in **Tools → AI Provider Keys** (or reconnect to the internet).
            2. Or open **Studios → Code Builder** when online to scaffold, edit, and ZIP complete projects.
            """.trimIndent()
        }

        return AiResponse(
            text = responseText,
            craftType = null,
            craftPayload = null,
            modelUsed = "Offline Status"
        )
    }
}
