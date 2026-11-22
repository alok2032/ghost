package com.ayra.ai.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ai.AiComparisonEngine
import com.ayra.ai.app.ai.ComparisonArenaReport
import com.ayra.ai.app.ai.PromptQualityAnalyzer
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.PromptTemplateEntity
import com.ayra.ai.app.data.PromptVersionEntity
import com.ayra.ai.app.ui.theme.LocalAyraUiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PromptCenterTab(val label: String) {
    BUILDER("Builder & Enhancer"),
    ANALYZER("Quality Analyzer"),
    LIBRARY("Library & Versions"),
    LAB("Prompt Lab")
}

@Composable
fun PromptEngineeringScreen(
    onBack: () -> Unit,
    onUsePromptInChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiConfig = LocalAyraUiConfig.current
    val dao = remember { ChatDatabase.getDatabase(context).ayraWorkspaceDao() }

    var activeTab by remember { mutableStateOf(PromptCenterTab.BUILDER) }

    // Builder state (8 structured fields + compiled buffer + edit target)
    var editingTemplate by remember { mutableStateOf<PromptTemplateEntity?>(null) }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Engineering") }
    var tagsCsv by remember { mutableStateOf("system, structured") }
    var roleText by remember { mutableStateOf("") }
    var objectiveText by remember { mutableStateOf("") }
    var contextText by remember { mutableStateOf("") }
    var inputText by remember { mutableStateOf("") }
    var requirementsText by remember { mutableStateOf("") }
    var constraintsText by remember { mutableStateOf("") }
    var outputFormatText by remember { mutableStateOf("") }
    var validationText by remember { mutableStateOf("") }
    var compiledPrompt by remember { mutableStateOf("") }
    var optimizationGoal by remember { mutableStateOf("Maximize clarity, edge-case constraints, and precision") }
    var isEnhancing by remember { mutableStateOf(false) }

    // Analyzer state
    var aiCritique by remember { mutableStateOf<String?>(null) }
    var isCritiquing by remember { mutableStateOf(false) }

    // Library state
    var searchQuery by remember { mutableStateOf("") }
    val templates by remember(searchQuery) {
        if (searchQuery.isBlank()) dao.observePromptTemplates() else dao.searchPromptTemplates(searchQuery.trim())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    var versionHistoryTarget by remember { mutableStateOf<PromptTemplateEntity?>(null) }
    val versions by remember(versionHistoryTarget?.id) {
        versionHistoryTarget?.let { dao.observePromptVersions(it.id) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    // Lab tester state
    val arenaEngine = remember { AiComparisonEngine(context) }
    var labReport by remember { mutableStateOf<ComparisonArenaReport?>(null) }
    var isRunningLab by remember { mutableStateOf(false) }

    fun copyText(txt: String, label: String) {
        val sensitiveAutoClear = com.ayra.ai.app.data.AyraPreferences.isClipboardAutoClearEnabled(context)
        com.ayra.ai.app.security.AndroidHardeningHelper.copyWithSensitivityProtection(
            context = context,
            label = label,
            text = txt,
            isSensitive = sensitiveAutoClear,
            autoClearMs = if (sensitiveAutoClear) 60_000L else 0L
        )
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
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
                modifier = Modifier.size(48.dp).testTag("prompt_center_back_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = uiConfig.accentColor)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Prompt Engineering Center", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Build structured 8-part prompts, grade quality, track versions & benchmark across models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PromptCenterTab.entries.forEach { tab ->
                val icon = when (tab) {
                    PromptCenterTab.BUILDER -> Icons.Default.AutoAwesome
                    PromptCenterTab.ANALYZER -> Icons.Default.Analytics
                    PromptCenterTab.LIBRARY -> Icons.Default.Bookmarks
                    PromptCenterTab.LAB -> Icons.Default.Science
                }
                FilterChip(
                    selected = activeTab == tab,
                    onClick = { activeTab = tab },
                    leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(tab.label, fontSize = 12.sp) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("prompt_tab_${tab.name.lowercase()}")
                )
            }
        }

        when (activeTab) {
            PromptCenterTab.BUILDER -> {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Template Title (optional for quick build)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Category") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = tagsCsv,
                        onValueChange = { tagsCsv = it },
                        label = { Text("Tags (CSV)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                OutlinedTextField(value = roleText, onValueChange = { roleText = it }, label = { Text("1. Role / Persona") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = objectiveText, onValueChange = { objectiveText = it }, label = { Text("2. Primary Objective") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = contextText, onValueChange = { contextText = it }, label = { Text("3. Background Context & Audience") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = inputText, onValueChange = { inputText = it }, label = { Text("4. Input Data / {{variables}}") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = requirementsText, onValueChange = { requirementsText = it }, label = { Text("5. Key Requirements") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = constraintsText, onValueChange = { constraintsText = it }, label = { Text("6. Constraints & Guardrails") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = outputFormatText, onValueChange = { outputFormatText = it }, label = { Text("7. Output Format / Schema") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = validationText, onValueChange = { validationText = it }, label = { Text("8. Validation Criteria") }, modifier = Modifier.fillMaxWidth())

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            compiledPrompt = PromptQualityAnalyzer.compileStructuredPrompt(
                                roleText, objectiveText, contextText, inputText,
                                requirementsText, constraintsText, outputFormatText, validationText
                            )
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("compile_prompt_button")
                    ) {
                        Text("Compile Prompt")
                    }
                    OutlinedButton(
                        onClick = {
                            val source = compiledPrompt.ifBlank {
                                PromptQualityAnalyzer.compileStructuredPrompt(
                                    roleText, objectiveText, contextText, inputText,
                                    requirementsText, constraintsText, outputFormatText, validationText
                                )
                            }
                            if (source.isBlank()) {
                                Toast.makeText(context, "Enter fields or draft prompt first", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            isEnhancing = true
                            scope.launch {
                                val res = PromptQualityAnalyzer.enhanceWithAi(context, source, optimizationGoal)
                                isEnhancing = false
                                res.onSuccess { compiledPrompt = it }
                                    .onFailure { Toast.makeText(context, "AI enhancement failed: ${it.localizedMessage}", Toast.LENGTH_LONG).show() }
                            }
                        },
                        enabled = !isEnhancing,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("ai_enhance_prompt_button")
                    ) {
                        if (isEnhancing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("AI Upgrade")
                        }
                    }
                }

                OutlinedTextField(
                    value = optimizationGoal,
                    onValueChange = { optimizationGoal = it },
                    label = { Text("AI Optimization Goal") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = compiledPrompt,
                    onValueChange = { compiledPrompt = it },
                    label = { Text("Compiled / Final Prompt") },
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth().testTag("compiled_prompt_editor")
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val textToSave = compiledPrompt.trim()
                            if (textToSave.isBlank()) {
                                Toast.makeText(context, "Compiled prompt cannot be empty", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            scope.launch(Dispatchers.IO) {
                                val entity = (editingTemplate ?: PromptTemplateEntity(title = "", compiledPrompt = "")).copy(
                                    title = title.ifBlank { textToSave.lines().firstOrNull()?.take(48) ?: "Untitled Prompt" },
                                    category = category.ifBlank { "General" },
                                    roleText = roleText,
                                    objectiveText = objectiveText,
                                    contextText = contextText,
                                    inputText = inputText,
                                    requirementsText = requirementsText,
                                    constraintsText = constraintsText,
                                    outputFormatText = outputFormatText,
                                    validationCriteriaText = validationText,
                                    compiledPrompt = textToSave,
                                    tagsCsv = tagsCsv
                                )
                                dao.savePromptWithVersion(entity, changeNote = if (editingTemplate == null) "Created in Builder" else "Updated in Builder")
                                withContext(Dispatchers.Main) {
                                    editingTemplate = null
                                    Toast.makeText(context, "Saved prompt + version snapshot!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("save_prompt_template_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (editingTemplate != null) "Save v+" else "Save")
                    }
                    OutlinedButton(
                        onClick = { if (compiledPrompt.isNotBlank()) copyText(compiledPrompt, "Prompt") },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }
                    Button(
                        onClick = {
                            val ready = compiledPrompt.trim()
                            if (ready.isNotBlank()) onUsePromptInChat(ready)
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("use_prompt_in_chat_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Run")
                    }
                }
            }

            PromptCenterTab.ANALYZER -> {
                OutlinedTextField(
                    value = compiledPrompt,
                    onValueChange = { compiledPrompt = it },
                    label = { Text("Prompt to Grade & Audit") },
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
                val report = remember(compiledPrompt) { PromptQualityAnalyzer.analyze(compiledPrompt) }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Quality Score: ${report.overallScore}/100", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = uiConfig.accentColor)
                            Text(report.grade, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(
                            progress = { report.overallScore / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = uiConfig.accentColor
                        )
                        report.dimensions.forEach { dim ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(dim.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(end = 8.dp))
                                Text("${dim.score}/${dim.maxScore} (${dim.note})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (report.variablesDetected.isNotEmpty()) {
                            Text("Variables: ${report.variablesDetected.joinToString(", ") { "{{$it}}" }}", style = MaterialTheme.typography.labelMedium, color = uiConfig.accentColor)
                        }
                        if (report.suggestions.isNotEmpty()) {
                            Text("Improvement Rubric:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            report.suggestions.forEach { s ->
                                Text("• $s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        if (compiledPrompt.isBlank()) return@Button
                        isCritiquing = true
                        scope.launch {
                            val res = PromptQualityAnalyzer.critiqueWithAi(context, compiledPrompt)
                            isCritiquing = false
                            aiCritique = res.getOrElse { "AI critique error: ${it.localizedMessage}" }
                        }
                    },
                    enabled = !isCritiquing && compiledPrompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isCritiquing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Run Live AI Deep Critique")
                }

                aiCritique?.let { critique ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(critique, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
                    }
                }
            }

            PromptCenterTab.LIBRARY -> {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search saved prompts, categories or tags...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (templates.isEmpty()) {
                    Text(
                        "No saved templates yet. Compile or paste a prompt in the Builder tab and tap Save.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    templates.forEach { tpl ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("${tpl.title} (v${tpl.versionNumber})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                                    Text(tpl.category, style = MaterialTheme.typography.labelSmall, color = uiConfig.accentColor)
                                }
                                if (tpl.tagsCsv.isNotBlank()) {
                                    Text("Tags: ${tpl.tagsCsv}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(tpl.compiledPrompt.take(180), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onUsePromptInChat(tpl.compiledPrompt) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Run") }
                                    TextButton(onClick = {
                                        editingTemplate = tpl
                                        title = tpl.title
                                        category = tpl.category
                                        tagsCsv = tpl.tagsCsv
                                        roleText = tpl.roleText
                                        objectiveText = tpl.objectiveText
                                        contextText = tpl.contextText
                                        inputText = tpl.inputText
                                        requirementsText = tpl.requirementsText
                                        constraintsText = tpl.constraintsText
                                        outputFormatText = tpl.outputFormatText
                                        validationText = tpl.validationCriteriaText
                                        compiledPrompt = tpl.compiledPrompt
                                        activeTab = PromptCenterTab.BUILDER
                                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text("Edit")
                                    }
                                    TextButton(onClick = { versionHistoryTarget = tpl }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text("Versions")
                                    }
                                    IconButton(
                                        onClick = { scope.launch(Dispatchers.IO) { dao.deletePromptTemplateById(tpl.id) } },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Template", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            PromptCenterTab.LAB -> {
                OutlinedTextField(
                    value = compiledPrompt,
                    onValueChange = { compiledPrompt = it },
                    label = { Text("Prompt to Test Across Active AI Models") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (compiledPrompt.isBlank()) return@Button
                        isRunningLab = true
                        scope.launch {
                            labReport = arenaEngine.runComparisonArena(compiledPrompt.trim(), runDebateVerification = true)
                            isRunningLab = false
                        }
                    },
                    enabled = !isRunningLab && compiledPrompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRunningLab) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Run Multi-Model A/B Test")
                }
                labReport?.let { report ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Benchmark Complete (${report.totalElapsedMs}ms)", fontWeight = FontWeight.Bold, color = uiConfig.accentColor)
                            Text(report.synthesisSummary, style = MaterialTheme.typography.bodySmall)
                            report.results.forEach { r ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(1.dp, uiConfig.accentColor.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Text("${r.displayName} • ${r.latencyMs}ms", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                    Text(if (r.success) r.responseText else "Error: ${r.errorMessage}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    versionHistoryTarget?.let { tpl ->
        AlertDialog(
            onDismissRequest = { versionHistoryTarget = null },
            title = { Text("Version History: ${tpl.title}") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (versions.isEmpty()) {
                        Text("No recorded snapshots yet.")
                    } else {
                        versions.forEach { v: PromptVersionEntity ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("v${v.versionNumber} • ${v.changeNote}", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                                    Row(
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                compiledPrompt = v.compiledPrompt
                                                scope.launch(Dispatchers.IO) {
                                                    dao.savePromptWithVersion(tpl.copy(compiledPrompt = v.compiledPrompt), "Reverted to v${v.versionNumber}")
                                                }
                                                versionHistoryTarget = null
                                                Toast.makeText(context, "Reverted to v${v.versionNumber}", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(horizontal = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Restore, contentDescription = "Revert", modifier = Modifier.size(14.dp), tint = uiConfig.accentColor)
                                        Spacer(Modifier.width(3.dp))
                                        Text("Revert", color = uiConfig.accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(v.compiledPrompt.take(160), fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { versionHistoryTarget = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            }
        )
    }
}
