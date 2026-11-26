package com.ayra.ai.app.ai.providers

import android.content.Context
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatMessage
import java.security.MessageDigest

enum class ProviderPoolMode {
    FAST,
    BEST,
    AUTO
}

sealed class AyraError(val userFriendlyMessage: String) : Exception(userFriendlyMessage) {
    data class AuthError(val provider: AiProviderType, val details: String = "") :
        AyraError("API key rejected for ${provider.displayName}. Please verify your key in Settings -> Keys.")
    data class RateLimited(val provider: AiProviderType, val retryAfterSeconds: Long? = null) :
        AyraError("Rate limit reached (429) for ${provider.displayName}.${if (retryAfterSeconds != null) " Retry in ${retryAfterSeconds}s." else " Please wait a moment."}")
    data class Timeout(val provider: AiProviderType, val details: String = "Inference timed out.") :
        AyraError("Request to ${provider.displayName} timed out. Please try again.")
    data class NoNetwork(val details: String = "No internet connection.") :
        AyraError("No internet connection. Please check your network and try again.")
    data class DnsError(val provider: AiProviderType, val details: String = "DNS or host resolution failed.") :
        AyraError("Your device is online, but ${provider.displayName} could not be reached (DNS/network route issue).${if (details.isNotBlank()) " $details" else ""}")
    data class SecurityPolicyBlocked(val reasonCode: String, val details: String = "") :
        AyraError("Request to AI endpoint was blocked by Ayra security policy ($reasonCode).${if (details.isNotBlank()) " $details" else ""}")
    data class ProviderDown(val provider: AiProviderType, val statusCode: Int, val details: String = "") :
        AyraError("${provider.displayName} is currently unavailable (HTTP $statusCode).${if (details.isNotBlank()) " $details" else ""}")
    data class ContentBlocked(val reason: String = "Safety filter triggered.") :
        AyraError("The request was blocked: $reason")
    object NoProviderConfigured :
        AyraError("No AI provider is configured. Please add an API key in Settings -> Keys to start chatting.")
}

data class ChatRequest(
    val prompt: String,
    val persona: String = "AI_ASSISTANT",
    val mode: ProviderPoolMode = ProviderPoolMode.AUTO,
    val history: List<ChatMessage> = emptyList(),
    val imageBase64: String? = null,
    val documentText: String? = null,
    val systemPromptOverride: String? = null,
    val preferredProvider: AiProviderType? = null,
    val preferredModelId: String? = null,
    val activePluginContext: String? = null,
    val activePluginNames: List<String> = emptyList(),
    val thinkingMode: String? = null,
    val workspaceContext: String? = null,
    val routingMode: AiRoutingMode = AiRoutingMode.AUTO,
    val specialistDirective: String? = null
)

sealed class AyraStreamEvent {
    data class Thinking(val model: String) : AyraStreamEvent()
    data class Token(val text: String) : AyraStreamEvent()
    data class Draft(val model: String, val text: String) : AyraStreamEvent()
    data class Complete(val response: ProviderResponse) : AyraStreamEvent()
    data class Error(val error: AyraError) : AyraStreamEvent()
}

internal data class ProviderPoolCacheEntry(
    val response: ProviderResponse,
    val timestamp: Long
)

internal object ProviderPoolQueryClassifier {
    private val timeSensitiveKeywords = listOf(
        "today", "yesterday", "tomorrow", "latest", "news", "price",
        "current", "weather", "ajke", "kal", "notun", "khobor", "2026",
        "বর্তমান", "আজ", "খবর", "সময়", "সময়", "দাম"
    )
    private val wordNowRegex = Regex("\\bnow\\b")

    private val casualGreetings = setOf(
        "hi", "hello", "hey", "hii", "hiii", "yo", "salam", "assalamualaikum",
        "good morning", "good night", "kemon acho", "kemon achis", "ki obostha",
        "কেমন আছো", "হ্যালো", "হাই", "ধন্যবাদ", "thanks", "thank you", "ok", "okay"
    )

    private val greetingRegex = Regex(
        "^(hi+|hello+|hey+|hii+|yo+|salam|namaste|good morning|good afternoon|good evening|good night|kemon acho|ki khobor|হ্যালো|হাই|ধন্যবাদ|thanks|thank you)(\\s+[a-z0-9]{1,12})?$"
    )

    fun isTimeSensitiveQuery(prompt: String): Boolean {
        val lower = prompt.lowercase()
        if (wordNowRegex.containsMatchIn(lower)) return true
        return timeSensitiveKeywords.any { lower.contains(it) }
    }

    fun isTrivialGreeting(prompt: String): Boolean {
        val clean = prompt.lowercase().trim().trimEnd('!', '.', '।')
        if (clean in casualGreetings) return true
        if (clean.contains("?") || clean.any { it.isDigit() }) return false
        return greetingRegex.matches(clean)
    }

    fun classifyTaskComplexity(request: ChatRequest): ProviderPool.TaskComplexity {
        if (isTrivialGreeting(request.prompt)) return ProviderPool.TaskComplexity.TRIVIAL
        val lower = request.prompt.lowercase()
        val hasMediaOrDoc = !request.imageBase64.isNullOrBlank() || !request.documentText.isNullOrBlank()
        val isComplexSignal = hasMediaOrDoc ||
            request.prompt.length > 260 ||
            lower.contains("architecture") || lower.contains("refactor") ||
            lower.contains("algorithm") || lower.contains("optimize") ||
            lower.contains("compare") || lower.contains("analyze") ||
            lower.contains("debug") || lower.contains("sql") ||
            lower.contains("kotlin") || lower.contains("python") ||
            lower.contains("proof") || lower.contains("research")
        return when {
            isComplexSignal -> ProviderPool.TaskComplexity.COMPLEX
            request.prompt.length <= 75 && !lower.contains("code") && !lower.contains("write") ->
                ProviderPool.TaskComplexity.STANDARD
            else -> ProviderPool.TaskComplexity.STANDARD
        }
    }

    fun computeCacheKey(context: Context?, request: ChatRequest): String? {
        val prompt = request.prompt.trim()
        if (isTimeSensitiveQuery(prompt)) return null
        if (request.history.isNotEmpty()) return null
        if (request.imageBase64 != null || request.documentText != null) return null
        if (!request.activePluginContext.isNullOrBlank()) return null

        val lang = context?.let { AyraPreferences.getPreferredLanguage(it) } ?: "Auto"
        val localOnly = context?.let { AyraPreferences.isLocalOnlyAiModeEnabled(it) } ?: false
        val privacyOn = context?.let { AyraPreferences.isPrivacyModeEnabled(it) } ?: true
        val thinking = request.thinkingMode ?: context?.let { AyraPreferences.getThinkingMode(it) } ?: "balanced"
        val raw = "$prompt|${request.persona}|${request.mode}|${request.routingMode.id}|$thinking|${request.preferredProvider?.id}|${request.preferredModelId}|$lang|$localOnly|$privacyOn|${request.systemPromptOverride.orEmpty()}|${request.workspaceContext.orEmpty()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
