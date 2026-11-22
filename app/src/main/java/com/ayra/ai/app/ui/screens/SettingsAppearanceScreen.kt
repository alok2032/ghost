package com.ayra.ai.app.ui.screens

import java.util.Locale

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.ui.components.DeviceAndUsageDashboardPanel
import com.ayra.ai.app.ui.components.PersonalizationAndMemoryPanel
import com.ayra.ai.app.ui.theme.LocalAyraUiConfig
import com.ayra.ai.app.ui.theme.resolveAccentColor
import kotlin.math.roundToInt

private enum class SettingsTab(val label: String) {
    APPEARANCE("Themes & Layout"),
    PERSONALIZATION("Thinking & Memory"),
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
    onOpenMemory: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiConfig = LocalAyraUiConfig.current
    var activeTab by remember { mutableStateOf(SettingsTab.APPEARANCE) }

    val themeMode by AyraPreferences.themeModeFlow.collectAsStateWithLifecycle()
    val accentPreset by AyraPreferences.accentPresetFlow.collectAsStateWithLifecycle()
    val customHexFlow by AyraPreferences.customAccentHexFlow.collectAsStateWithLifecycle()
    var customHexDraft by remember(customHexFlow) { mutableStateOf(customHexFlow) }
    val fontScale by AyraPreferences.fontScaleFlow.collectAsStateWithLifecycle()
    val bubbleCornerDp by AyraPreferences.bubbleCornerDpFlow.collectAsStateWithLifecycle()
    val uiDensity by AyraPreferences.uiDensityFlow.collectAsStateWithLifecycle()
    val reducedMotion by AyraPreferences.reducedMotionFlow.collectAsStateWithLifecycle()

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
                modifier = Modifier.size(48.dp).testTag("settings_appearance_back_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = uiConfig.accentColor)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Appearance & Brain Center", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Live themes, typography, density, thinking modes, memory & real device health.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsTab.entries.forEach { tab ->
                val icon = when (tab) {
                    SettingsTab.APPEARANCE -> Icons.Default.Palette
                    SettingsTab.PERSONALIZATION -> Icons.Default.Psychology
                    SettingsTab.DASHBOARD -> Icons.Default.Dashboard
                }
                FilterChip(
                    selected = activeTab == tab,
                    onClick = { activeTab = tab },
                    leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(tab.label, fontSize = 12.sp) },
                    modifier = Modifier.testTag("settings_tab_${tab.name.lowercase()}")
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
                            modifier = Modifier.testTag("theme_mode_$id")
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
                        Button(onClick = { AyraPreferences.setCustomAccentHex(context, customHexDraft) }) {
                            Text("Apply")
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Font Scale: ${String.format(Locale.US, "%.2fx", fontScale)}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0.88f to "Small", 1.0f to "Default", 1.12f to "Large", 1.24f to "XL").forEach { (sc, label) ->
                                FilterChip(
                                    selected = kotlin.math.abs(fontScale - sc) < 0.03f,
                                    onClick = { AyraPreferences.setFontScale(context, sc) },
                                    label = { Text(label, fontSize = 11.sp) }
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
                                    label = { Text(d.replaceFirstChar { it.uppercase() }, fontSize = 11.sp) }
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

            SettingsTab.PERSONALIZATION -> {
                PersonalizationAndMemoryPanel(
                    context = context,
                    onPersonaStyleSelected = onPersonaChanged,
                    onOpenMemoryVault = onOpenMemory
                )
            }

            SettingsTab.DASHBOARD -> {
                DeviceAndUsageDashboardPanel(
                    context = context,
                    onOpenModels = onOpenModels,
                    onOpenTools = onOpenTools,
                    onOpenSecurityCenter = onOpenSecurityCenter
                )
            }
        }
    }
}
