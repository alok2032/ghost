package com.ayra.ai.app.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ai.plugins.GitHubPluginEngine
import com.ayra.ai.app.ai.plugins.GitHubPluginRegistry
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGold
import com.ayra.ai.app.ui.theme.NeonGreen
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * ChatGPT-Plugin & Claude-Skill/MCP style GitHub Repository Store & Manager.
 * Allows live installation of any public GitHub repository, real-time ON/OFF toggling,
 * re-syncing, uninstalling, and one-tap invocation in chat.
 */
@Composable
fun GitHubPluginHubSection(
    context: Context,
    onUsePluginPrompt: (String) -> Unit,
    onQuickFeedback: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val installedPlugins by GitHubPluginRegistry.pluginsFlow.collectAsStateWithLifecycle()
    val pluginEngine = remember { GitHubPluginEngine(context) }

    var repoInput by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        GitHubPluginRegistry.getAllPlugins(context)
    }

    fun installRepo(target: String) {
        val clean = target.trim()
        if (clean.isBlank() || isInstalling) return
        scope.launch {
            isInstalling = true
            statusMessage = "Inspecting `$clean` on GitHub..."
            val result = pluginEngine.installPlugin(clean)
            isInstalling = false
            if (result.success && result.plugin != null) {
                repoInput = ""
                statusMessage = "✅ Installed `${result.plugin.repoFullName}` (${result.plugin.pluginType})!"
                onQuickFeedback("🔌 Plugin installed: ${result.plugin.name}")
            } else {
                statusMessage = "⚠️ ${result.errorMessage ?: "Install failed"}"
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0C1425))
            .border(
                1.2.dp,
                Brush.linearGradient(listOf(NeonCyan.copy(alpha = 0.6f), NeonGreen.copy(alpha = 0.5f))),
                RoundedCornerShape(16.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🔌 GitHub Plugins, Skills & MCP Hub",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan
                )
                Text(
                    text = "যেকোনো GitHub repo URL বা owner/repo দিয়ে সরাসরি Plugin/Skill ইনস্টল করুন",
                    color = TextSecondary,
                    fontSize = 10.5.sp
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(NeonGreen.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${installedPlugins.count { it.isEnabled }} Active",
                    color = NeonGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Input Row: Repo URL / owner/repo + Install Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = repoInput,
                onValueChange = { repoInput = it },
                placeholder = {
                    Text("owner/repo or https://github.com/...", color = TextSecondary, fontSize = 12.sp)
                },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("github_plugin_url_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = Color(0x3300FFFF),
                    focusedContainerColor = GlassSurfaceDark,
                    unfocusedContainerColor = GlassSurfaceDark
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Button(
                onClick = { installRepo(repoInput) },
                enabled = repoInput.isNotBlank() && !isInstalling,
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("install_github_plugin_button")
            ) {
                if (isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Add, contentDescription = "Install", tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Install", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        statusMessage?.let { msg ->
            Text(
                text = msg,
                color = if (msg.startsWith("✅")) NeonGreen else NeonGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Quick-Install Open-Source Ecosystem Repos (Real live GitHub API fetch on tap)
        Text(
            text = "Popular Open-Source Plugins (Tap to Live-Install):",
            color = NeonGold,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold
        )
        val quickRepos = listOf(
            "anthropics/skills",
            "obra/superpowers",
            "modelcontextprotocol/servers",
            "public-apis/public-apis"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickRepos.forEach { slug ->
                val alreadyInstalled = installedPlugins.any { it.repoFullName.equals(slug, ignoreCase = true) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (alreadyInstalled) NeonGreen.copy(alpha = 0.16f) else Color(0xFF162038))
                        .border(
                            1.dp,
                            if (alreadyInstalled) NeonGreen.copy(alpha = 0.6f) else NeonPurple.copy(alpha = 0.5f),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = !isInstalling) { installRepo(slug) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = if (alreadyInstalled) "✓ $slug" else "+ $slug",
                        color = if (alreadyInstalled) NeonGreen else TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Installed Plugins List
        if (installedPlugins.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF10192E))
                    .padding(12.dp)
            ) {
                Text(
                    text = "কোনো GitHub Plugin এখনো ইনস্টল করা নেই। উপরের বক্সে repo URL দিয়ে অথবা চ্যাটে `install owner/repo` লিখে ইনস্টল করুন!",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                installedPlugins.forEach { plugin ->
                    val commands = GitHubPluginEngine.parseCommands(plugin.commandsJson)
                    val defaultRunPrompt = commands.firstOrNull()?.prompt
                        ?: "Use installed plugin @${plugin.repoFullName} to help with my task."

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF131D33))
                            .border(
                                1.dp,
                                if (plugin.isEnabled) NeonCyan.copy(alpha = 0.55f) else Color(0x33475569),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = if (plugin.isEnabled) NeonCyan else TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = plugin.repoFullName,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                )
                                Text(
                                    text = "${plugin.pluginType} • ${plugin.primaryLanguage}",
                                    color = if (plugin.isEnabled) NeonGreen else TextSecondary,
                                    fontSize = 10.5.sp
                                )
                            }

                            Switch(
                                checked = plugin.isEnabled,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        GitHubPluginRegistry.setPluginEnabled(context, plugin.repoFullName, checked)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = NeonGreen
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { onUsePluginPrompt(defaultRunPrompt) },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen.copy(alpha = 0.16f)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Use Plugin",
                                    tint = NeonGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Run", color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            IconButton(
                                onClick = { installRepo(plugin.repoFullName) },
                                enabled = !isInstalling,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Sync Plugin",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    scope.launch {
                                        GitHubPluginRegistry.uninstallPlugin(context, plugin.repoFullName)
                                        onQuickFeedback("Removed plugin ${plugin.repoFullName}")
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Uninstall Plugin",
                                    tint = NeonRose,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
