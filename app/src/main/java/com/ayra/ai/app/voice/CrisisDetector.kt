package com.ayra.ai.app.voice

/**
 * Robust multilingual Crisis & Self-Harm Detector.
 * Safely intercepts self-harm and suicide crisis queries across English, Bengali, Banglish, and Hindi.
 *
 * Designed with word-boundary awareness to eliminate false positives on idioms
 * (e.g. "dead battery", "dead end", "dying of laughter", "মরার মতো ঘুম").
 */
object CrisisDetector {

    enum class CrisisRegion(val code: String, val label: String) {
        AUTO("AUTO", "Auto"),
        INDIA("IN", "India"),
        BANGLADESH("BD", "Bangladesh"),
        US_CA("US", "US & Canada"),
        UK("UK", "UK"),
        GLOBAL("GLOBAL", "Global");

        companion object {
            fun fromCode(code: String?): CrisisRegion =
                entries.firstOrNull { it.code.equals(code, ignoreCase = true) || it.name.equals(code, ignoreCase = true) }
                    ?: AUTO
        }
    }

    data class CrisisResponse(
        val isCrisis: Boolean,
        val supportiveMessage: String?
    )

    // Bengali explicit self-harm / suicidal intent patterns
    private val bengaliCrisisRegexes = listOf(
        Regex("(?:\\s|^)(?:আত্মহত্যা|আত্মঘাতী|মরে\\s*যেতে\\s*চাই|বাঁচতে\\s*চাই\\s*না|জীবন\\s*শেষ\\s*কর[\\u0980-\\u09FF]*|গলায়\\s*দড়ি|বিষ\\s*খেয়ে)(?:\\s|[।!?,.]|$)"),
        Regex("(?i)(?:\\b|\\s|^)(?:মরতে\\s*চাই|নিজেকে\\s*শেষ\\s*কর[\\u0980-\\u09FF]*|suicide|suicidal)(?:\\b|\\s|[।!?,.]|$)")
    )

    // Banglish, Romanized Hindi, & Devanagari Hindi patterns
    private val phoneticCrisisRegexes = listOf(
        Regex("(?i)\\b(?:more\\s*jete\\s*chai|morte\\s*chai|nijeke\\s*sesh|bachte\\s*chai\\s*na|khudkushi|marne\\s*ki\\s*soch|jaan\\s*dena\\s*chahta)\\b"),
        Regex("(?i)\\b(?:khatam\\s*kar\\s*lunga|mar\\s*jaunga|khud\\s*ko\\s*mar)\\b"),
        Regex("(?:आत्महत्या|खुदकुशी|मरना\\s*चाहता|मरना\\s*चाहती|जीना\\s*नहीं\\s*चाहता|जान\\s*देना\\s*चाहता)")
    )

    // English explicit crisis patterns (with word boundaries to avoid idioms)
    private val englishCrisisRegexes = listOf(
        Regex("(?i)\\b(?:kill(?:ing)?\\s+myself|end(?:ing)?\\s+my\\s+life|end(?:ing)?\\s+it\\s+all|commit\\s+suicide|want\\s+to\\s+die|suicidal\\s+(?:thoughts|ideation)|hang(?:ing)?\\s+myself|take\\s+my\\s+own\\s+life)\\b"),
        Regex("(?i)\\b(?:better\\s+off\\s+dead|don'?t\\s+want\\s+to\\s+live\\s+anymore|no\\s+reason\\s+to\\s+live)\\b")
    )

    // Idiom exclusion patterns (do not trigger crisis)
    private val idiomExclusions = listOf(
        Regex("(?i)\\b(?:dead\\s+battery|dead\\s+end|dead\\s+line|deadline|dead\\s+tired|dying\\s+of\\s+laughter|dying\\s+to\\s+see|killing\\s+time|killing\\s+it)\\b"),
        Regex("(?:মরার\\s*মতো\\s*ঘুম|হাসতে\\s*হাসতে\\s*শেষ|কাজের\\s*চাপে\\s*মরে)")
    )

    /**
     * Checks if a user's input expresses self-harm or suicide crisis.
     */
    fun evaluate(input: String, region: CrisisRegion = CrisisRegion.AUTO): CrisisResponse {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return CrisisResponse(isCrisis = false, supportiveMessage = null)
        }

        for (exclusion in idiomExclusions) {
            if (exclusion.containsMatchIn(trimmed)) {
                return CrisisResponse(isCrisis = false, supportiveMessage = null)
            }
        }

        val hasBengaliMatch = bengaliCrisisRegexes.any { it.containsMatchIn(trimmed) }
        val hasPhoneticMatch = phoneticCrisisRegexes.any { it.containsMatchIn(trimmed) }
        val hasEnglishMatch = englishCrisisRegexes.any { it.containsMatchIn(trimmed) }

        if (hasBengaliMatch || hasPhoneticMatch || hasEnglishMatch) {
            val isBengali = (hasBengaliMatch && !trimmed.equals("suicide", true)) || trimmed.any { it in '\u0980'..'\u09FF' }
            val primaryLine = when (region) {
                CrisisRegion.INDIA -> "• **India (Tele-MANAS)**: 14416 or 1800-891-4416 (24/7, Toll-Free)\n"
                CrisisRegion.BANGLADESH -> "• **Bangladesh (Kaan Pete Roi)**: +8801779554391 / +8801779554392\n"
                CrisisRegion.US_CA -> "• **US & Canada**: Call or text 988 (Suicide & Crisis Lifeline)\n"
                CrisisRegion.UK -> "• **UK**: Call 111 or text SHOUT to 85258\n"
                CrisisRegion.GLOBAL, CrisisRegion.AUTO -> ""
            }
            val message = if (isBengali) {
                "আমি আপনার কথা শুনছি এবং আপনার এই কঠিন অনুভূতিকে গুরুত্ব দিচ্ছি। আপনার জীবন অত্যন্ত মূল্যবান, এবং এই কঠিন সময়ে সাহায্য পাওয়ার জন্য সহায়তা সবসময় প্রস্তুত রয়েছে।\n\n" +
                    "অনুগ্রহ করে এখনই একজন বিশ্বস্ত মানুষ বা নিচের হেল্পলাইনে যোগাযোগ করুন:\n" +
                    primaryLine +
                    "• **Tele-MANAS (ভারত)**: 14416 অথবা 1800-891-4416 (টোল-ফ্রি, ২৪ ঘণ্টা)\n" +
                    "• **Kaan Pete Roi (বাংলাদেশ)**: +8801779554391 / +8801779554392\n" +
                    "• **US & Canada**: Call/Text 988\n" +
                    "• **আন্তর্জাতিক ও অন্যান্য অঞ্চল**: https://findahelpline.com\n\n" +
                    "আপনি একা নন। অনুগ্রহ করে এখনই কারো সাথে কথা বলুন।"
            } else {
                "I hear you, and I care about your safety. You don't have to carry this pain alone—support and caring people are available to help you right now.\n\n" +
                    "Please reach out to someone you trust or contact a free, confidential crisis helpline:\n" +
                    primaryLine +
                    "• **India (Tele-MANAS)**: 14416 or 1800-891-4416 (24/7, Toll-Free)\n" +
                    "• **Bangladesh (Kaan Pete Roi)**: +8801779554391 / +8801779554392\n" +
                    "• **US & Canada**: Call or text 988 (Suicide & Crisis Lifeline)\n" +
                    "• **UK**: Call 111 or text SHOUT to 85258\n" +
                    "• **International**: https://findahelpline.com\n\n" +
                    "Please reach out to one of these resources right away."
            }
            return CrisisResponse(isCrisis = true, supportiveMessage = message)
        }

        return CrisisResponse(isCrisis = false, supportiveMessage = null)
    }
}
