package com.ayra.ai.app.ui.components

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import com.ayra.ai.app.security.NetworkSecurityHelper
import com.ayra.ai.app.security.NetworkSecurityHelper.readBounded
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ai.providers.AiUsageTracker
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.ui.theme.LocalAyraUiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
fun PersonalizationAndMemoryPanel(
    context: Context,
    onPersonaStyleSelected: (String) -> Unit,
    onOpenMemoryVault: () -> Unit = {}
) {
    val uiConfig = LocalAyraUiConfig.current
    val thinkingMode by AyraPreferences.thinkingModeFlow.collectAsStateWithLifecycle()
    var activePersona by remember { mutableStateOf(AyraPreferences.getSavedPersona(context)) }
    var showCompanionAgeGate by remember { mutableStateOf(false) }
    var formality by remember { mutableFloatStateOf(AyraPreferences.getFormalityLevel(context).toFloat()) }
    var detail by remember { mutableFloatStateOf(AyraPreferences.getDetailLevel(context).toFloat()) }
    var techDepth by remember { mutableFloatStateOf(AyraPreferences.getTechnicalDepth(context).toFloat()) }
    var useExamples by remember { mutableStateOf(AyraPreferences.getUseExamples(context)) }
    var useStructured by remember { mutableStateOf(AyraPreferences.getUseStructuredFormatting(context)) }
    var emojiFreq by remember { mutableStateOf(AyraPreferences.getEmojiFrequency(context)) }
    var respLength by remember { mutableStateOf(AyraPreferences.getResponseLength(context)) }

    val initialStruct = remember { AyraPreferences.getStructuredInstructions(context) }
    var freeform by remember { mutableStateOf(AyraPreferences.getUserCustomInstructions(context)) }
    var rolePrompt by remember { mutableStateOf(initialStruct.rolePrompt) }
    var aboutUser by remember { mutableStateOf(initialStruct.aboutUser) }
    var howRespond by remember { mutableStateOf(initialStruct.howToRespond) }
    var outputFormat by remember { mutableStateOf(initialStruct.preferredOutputFormat) }
    var interests by remember { mutableStateOf(initialStruct.interests) }
    var restrictions by remember { mutableStateOf(initialStruct.restrictions) }

    var memoryEnabled by remember { mutableStateOf(AyraPreferences.isMemoryEnabled(context)) }
    var facts by remember { mutableStateOf(AyraPreferences.getRememberedFacts(context).toList()) }
    var newFact by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Default Thinking Mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "fast" to "Fast ⚡",
                "balanced" to "Balanced",
                "deep_analysis" to "Deep Analysis",
                "reasoning" to "Reasoning 🧠",
                "research" to "Research 🔬",
                "coding" to "Coding 💻",
                "creative" to "Creative ✨",
                "custom" to "Custom"
            ).forEach { (id, label) ->
                FilterChip(
                    selected = thinkingMode == id,
                    onClick = { AyraPreferences.setThinkingMode(context, id) },
                    label = { Text(label, fontSize = 11.sp) },
                    modifier = Modifier.testTag("thinking_mode_$id")
                )
            }
        }

        Text("Persona Style (15 Modes)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "AI_ASSISTANT", "GF_COMPANION", "PROFESSIONAL", "FRIENDLY", "FORMAL",
                "CREATIVE", "CONCISE", "DETAILED", "ANALYTICAL", "TECHNICAL",
                "EDUCATIONAL", "HUMOROUS", "CRITICAL", "EMPATHETIC", "CUSTOM"
            ).forEach { style ->
                FilterChip(
                    selected = activePersona == style,
                    onClick = {
                        if (style == "GF_COMPANION" && !AyraPreferences.isCompanionAgeVerified(context)) {
                            showCompanionAgeGate = true
                        } else {
                            activePersona = style
                            AyraPreferences.savePersona(context, style)
                            onPersonaStyleSelected(style)
                        }
                    },
                    label = {
                        Text(
                            text = if (style == "GF_COMPANION") "COMPANION (18+)" else style.replace("_", " "),
                            fontSize = 10.sp
                        )
                    }
                )
            }
        }

        if (showCompanionAgeGate) {
            AlertDialog(
                onDismissRequest = { showCompanionAgeGate = false },
                title = { Text("18+ Companion Persona Confirmation", fontWeight = FontWeight.Bold) },
                text = { Text("Companion Mode uses affectionate, personal conversational tones intended only for adults (18+). Confirm you are 18+ to enable this optional mode.") },
                confirmButton = {
                    Button(onClick = {
                        AyraPreferences.setCompanionAgeVerified(context, true)
                        activePersona = "GF_COMPANION"
                        AyraPreferences.savePersona(context, "GF_COMPANION")
                        onPersonaStyleSelected("GF_COMPANION")
                        showCompanionAgeGate = false
                    }) { Text("Confirm 18+ & Enable") }
                },
                dismissButton = {
                    TextButton(onClick = { showCompanionAgeGate = false }) { Text("Cancel") }
                }
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Response Calibration", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Formality: ${listOf("Very Casual", "Casual", "Balanced", "Formal", "Academic")[formality.roundToInt()]}", fontSize = 12.sp)
                Slider(value = formality, onValueChange = { formality = it; AyraPreferences.setFormalityLevel(context, it.roundToInt()) }, valueRange = 0f..4f, steps = 3)

                Text("Detail Level: ${listOf("Minimal", "Brief", "Balanced", "Detailed", "Exhaustive")[detail.roundToInt()]}", fontSize = 12.sp)
                Slider(value = detail, onValueChange = { detail = it; AyraPreferences.setDetailLevel(context, it.roundToInt()) }, valueRange = 0f..4f, steps = 3)

                Text("Technical Depth: ${listOf("Layperson", "Beginner", "Intermediate", "Advanced", "Expert")[techDepth.roundToInt()]}", fontSize = 12.sp)
                Slider(value = techDepth, onValueChange = { techDepth = it; AyraPreferences.setTechnicalDepth(context, it.roundToInt()) }, valueRange = 0f..4f, steps = 3)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Include Concrete Examples", fontSize = 12.sp)
                    Switch(checked = useExamples, onCheckedChange = { useExamples = it; AyraPreferences.setUseExamples(context, it) })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Structured Markdown & Tables", fontSize = 12.sp)
                    Switch(checked = useStructured, onCheckedChange = { useStructured = it; AyraPreferences.setUseStructuredFormatting(context, it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("none", "low", "moderate", "high").forEach { freq ->
                        FilterChip(selected = emojiFreq == freq, onClick = { emojiFreq = freq; AyraPreferences.setEmojiFrequency(context, freq) }, label = { Text("Emoji: $freq", fontSize = 10.sp) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("short", "medium", "long", "comprehensive").forEach { len ->
                        FilterChip(selected = respLength == len, onClick = { respLength = len; AyraPreferences.setResponseLength(context, len) }, label = { Text("Len: $len", fontSize = 10.sp) })
                    }
                }
            }
        }

        var showCustomInstructions by remember { mutableStateOf(false) }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { showCustomInstructions = !showCustomInstructions },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Custom Instructions (Optional)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            if (showCustomInstructions) "Tap to collapse instructions" else "Tap to edit role, style & format",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = if (showCustomInstructions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showCustomInstructions) "Collapse custom instructions" else "Expand custom instructions"
                    )
                }

                AnimatedVisibility(visible = showCustomInstructions) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = rolePrompt, onValueChange = { rolePrompt = it }, label = { Text("Role / Identity for Ayra AI") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = aboutUser, onValueChange = { aboutUser = it }, label = { Text("About You (Background / Goals)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = howRespond, onValueChange = { howRespond = it }, label = { Text("How Ayra AI Should Respond") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = outputFormat, onValueChange = { outputFormat = it }, label = { Text("Preferred Output Format") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = interests, onValueChange = { interests = it }, label = { Text("Domain Stack & Interests") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = restrictions, onValueChange = { restrictions = it }, label = { Text("Avoid / Restrictions") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = freeform, onValueChange = { freeform = it }, label = { Text("Additional Freeform Instructions") }, minLines = 2, modifier = Modifier.fillMaxWidth())

                        Button(
                            onClick = {
                                AyraPreferences.setUserCustomInstructions(context, freeform)
                                AyraPreferences.saveStructuredInstructions(
                                    context,
                                    AyraPreferences.StructuredInstructions(rolePrompt, aboutUser, howRespond, outputFormat, interests, restrictions)
                                )
                                Toast.makeText(context, "Encrypted personalization saved!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth().testTag("save_personalization_button")
                        ) { Text("Save Custom Instructions") }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Persistent Encrypted Memory", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Switch(checked = memoryEnabled, onCheckedChange = { memoryEnabled = it; AyraPreferences.setMemoryEnabled(context, it) })
        }

        OutlinedButton(
            onClick = onOpenMemoryVault,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Open Encrypted Memory Vault (Manage / Search / Edit)") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = newFact, onValueChange = { newFact = it }, label = { Text("Add memory fact...") }, modifier = Modifier.weight(1f), singleLine = true)
            IconButton(
                onClick = {
                    val clean = newFact.trim()
                    if (clean.isNotBlank()) {
                        if (AyraPreferences.addRememberedFact(context, clean)) {
                            facts = AyraPreferences.getRememberedFacts(context).toList()
                            newFact = ""
                        } else {
                            Toast.makeText(context, "Blocked: credentials or PII cannot be stored in memory.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.size(48.dp)
            ) { Icon(Icons.Default.Add, contentDescription = "Add Fact", tint = uiConfig.accentColor) }
        }

        facts.forEach { fact ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("• $fact", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        AyraPreferences.removeRememberedFact(context, fact)
                        facts = AyraPreferences.getRememberedFacts(context).toList()
                    },
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Default.Delete, contentDescription = "Forget Fact", tint = MaterialTheme.colorScheme.error) }
            }
        }
        if (facts.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    AyraPreferences.clearAllMemory(context)
                    facts = emptyList()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clear All Remembered Facts") }
        }
    }
}

@Composable
fun DeviceAndUsageDashboardPanel(
    context: Context,
    onOpenModels: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSecurityCenter: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val uiConfig = LocalAyraUiConfig.current
    val usageMetrics by AiUsageTracker.metricsFlow.collectAsStateWithLifecycle()
    val dailyUsage by AiUsageTracker.dailyStatsFlow.collectAsStateWithLifecycle()

    var sessionCount by remember { mutableStateOf(0) }
    var messageCount by remember { mutableStateOf(0) }
    var ramSummary by remember { mutableStateOf("Calculating...") }
    var storageSummary by remember { mutableStateOf("Calculating...") }

    var ollamaHost by remember { mutableStateOf(AyraPreferences.getOllamaHost(context)) }
    var ollamaModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var ollamaStatus by remember { mutableStateOf("Tap Probe to fetch live /api/tags") }
    var isProbingOllama by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        AiUsageTracker.loadIfNeeded(context)
        withContext(Dispatchers.IO) {
            val db = ChatDatabase.getDatabase(context)
            sessionCount = runCatching { db.chatSessionDao().getSessionCount() }.getOrDefault(0)
            messageCount = runCatching { db.chatDao().getTotalMessageCount() }.getOrDefault(0)

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val availMb = mi.availMem / (1024L * 1024L)
                val totalMb = mi.totalMem / (1024L * 1024L)
                ramSummary = "$availMb MB free / $totalMb MB total"
            }
            runCatching {
                val stat = StatFs(Environment.getDataDirectory().path)
                val availGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
                val totalGb = (stat.blockCountLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
                storageSummary = String.format(Locale.US, "%.1f GB free / %.1f GB total", availGb, totalGb)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Real Device & Workspace Telemetry", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = uiConfig.accentColor)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp), tint = uiConfig.accentColor)
                    Text("System RAM: $ramSummary", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp), tint = uiConfig.accentColor)
                    Text("Internal Storage: $storageSummary", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp), tint = uiConfig.accentColor)
                    Text("Conversations: $sessionCount sessions • $messageCount messages", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ollama / Local LLM Host Live Probe", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = ollamaHost,
                    onValueChange = { ollamaHost = it },
                    label = { Text("Ollama Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            AyraPreferences.setOllamaHost(context, ollamaHost.trim())
                            isProbingOllama = true
                            scope.launch(Dispatchers.IO) {
                                val (statusMsg, models) = runCatching {
                                    val base = ollamaHost.trim().trimEnd('/')
                                    val check = NetworkSecurityHelper.validateLocalAiEndpointUrl(context, base)
                                    if (!check.allowed) {
                                        return@runCatching (check.reason ?: "Blocked endpoint URL") to emptyList()
                                    }
                                    val req = Request.Builder().url("$base/api/tags").get().build()
                                    NetworkSecurityHelper.newClient(allowLanCleartext = true).newCall(req).execute().use { resp ->
                                        if (!resp.isSuccessful) return@use "HTTP ${resp.code}" to emptyList()
                                        val json = JSONObject(resp.readBounded())
                                        val arr = json.optJSONArray("models") ?: return@use "Connected (0 models)" to emptyList()
                                        val names = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                                        "Connected • ${names.size} local model(s) loaded" to names
                                    }
                                }.getOrElse { "Unreachable: ${it.localizedMessage ?: "Connection refused"}" to emptyList() }
                                withContext(Dispatchers.Main) {
                                    isProbingOllama = false
                                    ollamaStatus = statusMsg
                                    ollamaModels = models
                                }
                            }
                        },
                        enabled = !isProbingOllama
                    ) {
                        if (isProbingOllama) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save & Probe")
                        }
                    }
                }
                Text(ollamaStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ollamaModels.isNotEmpty()) {
                    Text("Installed Local Models: ${ollamaModels.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = uiConfig.accentColor)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Recorded AI Provider Call Telemetry", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (usageMetrics.isNotEmpty()) {
                        OutlinedButton(onClick = { AiUsageTracker.clearAll(context) }) { Text("Reset", fontSize = 11.sp) }
                    }
                }
                Text(
                    "Today (${dailyUsage.dateKey}): ${dailyUsage.requestsToday} / ${dailyUsage.dailyLimit} requests • ~${dailyUsage.estimatedTokensToday} est. tokens",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dailyUsage.isOverSoftLimit || dailyUsage.isNearLimit) MaterialTheme.colorScheme.error else uiConfig.accentColor,
                    fontWeight = FontWeight.SemiBold
                )
                if (usageMetrics.isEmpty()) {
                    Text("No live API calls recorded yet in this encrypted store.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    usageMetrics.forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.8.dp, uiConfig.accentColor.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(m.displayName, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Calls: ${m.totalRequests} • Success: ${m.successRatePercent}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("Avg ${m.avgLatencyMs}ms", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = uiConfig.accentColor)
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onOpenModels, modifier = Modifier.weight(1f)) { Text("Models & Keys Hub") }
            OutlinedButton(onClick = onOpenTools, modifier = Modifier.weight(1f)) { Text("Tools & Plugins") }
        }
        OutlinedButton(onClick = onOpenSecurityCenter, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open Zero-Trust Security Vault")
        }
    }
}
