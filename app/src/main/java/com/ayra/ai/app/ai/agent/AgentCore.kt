package com.ayra.ai.app.ai.agent

import android.content.Context
import androidx.compose.runtime.Immutable
import com.ayra.ai.app.ai.AiResponse
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.AyraError
import com.ayra.ai.app.ai.providers.AyraStreamEvent
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ChatTurn
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelCatalog
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.automation.ProposedAction
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class StepStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    FAILED,
    SKIPPED
}

enum class AgentExecutionStatus {
    PENDING,
    RUNNING,
    WAITING_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}

@Immutable
data class AgentCitation(
    val title: String,
    val url: String,
    val snippet: String = "",
    val publishedAt: String? = null,
    val domain: String = ""
)

@Immutable
data class AgentPlanStep(
    val id: Int,
    val title: String,
    val toolName: String? = null,
    val status: StepStatus = StepStatus.PENDING,
    val note: String = "",
    val dependsOnStepIds: List<Int> = emptyList(),
    val timeoutMs: Long = DefaultAgentCore.STEP_TIMEOUT_MS
)

data class AgentRequest(
    val prompt: String,
    val persona: String = "AI_ASSISTANT",
    val tier: String = "auto",
    val history: List<ChatMessage> = emptyList(),
    val imageBase64: String? = null,
    val documentText: String? = null,
    val documentName: String? = null,
    val sessionId: Long? = null,
    val maxSteps: Int = 12
)

sealed class AgentEvent {
    data class PlanUpdated(val steps: List<AgentPlanStep>) : AgentEvent()
    data class Status(val message: String) : AgentEvent()
    data class TextDelta(val text: String) : AgentEvent()
    data class ToolCallProposed(
        val toolName: String,
        val argsJson: String,
        val action: ProposedAction
    ) : AgentEvent()
    data class ToolResult(
        val toolName: String,
        val success: Boolean,
        val summary: String,
        val citations: List<AgentCitation> = emptyList(),
        val artifactPath: String? = null
    ) : AgentEvent()
    data class Citation(val citation: AgentCitation) : AgentEvent()
    data class Complete(
        val response: AiResponse,
        val usedTools: List<String> = emptyList(),
        val citations: List<AgentCitation> = emptyList(),
        val status: AgentExecutionStatus = AgentExecutionStatus.COMPLETED
    ) : AgentEvent()
    data class Error(val error: AyraError) : AgentEvent()
}

interface AgentCore {
    fun run(request: AgentRequest): Flow<AgentEvent>
}

/**
 * Default Plan -> Act -> Verify Agent Core.
 * - Hard-capped at max 12 steps, 25s step timeout, and max 2 retries per step.
 * - Supports bounded parallel read-only tool fan-out (max 3 concurrent) with step dependencies.
 * - Routes external/critical tool proposals through ActionConfirmationCard (`ToolCallProposed`) without claiming execution.
 * - Cancellation-safe at every async boundary.
 */
class DefaultAgentCore(
    private val context: Context,
    private val providerPool: ProviderPool = ProviderPool(context),
    private val externalProviders: ExternalAiProviders = ExternalAiProviders(context),
    private val toolRegistry: AgentToolRegistry = DefaultAgentToolRegistry(context)
) : AgentCore {

    data class ParsedToolInvocation(
        val toolName: String,
        val args: JSONObject
    )

    companion object {
        const val HARD_MAX_STEPS = 12
        const val MAX_STEP_RETRIES = 2
        const val STEP_TIMEOUT_MS = 25_000L
        const val MAX_PARALLEL_READONLY_TOOLS = 3

        private val JSON_TOOL_REGEX = Regex("\\{[\\s\\S]*?\"tool\"\\s*:\\s*\"([a-zA-Z0-9_\\-]+)\"[\\s\\S]*?\\}")

        fun shouldActivateMultiStepAgent(prompt: String, tier: String): Boolean {
            if (tier.equals("agent", ignoreCase = true)) return true
            val trimmed = prompt.trim()
            if (trimmed.startsWith("/agent ", ignoreCase = true)) return true
            val lower = trimmed.lowercase()
            val hasMultiIntentConnector = lower.contains(" and save ") ||
                lower.contains(" and create ") ||
                lower.contains(" and export ") ||
                lower.contains(" and schedule ") ||
                lower.contains(" then summarize ") ||
                lower.contains(" then email ") ||
                lower.contains(" step by step using tools") ||
                (lower.contains("search") && (lower.contains("pdf") || lower.contains("docx") || lower.contains("save") || lower.contains("memory")))
            return hasMultiIntentConnector
        }

        fun parseToolInvocationFromText(raw: String): ParsedToolInvocation? {
            val cleaned = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val candidates = buildList {
                if (cleaned.startsWith("{") && cleaned.endsWith("}")) add(cleaned)
                JSON_TOOL_REGEX.findAll(raw).forEach { add(it.value) }
            }
            for (jsonStr in candidates) {
                val parsed = runCatching { JSONObject(jsonStr) }.getOrNull() ?: continue
                val toolName = parsed.optString("tool", "").ifBlank { parsed.optString("name", "") }.trim()
                if (toolName.isNotBlank()) {
                    val args = parsed.optJSONObject("args")
                        ?: parsed.optJSONObject("arguments")
                        ?: JSONObject()
                    return ParsedToolInvocation(toolName, args)
                }
            }
            return null
        }
    }

    override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        val maxSteps = request.maxSteps.coerceIn(1, HARD_MAX_STEPS)
        val cleanPrompt = request.prompt.trim().removePrefix("/agent").trim()
        if (cleanPrompt.isEmpty()) return@flow

        val steps = mutableListOf(
            AgentPlanStep(1, "Analyze request & select tools", status = StepStatus.IN_PROGRESS)
        )
        emit(AgentEvent.PlanUpdated(steps.toList()))
        emit(AgentEvent.Status("Planning autonomous steps..."))

        val collectedObservations = StringBuilder()
        val collectedCitations = mutableListOf<AgentCitation>()
        val usedTools = mutableListOf<String>()
        var lastCreatedFile: String? = null
        var lastCraftType: String? = null
        var lastCraftPayload: String? = null

        val isLocalOnly = AyraPreferences.isLocalOnlyAiModeEnabled(context)
        val availableTools = toolRegistry.listTools().filter { tool ->
            !isLocalOnly || !tool.isExternal
        }

        val toolCatalogText = availableTools.joinToString("\n") { t ->
            "- ${t.name}: ${t.description} (args schema: ${t.parametersSchemaJson})"
        }

        val plannerSystemPrompt = """
            You are Ayra Autonomous Agent executing a bounded Plan -> Act -> Verify loop.
            Available tools:
            $toolCatalogText

            Rules:
            - Decide the next tool to call ONLY if external facts, device/workspace actions, calculations, memory, or file creation are genuinely needed.
            - To call a tool, respond ONLY with JSON: {"tool":"<tool_name>","args":{...}}
            - When you have verified enough evidence OR no more tools are needed, respond ONLY with: {"done":true,"answer":"<complete final answer citing any verified URLs>"}
            - Maximum steps allowed: $maxSteps. Do not fabricate tool outputs or URLs.
        """.trimIndent()

        var stepCount = 0
        var finalPlannedAnswer: String? = null

        while (stepCount < maxSteps) {
            currentCoroutineContext().ensureActive()
            stepCount++

            val turnPrompt = buildString {
                append("User goal: \"$cleanPrompt\"\n")
                if (!request.documentText.isNullOrBlank()) {
                    append("\nAttached document (${request.documentName ?: "doc"}):\n${request.documentText.take(4000)}\n")
                }
                if (collectedObservations.isNotBlank()) {
                    append("\nVerified tool observations so far:\n")
                    append(collectedObservations.toString().take(8000))
                }
                append("\nStep $stepCount/$maxSteps: Decide next tool call JSON or {\"done\":true,\"answer\":...}.")
            }

            val (rawModelDecision, nativeToolCalls) = decideNextStep(
                plannerSystemPrompt = plannerSystemPrompt,
                turnPrompt = turnPrompt,
                history = if (stepCount == 1) request.history else emptyList(),
                toolsArray = toolRegistry.toOpenAiToolsJsonArray(isLocalOnly)
            )

            // Support batched parallel read-only tool calls or single sequential tool invocation
            val invocations: List<ParsedToolInvocation> = if (nativeToolCalls.isNotEmpty()) {
                nativeToolCalls.map { call ->
                    val argsObj = runCatching { JSONObject(call.argumentsJson) }.getOrDefault(JSONObject())
                    ParsedToolInvocation(call.name, argsObj)
                }
            } else {
                val doneCheck = runCatching {
                    val j = JSONObject(rawModelDecision.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
                    if (j.optBoolean("done", false)) j.optString("answer", "") else null
                }.getOrNull()
                if (!doneCheck.isNullOrBlank()) {
                    finalPlannedAnswer = doneCheck
                    steps[0] = steps[0].copy(status = StepStatus.DONE)
                    emit(AgentEvent.PlanUpdated(steps.toList()))
                    break
                }
                listOfNotNull(parseToolInvocationFromText(rawModelDecision))
            }

            if (invocations.isEmpty()) {
                if (rawModelDecision.isNotBlank() && !rawModelDecision.startsWith("{")) {
                    finalPlannedAnswer = rawModelDecision
                }
                steps[0] = steps[0].copy(status = StepStatus.DONE)
                emit(AgentEvent.PlanUpdated(steps.toList()))
                break
            }

            steps[0] = steps[0].copy(status = StepStatus.DONE)

            val canParallelBatch = invocations.size > 1 && invocations.all { inv ->
                val t = toolRegistry.getTool(inv.toolName)
                t != null && t.isReadOnly && (!isLocalOnly || !t.isExternal)
            }

            if (canParallelBatch) {
                val batchSteps = invocations.mapIndexed { idx, inv ->
                    AgentPlanStep(
                        id = steps.size + idx + 1,
                        title = "Execute ${inv.toolName} (parallel)",
                        toolName = inv.toolName,
                        status = StepStatus.IN_PROGRESS,
                        dependsOnStepIds = listOf(1)
                    )
                }
                val startIdx = steps.size
                steps.addAll(batchSteps)
                emit(AgentEvent.PlanUpdated(steps.toList()))
                emit(AgentEvent.Status("Running ${invocations.size} read-only tools in bounded parallel..."))

                val sem = Semaphore(MAX_PARALLEL_READONLY_TOOLS)
                val outcomes = coroutineScope {
                    invocations.mapIndexed { offset, inv ->
                        async {
                            sem.withPermit {
                                val t = toolRegistry.getTool(inv.toolName)!!
                                inv to executeSingleToolWithTimeout(t, inv.args)
                            }
                        }
                    }.awaitAll()
                }

                for ((offset, pair) in outcomes.withIndex()) {
                    val (inv, finalOutput) = pair
                    val tool = toolRegistry.getTool(inv.toolName)!!
                    usedTools.add(tool.name)
                    steps[startIdx + offset] = steps[startIdx + offset].copy(
                        status = if (finalOutput.success) StepStatus.DONE else StepStatus.FAILED,
                        note = finalOutput.output.take(120)
                    )
                    emit(AgentEvent.PlanUpdated(steps.toList()))
                    emit(AgentEvent.ToolResult(tool.name, finalOutput.success, finalOutput.output, finalOutput.citations, finalOutput.artifactPath))
                    finalOutput.citations.forEach { cite ->
                        if (collectedCitations.none { it.url == cite.url }) {
                            collectedCitations.add(cite)
                            emit(AgentEvent.Citation(cite))
                        }
                    }
                    collectedObservations.append("[Tool ${tool.name} (${if (finalOutput.success) "OK" else "FAIL"})]:\n${finalOutput.output.take(2500)}\n\n")
                }
                continue
            }

            val invocation = invocations.first()
            val previousCompletedId = steps.lastOrNull { it.status == StepStatus.DONE }?.id ?: 1
            val stepId = steps.size + 1
            steps.add(
                AgentPlanStep(
                    id = stepId,
                    title = "Execute ${invocation.toolName}",
                    toolName = invocation.toolName,
                    status = StepStatus.IN_PROGRESS,
                    dependsOnStepIds = listOf(previousCompletedId)
                )
            )
            emit(AgentEvent.PlanUpdated(steps.toList()))
            emit(AgentEvent.Status("Running tool: ${invocation.toolName}..."))

            val tool = toolRegistry.getTool(invocation.toolName)
            if (tool == null) {
                steps[steps.lastIndex] = steps.last().copy(status = StepStatus.FAILED, note = "Unknown tool")
                emit(AgentEvent.PlanUpdated(steps.toList()))
                collectedObservations.append("Tool '${invocation.toolName}' unavailable.\n")
                continue
            }

            if (isLocalOnly && tool.isExternal) {
                steps[steps.lastIndex] = steps.last().copy(status = StepStatus.SKIPPED, note = "Blocked by Local-Only mode")
                emit(AgentEvent.PlanUpdated(steps.toList()))
                collectedObservations.append("Tool '${tool.name}' blocked because Local-Only Privacy Mode is enabled.\n")
                continue
            }

            val finalOutput = executeSingleToolWithTimeout(tool, invocation.args)

            if (finalOutput.requiresConfirmationAction != null) {
                // Never claim the action executed before explicit user confirmation
                steps[steps.lastIndex] = steps.last().copy(
                    status = StepStatus.PENDING,
                    note = "Waiting user confirmation (${tool.name})"
                )
                emit(AgentEvent.PlanUpdated(steps.toList()))
                emit(AgentEvent.Status("Waiting user confirmation for ${tool.name}..."))
                emit(AgentEvent.ToolCallProposed(tool.name, invocation.args.toString(), finalOutput.requiresConfirmationAction))
                return@flow
            }

            usedTools.add(tool.name)
            steps[steps.lastIndex] = steps.last().copy(
                status = if (finalOutput.success) StepStatus.DONE else StepStatus.FAILED,
                note = finalOutput.output.take(120)
            )
            emit(AgentEvent.PlanUpdated(steps.toList()))
            emit(AgentEvent.ToolResult(tool.name, finalOutput.success, finalOutput.output, finalOutput.citations, finalOutput.artifactPath))

            finalOutput.citations.forEach { cite ->
                if (collectedCitations.none { it.url == cite.url }) {
                    collectedCitations.add(cite)
                    emit(AgentEvent.Citation(cite))
                }
            }
            if (!finalOutput.artifactPath.isNullOrBlank()) {
                lastCreatedFile = finalOutput.artifactPath
                if (finalOutput.artifactPath.endsWith(".pdf", ignoreCase = true)) {
                    lastCraftType = "PDF_DOWNLOAD"
                    lastCraftPayload = finalOutput.artifactPath
                }
            } else if (!finalOutput.craftType.isNullOrBlank()) {
                lastCraftType = finalOutput.craftType
                lastCraftPayload = finalOutput.craftPayload
            }

            collectedObservations.append("[Tool ${tool.name} (${if (finalOutput.success) "OK" else "FAIL"})]:\n${finalOutput.output.take(2500)}\n\n")
        }

        // Verify & Synthesize final answer if not already produced with citations
        if (!finalPlannedAnswer.isNullOrBlank() && collectedObservations.isBlank()) {
            emit(AgentEvent.TextDelta(finalPlannedAnswer))
            emit(
                AgentEvent.Complete(
                    response = AiResponse(
                        text = finalPlannedAnswer,
                        craftType = lastCraftType,
                        craftPayload = lastCraftPayload,
                        modelUsed = buildModelLabel(usedTools)
                    ),
                    usedTools = usedTools,
                    citations = collectedCitations,
                    status = AgentExecutionStatus.COMPLETED
                )
            )
            return@flow
        }

        val synthPrompt = buildString {
            append("User goal: \"$cleanPrompt\"\n\n")
            if (!request.documentText.isNullOrBlank()) {
                append("Attached document (${request.documentName ?: "doc"}):\n${request.documentText.take(4000)}\n\n")
            }
            if (collectedObservations.isNotBlank()) {
                append("Verified Tool Results:\n${collectedObservations.toString().take(10000)}\n\n")
            }
            if (!finalPlannedAnswer.isNullOrBlank()) {
                append("Draft Synthesis:\n$finalPlannedAnswer\n\n")
            }
            if (lastCreatedFile != null) {
                append("Saved Artifact Path: $lastCreatedFile\n\n")
            }
            append("Provide the complete, verified final answer in the user's language, strictly grounded in the tool outputs above and citing any URLs clearly.")
        }

        val streamed = StringBuilder()
        var synthModel = buildModelLabel(usedTools)
        var synthError: AyraError? = null

        providerPool.streamChat(
            ChatRequest(
                prompt = synthPrompt,
                persona = request.persona,
                mode = ProviderPoolMode.BEST,
                history = request.history,
                imageBase64 = request.imageBase64
            )
        ).collect { ev ->
            when (ev) {
                is AyraStreamEvent.Thinking -> emit(AgentEvent.Status("Synthesizing with ${ev.model}..."))
                is AyraStreamEvent.Token -> {
                    streamed.append(ev.text)
                    emit(AgentEvent.TextDelta(ev.text))
                }
                is AyraStreamEvent.Draft -> {}
                is AyraStreamEvent.Complete -> {
                    synthModel = "${ev.response.modelUsed}${if (usedTools.isNotEmpty()) " • Agent(${usedTools.distinct().size} tools)" else ""}"
                    if (streamed.isEmpty()) streamed.append(ev.response.text)
                }
                is AyraStreamEvent.Error -> synthError = ev.error
            }
        }

        val finalText = streamed.toString().trim().ifBlank {
            finalPlannedAnswer ?: collectedObservations.toString().takeIf { it.isNotBlank() } ?: ""
        }

        if (finalText.isBlank()) {
            emit(AgentEvent.Error(synthError ?: AyraError.NoProviderConfigured))
        } else {
            val citesBlock = if (collectedCitations.isNotEmpty() && !finalText.contains("http")) {
                "\n\n### Sources\n" + collectedCitations.joinToString("\n") { "• [${it.title}](${it.url})" }
            } else ""
            emit(
                AgentEvent.Complete(
                    response = AiResponse(
                        text = finalText + citesBlock,
                        craftType = lastCraftType,
                        craftPayload = lastCraftPayload,
                        modelUsed = synthModel
                    ),
                    usedTools = usedTools,
                    citations = collectedCitations
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeSingleToolWithTimeout(
        tool: AgentTool,
        args: JSONObject
    ): AgentToolOutput {
        var output: AgentToolOutput? = null
        var retries = 0
        while (retries < MAX_STEP_RETRIES) {
            currentCoroutineContext().ensureActive()
            try {
                output = withTimeout(STEP_TIMEOUT_MS) {
                    tool.execute(args)
                }
                if (output.success || output.requiresConfirmationAction != null) break
            } catch (e: TimeoutCancellationException) {
                output = AgentToolOutput(false, "Tool '${tool.name}' timed out after ${STEP_TIMEOUT_MS}ms.")
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                output = AgentToolOutput(false, "Tool error: ${e.localizedMessage}")
            }
            retries++
        }
        return output ?: AgentToolOutput(false, "Tool returned no result.")
    }

    private fun buildModelLabel(usedTools: List<String>): String {
        return if (usedTools.isEmpty()) "Ayra Agent"
        else "Ayra Agent (${usedTools.distinct().joinToString(", ")})"
    }

    private suspend fun decideNextStep(
        plannerSystemPrompt: String,
        turnPrompt: String,
        history: List<ChatMessage>,
        toolsArray: org.json.JSONArray
    ): Pair<String, List<ExternalAiProviders.OpenAiToolCallSpec>> {
        val configured = externalProviders.getActiveConfiguredProviders()
        val openAiCompatProvider = configured.firstOrNull { p ->
            p in setOf(
                AiProviderType.OPENAI, AiProviderType.OPENROUTER, AiProviderType.GROQ,
                AiProviderType.DEEPSEEK, AiProviderType.NVIDIA_NIM, AiProviderType.GITHUB_MODELS,
                AiProviderType.LOCAL_OLLAMA
            )
        }
        if (openAiCompatProvider != null) {
            val model = ModelCatalog.defaultModelFor(openAiCompatProvider)?.id
                ?: externalProviders.getDefaultModelForProvider(openAiCompatProvider)
            val turns = externalProviders.buildConversationTurns(plannerSystemPrompt, history, turnPrompt, null)
            val res = try {
                externalProviders.callOpenAiWithTools(openAiCompatProvider, turns, model, toolsArray)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (res != null && (res.toolCalls.isNotEmpty() || res.content.isNotBlank())) {
                return res.content to res.toolCalls
            }
        }

        val resp = try {
            providerPool.executeSync(
                ChatRequest(
                    prompt = turnPrompt,
                    mode = ProviderPoolMode.FAST,
                    history = history,
                    systemPromptOverride = plannerSystemPrompt
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return (resp?.text ?: "") to emptyList()
    }
}
