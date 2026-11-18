package com.ayra.ai.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.CustomAssistantEntity
import com.ayra.ai.app.data.ProjectEntity
import com.ayra.ai.app.ui.theme.LocalAyraUiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class WorkspaceTab(val label: String) {
    PROJECTS("Isolated Projects"),
    ASSISTANTS("Custom AI Assistants")
}

@Composable
fun ProjectsAndAssistantsScreen(
    onBack: () -> Unit,
    onOpenProjectChat: (projectId: Long?, assistantId: Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiConfig = LocalAyraUiConfig.current
    val dao = remember { ChatDatabase.getDatabase(context).ayraWorkspaceDao() }

    var activeTab by remember { mutableStateOf(WorkspaceTab.PROJECTS) }
    val projects by dao.observeProjects().collectAsStateWithLifecycle(initialValue = emptyList())
    val assistants by dao.observeAssistants().collectAsStateWithLifecycle(initialValue = emptyList())
    val activeProjectId by AyraPreferences.activeProjectIdFlow.collectAsStateWithLifecycle()
    val activeAssistantId by AyraPreferences.activeAssistantIdFlow.collectAsStateWithLifecycle()

    var editingProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var showProjectDialog by remember { mutableStateOf(false) }

    var editingAssistant by remember { mutableStateOf<CustomAssistantEntity?>(null) }
    var showAssistantDialog by remember { mutableStateOf(false) }

    // Seed real editable starter Custom Assistants cleanly once if empty
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (dao.getAssistantsOnce().isEmpty()) {
                listOf(
                    CustomAssistantEntity(
                        name = "Principal Systems Architect",
                        description = "Production Kotlin/Rust/Cloud architecture & zero-stub code",
                        iconEmoji = "🏗️",
                        systemPrompt = "Act as a Principal Software Architect. Provide idiomatic, thread-safe, modular solutions with explicit trade-off analysis.",
                        personaStyle = "TECHNICAL",
                        defaultModelTier = "gemini_pro",
                        thinkingMode = "coding",
                        isBuiltInPreset = true
                    ),
                    CustomAssistantEntity(
                        name = "Academic & Wiki Researcher",
                        description = "Deep multi-source evidence synthesis & rigorous citations",
                        iconEmoji = "🔬",
                        systemPrompt = "Act as an Academic Research Scientist. Ground claims in verified Wiki/OpenAlex/PubMed facts and avoid unverified numbers.",
                        personaStyle = "ANALYTICAL",
                        defaultModelTier = "omniroute",
                        thinkingMode = "research",
                        isBuiltInPreset = true
                    ),
                    CustomAssistantEntity(
                        name = "Cyber Security Auditor",
                        description = "Defensive vulnerability auditing, CVE analysis & hardening",
                        iconEmoji = "🛡️",
                        systemPrompt = "Act as a Zero-Trust Security & Network Engineer specializing in OWASP, CVE triage, TLS/cryptography, and Tor/I2P network internals.",
                        personaStyle = "CRITICAL",
                        defaultModelTier = "security_research",
                        thinkingMode = "deep_analysis",
                        isBuiltInPreset = true
                    ),
                    CustomAssistantEntity(
                        name = "Bengali & English Polyglot",
                        description = "Natural expressive Bengali/Banglish/English writing & translation",
                        iconEmoji = "✍️",
                        systemPrompt = "Act as a master bilingual writer fluent in literary and conversational Bengali (বাংলা) and natural English.",
                        personaStyle = "CREATIVE",
                        defaultModelTier = "llama_3_3",
                        thinkingMode = "creative",
                        isBuiltInPreset = true
                    )
                ).forEach { dao.insertAssistant(it) }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp).testTag("projects_assistants_back_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = uiConfig.accentColor)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Projects & AI Assistants", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Isolate project context/memory or activate custom AI personas in every chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkspaceTab.entries.forEach { tab ->
                val icon = if (tab == WorkspaceTab.PROJECTS) Icons.Default.FolderSpecial else Icons.Default.SmartToy
                FilterChip(
                    selected = activeTab == tab,
                    onClick = { activeTab = tab },
                    leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(tab.label, fontSize = 12.sp) },
                    modifier = Modifier.testTag("workspace_tab_${tab.name.lowercase()}")
                )
            }
        }

        when (activeTab) {
            WorkspaceTab.PROJECTS -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            editingProject = null
                            showProjectDialog = true
                        },
                        modifier = Modifier.weight(1f).testTag("new_project_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New Project")
                    }
                    if (activeProjectId > 0L) {
                        OutlinedButton(onClick = { AyraPreferences.setActiveProjectId(context, 0L) }) {
                            Text("Clear Active")
                        }
                    }
                }

                if (projects.isEmpty()) {
                    Text(
                        "No isolated projects created yet. Tap New Project to give Ayra AI scoped rules, memory notes, and default routing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    projects.forEach { proj ->
                        val isActive = proj.id == activeProjectId
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isActive) Modifier.border(1.5.dp, uiConfig.accentColor, RoundedCornerShape(14.dp))
                                    else Modifier
                                ),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(proj.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    if (isActive) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = uiConfig.accentColor, modifier = Modifier.size(16.dp))
                                            Text("ACTIVE", color = uiConfig.accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                if (proj.description.isNotBlank()) {
                                    Text(proj.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (proj.customInstructions.isNotBlank()) {
                                    Text("Instructions: ${proj.customInstructions.take(120)}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (proj.memoryNotes.isNotBlank()) {
                                    Text("Project Memory: ${proj.memoryNotes.take(120)}", style = MaterialTheme.typography.labelSmall, color = uiConfig.accentColor)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = {
                                        AyraPreferences.setActiveProjectId(context, proj.id)
                                        onOpenProjectChat(proj.id, null)
                                    }) {
                                        Text(if (isActive) "Open Chat" else "Activate & Chat")
                                    }
                                    IconButton(
                                        onClick = {
                                            editingProject = proj
                                            showProjectDialog = true
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Project")
                                    }
                                    IconButton(
                                        onClick = {
                                            if (isActive) AyraPreferences.setActiveProjectId(context, 0L)
                                            scope.launch(Dispatchers.IO) { dao.deleteProjectById(proj.id) }
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Project", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            WorkspaceTab.ASSISTANTS -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            editingAssistant = null
                            showAssistantDialog = true
                        },
                        modifier = Modifier.weight(1f).testTag("new_assistant_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Create Assistant")
                    }
                    if (activeAssistantId > 0L) {
                        OutlinedButton(onClick = { AyraPreferences.setActiveAssistantId(context, 0L) }) {
                            Text("Reset Default")
                        }
                    }
                }

                assistants.forEach { ast ->
                    val isActive = ast.id == activeAssistantId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isActive) Modifier.border(1.5.dp, uiConfig.accentColor, RoundedCornerShape(14.dp))
                                else Modifier
                            ),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${ast.iconEmoji} ${ast.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (isActive) {
                                    Text("ACTIVE", color = uiConfig.accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (ast.description.isNotBlank()) {
                                Text(ast.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                "Style: ${ast.personaStyle} • Thinking: ${ast.thinkingMode} • Tier: ${ast.defaultModelTier}",
                                style = MaterialTheme.typography.labelSmall,
                                color = uiConfig.accentColor
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(onClick = {
                                    AyraPreferences.setActiveAssistantId(context, ast.id)
                                    onOpenProjectChat(null, ast.id)
                                }) {
                                    Text(if (isActive) "Chat Now" else "Activate")
                                }
                                IconButton(
                                    onClick = {
                                        editingAssistant = ast
                                        showAssistantDialog = true
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit Assistant")
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            dao.insertAssistant(ast.copy(id = 0, name = "${ast.name} (Copy)", isBuiltInPreset = false, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
                                        }
                                        Toast.makeText(context, "Duplicated assistant", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate Assistant")
                                }
                                IconButton(
                                    onClick = {
                                        if (isActive) AyraPreferences.setActiveAssistantId(context, 0L)
                                        scope.launch(Dispatchers.IO) { dao.deleteAssistantById(ast.id) }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete Assistant", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showProjectDialog) {
        ProjectEditorDialog(
            initial = editingProject,
            onDismiss = { showProjectDialog = false },
            onSave = { entity ->
                scope.launch(Dispatchers.IO) {
                    if (entity.id == 0L) dao.insertProject(entity) else dao.updateProject(entity)
                }
                showProjectDialog = false
            }
        )
    }

    if (showAssistantDialog) {
        AssistantEditorDialog(
            initial = editingAssistant,
            onDismiss = { showAssistantDialog = false },
            onSave = { entity ->
                scope.launch(Dispatchers.IO) {
                    if (entity.id == 0L) dao.insertAssistant(entity) else dao.updateAssistant(entity)
                }
                showAssistantDialog = false
            }
        )
    }
}

@Composable
private fun ProjectEditorDialog(
    initial: ProjectEntity?,
    onDismiss: () -> Unit,
    onSave: (ProjectEntity) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var instructions by remember { mutableStateOf(initial?.customInstructions ?: "") }
    var memoryNotes by remember { mutableStateOf(initial?.memoryNotes ?: "") }
    var tier by remember { mutableStateOf(initial?.defaultModelTier ?: "auto") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New Project Workspace" else "Edit Project") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = instructions, onValueChange = { instructions = it }, label = { Text("Project Custom Instructions") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = memoryNotes, onValueChange = { memoryNotes = it }, label = { Text("Project Memory Notes / Specs") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.ayra.ai.app.ai.providers.ModelTier.projectDefaultIds.forEach { t ->
                        FilterChip(selected = tier == t, onClick = { tier = t }, label = { Text(t, fontSize = 11.sp) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank()) return@Button
                onSave(
                    (initial ?: ProjectEntity(name = name)).copy(
                        name = name.trim(),
                        description = desc.trim(),
                        customInstructions = instructions.trim(),
                        memoryNotes = memoryNotes.trim(),
                        defaultModelTier = tier,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AssistantEditorDialog(
    initial: CustomAssistantEntity?,
    onDismiss: () -> Unit,
    onSave: (CustomAssistantEntity) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.iconEmoji ?: "🤖") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var systemPrompt by remember { mutableStateOf(initial?.systemPrompt ?: "") }
    var style by remember { mutableStateOf(initial?.personaStyle ?: "TECHNICAL") }
    var thinking by remember { mutableStateOf(initial?.thinkingMode ?: "balanced") }
    var tier by remember { mutableStateOf(initial?.defaultModelTier ?: "auto") }

    val dialogContext = LocalContext.current
    var showAgeGate by remember { mutableStateOf(false) }

    if (showAgeGate) {
        AlertDialog(
            onDismissRequest = { showAgeGate = false },
            title = { Text("18+ Companion Persona Confirmation", fontWeight = FontWeight.Bold) },
            text = { Text("Companion Mode is intended only for adults (18+). Confirm you are 18+ to assign GF_COMPANION to a custom assistant.") },
            confirmButton = {
                Button(onClick = {
                    AyraPreferences.setCompanionAgeVerified(dialogContext, true)
                    style = "GF_COMPANION"
                    showAgeGate = false
                }) { Text("Confirm 18+") }
            },
            dismissButton = {
                TextButton(onClick = { showAgeGate = false }) { Text("Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Create Custom AI Assistant" else "Edit Assistant") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = emoji, onValueChange = { emoji = it.take(4) }, label = { Text("Icon") }, singleLine = true, modifier = Modifier.weight(0.3f))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Assistant Name") }, singleLine = true, modifier = Modifier.weight(0.7f))
                }
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Specialty Summary") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = systemPrompt, onValueChange = { systemPrompt = it }, label = { Text("System Prompt / Rules") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text("Persona Style:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("AI_ASSISTANT", "GF_COMPANION", "PROFESSIONAL", "TECHNICAL", "ANALYTICAL", "CREATIVE", "CRITICAL", "EDUCATIONAL", "CONCISE").forEach { s ->
                        FilterChip(
                            selected = style == s,
                            onClick = {
                                if (s == "GF_COMPANION" && !AyraPreferences.isCompanionAgeVerified(dialogContext)) {
                                    showAgeGate = true
                                } else {
                                    style = s
                                }
                            },
                            label = { Text(if (s == "GF_COMPANION") "COMPANION (18+)" else s, fontSize = 10.sp) }
                        )
                    }
                }
                Text("Thinking Mode:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("fast", "balanced", "deep_analysis", "reasoning", "research", "coding", "creative").forEach { m ->
                        FilterChip(selected = thinking == m, onClick = { thinking = m }, label = { Text(m, fontSize = 10.sp) })
                    }
                }
                Text("Default Tier:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.ayra.ai.app.ai.providers.ModelTier.assistantDefaultIds.forEach { t ->
                        FilterChip(selected = tier == t, onClick = { tier = t }, label = { Text(t, fontSize = 10.sp) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank() || systemPrompt.isBlank()) return@Button
                onSave(
                    (initial ?: CustomAssistantEntity(name = name, systemPrompt = systemPrompt)).copy(
                        name = name.trim(),
                        iconEmoji = emoji.ifBlank { "🤖" },
                        description = desc.trim(),
                        systemPrompt = systemPrompt.trim(),
                        personaStyle = style,
                        thinkingMode = thinking,
                        defaultModelTier = tier,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
