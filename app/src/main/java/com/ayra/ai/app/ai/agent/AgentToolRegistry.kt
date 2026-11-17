package com.ayra.ai.app.ai.agent

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.ayra.ai.app.ai.AutonomousKnowledgeEngine
import com.ayra.ai.app.ai.UnifiedAiEngine
import com.ayra.ai.app.ai.memory.LongTermMemoryManager
import com.ayra.ai.app.ai.memory.MemorySaveResult
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.automation.AyraTaskScheduler
import com.ayra.ai.app.automation.ContactResolver
import com.ayra.ai.app.automation.ProposedAction
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.StorageHelper
import com.ayra.ai.app.search.TavilySearchEngine
import com.ayra.ai.app.security.AiToolSecurityGuard
import com.ayra.ai.app.security.NetworkSecurityHelper
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class AgentToolOutput(
    val success: Boolean,
    val output: String,
    val requiresConfirmationAction: ProposedAction? = null,
    val citations: List<AgentCitation> = emptyList(),
    val artifactPath: String? = null,
    val craftType: String? = null,
    val craftPayload: String? = null
)

interface AgentTool {
    val name: String
    val description: String
    val parametersSchemaJson: String
    val isExternal: Boolean
    val requiresConfirmation: Boolean
    suspend fun execute(args: JSONObject): AgentToolOutput
}

interface AgentToolRegistry {
    fun listTools(): List<AgentTool>
    fun getTool(name: String): AgentTool?
    fun toOpenAiToolsJsonArray(localOnlyMode: Boolean = false): JSONArray
}

/**
 * Complete Registry of 21 real device, web, document, code, media, and memory tools for Ayra AgentCore.
 */
class DefaultAgentToolRegistry(private val context: Context) : AgentToolRegistry {

    private val tools: Map<String, AgentTool> by lazy {
        buildList {
            add(webSearchTool())
            add(webFetchTool())
            add(calendarCreateTool())
            add(alarmSetTool())
            add(reminderScheduleTool())
            add(contactsLookupTool())
            add(callDraftTool())
            add(smsDraftTool())
            add(emailDraftTool())
            add(fileCreateTool())
            add(fileReadTool())
            add(imageGenerateTool())
            add(videoGenerateTool())
            add(songGenerateTool())
            add(model3dGenerateTool())
            add(codeRunTool())
            add(memoryReadTool())
            add(memoryWriteTool())
            add(deviceInfoTool())
            add(openAppTool())
            add(navigateMapTool())
        }.associateBy { it.name }
    }

    override fun listTools(): List<AgentTool> = tools.values.toList()

    override fun getTool(name: String): AgentTool? = tools[name.trim().lowercase(Locale.US)]

    override fun toOpenAiToolsJsonArray(localOnlyMode: Boolean): JSONArray {
        val arr = JSONArray()
        tools.values.filter { !localOnlyMode || !it.isExternal }.forEach { tool ->
            val params = runCatching { JSONObject(tool.parametersSchemaJson) }.getOrElse {
                JSONObject().put("type", "object").put("properties", JSONObject())
            }
            arr.put(
                JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", params)
                    })
                }
            )
        }
        return arr
    }

    private fun webSearchTool() = object : AgentTool {
        override val name = "web_search"
        override val description = "Search live public web or multi-layer academic/technical wiki for up-to-date facts and URLs."
        override val parametersSchemaJson = """{"type":"object","properties":{"query":{"type":"string"},"max_results":{"type":"integer"}},"required":["query"]}"""
        override val isExternal = true
        override val requiresConfirmation = false
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val q = args.optString("query", "").trim()
            if (q.isBlank()) return AgentToolOutput(false, "Missing search query.")
            val max = args.optInt("max_results", 4).coerceIn(1, 8)
            val tavilyKey = AyraPreferences.getTavilySearchKey(context)
            if (tavilyKey.isNotBlank()) {
                val res = runCatching { TavilySearchEngine(context).search(q, maxResults = max) }.getOrNull()
                if (res != null && res.results.isNotEmpty()) {
                    val cites = res.results.map {
                        val domain = runCatching { java.net.URI(it.url).host.orEmpty() }.getOrDefault("")
                        AgentCitation(it.title, it.url, it.content, domain = domain)
                    }
                    val guarded = AiToolSecurityGuard.inspectAndWrapUntrustedData(context, "tavily_search:$q", res.formattedSummary)
                    return AgentToolOutput(true, guarded.wrappedUntrustedContent, citations = cites)
                }
            }
            val wiki = runCatching {
                AutonomousKnowledgeEngine.queryMultiLayerWikiWithVectorCache(
                    context = context,
                    prompt = q,
                    isBengali = q.any { it in '\u0980'..'\u09FF' },
                    forceAllLayers = true
                )
            }.getOrNull()
            val formatted = wiki?.wikiResult?.toFormattedMarkdown().orEmpty()
            return if (formatted.isNotBlank()) {
                val cites = wiki?.wikiResult?.citations?.map { AgentCitation(it.title, it.url) }.orEmpty()
                val guarded = AiToolSecurityGuard.inspectAndWrapUntrustedData(context, "wiki:$q", formatted)
                AgentToolOutput(true, guarded.wrappedUntrustedContent, citations = cites)
            } else {
                AgentToolOutput(false, "No verified web sources found for '$q'.")
            }
        }
    }

    private fun webFetchTool() = object : AgentTool {
        override val name = "web_fetch"
        override val description = "Fetch clean text snippet from a verified public HTTPS URL (SSRF-protected)."
        override val parametersSchemaJson = """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""
        override val isExternal = true
        override val requiresConfirmation = false
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val url = args.optString("url", "").trim()
            val auth = AiToolSecurityGuard.authorizeExternalToolCall(context, name, url)
            if (!auth.allowed) {
                return AgentToolOutput(false, auth.reason ?: "URL blocked by security policy.")
            }
            return runCatching {
                val req = Request.Builder().url(url).header("Accept", "text/html,text/plain,application/json").build()
                ExternalAiProviders.sharedFastWebClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@use AgentToolOutput(false, "HTTP ${resp.code} fetching $url")
                    }
                    val raw = resp.body?.string().orEmpty()
                        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .take(4500)
                    val guarded = AiToolSecurityGuard.inspectAndWrapUntrustedData(context, url, raw)
                    AgentToolOutput(true, guarded.wrappedUntrustedContent, citations = listOf(AgentCitation(url, url, raw.take(200))))
                }
            }.getOrElse { AgentToolOutput(false, "Fetch error: ${it.localizedMessage}") }
        }
    }

    private fun calendarCreateTool() = object : AgentTool {
        override val name = "calendar_create"
        override val description = "Propose creating a Calendar event (user confirmation required via ActionGate)."
        override val parametersSchemaJson = """{"type":"object","properties":{"title":{"type":"string"},"description":{"type":"string"},"location":{"type":"string"},"offset_hours":{"type":"integer"}},"required":["title"]}"""
        override val isExternal = false
        override val requiresConfirmation = true
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val title = args.optString("title", "Ghost AI Event").trim().ifBlank { "Ghost AI Event" }
            val desc = args.optString("description", "")
            val loc = args.optString("location", "")
            val offsetHrs = args.optInt("offset_hours", 1).coerceIn(0, 720)
            val start = System.currentTimeMillis() + offsetHrs * 3_600_000L
            val action = ProposedAction.CalendarCreate(title, desc, loc, start, start + 3_600_000L)
            return AgentToolOutput(true, "Proposed Calendar event '$title' for user confirmation.", requiresConfirmationAction = action)
        }
    }

    private fun alarmSetTool() = object : AgentTool {
        override val name = "alarm_set"
        override val description = "Propose setting an Android device alarm (user confirmation required)."
        override val parametersSchemaJson = """{"type":"object","properties":{"hour":{"type":"integer"},"minute":{"type":"integer"},"label":{"type":"string"}},"required":["hour","minute"]}"""
        override val isExternal = false
        override val requiresConfirmation = true
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val hour = args.optInt("hour", 7).coerceIn(0, 23)
            val minute = args.optInt("minute", 0).coerceIn(0, 59)
            val label = args.optString("label", "Ghost AI Alarm").ifBlank { "Ghost AI Alarm" }
            val action = ProposedAction.SetAlarm(hour, minute, label)
            return AgentToolOutput(true, "Proposed alarm for ${action.formattedTime} awaiting user confirmation.", requiresConfirmationAction = action)
        }
    }

    private fun reminderScheduleTool() = object : AgentTool {
        override val name = "reminder_schedule"
        override val description = "Schedule a local reminder prompt in Ghost AI Scheduler."
        override val parametersSchemaJson = """{"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"]}"""
        override val isExternal = false
        override val requiresConfirmation = false
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val p = args.optString("prompt", "").trim()
            if (p.isBlank()) return AgentToolOutput(false, "Reminder text missing.")
            val res = AyraTaskScheduler(context).scheduleTaskFromPrompt(p)
            return AgentToolOutput(res.executed, res.feedbackMessage, craftType = res.actionType, craftPayload = res.extraData)
        }
    }

    private fun contactsLookupTool() = object : AgentTool {
        override val name = "contacts_lookup"
        override val description = "Look up a device contact name/number if Contacts permission is granted."
        override val parametersSchemaJson = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
        override val isExternal = false
        override val requiresConfirmation = false
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val q = args.optString("query", "").trim()
            if (q.isBlank()) return AgentToolOutput(false, "Missing contact query.")
            val resolved = ContactResolver.resolveContact(context, q)
            return if (resolved != null) {
                AgentToolOutput(true, "Found contact: ${resolved.name} (${resolved.phoneNumber})")
            } else {
                AgentToolOutput(false, "No matching contact found for '$q' (or Contacts permission not granted).")
            }
        }
    }

    private fun callDraftTool() = object : AgentTool {
        override val name = "call_draft"
        override val description = "Propose opening the Android phone dialer for confirmation."
        override val parametersSchemaJson = """{"type":"object","properties":{"name":{"type":"string"},"phone":{"type":"string"}},"required":["phone"]}"""
        override val isExternal = false
        override val requiresConfirmation = true
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val phone = args.optString("phone", "").trim()
            val name = args.optString("name", phone).ifBlank { phone }
            if (phone.isBlank()) return AgentToolOutput(false, "Phone number missing.")
            return AgentToolOutput(true, "Proposed dialer confirmation for $name.", requiresConfirmationAction = ProposedAction.DirectCall(name, phone))
        }
    }

    private fun smsDraftTool() = object : AgentTool {
        override val name = "sms_draft"
        override val description = "Propose opening the SMS composer for confirmation."
        override val parametersSchemaJson = """{"type":"object","properties":{"recipient":{"type":"string"},"phone":{"type":"string"},"body":{"type":"string"}},"required":["phone","body"]}"""
        override val isExternal = false
        override val requiresConfirmation = true
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val phone = args.optString("phone", "").trim()
            val name = args.optString("recipient", phone).ifBlank { phone }
            val body = args.optString("body", "").trim()
            if (phone.isBlank() || body.isBlank()) return AgentToolOutput(false, "Phone and message body are required.")
            return AgentToolOutput(true, "Proposed SMS confirmation.", requiresConfirmationAction = ProposedAction.SendSms(name, phone, body))
        }
    }

    private fun emailDraftTool() = object : AgentTool {
        override val name = "email_draft"
        override val description = "Propose opening the email composer via ACTION_SENDTO."
        override val parametersSchemaJson = """{"type":"object","properties":{"to":{"type":"string"},"subject":{"type":"string"},"body":{"type":"string"}},"required":["subject","body"]}"""
        override val isExternal = false
        override val requiresConfirmation = true
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val to = args.optString("to", "").trim()
            val subject = args.optString("subject", "Message from Ghost AI").trim()
            val body = args.optString("body", "").trim()
            return AgentToolOutput(true, "Proposed email draft confirmation.", requiresConfirmationAction = ProposedAction.ComposeEmail(to, subject, body))
        }
    }

    private fun fileCreateTool() = object : AgentTool {
        override val name = "file_create"
        override val description = "Export a real file inside app storage (.pdf, .md, .doc, .txt)."
        override val parametersSchemaJson = """{"type":"object","properties":{"title":{"type":"string"},"content":{"type":"string"},"format":{"type":"string"}},"required":["title","content"]}"""
        override val isExternal = false
        override val requiresConfirmation = false
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val title = args.optString("title", "ayra_export").ifBlank { "ayra_export" }
            val content = args.optString("content", "")
            val format = args.optString("format", "pdf").lowercase(Locale.US)
            if (content.isBlank()) return AgentToolOutput(false, "Content cannot be empty.")
            val file: File? = when (format) {
                "md", "markdown" -> StorageHelper.exportMarkdownDocument(context, title, content)
                "doc", "docx" -> StorageHelper.exportWordDocDocument(context, title, content)
                "txt" -> {
                    val f = File(File(context.filesDir, "exports").apply { mkdirs() }, "${StorageHelper.sanitizeFileName(title)}.txt")
                    f.writeText(content)
                    f
                }
                else -> StorageHelper.createRealPdf(context, title, content)
            }
            return if (file != null && file.exists()) {
                AgentToolOutput(true, "Created file: ${file.name} (${file.length()} bytes)", artifactPath = file.absolutePath)
            } else {
                AgentToolOutput(false, "Failed to write $format file.")
            }
        }
    }

    private fun fileReadTool() = object : AgentTool {
        override val name = "file_read"
        override val description = "Read an artifact inside Ghost AI's private files/exports directory or list saved workspace files."
        override val parametersSchemaJson = """{"type":"object","properties":{"filename":{"type":"string"}},"required":[]}"""
        override val isExternal = false
        override val requiresConfirmation = false
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val nameArg = args.optString("filename", "").trim()
            val artifacts = StorageHelper.listWorkspaceArtifacts(context)
            if (nameArg.isBlank()) {
                val summary = if (artifacts.isEmpty()) "No workspace artifacts found." else artifacts.take(15).joinToString("\n") { "${it.name} (${it.sizeBytes} bytes)" }
                return AgentToolOutput(true, summary)
            }
            val safeName = File(nameArg).name
            val match = artifacts.firstOrNull { it.name.equals(safeName, ignoreCase = true) }
                ?: return AgentToolOutput(false, "Artifact '$safeName' not found in workspace storage.")
            val text = runCatching {
                val file = match.file
                val ext = file.extension.lowercase(Locale.US)
                if (ext == "docx") file.inputStream().use { StorageHelper.extractDocxText(it) }
                else if (ext == "xlsx") file.inputStream().use { StorageHelper.extractXlsxText(it, file.name) }
                else if (ext == "pdf") StorageHelper.extractPdfTextTokensFromBytes(file.readBytes())
                else file.readText()
            }.getOrDefault("").take(5000)
            return if (text.isNotBlank()) AgentToolOutput(true, text) else AgentToolOutput(false, "File '$safeName' had no readable text.")
        }
    }

    private fun imageGenerateTool() = mediaDelegatedTool("image_generate", "Generate an image with Ghost AI Creative Studio.", "/image")
    private fun videoGenerateTool() = mediaDelegatedTool("video_generate", "Generate a video storyboard & frames.", "/video")
    private fun songGenerateTool() = mediaDelegatedTool("song_generate", "Compose a song melody & lyrics.", "/song")
    private fun model3dGenerateTool() = mediaDelegatedTool("model3d_generate", "Generate a 3D hologram/CAD model.", "/3d")

    private fun mediaDelegatedTool(toolName: String, desc: String, prefix: String) = object : AgentTool {
        override val name = toolName
        override val description = desc
        override val parametersSchemaJson = """{"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"]}"""
        override val isExternal = true
        override val requiresConfirmation = false
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val p = args.optString("prompt", "").trim()
            if (p.isBlank()) return AgentToolOutput(false, "Media prompt cannot be blank.")
            val resp = UnifiedAiEngine(context).generateUnifiedResponse(prompt = "$prefix $p")
            return AgentToolOutput(true, resp.text, craftType = resp.craftType, craftPayload = resp.craftPayload)
        }
    }

    private fun codeRunTool() = object : AgentTool {
        override val name = "code_run"
        override val description = "Evaluate deterministic safe math expressions or JSON transformations."
        override val parametersSchemaJson = """{"type":"object","properties":{"expression":{"type":"string"},"language":{"type":"string"}},"required":["expression"]}"""
        override val isExternal = false
        override val requiresConfirmation = false
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val expr = args.optString("expression", "").trim()
            if (expr.isBlank()) return AgentToolOutput(false, "No expression provided.")
            val eval = evaluateSafeArithmeticOrJson(expr)
            return if (eval != null) {
                AgentToolOutput(true, "Evaluated result: $eval")
            } else {
                AgentToolOutput(false, "Local deterministic sandbox evaluates numeric arithmetic and JSON syntax; arbitrary native code execution requires backend sandbox.")
            }
        }
    }

    private fun memoryReadTool() = object : AgentTool {
        override val name = "memory_read"
        override val description = "Read relevant opted-in long-term memories matching a query."
        override val parametersSchemaJson = """{"type":"object","properties":{"query":{"type":"string"}},"required":[]}"""
        override val isExternal = false
        override val requiresConfirmation = false
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            if (!AyraPreferences.isMemoryEnabled(context)) {
                return AgentToolOutput(false, "Long-Term Memory is disabled (opt-in required).")
            }
            val q = args.optString("query", "")
            val hits = LongTermMemoryManager.retrieveRelevantMemories(context, q, topK = 6)
            val txt = if (hits.isEmpty()) "No matching long-term memories." else hits.joinToString("\n") { "[${it.category}] ${it.content}" }
            return AgentToolOutput(true, txt)
        }
    }

    private fun memoryWriteTool() = object : AgentTool {
        override val name = "memory_write"
        override val description = "Save a non-sensitive user preference/fact when opt-in Memory is enabled."
        override val parametersSchemaJson = """{"type":"object","properties":{"category":{"type":"string"},"content":{"type":"string"}},"required":["content"]}"""
        override val isExternal = false
        override val requiresConfirmation = false
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val cat = args.optString("category", "GENERAL")
            val content = args.optString("content", "")
            return when (val res = LongTermMemoryManager.trySaveMemory(context, cat, content)) {
                is MemorySaveResult.Saved -> AgentToolOutput(true, "Saved memory [${res.entity.category}]: ${res.entity.content}")
                is MemorySaveResult.Rejected -> AgentToolOutput(false, res.reason)
            }
        }
    }

    private fun deviceInfoTool() = object : AgentTool {
        override val name = "device_info"
        override val description = "Inspect real Android device RAM, free storage, and database stats."
        override val parametersSchemaJson = """{"type":"object","properties":{},"required":[]}"""
        override val isExternal = false
        override val requiresConfirmation = false
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val sb = StringBuilder()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                sb.append("RAM: ${mi.availMem / (1024L * 1024L)} MB free / ${mi.totalMem / (1024L * 1024L)} MB total\n")
            }
            runCatching {
                val stat = StatFs(Environment.getDataDirectory().path)
                val freeGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
                sb.append(String.format(Locale.US, "Storage Free: %.2f GB\n", freeGb))
            }
            val sessions = runCatching { ChatDatabase.getDatabase(context).chatSessionDao().getSessionCount() }.getOrDefault(0)
            sb.append("Sessions stored locally: $sessions")
            return AgentToolOutput(true, sb.toString())
        }
    }

    private fun openAppTool() = object : AgentTool {
        override val name = "open_app"
        override val description = "Propose launching an installed Android application."
        override val parametersSchemaJson = """{"type":"object","properties":{"app_name":{"type":"string"}},"required":["app_name"]}"""
        override val isExternal = false
        override val requiresConfirmation = true
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val appName = args.optString("app_name", "").trim()
            if (appName.isBlank()) return AgentToolOutput(false, "App name required.")
            return AgentToolOutput(true, "Proposed app launch confirmation for '$appName'.", requiresConfirmationAction = ProposedAction.OpenApp(appName, null))
        }
    }

    private fun navigateMapTool() = object : AgentTool {
        override val name = "navigate_map"
        override val description = "Propose opening Maps directions for a destination."
        override val parametersSchemaJson = """{"type":"object","properties":{"destination":{"type":"string"}},"required":["destination"]}"""
        override val isExternal = false
        override val requiresConfirmation = true
        override suspend fun execute(args: JSONObject): AgentToolOutput {
            val dest = args.optString("destination", "").trim()
            if (dest.isBlank()) return AgentToolOutput(false, "Destination query required.")
            return AgentToolOutput(true, "Proposed Maps navigation to '$dest'.", requiresConfirmationAction = ProposedAction.NavigateMap(dest))
        }
    }

    private fun evaluateSafeArithmeticOrJson(expr: String): String? {
        val clean = expr.trim()
        if ((clean.startsWith("{") && clean.endsWith("}")) || (clean.startsWith("[") && clean.endsWith("]"))) {
            return runCatching {
                if (clean.startsWith("{")) JSONObject(clean).toString(2)
                else JSONArray(clean).toString(2)
            }.getOrNull()
        }
        if (!clean.matches(Regex("^[0-9.+\\-*/()\\s]+$"))) return null
        return runCatching {
            val result = SafeArithmeticParser(clean).parse()
            if (result % 1.0 == 0.0) result.toLong().toString() else result.toString()
        }.getOrNull()
    }

    private class SafeArithmeticParser(private val s: String) {
        private var pos = -1
        private var ch = 0
        private fun nextChar() { ch = if (++pos < s.length) s[pos].code else -1 }
        private fun eat(c: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == c) { nextChar(); return true }
            return false
        }
        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            if (pos < s.length) error("Unexpected char")
            return x
        }
        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                x = when {
                    eat('+'.code) -> x + parseTerm()
                    eat('-'.code) -> x - parseTerm()
                    else -> return x
                }
            }
        }
        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                x = when {
                    eat('*'.code) -> x * parseFactor()
                    eat('/'.code) -> {
                        val d = parseFactor()
                        if (d == 0.0) error("Division by zero")
                        x / d
                    }
                    else -> return x
                }
            }
        }
        private fun parseFactor(): Double {
            if (eat('+'.code)) return parseFactor()
            if (eat('-'.code)) return -parseFactor()
            val startPos = pos
            val x: Double
            if (eat('('.code)) {
                x = parseExpression()
                if (!eat(')'.code)) error("Unmatched parenthesis")
            } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                x = s.substring(startPos, pos).toDouble()
            } else error("Invalid token")
            return x
        }
    }
}
