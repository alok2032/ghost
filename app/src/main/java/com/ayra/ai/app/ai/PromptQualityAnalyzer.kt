package com.ayra.ai.app.ai

import android.content.Context
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deterministic rubric evaluator & structured prompt compiler for the Prompt Engineering Center,
 * plus optional real Multi-AI prompt enhancement & critique via [ProviderPool].
 */
object PromptQualityAnalyzer {

    data class DimensionScore(
        val name: String,
        val score: Int,
        val maxScore: Int,
        val note: String
    )

    data class QualityReport(
        val overallScore: Int,
        val grade: String,
        val dimensions: List<DimensionScore>,
        val suggestions: List<String>,
        val variablesDetected: List<String>
    )

    fun compileStructuredPrompt(
        role: String,
        objective: String,
        context: String,
        inputData: String,
        requirements: String,
        constraints: String,
        outputFormat: String,
        validationCriteria: String
    ): String = buildString {
        if (role.isNotBlank()) append("### Role\n").append(role.trim()).append("\n\n")
        if (objective.isNotBlank()) append("### Objective\n").append(objective.trim()).append("\n\n")
        if (context.isNotBlank()) append("### Context\n").append(context.trim()).append("\n\n")
        if (inputData.isNotBlank()) append("### Input\n").append(inputData.trim()).append("\n\n")
        if (requirements.isNotBlank()) append("### Requirements\n").append(requirements.trim()).append("\n\n")
        if (constraints.isNotBlank()) append("### Constraints & Guardrails\n").append(constraints.trim()).append("\n\n")
        if (outputFormat.isNotBlank()) append("### Output Format\n").append(outputFormat.trim()).append("\n\n")
        if (validationCriteria.isNotBlank()) append("### Validation Criteria\n").append(validationCriteria.trim()).append("\n\n")
    }.trim()

    fun analyze(prompt: String): QualityReport {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) {
            return QualityReport(
                overallScore = 0,
                grade = "Empty",
                dimensions = emptyList(),
                suggestions = listOf("Enter a prompt or fill out the structured builder fields."),
                variablesDetected = emptyList()
            )
        }

        val lower = trimmed.lowercase()
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }

        // 1. Clarity & Specificity (0..25)
        val clarityScore = when {
            words.size >= 35 -> 22
            words.size >= 18 -> 17
            words.size >= 8 -> 12
            else -> 6
        } + if (trimmed.contains("\n") || trimmed.contains(":")) 3 else 0

        // 2. Role & Context Grounding (0..25)
        val hasRole = Regex("\\b(act as|you are|role|persona|expert|engineer|architect|analyst)\\b").containsMatchIn(lower) ||
            lower.contains("### role") || lower.contains("ভূমিকা")
        val hasContext = Regex("\\b(context|background|audience|scenario|assuming|given that|environment)\\b").containsMatchIn(lower) ||
            lower.contains("### context")
        val roleContextScore = (if (hasRole) 14 else 4) + (if (hasContext) 11 else 3)

        // 3. Constraints & Edge Cases (0..25)
        val hasConstraints = Regex("\\b(do not|don't|never|must|avoid|limit|max|min|at least|without|edge case|constraint|strict)\\b").containsMatchIn(lower) ||
            lower.contains("### constraints")
        val hasCriteria = Regex("\\b(verify|validate|check|criteria|accurate|cite|truthful|test)\\b").containsMatchIn(lower)
        val constraintsScore = (if (hasConstraints) 15 else 5) + (if (hasCriteria) 10 else 3)

        // 4. Output Format & Reusability (0..25)
        val hasFormat = Regex("\\b(json|markdown|table|bullet|numbered|yaml|code|step-by-step|steps|format|schema|heading)\\b").containsMatchIn(lower) ||
            lower.contains("### output")
        val variables = Regex("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*\\}\\}|\\[([A-Z0-9_]{2,})\\]")
            .findAll(trimmed)
            .mapNotNull { it.groupValues.drop(1).firstOrNull { g -> g.isNotBlank() } }
            .distinct()
            .toList()
        val formatScore = (if (hasFormat) 18 else 6) + (if (variables.isNotEmpty()) 7 else 2)

        val dimensions = listOf(
            DimensionScore("Clarity & Specificity", clarityScore.coerceAtMost(25), 25, "${words.size} words"),
            DimensionScore("Role & Context", roleContextScore.coerceAtMost(25), 25, if (hasRole && hasContext) "Strong grounding" else "Add Role or Context"),
            DimensionScore("Constraints & Validation", constraintsScore.coerceAtMost(25), 25, if (hasConstraints) "Guardrails set" else "Specify boundaries"),
            DimensionScore("Output Format & Variables", formatScore.coerceAtMost(25), 25, if (hasFormat) "Structured output" else "Define expected format")
        )

        val overall = dimensions.sumOf { it.score }.coerceIn(0, 100)
        val grade = when {
            overall >= 88 -> "S (Production Ready)"
            overall >= 72 -> "A (Strong)"
            overall >= 55 -> "B (Good)"
            overall >= 35 -> "C (Basic)"
            else -> "D (Needs Structure)"
        }

        val suggestions = buildList {
            if (!hasRole) add("Define an explicit expert role (e.g., 'Act as a senior systems architect...').")
            if (!hasContext) add("Include audience, goal, or technical environment context.")
            if (!hasConstraints) add("Add explicit constraints and edge-case rules (what to include or avoid).")
            if (!hasFormat) add("Specify exact output structure (Markdown sections, JSON schema, table, or code blocks).")
            if (words.size < 12) add("Expand concise wording with concrete requirements so models don't guess intent.")
            if (variables.isEmpty()) add("Tip: Use {{variable_name}} placeholders to turn this into a reusable template.")
        }

        return QualityReport(
            overallScore = overall,
            grade = grade,
            dimensions = dimensions,
            suggestions = suggestions,
            variablesDetected = variables
        )
    }

    suspend fun enhanceWithAi(
        context: Context,
        draftPrompt: String,
        goal: String = "Maximize clarity, precision, constraints, and structured output"
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val pool = ProviderPool(context)
            val resp = pool.executeSync(
                ChatRequest(
                    prompt = "Rewrite and upgrade the following prompt.\nOptimization Goal: $goal\n\nOriginal Prompt:\n\"\"\"\n$draftPrompt\n\"\"\"\n\nReturn ONLY the upgraded prompt ready to copy and execute, preserving any {{variables}}.",
                    mode = ProviderPoolMode.FAST,
                    systemPromptOverride = "You are a Principal Prompt Engineer. Output ONLY the improved prompt text without preamble or meta commentary."
                )
            )
            resp.text.trim()
        }
    }

    suspend fun critiqueWithAi(
        context: Context,
        promptToAudit: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val pool = ProviderPool(context)
            val resp = pool.executeSync(
                ChatRequest(
                    prompt = "Audit this prompt for ambiguity, missing constraints, hallucination risks, and output format gaps:\n\"\"\"\n$promptToAudit\n\"\"\"",
                    mode = ProviderPoolMode.FAST,
                    systemPromptOverride = "You are a Prompt Reliability Auditor. Provide concise, actionable markdown bullet feedback plus a quick rewrite."
                )
            )
            resp.text.trim()
        }
    }
}
