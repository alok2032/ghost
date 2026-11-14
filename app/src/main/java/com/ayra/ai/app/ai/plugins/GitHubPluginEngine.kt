package com.ayra.ai.app.ai.plugins

import android.content.Context
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.data.InstalledGitHubPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

data class ParsedGitHubRepo(
    val owner: String,
    val repo: String,
    val explicitBranch: String? = null
) {
    val fullName: String get() = "$owner/$repo"
    val htmlUrl: String get() = "https://github.com/$owner/$repo"
}

data class PluginCommand(
    val title: String,
    val prompt: String
)

data class OpenApiEndpoint(
    val serverUrl: String,
    val method: String,
    val path: String,
    val summary: String,
    val queryParams: List<String> = emptyList()
)

data class PluginInstallResult(
    val success: Boolean,
    val plugin: InstalledGitHubPlugin? = null,
    val errorMessage: String? = null,
    val userSummaryText: String = ""
)

data class ActivePluginContext(
    val systemPromptInjection: String,
    val activePluginNames: List<String>,
    val liveToolOutput: String = ""
)

data class CompatibilityReport(
    val compatibilityLevel: String,
    val runtimeRequirements: List<String>,
    val androidIntegrationMode: String,
    val notes: String
)

/**
 * Real ChatGPT-Plugin & Claude-Skill/MCP style GitHub Repository Installer & Executor.
 * - Inspects live GitHub repositories via GitHub REST API + Git Trees + Raw Content CDN.
 * - Parses SKILL.md, CLAUDE.md, ai-plugin.json, openapi.json, mcp.json, package.json, README.md & source files.
 * - Executes live public HTTPS OpenAPI GET endpoints and on-demand repo file reads.
 * - Zero dynamic binary/DEX execution (100% Google Play Policy compliant).
 */
class GitHubPluginEngine @JvmOverloads constructor(
    private val context: Context? = null,
    private val githubApiBaseUrl: String = "https://api.github.com",
    private val githubRawBaseUrl: String = "https://raw.githubusercontent.com",
    private val customHttpClient: OkHttpClient? = null,
    private val githubToken: String? = null
) {
    private val httpClient: OkHttpClient
        get() = customHttpClient ?: ExternalAiProviders.sharedFastWebClient

    companion object {
        private val GITHUB_URL_REGEX = Regex(
            "(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git|/tree/([A-Za-z0-9_./-]+)|/blob/([A-Za-z0-9_./-]+)|[/?#\\s]|$)",
            RegexOption.IGNORE_CASE
        )
        private val SLUG_REGEX = Regex(
            "(?:^|\\s)([A-Za-z0-9_.-]{1,64}/[A-Za-z0-9_.-]{1,100})(?:\\.git)?(?:\\s|$|[,.!?])"
        )

        private val BLOCKED_OWNERS = setOf(
            "topics", "explore", "marketplace", "settings", "notifications",
            "features", "pricing", "about", "orgs", "users", "search", "login", "signup"
        )

        fun parseRepoSpecifier(input: String): ParsedGitHubRepo? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null

            val urlMatch = GITHUB_URL_REGEX.find(trimmed)
            if (urlMatch != null) {
                val owner = urlMatch.groupValues[1].trim()
                val rawRepo = urlMatch.groupValues[2].trim().removeSuffix(".git")
                val branch = urlMatch.groupValues[3].ifBlank { urlMatch.groupValues[4] }
                    .substringBefore("/")
                    .ifBlank { null }
                if (owner.isNotBlank() && rawRepo.isNotBlank() && !BLOCKED_OWNERS.contains(owner.lowercase())) {
                    return ParsedGitHubRepo(owner, rawRepo, branch)
                }
            }

            // Reject generic slashes in non-repo sentences like "ui/ux" or "and/or" or URLs from non-GitHub hosts
            if (trimmed.contains("http://", ignoreCase = true) || trimmed.contains("https://", ignoreCase = true)) {
                return null
            }

            val slugMatch = SLUG_REGEX.find(trimmed)
            if (slugMatch != null) {
                val slug = slugMatch.groupValues[1].removeSuffix(".git")
                val parts = slug.split("/")
                if (parts.size == 2) {
                    val owner = parts[0]
                    val repo = parts[1]
                    val lowerPair = "${owner.lowercase()}/${repo.lowercase()}"
                    if (lowerPair !in setOf("ui/ux", "and/or", "yes/no", "on/off", "bn/en", "http/2", "tcp/ip", "read/write")) {
                        return ParsedGitHubRepo(owner, repo)
                    }
                }
            }
            return null
        }

        fun parseCommands(commandsJson: String): List<PluginCommand> {
            return try {
                val arr = JSONArray(commandsJson)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val title = obj.optString("title").trim()
                        val prompt = obj.optString("prompt").trim()
                        if (title.isNotBlank() && prompt.isNotBlank()) {
                            add(PluginCommand(title, prompt))
                        }
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseOpenApiEndpoints(openApiSpecJson: String): List<OpenApiEndpoint> {
            return try {
                val arr = JSONArray(openApiSpecJson)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val server = obj.optString("serverUrl").trim()
                        val method = obj.optString("method", "GET").uppercase()
                        val path = obj.optString("path").trim()
                        val summary = obj.optString("summary").trim()
                        val paramsArr = obj.optJSONArray("queryParams")
                        val params = mutableListOf<String>()
                        if (paramsArr != null) {
                            for (p in 0 until paramsArr.length()) {
                                val pname = paramsArr.optString(p).trim()
                                if (pname.isNotBlank()) params.add(pname)
                            }
                        }
                        if (server.isNotBlank() && path.isNotBlank()) {
                            add(OpenApiEndpoint(server, method, path, summary, params))
                        }
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseFileTree(fileTreeJson: String): List<String> {
            return try {
                val arr = JSONArray(fileTreeJson)
                buildList {
                    for (i in 0 until arr.length()) {
                        val p = arr.optString(i).trim()
                        if (p.isNotBlank()) add(p)
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun isSafePublicHttpsUrl(rawUrl: String): Boolean {
            return try {
                val uri = URI(rawUrl.trim())
                if (!uri.scheme.equals("https", ignoreCase = true)) return false
                val host = uri.host?.lowercase(Locale.ROOT) ?: return false
                if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return false
                val ipLiteral = host.removePrefix("[").removeSuffix("]")
                if (ipLiteral == "127.0.0.1" || ipLiteral == "::1" || ipLiteral == "0.0.0.0" ||
                    ipLiteral.startsWith("10.") || ipLiteral.startsWith("192.168.") || ipLiteral.startsWith("169.254.")
                ) return false
                if (ipLiteral.startsWith("172.")) {
                    val secondOctet = ipLiteral.split(".").getOrNull(1)?.toIntOrNull()
                    if (secondOctet != null && secondOctet in 16..31) return false
                }
                val addr = runCatching { InetAddress.getByName(host) }.getOrNull()
                if (addr != null && (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress)) {
                    return false
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Live-inspects any public GitHub repository, extracts its skills/OpenAPI/MCP/code context,
     * and persists it as an active plugin in Room + [GitHubPluginRegistry].
     */
    suspend fun installPlugin(repoInput: String): PluginInstallResult = withContext(Dispatchers.IO) {
        val parsed = parseRepoSpecifier(repoInput)
            ?: return@withContext PluginInstallResult(
                success = false,
                errorMessage = "Invalid GitHub repository reference. Provide a valid URL like `https://github.com/owner/repo` or `owner/repo`."
            )

        val apiBase = githubApiBaseUrl.trimEnd('/')
        val rawBase = githubRawBaseUrl.trimEnd('/')

        // 1. Fetch repository metadata from GitHub REST API
        val repoMetaResult = httpGet("$apiBase/repos/${parsed.owner}/${parsed.repo}", accept = "application/vnd.github+json")
        if (repoMetaResult.statusCode == 404) {
            return@withContext PluginInstallResult(
                success = false,
                errorMessage = "Repository `${parsed.fullName}` not found (HTTP 404). Make sure it exists and is public."
            )
        }

        var repoName = parsed.repo
        var description = ""
        var defaultBranch = parsed.explicitBranch ?: "main"
        var stars = 0
        var primaryLanguage = "Unknown"
        val topicsList = mutableListOf<String>()
        var apiRateLimited = false

        if (repoMetaResult.statusCode == 200 && repoMetaResult.body.isNotBlank()) {
            try {
                val json = JSONObject(repoMetaResult.body)
                repoName = json.optString("name", parsed.repo).ifBlank { parsed.repo }
                description = json.optString("description", "").takeIf { it != "null" } ?: ""
                if (parsed.explicitBranch == null) {
                    defaultBranch = json.optString("default_branch", "main").ifBlank { "main" }
                }
                stars = json.optInt("stargazers_count", 0)
                primaryLanguage = json.optString("language", "Unknown").takeIf { it != "null" && it.isNotBlank() } ?: "Unknown"
                val topicsArr = json.optJSONArray("topics")
                if (topicsArr != null) {
                    for (i in 0 until topicsArr.length()) {
                        val t = topicsArr.optString(i).trim()
                        if (t.isNotBlank()) topicsList.add(t)
                    }
                }
            } catch (_: Exception) {}
        } else if (repoMetaResult.statusCode == 403 || repoMetaResult.statusCode == 429) {
            apiRateLimited = true
        } else if (repoMetaResult.statusCode <= 0) {
            return@withContext PluginInstallResult(
                success = false,
                errorMessage = "Could not connect to GitHub (${repoMetaResult.error ?: "Network error"}). Please check your internet connection."
            )
        }

        // 2. Fetch recursive Git Tree (to inspect files just like Claude/ChatGPT repository scanners)
        val treePaths = mutableListOf<String>()
        if (!apiRateLimited) {
            val treeResp = httpGet(
                "$apiBase/repos/${parsed.owner}/${parsed.repo}/git/trees/$defaultBranch?recursive=1",
                accept = "application/vnd.github+json"
            )
            if (treeResp.statusCode == 200 && treeResp.body.isNotBlank()) {
                try {
                    val treeJson = JSONObject(treeResp.body)
                    val arr = treeJson.optJSONArray("tree")
                    if (arr != null) {
                        for (i in 0 until minOf(arr.length(), 400)) {
                            val item = arr.optJSONObject(i) ?: continue
                            if (item.optString("type") == "blob") {
                                val path = item.optString("path").trim()
                                if (path.isNotBlank()) treePaths.add(path)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. Select highest-signal files to download in parallel
        val candidateFiles = selectHighSignalFiles(treePaths)
        val fetchedContents = fetchFilesParallel(rawBase, parsed.owner, parsed.repo, defaultBranch, candidateFiles)

        // Fallback probe on `master` if `main` returned nothing during API rate limit
        if (fetchedContents.isEmpty() && apiRateLimited && parsed.explicitBranch == null) {
            val masterProbe = fetchFilesParallel(rawBase, parsed.owner, parsed.repo, "master", candidateFiles)
            if (masterProbe.isNotEmpty()) {
                defaultBranch = "master"
                fetchedContents.putAll(masterProbe)
            }
        }

        if (repoMetaResult.statusCode != 200 && fetchedContents.isEmpty()) {
            val detail = if (apiRateLimited) "GitHub API rate limit reached and raw files were unreachable." else "HTTP ${repoMetaResult.statusCode}"
            return@withContext PluginInstallResult(
                success = false,
                errorMessage = "Could not inspect `${parsed.fullName}` ($detail). Try again in a moment."
            )
        }

        // 4. Parse manifests (OpenAPI, ai-plugin.json, MCP, Skill files, package.json, README, code)
        val openApiEndpoints = extractOpenApiEndpoints(fetchedContents)
        val pluginType = classifyPluginType(treePaths, fetchedContents, openApiEndpoints)
        val summary = buildCapabilitiesSummary(
            repoFullName = parsed.fullName,
            description = description,
            language = primaryLanguage,
            pluginType = pluginType,
            fetchedFiles = fetchedContents,
            endpoints = openApiEndpoints,
            treePaths = treePaths
        )
        val skillContext = buildSkillPromptContext(
            repoFullName = parsed.fullName,
            repoUrl = parsed.htmlUrl,
            defaultBranch = defaultBranch,
            description = description,
            language = primaryLanguage,
            pluginType = pluginType,
            fetchedFiles = fetchedContents,
            endpoints = openApiEndpoints,
            treePaths = treePaths
        )
        val commands = buildSuggestedCommands(parsed, pluginType, fetchedContents, openApiEndpoints)

        val commandsArr = JSONArray().apply {
            commands.forEach { cmd ->
                put(JSONObject().put("title", cmd.title).put("prompt", cmd.prompt))
            }
        }
        val endpointsArr = JSONArray().apply {
            openApiEndpoints.forEach { ep ->
                put(
                    JSONObject()
                        .put("serverUrl", ep.serverUrl)
                        .put("method", ep.method)
                        .put("path", ep.path)
                        .put("summary", ep.summary)
                        .put("queryParams", JSONArray(ep.queryParams))
                )
            }
        }

        val now = System.currentTimeMillis()
        val plugin = InstalledGitHubPlugin(
            repoFullName = parsed.fullName,
            repoUrl = parsed.htmlUrl,
            name = repoName,
            owner = parsed.owner,
            description = description.ifBlank { "GitHub repository plugin (${parsed.fullName})" },
            defaultBranch = defaultBranch,
            stars = stars,
            primaryLanguage = primaryLanguage,
            pluginType = pluginType,
            topicsJson = JSONArray(topicsList).toString(),
            capabilitiesSummary = summary,
            skillPromptContext = skillContext,
            openApiSpecJson = endpointsArr.toString(),
            commandsJson = commandsArr.toString(),
            fileTreeJson = JSONArray(treePaths.take(80)).toString(),
            isEnabled = true,
            installedAt = now,
            lastSyncedAt = now
        )

        GitHubPluginRegistry.savePlugin(context, plugin)

        val responseMarkdown = buildInstallConfirmationMarkdown(plugin, fetchedContents.keys.toList(), commands, openApiEndpoints)
        PluginInstallResult(
            success = true,
            plugin = plugin,
            userSummaryText = responseMarkdown
        )
    }

    /**
     * Fetches any specific file inside an already-installed GitHub plugin repository on demand.
     */
    suspend fun fetchRepoFile(plugin: InstalledGitHubPlugin, filePath: String): String? = withContext(Dispatchers.IO) {
        val cleanPath = filePath.trim().trimStart('/')
        if (cleanPath.isBlank() || cleanPath.contains("..")) return@withContext null
        val rawBase = githubRawBaseUrl.trimEnd('/')
        val resp = httpGet("$rawBase/${plugin.repoFullName}/${plugin.defaultBranch}/$cleanPath", accept = "text/plain")
        if (resp.statusCode == 200 && resp.body.isNotBlank()) resp.body.take(16_000) else null
    }

    /**
     * Executes a real public HTTPS GET request against an OpenAPI endpoint declared by an installed plugin.
     */
    suspend fun executeOpenApiGetTool(
        endpoint: OpenApiEndpoint,
        queryParams: Map<String, String> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        val base = endpoint.serverUrl.trimEnd('/')
        val path = if (endpoint.path.startsWith("/")) endpoint.path else "/${endpoint.path}"
        val fullBase = "$base$path"
        val authCheck = com.ayra.ai.app.security.AiToolSecurityGuard.authorizeExternalToolCall(
            context = context,
            toolName = "${endpoint.method} ${endpoint.path}",
            targetUrl = fullBase
        )
        if (!authCheck.allowed || !isSafePublicHttpsUrl(fullBase)) {
            return@withContext Result.failure(
                IllegalArgumentException(authCheck.reason ?: "Blocked non-HTTPS or private network endpoint: $fullBase")
            )
        }

        val url = if (queryParams.isNotEmpty()) {
            val qs = queryParams.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val sep = if (fullBase.contains("?")) "&" else "?"
            "$fullBase$sep$qs"
        } else fullBase

        val resp = httpGet(url, accept = "application/json, text/plain, */*")
        if (resp.statusCode in 200..299 && resp.body.isNotBlank()) {
            Result.success(resp.body.take(12_000))
        } else {
            Result.failure(RuntimeException("Endpoint returned HTTP ${resp.statusCode}: ${resp.error ?: resp.body.take(200)}"))
        }
    }

    /**
     * Builds active system-prompt plugin context + optional live repo file / OpenAPI execution
     * for [ProviderPool] and [AyraOrchestrator], mirroring ChatGPT Plugins & Claude Skills.
     */
    suspend fun buildActiveContextForPrompt(
        prompt: String,
        enabledPlugins: List<InstalledGitHubPlugin>
    ): ActivePluginContext = withContext(Dispatchers.IO) {
        if (enabledPlugins.isEmpty()) {
            return@withContext ActivePluginContext("", emptyList(), "")
        }

        val lower = prompt.lowercase(Locale.ROOT)
        val promptWords = lower.split(Regex("[^a-z0-9_.-]+")).filter { it.length >= 3 }.toSet()

        // Score relevance of each enabled plugin
        val ranked = enabledPlugins.map { plugin ->
            var score = 1 // Every enabled plugin retains baseline priority so skills remain available
            val repoLower = plugin.repoFullName.lowercase(Locale.ROOT)
            val nameLower = plugin.name.lowercase(Locale.ROOT)
            if (lower.contains("@$repoLower") || lower.contains("@$nameLower") ||
                lower.contains(repoLower) || lower.contains(nameLower)
            ) {
                score += 100
            }
            if (lower.contains(plugin.primaryLanguage.lowercase(Locale.ROOT))) score += 10
            val summaryTokens = (plugin.description + " " + plugin.topicsJson).lowercase(Locale.ROOT)
            for (word in promptWords) {
                if (summaryTokens.contains(word)) score += 6
            }
            plugin to score
        }.sortedByDescending { it.second }

        val selectedPlugins = ranked.take(4).map { it.first }
        val liveToolResults = StringBuilder()

        // On-demand file read or OpenAPI endpoint invocation when user explicitly asks to inspect/run a plugin file or endpoint
        val topPlugin = selectedPlugins.first()
        val fileReadMatch = Regex("(?:read|show|open|inspect|cat|file|দেখাও|পড়ো)\\s+([A-Za-z0-9_./-]+\\.[A-Za-z0-9]{1,8})", RegexOption.IGNORE_CASE).find(prompt)
        if (fileReadMatch != null) {
            val requestedFile = fileReadMatch.groupValues[1]
            val tree = parseFileTree(topPlugin.fileTreeJson)
            val exactPath = tree.firstOrNull { it.equals(requestedFile, ignoreCase = true) || it.endsWith("/$requestedFile", ignoreCase = true) }
                ?: requestedFile
            val content = fetchRepoFile(topPlugin, exactPath)
            if (!content.isNullOrBlank()) {
                liveToolResults.append("[LIVE FILE FROM PLUGIN `${topPlugin.repoFullName}` -> `$exactPath`]:\n$content\n\n")
            }
        }

        // If user explicitly requests invoking an OpenAPI endpoint (e.g., `call /endpoint` or matching endpoint summary on OPENAPI_PLUGIN)
        for (plugin in selectedPlugins.filter { it.pluginType == "OPENAPI_PLUGIN" }) {
            val endpoints = parseOpenApiEndpoints(plugin.openApiSpecJson)
                .filter { it.method == "GET" && !it.path.contains("{") }
            val matchedEp = endpoints.firstOrNull { ep ->
                lower.contains(ep.path.lowercase(Locale.ROOT)) ||
                    (ranked.firstOrNull { it.first.repoFullName == plugin.repoFullName }?.second ?: 0) >= 100 &&
                    lower.split(" ").any { token -> token.length >= 4 && ep.summary.lowercase(Locale.ROOT).contains(token) }
            }
            if (matchedEp != null) {
                val apiResult = executeOpenApiGetTool(matchedEp)
                apiResult.onSuccess { body ->
                    liveToolResults.append("[LIVE OPENAPI TOOL RESPONSE `${matchedEp.method} ${matchedEp.serverUrl}${matchedEp.path}`]:\n$body\n\n")
                }
            }
        }

        val injection = buildString {
            append("[ACTIVE INSTALLED GITHUB PLUGINS & SKILLS (${selectedPlugins.size} enabled)]:\n")
            append("You have these real user-installed GitHub repositories active as plugins/skills. Actively apply their architectures, workflows, commands, APIs, and code patterns whenever relevant:\n\n")
            var remainingBudget = 6500
            for (plugin in selectedPlugins) {
                if (remainingBudget <= 300) break
                val rawChunk = plugin.skillPromptContext.take(minOf(2400, remainingBudget))
                val guarded = com.ayra.ai.app.security.AiToolSecurityGuard.inspectAndWrapUntrustedData(
                    context = context,
                    sourceLabel = "plugin:${plugin.repoFullName}",
                    rawContent = rawChunk
                )
                append(guarded.wrappedUntrustedContent)
                append("\n---\n")
                remainingBudget -= rawChunk.length
            }
            if (liveToolResults.isNotBlank()) {
                val guardedLive = com.ayra.ai.app.security.AiToolSecurityGuard.inspectAndWrapUntrustedData(
                    context = context,
                    sourceLabel = "plugin_live_output",
                    rawContent = liveToolResults.toString().take(3500)
                )
                append("\n").append(guardedLive.wrappedUntrustedContent)
            }
        }

        ActivePluginContext(
            systemPromptInjection = injection,
            activePluginNames = selectedPlugins.map { it.name },
            liveToolOutput = liveToolResults.toString()
        )
    }

    private fun selectHighSignalFiles(treePaths: List<String>): List<String> {
        if (treePaths.isEmpty()) {
            return listOf(
                ".well-known/ai-plugin.json",
                "openapi.json",
                "mcp.json",
                "SKILL.md",
                "CLAUDE.md",
                "README.md",
                "package.json",
                "pyproject.toml"
            )
        }

        val priorityList = linkedSetOf<String>()
        val exactTargets = listOf(
            ".well-known/ai-plugin.json", "ai-plugin.json",
            "openapi.json", "openapi.yaml", "swagger.json",
            "mcp.json", "server.json", "plugin.json",
            "SKILL.md", "CLAUDE.md", "AGENTS.md", ".cursorrules",
            "README.md", "readme.md",
            "package.json", "pyproject.toml", "Cargo.toml", "build.gradle.kts"
        )

        for (target in exactTargets) {
            val match = treePaths.firstOrNull { it.equals(target, ignoreCase = true) }
            if (match != null) priorityList.add(match)
        }

        // Nested Claude-style skill definitions (e.g. skills/*/SKILL.md)
        treePaths.filter {
            it.endsWith("SKILL.md", ignoreCase = true) ||
                (it.contains("skills/", ignoreCase = true) && it.endsWith(".md", ignoreCase = true)) ||
                (it.contains("prompts/", ignoreCase = true) && it.endsWith(".md", ignoreCase = true))
        }.take(4).forEach { priorityList.add(it) }

        // Entry source files so code tools/libraries give working API usage
        val entryCodeRegex = Regex("^(src/|lib/|app/)?(index|main|app|server|client|cli|plugin|tools)\\.(py|ts|js|kt|go|rs|java)$", RegexOption.IGNORE_CASE)
        treePaths.filter { entryCodeRegex.matches(it) }.take(2).forEach { priorityList.add(it) }

        if (priorityList.none { it.equals("README.md", ignoreCase = true) }) {
            treePaths.firstOrNull { it.endsWith("README.md", ignoreCase = true) }?.let { priorityList.add(it) }
        }

        return priorityList.take(10)
    }

    private suspend fun fetchFilesParallel(
        rawBase: String,
        owner: String,
        repo: String,
        branch: String,
        paths: List<String>
    ): MutableMap<String, String> = supervisorScope {
        val result = linkedMapOf<String, String>()
        val deferreds = paths.map { path ->
            async(Dispatchers.IO) {
                val url = "$rawBase/$owner/$repo/$branch/${path.trimStart('/')}"
                val resp = withTimeoutOrNull(4500L) { httpGet(url, accept = "text/plain") }
                if (resp != null && resp.statusCode == 200 && resp.body.isNotBlank()) {
                    path to resp.body.take(8000)
                } else null
            }
        }
        deferreds.awaitAll().filterNotNull().forEach { (p, content) ->
            result[p] = content
        }
        result
    }

    private fun extractOpenApiEndpoints(fetchedFiles: Map<String, String>): List<OpenApiEndpoint> {
        val endpoints = mutableListOf<OpenApiEndpoint>()
        val specEntry = fetchedFiles.entries.firstOrNull {
            it.key.endsWith("openapi.json", ignoreCase = true) || it.key.endsWith("swagger.json", ignoreCase = true)
        }
        if (specEntry != null) {
            try {
                val spec = JSONObject(specEntry.value)
                var serverUrl = ""
                val serversArr = spec.optJSONArray("servers")
                if (serversArr != null && serversArr.length() > 0) {
                    serverUrl = serversArr.optJSONObject(0)?.optString("url", "")?.trim() ?: ""
                } else if (spec.has("host")) {
                    val host = spec.optString("host").trim()
                    val basePath = spec.optString("basePath", "").trim()
                    if (host.isNotBlank()) serverUrl = "https://$host$basePath"
                }

                val pathsObj = spec.optJSONObject("paths")
                if (pathsObj != null && serverUrl.isNotBlank()) {
                    val pathKeys = pathsObj.keys()
                    while (pathKeys.hasNext() && endpoints.size < 12) {
                        val path = pathKeys.next()
                        val methodsObj = pathsObj.optJSONObject(path) ?: continue
                        val methodKeys = methodsObj.keys()
                        while (methodKeys.hasNext() && endpoints.size < 12) {
                            val rawMethod = methodKeys.next()
                            val method = rawMethod.uppercase(Locale.ROOT)
                            if (method !in setOf("GET", "POST", "PUT", "DELETE")) continue
                            val opObj = methodsObj.optJSONObject(rawMethod) ?: continue
                            val summary = opObj.optString("summary", opObj.optString("description", "")).take(140)
                            val paramsArr = opObj.optJSONArray("parameters")
                            val queryParams = mutableListOf<String>()
                            if (paramsArr != null) {
                                for (i in 0 until paramsArr.length()) {
                                    val pObj = paramsArr.optJSONObject(i) ?: continue
                                    if (pObj.optString("in") == "query") {
                                        val pName = pObj.optString("name").trim()
                                        if (pName.isNotBlank()) queryParams.add(pName)
                                    }
                                }
                            }
                            endpoints.add(OpenApiEndpoint(serverUrl, method, path, summary, queryParams))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Also inspect ai-plugin.json metadata if no OpenAPI JSON existed
        if (endpoints.isEmpty()) {
            val aiPluginEntry = fetchedFiles.entries.firstOrNull { it.key.endsWith("ai-plugin.json", ignoreCase = true) }
            if (aiPluginEntry != null) {
                try {
                    val json = JSONObject(aiPluginEntry.value)
                    val apiUrl = json.optJSONObject("api")?.optString("url", "")?.trim() ?: ""
                    val desc = json.optString("description_for_human", json.optString("name_for_human", ""))
                    if (apiUrl.startsWith("https://")) {
                        val uri = URI(apiUrl)
                        val base = "${uri.scheme}://${uri.host}"
                        endpoints.add(OpenApiEndpoint(base, "GET", uri.path.ifBlank { "/openapi.json" }, desc.take(120)))
                    }
                } catch (_: Exception) {}
            }
        }
        return endpoints
    }

    private fun classifyPluginType(
        treePaths: List<String>,
        fetchedFiles: Map<String, String>,
        endpoints: List<OpenApiEndpoint>
    ): String {
        val keysLower = (treePaths + fetchedFiles.keys).map { it.lowercase(Locale.ROOT) }
        return when {
            endpoints.isNotEmpty() || keysLower.any { it.endsWith("ai-plugin.json") || it.endsWith("openapi.json") || it.endsWith("openapi.yaml") } -> "OPENAPI_PLUGIN"
            keysLower.any { it.endsWith("mcp.json") } || fetchedFiles.values.any { it.contains("modelcontextprotocol", ignoreCase = true) } -> "MCP_SERVER"
            keysLower.any { it.endsWith("skill.md") || it.endsWith("claude.md") || it.endsWith("agents.md") || it.endsWith(".cursorrules") } -> "SKILL_PACK"
            keysLower.any { it.endsWith("index.html") } -> "WEB_TOOL"
            else -> "CODE_PLUGIN"
        }
    }

    private fun buildCapabilitiesSummary(
        repoFullName: String,
        description: String,
        language: String,
        pluginType: String,
        fetchedFiles: Map<String, String>,
        endpoints: List<OpenApiEndpoint>,
        treePaths: List<String>
    ): String = buildString {
        append("$repoFullName ($pluginType • $language)")
        if (description.isNotBlank()) append(" — $description")
        if (endpoints.isNotEmpty()) {
            append("\nLive OpenAPI Endpoints (${endpoints.size}): ")
            append(endpoints.take(4).joinToString(", ") { "${it.method} ${it.path}" })
        }
        val skillFiles = fetchedFiles.keys.filter { it.endsWith(".md", ignoreCase = true) }
        if (skillFiles.isNotEmpty()) {
            append("\nKnowledge/Skills: ${skillFiles.joinToString(", ")}")
        }
        if (treePaths.isNotEmpty()) {
            append("\nRepo files indexed: ${treePaths.size}")
        }
    }

    private fun buildSkillPromptContext(
        repoFullName: String,
        repoUrl: String,
        defaultBranch: String,
        description: String,
        language: String,
        pluginType: String,
        fetchedFiles: Map<String, String>,
        endpoints: List<OpenApiEndpoint>,
        treePaths: List<String>
    ): String = buildString {
        append("=== PLUGIN: $repoFullName ($pluginType | $language | branch: $defaultBranch) ===\n")
        append("URL: $repoUrl\n")
        if (description.isNotBlank()) append("Description: $description\n")

        if (endpoints.isNotEmpty()) {
            append("OpenAPI Endpoints:\n")
            endpoints.take(8).forEach { ep ->
                append("- ${ep.method} ${ep.serverUrl}${ep.path}: ${ep.summary}\n")
            }
        }

        if (treePaths.isNotEmpty()) {
            append("Key Files: ${treePaths.take(35).joinToString(", ")}\n")
        }

        var remaining = 9000
        // Prioritize SKILL.md / CLAUDE.md / ai-plugin.json / mcp.json / package.json before README & code
        val orderedEntries = fetchedFiles.entries.sortedBy { (path, _) ->
            val p = path.lowercase(Locale.ROOT)
            when {
                p.endsWith("skill.md") -> 0
                p.endsWith("claude.md") || p.endsWith("agents.md") -> 1
                p.endsWith("ai-plugin.json") || p.endsWith("mcp.json") -> 2
                p.endsWith("package.json") || p.endsWith("pyproject.toml") -> 3
                p.endsWith("readme.md") -> 4
                else -> 5
            }
        }

        for ((path, content) in orderedEntries) {
            if (remaining <= 250) break
            val snippet = content.trim().take(minOf(2600, remaining))
            append("\n--- File: $path ---\n")
            append(snippet)
            append("\n")
            remaining -= snippet.length
        }
    }

    private fun buildSuggestedCommands(
        parsed: ParsedGitHubRepo,
        pluginType: String,
        fetchedFiles: Map<String, String>,
        endpoints: List<OpenApiEndpoint>
    ): List<PluginCommand> {
        val list = mutableListOf<PluginCommand>()

        when (pluginType) {
            "SKILL_PACK" -> {
                list.add(
                    PluginCommand(
                        title = "Apply Skill to Task ⚡",
                        prompt = "Use installed plugin @${parsed.fullName} workflow step-by-step for my project: explain and apply its core methodology."
                    )
                )
            }
            "OPENAPI_PLUGIN" -> {
                val firstEp = endpoints.firstOrNull { it.method == "GET" && !it.path.contains("{") }
                if (firstEp != null) {
                    list.add(
                        PluginCommand(
                            title = "Call ${firstEp.path} 🌐",
                            prompt = "Run @${parsed.fullName} endpoint ${firstEp.path} and present the live results clearly."
                        )
                    )
                }
            }
            "MCP_SERVER" -> {
                list.add(
                    PluginCommand(
                        title = "Use MCP Tools 🛠️",
                        prompt = "List and demonstrate every tool, schema, and command exposed by @${parsed.fullName} with working examples."
                    )
                )
            }
        }

        list.add(
            PluginCommand(
                title = "Build / Write Code with ${parsed.repo} 💻",
                prompt = "Using @${parsed.fullName}, write complete working implementation code and setup instructions."
            )
        )

        val inspectableFile = fetchedFiles.keys.firstOrNull { !it.equals("README.md", ignoreCase = true) } ?: "README.md"
        list.add(
            PluginCommand(
                title = "Inspect $inspectableFile 📄",
                prompt = "Inspect @${parsed.fullName} file $inspectableFile and explain all features & APIs inside it."
            )
        )

        return list.distinctBy { it.title }.take(4)
    }

    fun evaluateAndroidCompatibility(
        treePaths: List<String>,
        fetchedFiles: Map<String, String>,
        language: String,
        description: String
    ): CompatibilityReport {
        val pathsLower = (treePaths + fetchedFiles.keys).map { it.lowercase(Locale.ROOT) }
        val combinedText = (description + " " + fetchedFiles.values.joinToString("\n")).lowercase(Locale.ROOT)
        val reqs = mutableListOf<String>()

        if (pathsLower.any { it.endsWith("requirements.txt") || it.endsWith("pyproject.toml") || it.endsWith(".py") }) {
            reqs.add("Python 3.x")
        }
        if (combinedText.contains("cuda") || combinedText.contains("torch") || combinedText.contains("diffusers") || combinedText.contains("comfy")) {
            reqs.add("GPU / PyTorch Runtime")
        }
        if (pathsLower.any { it.endsWith("package.json") }) {
            reqs.add("Node.js / JS")
        }
        if (pathsLower.any { it.endsWith("dockerfile") || it.endsWith("docker-compose.yml") }) {
            reqs.add("Docker Container")
        }

        val hasOpenApi = pathsLower.any { it.endsWith("openapi.json") || it.endsWith("openapi.yaml") || it.endsWith("ai-plugin.json") }
        val hasSkillMd = pathsLower.any { it.endsWith("skill.md") || it.endsWith("claude.md") || it.endsWith("agents.md") }
        val isComfyServer = combinedText.contains("comfyui") || pathsLower.any { it.contains("comfy") }

        return when {
            hasOpenApi -> CompatibilityReport(
                compatibilityLevel = "DIRECT_IN_APP_API",
                runtimeRequirements = reqs.ifEmpty { listOf("HTTPS OpenAPI") },
                androidIntegrationMode = "Live HTTPS OpenAPI GET Invocation + AI Prompt Context",
                notes = "Public GET endpoints run natively inside Ayra over HTTPS without desktop dependencies."
            )
            hasSkillMd -> CompatibilityReport(
                compatibilityLevel = "DIRECT_SKILL_NATIVE",
                runtimeRequirements = listOf("Markdown Skill Context"),
                androidIntegrationMode = "Claude/ChatGPT Skill & Workflow Injection",
                notes = "Instructions and workflows execute directly through Ayra's multi-model AI orchestrator."
            )
            isComfyServer -> CompatibilityReport(
                compatibilityLevel = "REMOTE_SERVER_BRIDGE",
                runtimeRequirements = reqs.ifEmpty { listOf("Python + CUDA GPU Server") },
                androidIntegrationMode = "Connect via Tools > ComfyUI Server URL",
                notes = "Server-side GPU image/video pipelines cannot run natively on mobile CPUs; configure your ComfyUI LAN/cloud URL in Tools to dispatch real /prompt jobs."
            )
            reqs.any { it.contains("Python") || it.contains("GPU") || it.contains("Docker") || it.contains("Node") } -> CompatibilityReport(
                compatibilityLevel = "KNOWLEDGE_AND_CODE_ASSIST",
                runtimeRequirements = reqs,
                androidIntegrationMode = "Architecture Reference + Source Code Inspection + Code Studio Generation",
                notes = "Heavy desktop/GPU runtimes (${reqs.joinToString(", ")}) do not execute locally on Android; Ayra indexes the repo files so you can inspect code and generate compatible Kotlin/API code."
            )
            else -> CompatibilityReport(
                compatibilityLevel = "KNOWLEDGE_AND_CODE_ASSIST",
                runtimeRequirements = listOf(language.ifBlank { "Source Repository" }),
                androidIntegrationMode = "On-Demand File Inspection + AI Reasoning Context",
                notes = "Indexed repository files and docs are injected into Ayra AI prompts."
            )
        }
    }

    private fun buildInstallConfirmationMarkdown(
        plugin: InstalledGitHubPlugin,
        inspectedFiles: List<String>,
        commands: List<PluginCommand>,
        endpoints: List<OpenApiEndpoint>
    ): String = buildString {
        val compat = evaluateAndroidCompatibility(
            treePaths = parseFileTree(plugin.fileTreeJson),
            fetchedFiles = emptyMap(),
            language = plugin.primaryLanguage,
            description = plugin.description
        )
        append("### 🔌 GitHub Plugin Installed & Active: `${plugin.repoFullName}`\n\n")
        append("- **Type:** `${plugin.pluginType}` • **Language:** `${plugin.primaryLanguage}` • **Branch:** `${plugin.defaultBranch}`")
        if (plugin.stars > 0) append(" • **⭐ ${plugin.stars}**")
        append("\n")
        append("- **Android Compatibility:** `${compat.compatibilityLevel}` (${compat.androidIntegrationMode})\n")
        if (compat.notes.isNotBlank()) {
            append("- **Runtime Note:** ${compat.notes}\n")
        }
        if (plugin.description.isNotBlank()) {
            append("- **About:** ${plugin.description}\n")
        }
        if (inspectedFiles.isNotEmpty()) {
            append("- **Inspected Files:** ${inspectedFiles.joinToString(", ") { "`$it`" }}\n")
        }
        if (endpoints.isNotEmpty()) {
            append("- **Live API Endpoints Registered (${endpoints.size}):** ${endpoints.take(5).joinToString(", ") { "`${it.method} ${it.path}`" }}\n")
        }
        append("\nএই প্লাগইনটি এখন সক্রিয় (`ON`) রয়েছে। আপনি সরাসরি `@${plugin.name}` বা সাধারণ প্রশ্নে এর সব ফিচার ও কোড ব্যবহার করতে পারেন, অথবা নিচের যেকোনো **Run Feature** বাটনে ট্যাপ করুন!")
    }

    private data class HttpFetchResult(
        val statusCode: Int,
        val body: String,
        val error: String? = null
    )

    private fun httpGet(url: String, accept: String): HttpFetchResult {
        return try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", accept)
                .header("User-Agent", "Ayra-AI-Android-Plugin-Client/1.0")
            if (!githubToken.isNullOrBlank() && url.startsWith(githubApiBaseUrl.trimEnd('/'))) {
                reqBuilder.header("Authorization", "Bearer $githubToken")
            }
            httpClient.newCall(reqBuilder.get().build()).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                HttpFetchResult(resp.code, bodyStr)
            }
        } catch (e: Exception) {
            HttpFetchResult(-1, "", e.message ?: "Network exception")
        }
    }
}
