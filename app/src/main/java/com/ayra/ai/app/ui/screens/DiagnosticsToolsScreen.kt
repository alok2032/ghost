package com.ayra.ai.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.StorageHelper
import com.ayra.ai.app.data.StorageHelper.WorkspaceArtifact
import com.ayra.ai.app.ui.components.AyraDiagnosticsSection
import com.ayra.ai.app.ui.components.CyberTerminalCard
import com.ayra.ai.app.ui.components.GitHubPluginHubSection
import com.ayra.ai.app.ui.components.McpServersSection
import com.ayra.ai.app.ui.components.ProviderKeysSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ToolsSubTab(val label: String) {
    SECURITY("Security Vault"),
    NETWORK("Network & Diag"),
    KEYS("API Keys Vault"),
    WORKSPACE("Files & Backup"),
    TERMINAL("Cyber Terminal"),
    PLUGINS("GitHub Plugins"),
    MCP("MCP Servers")
}

@Composable
fun DiagnosticsToolsScreen(
    onRunPromptInChat: (String) -> Unit,
    onVaultWiped: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(ToolsSubTab.SECURITY) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp).testTag("tools_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                text = "Tools, Security Vault & Workspace",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            text = "Enterprise Zero-Trust Security Center, Keystore keys, workspace artifacts, safe backups, cyber terminal & plugins.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = activeTab == ToolsSubTab.SECURITY,
                onClick = { activeTab = ToolsSubTab.SECURITY },
                leadingIcon = { Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Security Vault", fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("tools_subtab_security")
            )
            FilterChip(
                selected = activeTab == ToolsSubTab.NETWORK,
                onClick = { activeTab = ToolsSubTab.NETWORK },
                leadingIcon = { Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Network & Diag", fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("tools_subtab_network")
            )
            FilterChip(
                selected = activeTab == ToolsSubTab.KEYS,
                onClick = { activeTab = ToolsSubTab.KEYS },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Keys", fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("tools_subtab_keys")
            )
            FilterChip(
                selected = activeTab == ToolsSubTab.WORKSPACE,
                onClick = { activeTab = ToolsSubTab.WORKSPACE },
                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Workspace", fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("tools_subtab_workspace")
            )
            FilterChip(
                selected = activeTab == ToolsSubTab.TERMINAL,
                onClick = { activeTab = ToolsSubTab.TERMINAL },
                leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Terminal", fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("tools_subtab_terminal")
            )
            FilterChip(
                selected = activeTab == ToolsSubTab.PLUGINS,
                onClick = { activeTab = ToolsSubTab.PLUGINS },
                leadingIcon = { Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Plugins", fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("tools_subtab_plugins")
            )
            FilterChip(
                selected = activeTab == ToolsSubTab.MCP,
                onClick = { activeTab = ToolsSubTab.MCP },
                leadingIcon = { Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("MCP", fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("tools_subtab_mcp")
            )
        }

        when (activeTab) {
            ToolsSubTab.SECURITY -> {
                SecurityCenterScreen(
                    onBack = null,
                    onVaultWiped = onVaultWiped,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
            ToolsSubTab.NETWORK -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AyraDiagnosticsSection(context = context)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
            ToolsSubTab.KEYS -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ProviderKeysSection(context = context)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
            ToolsSubTab.WORKSPACE -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WorkspaceAndBackupPanel(context = context)
                    AyraDiagnosticsSection(context = context)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
            ToolsSubTab.TERMINAL -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CyberTerminalCard(
                        onClose = { activeTab = ToolsSubTab.KEYS }
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
            ToolsSubTab.PLUGINS -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GitHubPluginHubSection(
                        context = context,
                        onUsePluginPrompt = { pluginPrompt ->
                            onRunPromptInChat(pluginPrompt)
                        },
                        onQuickFeedback = { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
            ToolsSubTab.MCP -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    McpServersSection(context = context)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun WorkspaceAndBackupPanel(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    val db = remember { ChatDatabase.getDatabase(context) }

    val themeMode by AyraPreferences.themeModeFlow.collectAsStateWithLifecycle(
        initialValue = AyraPreferences.getThemeMode(context)
    )
    var searchGrounding by remember { mutableStateOf(AyraPreferences.isSearchGroundingEnabled(context)) }
    val isLocalOnly = AyraPreferences.isLocalOnlyAiModeEnabled(context)
    var freePool by remember { mutableStateOf(AyraPreferences.isFreeAiPoolEnabled(context)) }
    var showFreePoolConsentDialog by remember { mutableStateOf(false) }
    var memoryEnabled by remember { mutableStateOf(AyraPreferences.isMemoryEnabled(context)) }
    var artifacts by remember { mutableStateOf<List<WorkspaceArtifact>>(emptyList()) }
    var backupStatus by remember { mutableStateOf<String?>(null) }

    fun reloadArtifacts() {
        scope.launch {
            artifacts = withContext(Dispatchers.IO) {
                StorageHelper.listWorkspaceArtifacts(context)
            }
        }
    }

    LaunchedEffect(Unit) {
        artifacts = withContext(Dispatchers.IO) {
            StorageHelper.listWorkspaceArtifacts(context)
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val countSummary = withContext(Dispatchers.IO) {
                    val sessions = db.chatSessionDao().getAllSessionsOnce()
                    val messages = db.chatDao().getAllMessages().first()
                    val plugins = db.gitHubPluginDao().getAllPluginsSync()
                    val json = StorageHelper.exportSafeWorkspaceBackupJson(
                        context = context,
                        sessions = sessions,
                        messages = messages,
                        plugins = plugins
                    )
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    "Exported ${sessions.size} sessions, ${messages.size} messages & ${plugins.size} plugins (0 secrets included)."
                }
                backupStatus = countSummary
                Toast.makeText(context, "Key-free workspace backup exported", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val resultMsg = withContext(Dispatchers.IO) {
                    runCatching {
                        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                        val payload = StorageHelper.importSafeWorkspaceBackupJson(context, raw)
                        val sessionIdMap = mutableMapOf<Long, Long>()
                        payload.sessions.forEach { oldSession ->
                            val newId = db.chatSessionDao().insertSession(oldSession.copy(id = 0L))
                            sessionIdMap[oldSession.id] = newId
                        }
                        payload.messages.forEach { msg ->
                            val mappedSession = sessionIdMap[msg.sessionId] ?: msg.sessionId
                            db.chatDao().insertMessage(msg.copy(id = 0L, sessionId = mappedSession))
                        }
                        payload.plugins.forEach { plugin ->
                            db.gitHubPluginDao().upsertPlugin(plugin)
                        }
                        "Restored ${payload.sessions.size} sessions, ${payload.messages.size} messages & ${payload.plugins.size} plugins."
                    }.getOrElse { err ->
                        "Restore failed: ${err.localizedMessage ?: "Invalid JSON"}"
                    }
                }
                backupStatus = resultMsg
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Appearance & Intelligence Toggles Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Appearance & Engine Controls",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text("Material 3 Theme", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.5.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("dark" to "Dark", "light" to "Light", "system" to "System").forEach { (mode, label) ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { AyraPreferences.setThemeMode(context, mode) },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("theme_mode_$mode")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Live Search Grounding", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Augment answers with live DuckDuckGo / Wikipedia / CrossRef", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Switch(
                    checked = searchGrounding,
                    onCheckedChange = {
                        searchGrounding = it
                        AyraPreferences.setSearchGroundingEnabled(context, it)
                    },
                    modifier = Modifier.testTag("toggle_search_grounding")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Global Free AI Pool", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = when {
                            isLocalOnly -> "Blocked while Local-Only AI Mode is enabled"
                            !AyraPreferences.hasExternalAiConsent(context) -> "Paused — enable External Cloud AI consent to reactivate"
                            else -> "Auto-active keyless GPT/OSS, Qwen Coder & DeepSeek ensemble"
                        },
                        color = if (isLocalOnly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = !isLocalOnly && freePool && AyraPreferences.hasExternalAiConsent(context),
                    enabled = !isLocalOnly,
                    onCheckedChange = { enable ->
                        if (!enable) {
                            AyraPreferences.setFreeAiPoolEnabled(context, false)
                            freePool = false
                        } else if (AyraPreferences.hasExternalAiConsent(context)) {
                            AyraPreferences.setFreeAiPoolEnabled(context, true)
                            freePool = AyraPreferences.isFreeAiPoolEnabled(context)
                        } else {
                            showFreePoolConsentDialog = true
                        }
                    },
                    modifier = Modifier.testTag("toggle_free_ai_pool")
                )
            }

            if (showFreePoolConsentDialog) {
                AlertDialog(
                    onDismissRequest = { showFreePoolConsentDialog = false },
                    title = { Text("Enable External Cloud AI & Free Pool?") },
                    text = {
                        Text(
                            "Enabling the Global Free AI Pool grants External Cloud AI Provider Consent so prompts can use keyless community fallback endpoints.",
                            fontSize = 12.5.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                AyraPreferences.setExternalAiConsent(context, true)
                                AyraPreferences.setFreeAiPoolEnabled(context, true)
                                freePool = AyraPreferences.isFreeAiPoolEnabled(context)
                                showFreePoolConsentDialog = false
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Enable")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showFreePoolConsentDialog = false },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Persistent AI Memory", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Remember user profile facts & coding preferences across chats", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Switch(
                    checked = memoryEnabled,
                    onCheckedChange = {
                        memoryEnabled = it
                        AyraPreferences.setMemoryEnabled(context, it)
                    },
                    modifier = Modifier.testTag("toggle_ai_memory")
                )
            }
        }

        // Safe Key-Free JSON Backup & Restore Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Key-Free Workspace Backup & Restore",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                text = "Exports chats, installed plugins, and non-secret preferences to JSON. API keys remain strictly inside Android Keystore and are NEVER exported.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { exportBackupLauncher.launch("ayra_workspace_backup.json") },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("workspace_export_backup_button")
                ) {
                    Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export JSON", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { importBackupLauncher.launch(arrayOf("application/json", "text/plain")) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("workspace_import_backup_button")
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restore JSON", fontSize = 12.sp)
                }
            }
            backupStatus?.let { msg ->
                Text(text = msg, color = MaterialTheme.colorScheme.secondary, fontSize = 11.5.sp)
            }
        }

        // Workspace Artifacts Explorer Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val totalKb = artifacts.sumOf { it.sizeBytes } / 1024L
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Workspace Files Explorer (${artifacts.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Total storage: ${totalKb} KB across docs, images, 3D, audio & code ZIPs",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                IconButton(
                    onClick = { reloadArtifacts() },
                    modifier = Modifier.size(48.dp).testTag("workspace_refresh_files_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Files", tint = MaterialTheme.colorScheme.primary)
                }
            }

            if (artifacts.isEmpty()) {
                Text(
                    text = "No generated documents, 3D meshes, images, audio, or code ZIPs on disk yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            } else {
                artifacts.take(40).forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = item.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = "${item.folder} • ${(item.sizeBytes / 1024L).coerceAtLeast(1L)} KB",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.5.sp
                            )
                        }
                        if (item.file.isFile) {
                            IconButton(
                                onClick = {
                                    runCatching {
                                        val shareFile = StorageHelper.prepareShareableFile(context, item.file)
                                        val uri = FileProvider.getUriForFile(context, "com.ayra.ai.app.fileprovider", shareFile)
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "*/*"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(share, "Share ${item.name}"))
                                    }.onFailure {
                                        Toast.makeText(context, item.file.absolutePath, Toast.LENGTH_LONG).show()
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share ${item.name}", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val deleted = withContext(Dispatchers.IO) {
                                        StorageHelper.deleteWorkspaceArtifact(item.file)
                                    }
                                    if (deleted) {
                                        artifacts = withContext(Dispatchers.IO) {
                                            StorageHelper.listWorkspaceArtifacts(context)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${item.name}", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
