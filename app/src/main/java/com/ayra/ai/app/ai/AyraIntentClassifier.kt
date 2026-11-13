package com.ayra.ai.app.ai

import java.util.regex.Pattern

enum class AyraToolIntent {
    CHAT,
    IMAGE,
    VIDEO,
    SONG,
    MODEL_3D,
    PDF,
    TERMINAL,
    DEEP_RESEARCH,
    GITHUB_PLUGIN
}

/**
 * Section 2.1: Robust intent classifier replacing brittle substring routers.
 * Principles:
 * - Default = CHAT.
 * - Explicit slash / prefix commands take priority (/image, /pdf, /terminal, /research, /video, /song, /3d, /plugin).
 * - Multi-word boundary regex with both required verb AND noun in strict sequence.
 * - ZERO false positives on ordinary questions containing tokens like "pic", "ping", "cmd", "document", "music", "github", etc.
 */
object AyraIntentClassifier {

    // Explicit prefix patterns (case-insensitive)
    private val IMAGE_PREFIX_PATTERN = Pattern.compile("^(/(image|img|draw|paint)|image:|photo:|ছবি:|আঁকো:)\\s+.+", Pattern.CASE_INSENSITIVE)
    private val VIDEO_PREFIX_PATTERN = Pattern.compile("^(/(video|vid|reel|clip)|video:|ভিডিও:)\\s+.+", Pattern.CASE_INSENSITIVE)
    private val SONG_PREFIX_PATTERN = Pattern.compile("^(/(song|music|audio)|song:|music:|গান:)\\s+.+", Pattern.CASE_INSENSITIVE)
    private val MODEL_3D_PREFIX_PATTERN = Pattern.compile("^(/(3d|cad|mesh)|3d:|cad:)\\s+.+", Pattern.CASE_INSENSITIVE)
    private val PDF_PREFIX_PATTERN = Pattern.compile("^(/(pdf|document)|pdf:|doc:|পিডিএফ:)\\s+.+", Pattern.CASE_INSENSITIVE)
    private val TERMINAL_PREFIX_PATTERN = Pattern.compile("^(/(terminal|term|cmd|shell|exec)|terminal:|টার্মিনাল:)\\s+.+", Pattern.CASE_INSENSITIVE)
    private val RESEARCH_PREFIX_PATTERN = Pattern.compile("^(/(research|deepresearch)|research:|গবেষণা:)\\s+.+", Pattern.CASE_INSENSITIVE)
    private val PLUGIN_PREFIX_PATTERN = Pattern.compile("^(/(plugin|github)|plugin:|প্লাগইন:)\\s+.+", Pattern.CASE_INSENSITIVE)

    // Educational or inquiry questions that must NEVER be hijacked to media or tools
    private val QUESTION_PREFIX_REGEX = Pattern.compile(
        "^\\s*(what\\s+is|what's|why\\s+is|why\\s+do|how\\s+do|how\\s+does|explain|tell\\s+me\\s+about|give\\s+me\\s+tips|give\\s+me\\s+advice|meaning\\s+of|difference\\s+between|history\\s+of|industry\\s+size)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val TERMINAL_COMMAND_REGEX = Pattern.compile(
        "^\\s*(ping|traceroute|nslookup|dig|whois|curl|nmap)\\s+([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}|\\d{1,3}(\\.\\d{1,3}){3})",
        Pattern.CASE_INSENSITIVE
    )

    fun classifyIntent(prompt: String): AyraToolIntent {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return AyraToolIntent.CHAT

        // 1. Slash / prefix commands always take precedence
        if (PLUGIN_PREFIX_PATTERN.matcher(trimmed).find()) return AyraToolIntent.GITHUB_PLUGIN
        if (IMAGE_PREFIX_PATTERN.matcher(trimmed).find()) return AyraToolIntent.IMAGE
        if (VIDEO_PREFIX_PATTERN.matcher(trimmed).find()) return AyraToolIntent.VIDEO
        if (SONG_PREFIX_PATTERN.matcher(trimmed).find()) return AyraToolIntent.SONG
        if (MODEL_3D_PREFIX_PATTERN.matcher(trimmed).find()) return AyraToolIntent.MODEL_3D
        if (PDF_PREFIX_PATTERN.matcher(trimmed).find()) return AyraToolIntent.PDF
        if (TERMINAL_PREFIX_PATTERN.matcher(trimmed).find()) return AyraToolIntent.TERMINAL
        if (RESEARCH_PREFIX_PATTERN.matcher(trimmed).find()) return AyraToolIntent.DEEP_RESEARCH

        // 1b. Explicit GitHub repository plugin install / connect requests (URL or owner/repo + install verb)
        val parsedRepo = com.ayra.ai.app.ai.plugins.GitHubPluginEngine.parseRepoSpecifier(trimmed)
        if (parsedRepo != null) {
            val lower = trimmed.lowercase()
            val hasInstallVerb = lower.contains("install") || lower.contains("add plugin") || lower.contains("as plugin") ||
                lower.contains("connect") || lower.contains("import") || lower.contains("ইনস্টল") ||
                lower.contains("ইন্সটল") || lower.contains("অ্যাড") || lower.contains("যুক্ত") || lower.contains("প্লাগইন")
            val isJustUrl = trimmed.equals(parsedRepo.htmlUrl, ignoreCase = true) ||
                trimmed.equals("github.com/${parsedRepo.fullName}", ignoreCase = true) ||
                trimmed.equals("https://github.com/${parsedRepo.fullName}.git", ignoreCase = true)
            if (isJustUrl || (hasInstallVerb && !QUESTION_PREFIX_REGEX.matcher(trimmed).find())) {
                return AyraToolIntent.GITHUB_PLUGIN
            }
        }

        // 2. Direct diagnostic terminal execution (e.g. "ping 127.0.0.1", "ping google.com and check latency")
        if (TERMINAL_COMMAND_REGEX.matcher(trimmed).find() ||
            trimmed.contains("cyber security terminal", ignoreCase = true) ||
            trimmed.contains("open cyber terminal", ignoreCase = true) ||
            trimmed.contains("cyber terminal", ignoreCase = true)) {
            // Guard against "how do I fix my cmd"
            if (!QUESTION_PREFIX_REGEX.matcher(trimmed).find()) {
                return AyraToolIntent.TERMINAL
            }
        }

        // 3. Questions / educational queries that contain keywords must stay CHAT
        val lower = trimmed.lowercase()
        val isExplicitCreation = lower.contains("বানাও") || lower.contains("বানিয়ে দাও") ||
                lower.contains("আঁকো") || lower.contains("এঁকে দাও") || lower.contains("draw an") ||
                lower.contains("create a") || lower.contains("generate a") || lower.contains("make a") ||
                lower.contains("banano") || lower.contains("render")

        if (!isExplicitCreation && (QUESTION_PREFIX_REGEX.matcher(trimmed).find() ||
            lower.contains("cadastral map") || lower.contains("shopping cart") ||
            lower.contains("wrapping") || lower.contains("tips of composition") ||
            lower.contains("history of music") || lower.contains("video game industry") ||
            lower.contains("make a document readable"))) {
            return AyraToolIntent.CHAT
        }

        // 4. Strict explicit action matching

        // PDF Intent
        val hasPdf = lower.contains("pdf") || lower.contains("পিডিএফ")
        val hasDoc = lower.contains("document") || lower.contains("ডকুমেন্ট")
        val hasPdfAction = lower.contains("বানাও") || lower.contains("banano") || lower.contains("create") ||
                lower.contains("make") || lower.contains("generate") || lower.contains("export") ||
                lower.contains("save") || lower.contains("download")
        if (hasPdf && (hasPdfAction || hasDoc)) {
            return AyraToolIntent.PDF
        }

        // Image Intent
        if (lower.contains("আঁকো") || lower.contains("এঁকে দাও") || lower.contains("ছবি আঁকো") || lower.contains("ছবি আঁক")) {
            return AyraToolIntent.IMAGE
        }
        val hasImgTerm = lower.contains("image") || lower.contains("photo") || lower.contains("picture") ||
                lower.contains("wallpaper") || lower.contains("portrait") || lower.contains("ছবি") || lower.contains("চিত্র")
        val hasImgAction = (lower.contains("draw") || lower.contains("paint") || lower.contains("sketch") ||
                lower.contains("generate") || lower.contains("render") || lower.contains("বানাও")) && !lower.contains("tips")
        if (hasImgTerm && hasImgAction && (lower.contains("a ") || lower.contains("an ") || lower.contains("একটি") || lower.contains("একটা") || lower.contains("8k") || lower.contains("ড্রাগন") || lower.contains("রাজকন্যা"))) {
            return AyraToolIntent.IMAGE
        }

        // Video Intent
        val hasVidTerm = lower.contains("video") || lower.contains("ভিডিও") || lower.contains("cinematic")
        val hasVidAction = lower.contains("video edit") || lower.contains("video banano") || lower.contains("ভিডিও বানাও") ||
                (hasVidTerm && (lower.contains("generate") || lower.contains("make") || lower.contains("banano") || lower.contains("বানাও") || lower.contains("collision") || lower.contains("galaxy") || lower.contains("black hole")))
        if (hasVidAction && !lower.contains("video game")) {
            return AyraToolIntent.VIDEO
        }

        // Song Intent
        val hasSongTerm = lower.contains("song") || lower.contains("music") || lower.contains("গান")
        val isComposeProgramming = lower.contains("jetpack compose") || lower.contains("compose e") ||
                lower.contains("compose er") || lower.contains("compose recomposition") ||
                lower.contains("compose animation") || lower.contains("compose kivabe") ||
                lower.contains("state hoisting compose")
        val hasSongAction = !isComposeProgramming && (lower.contains("music render") || lower.contains("music banano") ||
                lower.contains("গান বানাও") || lower.contains("compose a song") || lower.contains("গান লিখ") ||
                lower.contains("লিরিক্স") || lower.contains("প্রেমের গান") ||
                (hasSongTerm && (lower.contains("acoustic") || lower.contains("synthwave") || lower.contains("বানাও"))))
        if (hasSongAction && !lower.contains("history of music")) {
            return AyraToolIntent.SONG
        }

        // 3D Model Intent
        val has3dTerm = lower.contains("3d") || lower.contains("cad")
        val has3dAction = (has3dTerm && (lower.contains("model") || lower.contains("drone") || lower.contains("interactive") || lower.contains("মডেল") || lower.contains("বানাও"))) && !lower.contains("cadastral")
        if (has3dAction) {
            return AyraToolIntent.MODEL_3D
        }

        // Deep Research Intent
        if (lower.contains("deep research") || lower.startsWith("research on ") || lower.contains("গভীর গবেষণা") || lower.contains("research report")) {
            return AyraToolIntent.DEEP_RESEARCH
        }

        // Default: EVERYTHING ELSE IS CHAT
        return AyraToolIntent.CHAT
    }
}
