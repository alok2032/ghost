package com.ayra.ai.app.ai.securityresearch

import android.content.Context
import com.ayra.ai.app.ai.AiResponse
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.security.CyberTerminalEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Defensive Security Research Engine.
 * Specialized in authorized defensive security education, live CVE mitigation lookup,
 * OWASP secure code review, threat modeling, and resilient systems architecture.
 */
class SecurityResearchEngine(private val context: Context? = null) {

    private val externalProviders by lazy { ExternalAiProviders(context) }

    companion object {
        const val SAFE_DEFENSIVE_PROMPT =
            """You are Ayra Defensive Security Research Engine.
Analyze systems architecture, OWASP vulnerability mitigation, CVE remediation, cryptography, and network defense with technical accuracy.
Focus strictly on defensive security, authorized code hardening, and educational principles. Refuse requests for malware development, weaponized exploit payloads, unauthorized intrusion steps, or private data theft."""
    }

    suspend fun buildSecurityGrounding(prompt: String): String = withContext(Dispatchers.IO) {
        val blocks = mutableListOf<String>()
        val cveMatches = Regex("CVE-\\d{4}-\\d{4,7}", RegexOption.IGNORE_CASE)
            .findAll(prompt)
            .map { it.value.uppercase() }
            .distinct()
            .take(2)
            .toList()

        if (externalProviders.allowExternalWikiGrounding) {
            for (cveId in cveMatches) {
                val cveReport = withTimeoutOrNull(1800L) {
                    try {
                        CyberTerminalEngine.executeCommand("cve $cveId", context).output
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        ""
                    }
                }.orEmpty()
                if (cveReport.isNotBlank()) {
                    blocks.add("[LIVE CVE INTEL: $cveId]\n${cveReport.take(1400)}")
                }
            }
        }

        val lower = prompt.lowercase()
        if (lower.contains("owasp") || lower.contains("sqli") || lower.contains("xss") ||
            lower.contains("ssrf") || lower.contains("csrf") || lower.contains("injection")
        ) {
            val owaspRef = runCatching { CyberTerminalEngine.executeCommand("owasp", context).output }.getOrDefault("")
            if (owaspRef.isNotBlank()) {
                blocks.add("[OWASP SECURITY REFERENCE]\n${owaspRef.take(1200)}")
            }
        }

        blocks.joinToString("\n\n")
    }

    suspend fun analyzeSecurityQuery(prompt: String): AiResponse = withContext(Dispatchers.IO) {
        val grounding = buildSecurityGrounding(prompt)
        val systemPrompt = buildString {
            append(SAFE_DEFENSIVE_PROMPT)
            if (grounding.isNotBlank()) {
                append("\n\n")
                append(grounding)
            }
        }

        val resp = try {
            ProviderPool(context, externalProviders).executeSync(
                ChatRequest(
                    prompt = prompt,
                    mode = ProviderPoolMode.BEST,
                    systemPromptOverride = systemPrompt
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            externalProviders.generateChatResponse(
                prompt = prompt,
                systemPrompt = systemPrompt,
                withSearchGrounding = false
            )
        }

        AiResponse(
            text = resp.text,
            modelUsed = "Security Research (${resp.modelUsed}) 🛡️"
        )
    }
}
