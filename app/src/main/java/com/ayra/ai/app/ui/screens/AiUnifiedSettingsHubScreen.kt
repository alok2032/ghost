package com.ayra.ai.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Switch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.ayra.ai.app.security.NetworkConnectivityManager
import com.ayra.ai.app.ui.components.ProviderKeysSection
import com.ayra.ai.app.ui.theme.LocalAyraUiConfig

@Composable
fun AiUnifiedSettingsHubScreen(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSecurityCenter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiConfig = LocalAyraUiConfig.current

    BackHandler { onBack() }

    var refreshTick by remember { mutableIntStateOf(0) }
    var editsLocked by remember { mutableStateOf(true) }

    var selectedTier by remember { mutableStateOf(AyraPreferences.getSavedModelTier(context)) }
    val thinkingMode by AyraPreferences.thinkingModeFlow.collectAsStateWithLifecycle(
        initialValue = AyraPreferences.getThinkingMode(context)
    )
    val networkState by NetworkConnectivityManager.stateFlow.collectAsStateWithLifecycle()

    val isLocalOnly = remember(refreshTick) { AyraPreferences.isLocalOnlyAiModeEnabled(context) }
    val hasConsent = remember(refreshTick) { AyraPreferences.hasExternalAiConsent(context) }
    val freePoolOn = remember(refreshTick) { AyraPreferences.isFreeAiPoolEnabled(context) }
    val firebaseAvailable = remember(refreshTick) { FirebaseAiEngine.isAvailable(context) }
    val gatewayUrl = remember(refreshTick) { AyraPreferences.getBackendGatewayUrl(context) }
    val activeChannelCount = remember(refreshTick) {
        runCatching { ExternalAiProviders(context).getActiveChannels().size }.getOrDefault(0)
    }

    val tierChoices = remember {
        listOf(
            ModelTier.AUTO to "Auto Smart",
            ModelTier.OMNI_ROUTE to "Multi-AI Best",
            ModelTier.GEMINI_FLASH to "Gemini Flash",
            ModelTier.GEMINI_PRO to "Gemini Pro",
            ModelTier.OPEN_WEIGHTS to "Open Weights"
        )
    }
    val thinkingChoices = remember {
        listOf(
            "fast" to "Fast",
            "balanced" to "Balanced",
            "deep_analysis" to "Deep Analysis",
            "reasoning" to "Reasoning",
            "coding" to "Coding"
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
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
                modifier = Modifier
                    .size(48.dp)
                    .testTag("settings_ai_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Settings",
                    tint = uiConfig.accentColor
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI Engine & Keys Hub",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "1-Tap Multi-AI & Live Search Control",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val allAutoActive = !isLocalOnly && hasConsent && freePoolOn
        Card(
            colors = CardDefaults.cardColors(
                containerColor = uiConfig.accentColor.copy(alpha = if (allAutoActive) 0.14f else 0.10f)
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    uiConfig.accentColor.copy(alpha = 0.55f),
                    RoundedCornerShape(16.dp)
                )
                .testTag("ai_hub_auto_setup_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = uiConfig.accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (allAutoActive) "All Active AIs & Live Search Ready ✓" else "Auto Multi-AI & Live Search Setup",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Text(
                    text = buildString {
                        append("Network: ")
                        append(networkState.statusSummary)
                        append("\nBuilt-in Firebase AI: ")
                        append(if (firebaseAvailable) "Active" else if (isLocalOnly) "Paused (Local-Only)" else "Unavailable")
                        append(" • External Consent: ")
                        append(if (hasConsent) "ON" else "OFF")
                        append("\nFree AI Pool + Multi-Engine: ")
                        append(if (freePoolOn) "ON" else "OFF")
                        append(" • Active Channels: $activeChannelCount")
                        if (gatewayUrl.isNotBlank()) append("\nRoute B Gateway: $gatewayUrl")
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp
                )
                Button(
                    onClick = {
                        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
                        AyraPreferences.setExternalAiConsent(context, true)
                        AyraPreferences.setFreeAiPoolEnabled(context, true)
                        ExternalAiProviders.clearDynamicModelCache(null)
                        com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
                        com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
                        refreshTick++
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("ai_hub_auto_setup_button")
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (allAutoActive) {
                            "Refresh All AI Engines & Live Search ⚡"
                        } else {
                            "Activate All AIs & Live Search (1-Tap) ⚡"
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenModels,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("ai_hub_open_models_button")
                    ) {
                        Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Models Arena", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onOpenSecurityCenter,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("ai_hub_open_security_button")
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Security Vault", fontSize = 12.sp)
                    }
                }
            }
        }

        // Preferred Tier & Thinking Mode Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Preferred Model Tier & Thinking Depth",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tierChoices.forEach { (tier, label) ->
                        FilterChip(
                            selected = selectedTier == tier.id,
                            onClick = {
                                selectedTier = tier.id
                                AyraPreferences.saveModelTier(context, tier.id)
                                refreshTick++
                            },
                            label = { Text(label, fontSize = 11.5.sp) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("ai_hub_tier_${tier.id}")
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    thinkingChoices.forEach { (modeId, label) ->
                        FilterChip(
                            selected = thinkingMode == modeId,
                            onClick = {
                                AyraPreferences.setThinkingMode(context, modeId)
                                refreshTick++
                            },
                            label = { Text(label, fontSize = 11.5.sp) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("ai_hub_thinking_$modeId")
                        )
                    }
                }
            }
        }

        // Safe Edit Lock Card to prevent accidental consent toggles or API key wipes
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (editsLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (editsLocked) uiConfig.accentColor else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (editsLocked) "Safe Edit Lock ON" else "Safe Edit Lock OFF",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                    Text(
                        text = if (editsLocked) {
                            "Unlock to change External Consent, routing rules, or BYOK keys."
                        } else {
                            "Editing enabled — lock again when finished."
                        },
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = editsLocked,
                    onCheckedChange = { editsLocked = it },
                    modifier = Modifier.testTag("ai_settings_lock_switch")
                )
            }
        }

        ProviderKeysSection(
            context = context,
            editsLocked = editsLocked,
            initiallyExpandDeveloperKeys = false,
            externalRefreshTick = refreshTick,
            onActivationChanged = { refreshTick++ }
        )

        Spacer(Modifier.height(24.dp))
    }
}
