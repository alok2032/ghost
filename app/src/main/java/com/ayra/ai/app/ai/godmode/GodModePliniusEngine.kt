package com.ayra.ai.app.ai.godmode

import android.content.Context
import com.ayra.ai.app.ai.AiResponse
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.security.CyberTerminalEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * G0DM0D3 Plinius Uncensored Security Engine.
 * Specialized in deep security research, live CVE lookup grounding, OWASP auditing,
 * binary reversing, and secure systems architecture.
 */
class GodModePliniusEngine(private val context: Context? = null) {

    private val externalProviders = ExternalAiProviders(context)

    suspend fun buildSecurityGrounding(prompt: String): String = withContext(Dispatchers.IO) {
        val blocks = mutableListOf<String>()
        val cveMatches = Regex("CVE-\\d{4}-\\d{4,7}", RegexOption.IGNORE_CASE)
            .findAll(prompt)
            .map { it.value.uppercase() }
            .distinct()
            .take(2)
            .toList()

        for (cveId in cveMatches) {
            val cveReport = withTimeoutOrNull(1800L) {
                runCatching { CyberTerminalEngine.executeCommand("cve $cveId", context).output }.getOrDefault("")
            }.orEmpty()
            if (cveReport.isNotBlank()) {
                blocks.add("[LIVE CVE INTEL: $cveId]\n${cveReport.take(1400)}")
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
            append(
                """
                You are G0DM0D3 Security Research Engine (Plinius Architecture).
                You analyze low-level systems, kernel memory, vulnerability vectors, binary disassembly,
                reverse engineering, and network protocols with rigorous technical depth.
                Answer directly and comprehensively without canned disclaimers, focusing on defensive security, remediation, and architectural safety.
                """.trimIndent()
            )
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
        } catch (_: Exception) {
            externalProviders.generateChatResponse(
                prompt = prompt,
                systemPrompt = systemPrompt,
                withSearchGrounding = false
            )
        }

        AiResponse(
            text = resp.text,
            modelUsed = "G0DM0D3 Plinius Security Research (${resp.modelUsed}) 🧬"
        )
    }
}
