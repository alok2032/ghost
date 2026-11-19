package com.ayra.ai.app.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ai.mcp.McpClientManager
import kotlinx.coroutines.launch

@Composable
fun McpServersSection(
    context: Context,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf(McpClientManager.listServers(context)) }
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    var requireConfirm by remember { mutableStateOf(true) }
    var probingId by remember { mutableStateOf<String?>(null) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MCP Servers (JSON-RPC 2.0 Tools)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Connect external HTTPS or LAN MCP endpoints with SSRF protection, encrypted tokens & ActionGate approval.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Server Label (e.g., GitHub MCP / Local Dev)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("mcp_name_input")
            )
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("Endpoint URL (https://... or validated LAN URL)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("mcp_url_input")
            )
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("Optional Bearer Token (Keystore Encrypted)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("mcp_token_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Require ActionGate Confirmation Per Tool", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = requireConfirm,
                    onCheckedChange = { requireConfirm = it },
                    modifier = Modifier.testTag("mcp_require_confirm_switch")
                )
            }

            Button(
                onClick = {
                    val res = McpClientManager.addOrUpdateServer(
                        context = context,
                        name = nameInput,
                        endpointUrl = urlInput,
                        bearerToken = tokenInput,
                        requireConfirmationPerTool = requireConfirm
                    )
                    res.fold(
                        onSuccess = { added ->
                            servers = McpClientManager.listServers(context)
                            nameInput = ""
                            urlInput = ""
                            tokenInput = ""
                            statusBanner = "Registered '${added.name}'. Tap Discover Tools to query JSON-RPC tools/list."
                        },
                        onFailure = { err ->
                            statusBanner = "Blocked: ${err.localizedMessage ?: "Invalid MCP URL"}"
                        }
                    )
                },
                enabled = urlInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("mcp_add_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Connect MCP Server")
            }

            statusBanner?.let { banner ->
                Text(text = banner, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }

        if (servers.isEmpty()) {
            Text(
                text = "No MCP servers connected yet. Add any Model Context Protocol HTTPS endpoint above.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        } else {
            servers.forEach { srv ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(srv.name, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                            Text(srv.endpointUrl, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(srv.lastStatus, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Switch(
                            checked = srv.enabled,
                            onCheckedChange = {
                                McpClientManager.setServerEnabled(context, srv.id, it)
                                servers = McpClientManager.listServers(context)
                            }
                        )
                        IconButton(
                            onClick = {
                                McpClientManager.revokeAndRemoveServer(context, srv.id)
                                servers = McpClientManager.listServers(context)
                                Toast.makeText(context, "Disconnected & wiped token for ${srv.name}", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(48.dp).testTag("mcp_revoke_${srv.id}")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Revoke ${srv.name}", tint = MaterialTheme.colorScheme.error)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Confirm before tool call", fontSize = 11.5.sp)
                        Switch(
                            checked = srv.requireConfirmationPerTool,
                            onCheckedChange = {
                                McpClientManager.setRequireConfirmation(context, srv.id, it)
                                servers = McpClientManager.listServers(context)
                            }
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            probingId = srv.id
                            scope.launch {
                                val res = McpClientManager.discoverTools(context, srv.id)
                                probingId = null
                                servers = McpClientManager.listServers(context)
                                statusBanner = res.fold(
                                    onSuccess = { "${it.name}: discovered ${it.tools.size} tool(s)." },
                                    onFailure = { "Discovery failed: ${it.localizedMessage}" }
                                )
                            }
                        },
                        enabled = probingId == null,
                        modifier = Modifier.fillMaxWidth().testTag("mcp_discover_${srv.id}")
                    ) {
                        if (probingId == srv.id) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Discover Tools (tools/list)")
                        }
                    }

                    if (srv.tools.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            srv.tools.forEach { t ->
                                Text(
                                    text = "• ${t.name}${if (t.description.isNotBlank()) " — ${t.description.take(90)}" else ""}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
