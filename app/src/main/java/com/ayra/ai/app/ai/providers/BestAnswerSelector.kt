package com.ayra.ai.app.ai.providers

import com.ayra.ai.app.ai.agents.AgentDomain
import com.ayra.ai.app.ai.agents.AyraAgentOrchestrator
import java.util.Locale

data class ScoredDraft(
    val channel: AiModelChannel,
    val text: String,
    val score: Float,
    val rationale: String
)

/**
 * Deterministic multi-factor answer evaluator for Multi-AI Ensemble orchestration.
 * Ranks candidate AI responses by factual/consensus alignment, domain expertise fit,
 * structural completeness (code blocks, equations, citations), and language match
 * so the user always receives the highest-quality verified answer instead of an arbitrary
 * or longest string.
 */
object BestAnswerSelector {

    private val hardRefusalOrErrorMarkers = listOf(
        "as an ai language model, i cannot",
        "as an ai, i cannot",
        "i don't have access to real-time",
        "please provide an api key",
        "api key is missing",
        "<!doctype html",
        "<html",
        "502 bad gateway",
        "credit balance is too low"
    )

    private val refusalOrErrorMarkers = hardRefusalOrErrorMarkers + listOf(
        "rate limit exceeded",
        "too many requests",
        "service temporarily unavailable",
        "bad gateway",
        "cloudflare",
        "internal server error",
        "model not found"
    )

    private val banglishOrBengaliCodeStopwords = setOf(
        "this", "that", "with", "from", "have", "your", "will", "about", "what",
        "when", "where", "which", "there", "their", "they", "them", "these", "those",
        "into", "also", "more", "some", "such", "than", "then", "only", "just",
        "make", "like", "very", "here", "does", "using", "used", "use"
    )

    fun isLowValueOrRefusal(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 6) return true
        val lower = trimmed.lowercase(Locale.ROOT)
        if (hardRefusalOrErrorMarkers.any { lower.contains(it) }) return true
        if (refusalOrErrorMarkers.any { lower.contains(it) } && trimmed.length < 500) return true
        return false
    }

    fun scoreCandidateDraft(
        prompt: String,
        domain: AgentDomain,
        candidateText: String,
        channel: AiModelChannel,
        peerDrafts: List<Pair<AiModelChannel, String>> = emptyList(),
        wikiContext: String = ""
    ): ScoredDraft {
        val clean = candidateText.trim()
        if (clean.isEmpty()) {
            return ScoredDraft(channel, clean, 0f, "empty")
        }

        val lower = clean.lowercase(Locale.ROOT)
        val reasons = mutableListOf<String>()
        var score = 40f

        // 1. Penalize refusals, error pages, or stub answers
        if (isLowValueOrRefusal(clean)) {
            score -= 55f
            reasons.add("refusal/stub")
        } else if (refusalOrErrorMarkers.any { lower.contains(it) }) {
            score -= 22f
            reasons.add("partial-refusal")
        }

        // 2. Domain-channel specialty fit (unbiased across providers)
        val desiredSpecialty = when (domain) {
            AgentDomain.CODING_AGENT -> "coding"
            AgentDomain.MATHEMATICS_AGENT -> "math"
            AgentDomain.RESEARCH_AGENT -> "research"
            AgentDomain.DOCUMENT_AGENT -> "document"
            AgentDomain.CYBER_DEFENSE_AGENT -> "cyber"
            AgentDomain.DATA_ANALYSIS_AGENT -> "data"
            AgentDomain.IMAGE_AGENT, AgentDomain.VIDEO_AGENT -> "creative"
            AgentDomain.EDUCATION_AGENT -> "education"
            AgentDomain.VOICE_AGENT -> "fast"
            AgentDomain.AUTOMATION_AGENT, AgentDomain.GENERAL_ASSISTANT -> "general"
        }
        if (channel.specialties.contains(desiredSpecialty)) {
            score += 14f
            reasons.add("domain-fit:$desiredSpecialty")
        }
        if (channel.isReasoning && (domain == AgentDomain.MATHEMATICS_AGENT || domain == AgentDomain.CODING_AGENT || domain == AgentDomain.CYBER_DEFENSE_AGENT || domain == AgentDomain.RESEARCH_AGENT)) {
            score += 6f
        }

        // 3. Language alignment (Bengali / Banglish / English)
        val promptHasBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val answerHasBengali = clean.any { it in '\u0980'..'\u09FF' }
        val promptIsBanglish = !promptHasBengali && AyraAgentOrchestrator.isBanglish(prompt)

        when {
            promptHasBengali && answerHasBengali -> {
                score += 15f
                reasons.add("bn-match")
            }
            promptHasBengali && !answerHasBengali -> {
                score -= 12f
                reasons.add("bn-miss")
            }
            promptIsBanglish && (answerHasBengali || AyraAgentOrchestrator.isBanglish(clean)) -> {
                score += 10f
                reasons.add("banglish-match")
            }
            !promptHasBengali && !promptIsBanglish && !answerHasBengali -> {
                score += 6f
            }
        }

        // 4. Domain structural quality & completeness
        when (domain) {
            AgentDomain.CODING_AGENT -> {
                val fenceCount = Regex("```").findAll(clean).count()
                if (fenceCount >= 2 && fenceCount % 2 == 0) {
                    score += 14f
                    reasons.add("complete-code-block")
                } else if (fenceCount % 2 == 1) {
                    score -= 10f
                    reasons.add("unclosed-code-block")
                } else if (Regex("\\b(fun|class|def|function|const|let|var|import|SELECT|public)\\b").containsMatchIn(clean)) {
                    score += 7f
                }
            }
            AgentDomain.MATHEMATICS_AGENT -> {
                val hasMathSteps = clean.contains("=") || clean.contains("∴") ||
                    Regex("(?i)(step|ধাপ|সুতরাং|therefore|\\d+\\s*[+\\-*/=]\\s*\\d+)").containsMatchIn(clean)
                if (hasMathSteps) {
                    score += 12f
                    reasons.add("step-math")
                }
            }
            AgentDomain.CYBER_DEFENSE_AGENT, AgentDomain.RESEARCH_AGENT, AgentDomain.DATA_ANALYSIS_AGENT -> {
                val hasStructure = clean.contains("\n-") || clean.contains("\n*") ||
                    clean.contains("\n1.") || clean.contains("##") || clean.contains("**")
                if (hasStructure) {
                    score += 10f
                    reasons.add("structured")
                }
            }
            else -> {
                if (clean.contains("\n-") || clean.contains("\n1.") || clean.contains("**")) {
                    score += 5f
                }
            }
        }

        // 5. Substantive depth without blindly rewarding verbose padding
        val len = clean.length
        when {
            len < 20 -> score -= 12f
            len in 20..80 -> score += 4f
            len in 81..1800 -> score += 12f
            len in 1801..5500 -> score += 10f
            else -> score += 6f
        }

        // Penalize broken abrupt truncation if text ends mid-fence
        if (clean.endsWith("```") && Regex("```").findAll(clean).count() % 2 == 1) {
            score -= 8f
        }

        // 6. Direct prompt relevance + cross-model consensus + live Wiki/Web grounding overlap
        val candidateTerms = extractSignificantTerms(clean)
        val promptTerms = extractSignificantTerms(prompt)
        if (promptTerms.isNotEmpty() && candidateTerms.isNotEmpty()) {
            val promptHits = promptTerms.count { it in candidateTerms }
            val promptBonus = ((promptHits.toFloat() / promptTerms.size.coerceAtLeast(1)) * 10f).coerceIn(0f, 10f)
            score += promptBonus
            if (promptBonus >= 5f) reasons.add("on-topic")
        }

        val referenceTerms = extractSignificantTerms(
            peerDrafts
                .filter { it.first.id != channel.id }
                .joinToString(" ") { it.second } + " " + wikiContext
        )
        if (referenceTerms.isNotEmpty() && candidateTerms.isNotEmpty()) {
            val overlapCount = candidateTerms.count { it in referenceTerms }
            val overlapRatio = overlapCount.toFloat() / candidateTerms.size.coerceAtLeast(1)
            val consensusBonus = (overlapRatio * 24f).coerceIn(0f, 16f)
            score += consensusBonus
            if (consensusBonus >= 6f) reasons.add("consensus")
        }

        if (wikiContext.isNotBlank() && clean.contains("https://")) {
            val urlRegex = Regex("https://[^\\s)\\]>\"']+")
            val wikiUrls = urlRegex.findAll(wikiContext).map { it.value.trimEnd('.', ',') }.toSet()
            if (wikiUrls.isNotEmpty()) {
                val citedGrounded = urlRegex.findAll(clean).any { it.value.trimEnd('.', ',') in wikiUrls }
                if (citedGrounded) {
                    score += 8f
                    reasons.add("grounded-citation")
                }
            }
        }

        val finalScore = if (isLowValueOrRefusal(clean)) {
            score.coerceIn(0f, 35f)
        } else {
            score.coerceIn(0f, 100f)
        }
        return ScoredDraft(
            channel = channel,
            text = clean,
            score = finalScore,
            rationale = reasons.joinToString(", ").ifBlank { "baseline" }
        )
    }

    fun rankDrafts(
        prompt: String,
        domain: AgentDomain,
        drafts: List<Pair<AiModelChannel, String>>,
        wikiContext: String = ""
    ): List<ScoredDraft> {
        if (drafts.isEmpty()) return emptyList()
        val scored = drafts.map { (ch, text) ->
            scoreCandidateDraft(
                prompt = prompt,
                domain = domain,
                candidateText = text,
                channel = ch,
                peerDrafts = drafts,
                wikiContext = wikiContext
            )
        }
        val (garbage, nonGarbage) = scored.partition { isLowValueOrRefusal(it.text) }
        return nonGarbage.sortedByDescending { it.score } + garbage.sortedByDescending { it.score }
    }

    fun selectBestDraft(
        prompt: String,
        domain: AgentDomain,
        drafts: List<Pair<AiModelChannel, String>>,
        wikiContext: String = ""
    ): ScoredDraft {
        return rankDrafts(prompt, domain, drafts, wikiContext).first()
    }

    private fun extractSignificantTerms(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        return Regex("[\\p{L}\\p{N}]{4,}")
            .findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .filterNot { it in banglishOrBengaliCodeStopwords }
            .take(120)
            .toSet()
    }
}
