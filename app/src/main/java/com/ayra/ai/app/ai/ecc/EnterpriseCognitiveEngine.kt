package com.ayra.ai.app.ai.ecc

import android.content.Context
import com.ayra.ai.app.ai.AiResponse
import com.ayra.ai.app.ai.AutonomousKnowledgeEngine
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.ai.rag.DocumentRagEngine
import com.ayra.ai.app.data.ChatDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class CognitiveContext(
    val detectedEntities: List<String>,
    val domain: String,
    val disambiguatedQuery: String
)

/**
 * Enterprise Cognitive Computing (ECC) Engine.
 * Extracts named entities (acronyms, multi-word proper nouns, quoted terms), fuses persistent
 * Room knowledge-base RAG citations + multi-layer wiki context, and routes through Multi-AI BEST quorum.
 */
class EnterpriseCognitiveEngine(private val context: Context? = null) {

    private val externalProviders = ExternalAiProviders(context)

    fun extractCognitiveContext(prompt: String): CognitiveContext {
        val entities = linkedSetOf<String>()

        // 1. Quoted terms & backtick identifiers
        Regex("[\"`']([^\"`']{2,48})[\"`']").findAll(prompt).forEach {
            entities.add(it.groupValues[1].trim())
        }
        // 2. Acronyms (API, SDK, JWT, CVE, NASA, etc.)
        Regex("\\b[A-Z]{2,8}(?:-\\d+)?\\b").findAll(prompt).forEach {
            entities.add(it.value)
        }
        // 3. Multi-word Capitalized Proper Nouns
        Regex("\\b[A-Z][a-zA-Z0-9]+(?:\\s+[A-Z][a-zA-Z0-9]+)+\\b").findAll(prompt).forEach {
            entities.add(it.value)
        }
        // 4. Single Capitalized words >= 4 chars (skipping sentence-initial filler)
        val stopWords = setOf("What", "When", "Where", "Which", "Explain", "Please", "Write", "Create", "Compare", "Analyze", "How", "Why")
        prompt.split(Regex("\\s+")).forEach { token ->
            val cleaned = token.trim('.', ',', ':', ';', '?', '!', '(', ')')
            if (cleaned.length >= 4 && cleaned[0].isUpperCase() && cleaned !in stopWords) {
                entities.add(cleaned)
            }
        }

        val domain = when {
            prompt.contains("security", true) || prompt.contains("cve", true) || prompt.contains("vulnerability", true) || prompt.contains("owasp", true) -> "Cybersecurity"
            prompt.contains("code", true) || prompt.contains("bug", true) || prompt.contains("function", true) || prompt.contains("api", true) || prompt.contains("kotlin", true) || prompt.contains("architecture", true) -> "Engineering"
            prompt.contains("business", true) || prompt.contains("revenue", true) || prompt.contains("market", true) || prompt.contains("finance", true) || prompt.contains("strategy", true) -> "Enterprise Business"
            prompt.contains("science", true) || prompt.contains("physics", true) || prompt.contains("quantum", true) || prompt.contains("biology", true) || prompt.contains("chemistry", true) -> "Scientific Research"
            prompt.contains("medical", true) || prompt.contains("clinical", true) || prompt.contains("symptom", true) -> "Medical & Health"
            else -> "General Knowledge"
        }

        return CognitiveContext(
            detectedEntities = entities.take(8),
            domain = domain,
            disambiguatedQuery = prompt.trim()
        )
    }

    suspend fun disambiguateAndExecute(prompt: String): AiResponse = withContext(Dispatchers.IO) {
        val cognitiveContext = extractCognitiveContext(prompt)
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }

        val ragContext = if (context != null) {
            runCatching {
                val dao = ChatDatabase.getDatabase(context).knowledgeDocumentDao()
                val (formatted, hits) = DocumentRagEngine(dao).buildCitedContext(prompt, topK = 4)
                if (hits.isNotEmpty()) formatted else ""
            }.getOrDefault("")
        } else ""

        val wikiContext = if (externalProviders.allowExternalWikiGrounding) {
            withTimeoutOrNull(1500L) {
                try {
                    AutonomousKnowledgeEngine.queryMultiLayerWiki(prompt, isBengali)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ""
                }
            }.orEmpty()
        } else ""

        val systemDirective = buildString {
            append("You are the ECC (Enterprise Cognitive Computing) Engine.\n")
            append("Domain: ${cognitiveContext.domain}\n")
            append("Key Entities: ${cognitiveContext.detectedEntities.joinToString(", ").ifBlank { "None detected" }}\n")
            append("Task: Provide a rigorous, factually verified, enterprise-grade response with clear reasoning and cited sources in the user's language.")
            if (ragContext.isNotBlank()) {
                append("\n\n[ENTERPRISE KNOWLEDGE BASE CITATIONS]:\n")
                append(ragContext.take(4500))
            }
            if (wikiContext.isNotBlank()) {
                append("\n\n[MULTI-LAYER WIKI GROUNDING]:\n")
                append(wikiContext.take(2200))
            }
        }

        val resp = try {
            ProviderPool(context, externalProviders).executeSync(
                ChatRequest(
                    prompt = prompt,
                    mode = ProviderPoolMode.BEST,
                    systemPromptOverride = systemDirective
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            externalProviders.generateChatResponse(
                prompt = prompt,
                systemPrompt = systemDirective,
                withSearchGrounding = externalProviders.allowExternalWikiGrounding
            )
        }

        AiResponse(
            text = resp.text,
            modelUsed = "ECC Enterprise Cognitive Computing (${resp.modelUsed}) 🌐"
        )
    }
}
