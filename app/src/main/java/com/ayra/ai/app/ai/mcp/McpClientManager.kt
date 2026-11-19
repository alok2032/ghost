package com.ayra.ai.app.ai.mcp

import android.content.Context
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.AiToolSecurityGuard
import com.ayra.ai.app.security.AyraDiagnosticsEngine
import com.ayra.ai.app.security.AyraKeyManager
import com.ayra.ai.app.security.EncryptedPreferences
import com.ayra.ai.app.security.NetworkSecurityHelper
import com.ayra.ai.app.security.NetworkSecurityHelper.readBounded
import com.ayra.ai.app.security.SecurityAuditCategory
import com.ayra.ai.app.security.SecurityAuditLogger
import com.ayra.ai.app.security.SecurityAuditSeverity
import com.ayra.ai.app.security.UrlValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID

data class McpToolSpec(
    val name: String,
    val description: String = "",
    val inputSchemaJson: String = """{"type":"object","properties":{}}"""
)

data class McpServerConfig(
    val id: String,
    val name: String,
    val endpointUrl: String,
    val enabled: Boolean = true,
    val requireConfirmationPerTool: Boolean = true,
    val lastDiscoveredAtMs: Long = 0L,
    val lastStatus: String = "Configured (not probed yet)",
    val tools: List<McpToolSpec> = emptyList()
)

data class McpCallResult(
    val success: Boolean,
    val wrappedOutput: String,
    val rawError: String? = null,
    val latencyMs: Long = 0L
)

/**
 * Model Context Protocol (MCP) HTTP/JSON-RPC 2.0 Client Manager.
 *
 * Security Guarantees:
 * - Server registry & bearer tokens are encrypted at rest via Android Keystore KEKs.
 * - Remote HTTPS servers undergo strict public-IP & anti-DNS-rebinding SSRF validation.
 * - LAN/loopback MCP servers are permitted only when "Allow LAN Endpoints" is explicitly enabled.
 * - Remote MCP calls are blocked when "Local-Only AI Mode" is enabled.
 * - All returned tool payloads are sanitized & framed by [AiToolSecurityGuard] before entering LLM context.
 */
object McpClientManager {

    private const val PREF_SERVERS = "ayra_mcp_registry_enc"
    private const val PREF_TOKENS = "ayra_mcp_tokens_enc"
    private const val KEY_SERVERS_JSON = "mcp_servers_v1"

    private fun serverPrefs(context: Context): EncryptedPreferences = EncryptedPreferences(
        context = context,
        prefsFileName = PREF_SERVERS,
        keyAlias = AyraKeyManager.ALIAS_PREFS_KEK
    )

    private fun tokenPrefs(context: Context): EncryptedPreferences = EncryptedPreferences(
        context = context,
        prefsFileName = PREF_TOKENS,
        keyAlias = AyraKeyManager.ALIAS_MASTER_SECRETS
    )

    fun listServers(context: Context): List<McpServerConfig> {
        val raw = serverPrefs(context).getString(KEY_SERVERS_JSON, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val toolsArr = o.optJSONArray("tools") ?: JSONArray()
                    val tools = buildList {
                        for (j in 0 until toolsArr.length()) {
                            val t = toolsArr.optJSONObject(j) ?: continue
                            val n = t.optString("name", "").trim()
                            if (n.isNotBlank()) {
                                add(
                                    McpToolSpec(
                                        name = n,
                                        description = t.optString("description", ""),
                                        inputSchemaJson = t.optString("inputSchemaJson", """{"type":"object","properties":{}}""")
                                    )
                                )
                            }
                        }
                    }
                    add(
                        McpServerConfig(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            name = o.optString("name", "MCP Server"),
                            endpointUrl = o.optString("endpointUrl", ""),
                            enabled = o.optBoolean("enabled", true),
                            requireConfirmationPerTool = o.optBoolean("requireConfirmationPerTool", true),
                            lastDiscoveredAtMs = o.optLong("lastDiscoveredAtMs", 0L),
                            lastStatus = o.optString("lastStatus", "Configured"),
                            tools = tools
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveServers(context: Context, servers: List<McpServerConfig>) {
        val arr = JSONArray()
        servers.forEach { s ->
            val toolsArr = JSONArray()
            s.tools.forEach { t ->
                toolsArr.put(
                    JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("inputSchemaJson", t.inputSchemaJson)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("endpointUrl", s.endpointUrl)
                    .put("enabled", s.enabled)
                    .put("requireConfirmationPerTool", s.requireConfirmationPerTool)
                    .put("lastDiscoveredAtMs", s.lastDiscoveredAtMs)
                    .put("lastStatus", s.lastStatus)
                    .put("tools", toolsArr)
            )
        }
        serverPrefs(context).putString(KEY_SERVERS_JSON, arr.toString())
    }

    fun validateEndpoint(context: Context, rawUrl: String): Pair<UrlValidationResult, Boolean> {
        val trimmed = rawUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT)?.trimEnd('.').orEmpty()
        val isLocal = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" ||
            host.startsWith("192.168.") || host.startsWith("10.") || host.endsWith(".local")
        return if (isLocal) {
            NetworkSecurityHelper.validateLocalAiEndpointUrl(context, trimmed) to true
        } else {
            NetworkSecurityHelper.validatePublicHttpsUrl(trimmed) to false
        }
    }

    fun addOrUpdateServer(
        context: Context,
        name: String,
        endpointUrl: String,
        bearerToken: String = "",
        requireConfirmationPerTool: Boolean = true
    ): Result<McpServerConfig> {
        val cleanName = name.trim().ifBlank { "Custom MCP Server" }
        val cleanUrl = endpointUrl.trim().trimEnd('/')
        val (check, _) = validateEndpoint(context, cleanUrl)
        if (!check.allowed) {
            return Result.failure(IllegalArgumentException(check.reason ?: "Blocked MCP endpoint URL."))
        }
        val current = listServers(context).toMutableList()
        val existing = current.firstOrNull { it.endpointUrl.equals(cleanUrl, ignoreCase = true) }
        val id = existing?.id ?: ("mcp_" + UUID.randomUUID().toString().replace("-", "").take(12))
        val entry = (existing ?: McpServerConfig(id = id, name = cleanName, endpointUrl = cleanUrl)).copy(
            name = cleanName,
            endpointUrl = cleanUrl,
            enabled = true,
            requireConfirmationPerTool = requireConfirmationPerTool,
            lastStatus = "Registered • Tap Test & Discover"
        )
        current.removeAll { it.id == id }
        current.add(0, entry)
        saveServers(context, current)

        if (bearerToken.isNotBlank()) {
            tokenPrefs(context).putString("tok_$id", bearerToken.trim())
        }
        SecurityAuditLogger.log(
            context = context,
            category = SecurityAuditCategory.AI_POLICY,
            severity = SecurityAuditSeverity.INFO,
            message = "Registered MCP server '${entry.name}'"
        )
        return Result.success(entry)
    }

    fun setServerEnabled(context: Context, serverId: String, enabled: Boolean) {
        val updated = listServers(context).map { if (it.id == serverId) it.copy(enabled = enabled) else it }
        saveServers(context, updated)
    }

    fun setRequireConfirmation(context: Context, serverId: String, required: Boolean) {
        val updated = listServers(context).map {
            if (it.id == serverId) it.copy(requireConfirmationPerTool = required) else it
        }
        saveServers(context, updated)
    }

    fun revokeAndRemoveServer(context: Context, serverId: String) {
        val removed = listServers(context).filterNot { it.id == serverId }
        saveServers(context, removed)
        tokenPrefs(context).remove("tok_$serverId")
        SecurityAuditLogger.log(
            context = context,
            category = SecurityAuditCategory.AI_POLICY,
            severity = SecurityAuditSeverity.INFO,
            message = "Revoked and disconnected MCP server $serverId"
        )
    }

    suspend fun discoverTools(context: Context, serverId: String): Result<McpServerConfig> = withContext(Dispatchers.IO) {
        val server = listServers(context).firstOrNull { it.id == serverId }
            ?: return@withContext Result.failure(IllegalArgumentException("MCP server not found."))
        val (check, isLan) = validateEndpoint(context, server.endpointUrl)
        if (!check.allowed) {
            updateServerStatus(context, serverId, "Blocked: ${check.reason}", emptyList())
            return@withContext Result.failure(SecurityException(check.reason ?: "Blocked MCP URL."))
        }
        if (!isLan && AyraPreferences.isLocalOnlyAiModeEnabled(context)) {
            updateServerStatus(context, serverId, "Blocked by Local-Only AI Mode", server.tools)
            return@withContext Result.failure(SecurityException("Remote MCP servers are blocked while Local-Only AI Mode is active."))
        }

        val started = System.currentTimeMillis()
        val client = if (isLan) NetworkSecurityHelper.localLanClient else NetworkSecurityHelper.hardenedPooledClient
        val rpcBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "tools/list")
            .put("params", JSONObject())
            .toString()

        val token = tokenPrefs(context).getString("tok_$serverId", "")
        val reqBuilder = Request.Builder()
            .url(server.endpointUrl)
            .post(rpcBody.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
        if (token.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $token")
        }

        runCatching {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = "HTTP ${resp.code} ${resp.message}"
                    updateServerStatus(context, serverId, "Error: $err", emptyList())
                    error(err)
                }
                val raw = resp.readBounded()
                val json = JSONObject(raw)
                json.optJSONObject("error")?.let { rpcErr ->
                    error(rpcErr.optString("message", "MCP JSON-RPC error"))
                }
                val toolsArr = json.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
                val discovered = buildList {
                    for (i in 0 until toolsArr.length()) {
                        val t = toolsArr.optJSONObject(i) ?: continue
                        val tName = t.optString("name", "").trim()
                        if (tName.isNotBlank()) {
                            val schema = t.optJSONObject("inputSchema")?.toString()
                                ?: """{"type":"object","properties":{}}"""
                            add(McpToolSpec(tName, t.optString("description", ""), schema))
                        }
                    }
                }
                val latency = System.currentTimeMillis() - started
                AyraDiagnosticsEngine.record("MCP", "tools/list", latency, true, "${server.name}: ${discovered.size} tools")
                val updated = updateServerStatus(
                    context,
                    serverId,
                    "Connected • ${discovered.size} tool(s) (${latency}ms)",
                    discovered
                )
                updated ?: server.copy(tools = discovered)
            }
        }.onFailure { err ->
            AyraDiagnosticsEngine.record("MCP", "tools/list", System.currentTimeMillis() - started, false, err.localizedMessage ?: "error")
        }
    }

    suspend fun callTool(
        context: Context,
        serverId: String,
        toolName: String,
        arguments: JSONObject
    ): McpCallResult = withContext(Dispatchers.IO) {
        val server = listServers(context).firstOrNull { it.id == serverId }
            ?: return@withContext McpCallResult(false, "", "MCP server '$serverId' not found.")
        if (!server.enabled) {
            return@withContext McpCallResult(false, "", "MCP server '${server.name}' is disabled.")
        }
        val (check, isLan) = validateEndpoint(context, server.endpointUrl)
        if (!check.allowed) {
            return@withContext McpCallResult(false, "", check.reason ?: "Blocked MCP URL.")
        }
        if (!isLan && AyraPreferences.isLocalOnlyAiModeEnabled(context)) {
            return@withContext McpCallResult(false, "", "Remote MCP tool call blocked by Local-Only AI Mode.")
        }

        val started = System.currentTimeMillis()
        val client = if (isLan) NetworkSecurityHelper.localLanClient else NetworkSecurityHelper.hardenedPooledClient
        val rpcBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", System.currentTimeMillis())
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", toolName)
                    .put("arguments", arguments)
            )
            .toString()

        val token = tokenPrefs(context).getString("tok_$serverId", "")
        val reqBuilder = Request.Builder()
            .url(server.endpointUrl)
            .post(rpcBody.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
        if (token.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $token")
        }

        runCatching {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val latency = System.currentTimeMillis() - started
                if (!resp.isSuccessful) {
                    AyraDiagnosticsEngine.record("MCP", "tools/call:$toolName", latency, false, "HTTP ${resp.code}")
                    return@use McpCallResult(false, "", "MCP HTTP ${resp.code}", latency)
                }
                val raw = resp.readBounded()
                val json = JSONObject(raw)
                json.optJSONObject("error")?.let { rpcErr ->
                    val msg = rpcErr.optString("message", "MCP JSON-RPC error")
                    AyraDiagnosticsEngine.record("MCP", "tools/call:$toolName", latency, false, msg)
                    return@use McpCallResult(false, "", msg, latency)
                }
                val resultObj = json.optJSONObject("result")
                val extractedText = buildString {
                    val contentArr = resultObj?.optJSONArray("content")
                    if (contentArr != null) {
                        for (i in 0 until contentArr.length()) {
                            val item = contentArr.optJSONObject(i)
                            val txt = item?.optString("text", "").orEmpty()
                            if (txt.isNotBlank()) append(txt).append("\n")
                        }
                    } else if (resultObj != null) {
                        append(resultObj.toString())
                    } else {
                        append(raw)
                    }
                }.trim().take(6000)

                val guarded = AiToolSecurityGuard.inspectAndWrapUntrustedData(
                    context = context,
                    sourceLabel = "mcp:${server.name}/$toolName",
                    rawContent = extractedText.ifBlank { "(empty MCP response)" }
                )
                AyraDiagnosticsEngine.record("MCP", "tools/call:$toolName", latency, true, "OK")
                McpCallResult(true, guarded.wrappedUntrustedContent, latencyMs = latency)
            }
        }.getOrElse { err ->
            McpCallResult(false, "", err.localizedMessage ?: "MCP request failed")
        }
    }

    private fun updateServerStatus(
        context: Context,
        serverId: String,
        status: String,
        tools: List<McpToolSpec>
    ): McpServerConfig? {
        var updatedItem: McpServerConfig? = null
        val list = listServers(context).map { s ->
            if (s.id == serverId) {
                s.copy(
                    lastStatus = status,
                    lastDiscoveredAtMs = System.currentTimeMillis(),
                    tools = tools
                ).also { updatedItem = it }
            } else s
        }
        saveServers(context, list)
        return updatedItem
    }
}
