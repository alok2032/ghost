package com.ayra.ai.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelTier
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.ui.components.DeviceAndUsageDashboardPanel
import com.ayra.ai.app.ui.components.PersonalizationAndMemoryPanel
import com.ayra.ai.app.ui.components.ProviderKeysSection
import com.ayra.ai.app.ui.theme.LocalAyraUiConfig
import com.ayra.ai.app.ui.theme.resolveAccentColor
import java.util.Locale
import kotlin.math.roundToInt

private enum class SettingsTab(val label: String) {
    APPEARANCE("Themes & Layout"),
    DASHBOARD("Usage & Device Health")
}

@Composable
fun SettingsAppearanceScreen(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSecurityCenter: () -> Unit,
    onPersonaChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenMemory: () -> Unit = {},
    onModelTierChanged: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val uiConfig = LocalAyraUiConfig.current
    var activeTab by remember { mutableStateOf(SettingsTab.APPEARANCE) }
    var showAiHubScreen by rememberSaveable { mutableStateOf(false) }

    if (showAiHubScreen) {
        BackHandler {
            showAiHubScreen = false
        }
        AiUnifiedSettingsHubScreen(
            onBackToSettings = { showAiHubScreen = false },
            onOpenModels = onOpenModels,
            onOpenMemory = onOpenMemory,
            onPersonaChanged = onPersonaChanged,
            onModelTierChanged = onModelTierChanged,
            modifier = modifier
        )
    } else {
        val themeMode by AyraPreferences.themeModeFlow.collectAsStateWithLifecycle()
        val accentPreset by AyraPreferences.accentPresetFlow.collectAsStateWithLifecycle()
        val customHexFlow by AyraPreferences.customAccentHexFlow.collectAsStateWithLifecycle()
        var customHexDraft by remember(customHexFlow) { mutableStateOf(customHexFlow) }
        val fontScale by AyraPreferences.fontScaleFlow.collectAsStateWithLifecycle()
        val bubbleCornerDp by AyraPreferences.bubbleCornerDpFlow.collectAsStateWithLifecycle()
        val uiDensity by AyraPreferences.uiDensityFlow.collectAsStateWithLifecycle()
        val reducedMotion by AyraPreferences.reducedMotionFlow.collectAsStateWithLifecycle()

        val activeEnginesCount = remember {
            ExternalAiProviders(context).getActiveConfiguredProviders().size +
                if (FirebaseAiEngine.isAvailable(context)) 1 else 0
        }

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
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp).testTag("settings_appearance_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = uiConfig.accentColor)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Themes, typography, device health & protected AI hub.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Dedicated "AI" Button — All AI engines, model selection, thinking modes & API keys live safely inside
                Card(
                    onClick = { showAiHubScreen = true },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = uiConfig.accentColor.copy(alpha = 0.14f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .border(1.2.dp, uiConfig.accentColor.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                        .testTag("settings_ai_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(uiConfig.accentColor.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = uiConfig.accentColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "$activeEnginesCount active engines • All API keys, models, thinking & edit lock",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Open AI Settings",
                            tint = uiConfig.accentColor
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsTab.entries.forEach { tab ->
                        val icon = when (tab) {
                            SettingsTab.APPEARANCE -> Icons.Default.Palette
                            SettingsTab.DASHBOARD -> Icons.Default.Dashboard
                        }
                        FilterChip(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(tab.label, fontSize = 12.sp) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("settings_tab_${tab.name.lowercase()}")
                        )
                    }
                }

                when (activeTab) {
                    SettingsTab.APPEARANCE -> {
                        Text("Theme Mode (6 Modes)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "dark" to "Cosmic Dark",
                                "light" to "Clean Light",
                                "system" to "System",
                                "auto" to "Day/Night Auto",
                                "high_contrast" to "High Contrast",
                                "custom" to "Midnight Indigo"
                            ).forEach { (id, label) ->
                                FilterChip(
                                    selected = themeMode == id,
                                    onClick = { AyraPreferences.setThemeMode(context, id) },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("theme_mode_$id")
                                )
                            }
                        }

                        Text("Accent Color (8 Presets)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf("cyan", "blue", "purple", "green", "orange", "pink", "red", "custom").forEach { preset ->
                                val (swatchColor, _) = resolveAccentColor(preset, customHexDraft, uiConfig.isDark)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(swatchColor)
                                            .then(
                                                if (accentPreset == preset) Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                                                else Modifier
                                            )
                                            .clickable { AyraPreferences.setAccentPreset(context, preset) }
                                            .testTag("accent_preset_$preset")
                                    )
                                    Text(preset, fontSize = 10.sp)
                                }
                            }
                        }

                        if (accentPreset == "custom") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = customHexDraft,
                                    onValueChange = { customHexDraft = it },
                                    label = { Text("Custom Hex (#RRGGBB)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { AyraPreferences.setCustomAccentHex(context, customHexDraft) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("Apply")
                                }
                            }
                        }

                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Font Scale: ${String.format(Locale.US, "%.2fx", fontScale)}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(0.88f to "Small", 1.0f to "Default", 1.12f to "Large", 1.24f to "XL").forEach { (sc, label) ->
                                        FilterChip(
                                            selected = kotlin.math.abs(fontScale - sc) < 0.03f,
                                            onClick = { AyraPreferences.setFontScale(context, sc) },
                                            label = { Text(label, fontSize = 11.sp) },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        )
                                    }
                                }
                                Slider(
                                    value = fontScale,
                                    onValueChange = { AyraPreferences.setFontScale(context, it) },
                                    valueRange = 0.85f..1.30f
                                )

                                Text("Message Bubble Corner Radius: ${bubbleCornerDp}dp", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Slider(
                                    value = bubbleCornerDp.toFloat(),
                                    onValueChange = { AyraPreferences.setBubbleCornerDp(context, it.roundToInt()) },
                                    valueRange = 6f..28f
                                )

                                Text("UI Layout Density", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("compact", "balanced", "spacious").forEach { d ->
                                        FilterChip(
                                            selected = uiDensity == d,
                                            onClick = { AyraPreferences.setUiDensity(context, d) },
                                            label = { Text(d.replaceFirstChar { it.uppercase() }, fontSize = 11.sp) },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        )
                                    }
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Reduced Motion", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text("Minimizes ambient animations & reduces GPU usage", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = reducedMotion,
                                        onCheckedChange = { AyraPreferences.setReducedMotionEnabled(context, it) },
                                        modifier = Modifier.testTag("reduced_motion_switch")
                                    )
                                }
                            }
                        }

                        // Live Preview Card
                        Card(
                            shape = RoundedCornerShape(bubbleCornerDp.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, uiConfig.accentColor.copy(alpha = 0.5f), RoundedCornerShape(bubbleCornerDp.dp)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(uiConfig.bubblePaddingDp)) {
                                Text("Live Bubble Preview", color = uiConfig.accentColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(4.dp))
                                Text("Theme: $themeMode • Density: $uiDensity • Font: ${String.format(Locale.US, "%.2fx", fontScale)}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    SettingsTab.DASHBOARD -> {
                        DeviceAndUsageDashboardPanel(
                            context = context,
                            onOpenModels = onOpenModels,
                            onOpenTools = onOpenTools,
                            onOpenSecurityCenter = onOpenSecurityCenter,
                            onOpenAiSettings = { showAiHubScreen = true }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiUnifiedSettingsHubScreen(
    onBackToSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenMemory: () -> Unit,
    onPersonaChanged: (String) -> Unit,
    onModelTierChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiConfig = LocalAyraUiConfig.current
    var aiEditLocked by rememberSaveable { mutableStateOf(false) }
    var selectedModelTier by remember { mutableStateOf(AyraPreferences.getSavedModelTier(context)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onBackToSettings,
                modifier = Modifier.size(48.dp).testTag("settings_ai_back_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Settings", tint = uiConfig.accentColor)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI Control & API Keys",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("settings_ai_hub_title")
                )
                Text(
                    text = "All AI models, thinking modes, memory, consent & encrypted API keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

        // Safe Edit Lock Guard
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (aiEditLocked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (aiEditLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (aiEditLocked) MaterialTheme.colorScheme.secondary else uiConfig.accentColor
                    )
                    Column {
                        Text(
                            text = if (aiEditLocked) "Safe Edit Lock Enabled" else "Protect AI Settings (Edit Lock)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        )
                        Text(
                            text = if (aiEditLocked) "Controls are read-only so accidental taps cannot alter your AI setup."
                            else "Toggle ON after finishing setup to prevent accidental changes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = aiEditLocked,
                    onCheckedChange = { aiEditLocked = it },
                    modifier = Modifier.testTag("ai_settings_lock_switch")
                )
            }
        }

        // Model Tier Selection
        Text("Default Model Tier", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModelTier.entries.filter { it.isSelectableTier }.forEach { tier ->
                val selected = ModelTier.canonicalizeId(selectedModelTier) == tier.id
                FilterChip(
                    selected = selected,
                    enabled = !aiEditLocked,
                    onClick = {
                        selectedModelTier = tier.id
                        AyraPreferences.saveModelTier(context, tier.id)
                        onModelTierChanged(tier.id)
                    },
                    label = { Text(tier.displayName, fontSize = 11.5.sp) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ai_tier_chip_${tier.id}")
                )
            }
        }

        OutlinedButton(
            onClick = onOpenModels,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Text("Open Models Ecosystem & Live Benchmark Arena")
        }

        // All Provider Keys, Endpoints, Batch Test, Routing & Consent Controls
        ProviderKeysSection(context = context, editsLocked = aiEditLocked)

        // Thinking Modes, Persona, Calibration, Custom Instructions & Memory
        PersonalizationAndMemoryPanel(
            context = context,
            onPersonaStyleSelected = onPersonaChanged,
            onOpenMemoryVault = onOpenMemory,
            editsLocked = aiEditLocked
        )

        Spacer(Modifier.height(24.dp))
        }
    }
}
