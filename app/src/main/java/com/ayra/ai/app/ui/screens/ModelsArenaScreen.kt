package com.ayra.ai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ai.AiComparisonEngine
import com.ayra.ai.app.ai.ComparisonArenaReport
import com.ayra.ai.app.ai.providers.AiModelRegistry
import com.ayra.ai.app.ai.providers.AiUsageTracker
import com.ayra.ai.app.ai.providers.ModelCategory
import com.ayra.ai.app.ai.providers.ModelHealthStatus
import com.ayra.ai.app.ui.theme.CosmicBackground
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGold
import com.ayra.ai.app.ui.theme.NeonGreen
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private enum class ArenaTab(val label: String) {
    REGISTRY("Live Registry & Health"),
    COMPARISON("Comparison Arena")
}

@Composable
fun ModelsArenaScreen(
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(ArenaTab.REGISTRY) }

    LaunchedEffect(Unit) {
        AiUsageTracker.loadIfNeeded(context)
        AiModelRegistry.refreshConfigurationStates(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp).testTag("models_arena_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                text = "AI Model Ecosystem & Arena",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            text = "Inspect real provider configuration & latency, discover live /models, or benchmark multiple models in parallel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = activeTab == ArenaTab.REGISTRY,
                onClick = { activeTab = ArenaTab.REGISTRY },
                leadingIcon = { Icon(Icons.Default.HealthAndSafety, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(ArenaTab.REGISTRY.label, fontSize = 12.sp) },
                modifier = Modifier.testTag("arena_subtab_registry")
            )
            FilterChip(
                selected = activeTab == ArenaTab.COMPARISON,
                onClick = { activeTab = ArenaTab.COMPARISON },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(ArenaTab.COMPARISON.label, fontSize = 12.sp) },
                modifier = Modifier.testTag("arena_subtab_comparison")
            )
        }

        when (activeTab) {
            ArenaTab.REGISTRY -> RegistryHealthPanel(context = context)
            ArenaTab.COMPARISON -> ComparisonArenaPanel(context = context)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun RegistryHealthPanel(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    val entries by AiModelRegistry.entries.collectAsState()
    val usageMetrics by AiUsageTracker.metricsFlow.collectAsState()
    var selectedCategory by remember { mutableStateOf<ModelCategory?>(null) }
    var isSweeping by remember { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoveryStatus by remember { mutableStateOf<String?>(null) }

    val filtered = remember(entries, selectedCategory) {
        if (selectedCategory == null) entries else entries.filter { it.category == selectedCategory }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (isSweeping) return@Button
                    isSweeping = true
                    scope.launch {
                        AiModelRegistry.runHealthSweep(context)
                        isSweeping = false
                    }
                },
                enabled = !isSweeping,
                modifier = Modifier.weight(1f).testTag("registry_health_sweep_button")
            ) {
                if (isSweeping) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (isSweeping) "Sweeping..." else "Health Sweep", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = {
                    if (isDiscovering) return@OutlinedButton
                    isDiscovering = true
                    scope.launch {
                        val discovered = AiModelRegistry.discoverAndMergeLiveModels(context)
                        discoveryStatus = if (discovered > 0) {
                            "Merged $discovered live models from configured endpoints."
                        } else {
                            "No additional live /models discovered from configured keys."
                        }
                        isDiscovering = false
                    }
                },
                enabled = !isDiscovering,
                modifier = Modifier.weight(1f).testTag("registry_discover_models_button")
            ) {
                if (isDiscovering) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (isDiscovering) "Probing..." else "Discover /models", fontSize = 12.sp)
            }
        }

        discoveryStatus?.let { msg ->
            Text(text = msg, color = NeonCyan, fontSize = 11.5.sp)
        }

        if (usageMetrics.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlassSurfaceDark)
                    .border(1.dp, NeonPurple.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .padding(12.dp)
                    .testTag("provider_usage_telemetry_card"),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Local Provider Telemetry (${usageMetrics.sumOf { it.totalRequests }} calls)",
                        color = NeonPurple,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp
                    )
                    OutlinedButton(
                        onClick = { AiUsageTracker.clearAll(context) },
                        modifier = Modifier.testTag("telemetry_clear_button")
                    ) {
                        Text("Reset", fontSize = 11.sp)
                    }
                }
                usageMetrics.take(8).forEach { metric ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = metric.displayName,
                            color = TextPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${metric.totalRequests} req • ${metric.successRatePercent}% ok • avg ${metric.avgLatencyMs}ms",
                            color = if (metric.successRatePercent >= 75) NeonGreen else NeonGold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { selectedCategory = null },
                label = { Text("All (${entries.size})", fontSize = 11.sp) }
            )
            ModelCategory.entries.forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat.label, fontSize = 11.sp) }
                )
            }
        }

        filtered.forEach { entry ->
            val statusColor = when (entry.healthStatus) {
                ModelHealthStatus.OPERATIONAL -> NeonGreen
                ModelHealthStatus.DEGRADED -> NeonGold
                ModelHealthStatus.OFFLINE -> NeonRose
                ModelHealthStatus.UNCONFIGURED -> TextSecondary
                ModelHealthStatus.UNKNOWN -> NeonCyan
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlassSurfaceDark)
                    .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.displayName,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = buildString {
                            append(entry.healthStatus.label)
                            entry.latencyMs?.let { append(" • ${it}ms") }
                        },
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "${entry.tierStatus.label} • ${entry.contextWindow} • ${entry.rateLimitNote}",
                    color = NeonCyan,
                    fontSize = 11.sp
                )
                Text(
                    text = "Model: ${entry.modelId} • ${entry.authRequirement}",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                entry.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                    Text(text = err, color = NeonRose, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ComparisonArenaPanel(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    val engine = remember { AiComparisonEngine(context) }
    val targets = remember { engine.getAvailableTargets() }

    var selectedIds by remember {
        mutableStateOf(targets.take(3).map { it.id }.toSet())
    }
    var prompt by remember { mutableStateOf("") }
    var runDebate by remember { mutableStateOf(true) }
    var isRunning by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ComparisonArenaReport?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurfaceDark)
            .border(1.dp, NeonPurple.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Select 2–4 Configured Models",
            color = NeonCyan,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )

        if (targets.isEmpty()) {
            Text(
                text = "No AI providers are configured yet. Add free or paid API keys in Tools -> API Keys Vault or enable Free AI Pool.",
                color = NeonRose,
                fontSize = 12.sp
            )
        } else {
            targets.take(8).forEach { target ->
                val checked = target.id in selectedIds
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            selectedIds = if (isChecked) {
                                (selectedIds + target.id).take(4).toSet()
                            } else {
                                selectedIds - target.id
                            }
                        }
                    )
                    Column {
                        Text(target.displayName, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(target.providerName, color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Cross-Model Debate & Verification", color = TextPrimary, fontSize = 12.5.sp)
            Switch(checked = runDebate, onCheckedChange = { runDebate = it })
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Enter prompt to benchmark in parallel") },
            modifier = Modifier.fillMaxWidth().testTag("arena_prompt_input"),
            maxLines = 3
        )

        Button(
            onClick = {
                val q = prompt.trim()
                if (q.isBlank() || isRunning || selectedIds.isEmpty()) return@Button
                isRunning = true
                scope.launch {
                    report = engine.runComparisonArena(
                        prompt = q,
                        selectedTargetIds = selectedIds,
                        runDebateVerification = runDebate
                    )
                    isRunning = false
                }
            },
            enabled = prompt.isNotBlank() && selectedIds.isNotEmpty() && !isRunning,
            modifier = Modifier.fillMaxWidth().testTag("arena_run_button")
        ) {
            if (isRunning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isRunning) "Executing Parallel Models..." else "Run Parallel Comparison")
        }

        report?.let { rep ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CosmicBackground)
                    .border(1.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Verification Synthesis (${rep.totalElapsedMs}ms total)",
                    color = NeonGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(text = rep.synthesisSummary, color = TextPrimary, fontSize = 12.5.sp, lineHeight = 18.sp)
            }

            rep.results.forEach { res ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CosmicBackground)
                        .border(
                            1.dp,
                            if (res.success) NeonCyan.copy(alpha = 0.35f) else NeonRose.copy(alpha = 0.45f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(res.displayName, color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            text = if (res.success) "${res.latencyMs}ms" else "Failed (${res.latencyMs}ms)",
                            color = if (res.success) NeonGreen else NeonRose,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = if (res.success) res.responseText else (res.errorMessage ?: "Unknown error"),
                        color = if (res.success) TextPrimary else NeonRose,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}
