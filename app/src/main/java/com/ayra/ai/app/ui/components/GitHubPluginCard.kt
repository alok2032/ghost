package com.ayra.ai.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ai.plugins.GitHubPluginEngine
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGold
import com.ayra.ai.app.ui.theme.NeonGreen
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import org.json.JSONObject

/**
 * Rich interactive Card rendered in Chat when a GitHub repository plugin
 * (Claude-style Skill Pack, ChatGPT-style OpenAPI Plugin, MCP Server, or Code Tool) is installed.
 */
@Composable
fun GitHubPluginCard(
    jsonPayload: String,
    onRunPluginPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val data = remember(jsonPayload) {
        runCatching { JSONObject(jsonPayload) }.getOrNull()
    } ?: return

    val repoFullName = data.optString("repoFullName", "")
    val repoUrl = data.optString("repoUrl", "https://github.com/$repoFullName")
    val description = data.optString("description", "")
    val stars = data.optInt("stars", 0)
    val language = data.optString("primaryLanguage", "Code")
    val pluginType = data.optString("pluginType", "CODE_PLUGIN")
    val commands = remember(jsonPayload) {
        GitHubPluginEngine.parseCommands(data.optString("commandsJson", "[]"))
    }
    val endpoints = remember(jsonPayload) {
        GitHubPluginEngine.parseOpenApiEndpoints(data.optString("openApiSpecJson", "[]"))
    }
    val fileCount = remember(jsonPayload) {
        GitHubPluginEngine.parseFileTree(data.optString("fileTreeJson", "[]")).size
    }

    val typeLabel = when (pluginType) {
        "SKILL_PACK" -> "Claude-Style Skill Pack"
        "OPENAPI_PLUGIN" -> "OpenAPI Live Plugin"
        "MCP_SERVER" -> "MCP Tool Server"
        "WEB_TOOL" -> "Web Utility Plugin"
        else -> "GitHub Code Plugin"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B1221))
            .border(
                1.2.dp,
                Brush.linearGradient(listOf(NeonGreen, NeonCyan, NeonPurple)),
                RoundedCornerShape(16.dp)
            )
            .padding(14.dp)
            .testTag("github_plugin_card")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NeonGreen.copy(alpha = 0.16f))
                        .border(1.dp, NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = "GitHub Plugin Icon",
                        tint = NeonGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = repoFullName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "$typeLabel • $language" +
                            (if (stars > 0) " • ⭐ $stars" else "") +
                            (if (fileCount > 0) " • $fileCount files" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan,
                        fontSize = 11.sp
                    )
                }

                IconButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl)))
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open repository on GitHub",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = 17.sp
                )
            }

            if (endpoints.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF131B2E))
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Registered OpenAPI Endpoints (${endpoints.size})",
                            color = NeonGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        endpoints.take(3).forEach { ep ->
                            Text(
                                text = "• ${ep.method} ${ep.path} — ${ep.summary.ifBlank { ep.serverUrl }}",
                                color = TextSecondary,
                                fontSize = 10.5.sp
                            )
                        }
                    }
                }
            }

            if (commands.isNotEmpty()) {
                Text(
                    text = "Quick Run Features / প্লাগইন অ্যাকশন:",
                    color = NeonGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    commands.forEach { cmd ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF132238))
                                .border(1.dp, NeonCyan.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                                .clickable { onRunPluginPrompt(cmd.prompt) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = NeonGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = cmd.title,
                                color = TextPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
