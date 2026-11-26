package com.ayra.ai.app.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelTier
import com.ayra.ai.app.data.AyraPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AiActivationAndConsentCard(
    context: Context,
    hasExternalConsent: Boolean,
    isLocalOnly: Boolean,
    activationRevision: Int,
    editsLocked: Boolean,
    onConsentChanged: (Boolean) -> Unit,
    onSynced: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isSyncingAll by remember { mutableStateOf(false) }
    var syncSummary by remember { mutableStateOf<String?>(null) }
    var syncIsError by remember { mutableStateOf(false) }

    val snapshot = remember(hasExternalConsent, isLocalOnly, activationRevision) {
        val ext = ExternalAiProviders(context)
        val activeProviders = ext.getActiveConfiguredProviders()
        val activeChannels = ext.getActiveChannels()
        val savedAny = ext.getSavedKeyProvidersIgnoringConsent()
        val fbReady = com.ayra.ai.app.ai.FirebaseAiEngine.isAvailable(context)
        Triple(activeProviders.size, activeChannels.size + if (fbReady) 1 else 0, savedAny.size)
    }
    val (activeProvidersCount, activeEnginesCount, savedCount) = snapshot

    val borderColor = when {
        activeEnginesCount > 0 -> Color(0xFF10B981).copy(alpha = 0.45f)
        savedCount > 0 && !hasExternalConsent -> Color(0xFFF59E0B).copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("provider_activation_consent_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(borderColor)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = when {
                            isLocalOnly -> "Local-Only Privacy Mode ON ($activeProvidersCount local)"
                            activeEnginesCount > 0 -> "$activeEnginesCount Active AI Engines (${if (activeProvidersCount > 0) "$activeProvidersCount BYOK/Local + Built-in" else "Built-in Platform AI"})"
                            savedCount > 0 && !hasExternalConsent -> "$savedCount Key(s) Saved • Consent Required"
                            else -> "No External or Built-In Engines Active"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = when {
                            activeEnginesCount > 0 -> Color(0xFF10B981)
                            savedCount > 0 && !hasExternalConsent -> Color(0xFFF59E0B)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        text = "Built-in Firebase AI works automatically unless Local-Only Mode is ON. Enable External Cloud AI Consent for optional third-party BYOK keys (Groq, NIM, OpenAI, Claude, etc.).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = hasExternalConsent,
                    onCheckedChange = onConsentChanged,
                    enabled = !editsLocked,
                    modifier = Modifier.testTag("external_cloud_consent_switch"),
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isSyncingAll = true
                            syncSummary = null
                            syncIsError = false
                            try {
                                val ext = ExternalAiProviders(context)
                                ExternalAiProviders.clearDynamicModelCache(null)
                                val targets = ext.getActiveConfiguredProviders()
                                    .filter { it != AiProviderType.POLLINATIONS_FREE && it != AiProviderType.OMNI_ROUTE }
                                if (targets.isEmpty()) {
                                    syncSummary = if (savedCount > 0 && !hasExternalConsent) {
                                        "Enable External Cloud AI Consent above to test & activate your saved keys."
                                    } else {
                                        "Save at least one API key or Local Ollama host first."
                                    }
                                    syncIsError = true
                                } else {
                                    val results = mutableListOf<String>()
                                    var okCount = 0
                                    for (p in targets) {
                                        val res = runCatching { ext.testProviderConnection(p) }
                                        if (res.isSuccess) {
                                            okCount++
                                            results.add("✓ ${p.displayName} (${res.getOrNull()}ms)")
                                        } else {
                                            results.add("✗ ${p.displayName}: ${res.exceptionOrNull()?.message ?: "failed"}")
                                        }
                                    }
                                    val added = runCatching {
                                        com.ayra.ai.app.ai.providers.AiModelRegistry.discoverAndMergeLiveModels(context)
                                    }.getOrDefault(0)
                                    syncSummary = "$okCount/${targets.size} active • Synced +$added live models\n" + results.joinToString(" • ")
                                    syncIsError = okCount == 0
                                    onSynced()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                syncSummary = "Verification error: ${e.message}"
                                syncIsError = true
                            } finally {
                                isSyncingAll = false
                            }
                        }
                    },
                    enabled = !isSyncingAll,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("verify_all_providers_button")
                ) {
                    if (isSyncingAll) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verifying & Syncing /models…", style = MaterialTheme.typography.labelMedium)
                    } else {
                        Text("Verify Active Providers & Sync Live /models", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (syncSummary != null) {
                Text(
                    text = syncSummary.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncIsError) Color(0xFFF59E0B) else Color(0xFF10B981)
                )
            }
        }
    }
}

@Composable
fun AiRoutingAndMemoryCard(context: Context, editsLocked: Boolean = false) {
    var selectedMode by remember { mutableStateOf(AyraPreferences.getRoutingMode(context)) }
    var isMemoryEnabled by remember { mutableStateOf(AyraPreferences.isMemoryEnabled(context)) }
    var userInstructions by remember { mutableStateOf(AyraPreferences.getUserCustomInstructions(context)) }
    var rememberedFactsCount by remember { mutableStateOf(AyraPreferences.getRememberedFacts(context).size) }
    var statusMessage by remember { mutableStateOf("") }
    var showWipeMemoryDialog by remember { mutableStateOf(false) }

    val routingModes = remember {
        listOf(
            Triple(ModelTier.AUTO.id, "Auto ⚡", "Adaptive selection"),
            Triple("quality", "Best Quality 👑", "Gemini 3.1 Pro / Claude 5.5"),
            Triple("fast", "Ultra Fast 🚀", "Gemini 3.6 Flash / Groq"),
            Triple(ModelTier.OPEN_WEIGHTS.id, "Open-Weights 🌐", "Local Ollama / OpenRouter Qwen"),
            Triple("reasoning", "Deep Reasoning 🧠", "DeepSeek V4 Pro / GPT-6.2 Astra"),
            Triple("coding", "Coding & STEM 💻", "GLM 5.3 / Codestral"),
            Triple("local", "Private LAN 🏠", "Local Ollama / LM Studio")
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "⚡ Intelligent Routing & Memory Controls",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Active Routing Mode:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                routingModes.take(3).forEach { (id, label, _) ->
                    val isSelected = selectedMode == id
                    OutlinedButton(
                        onClick = {
                            selectedMode = id
                            AyraPreferences.setRoutingMode(context, id)
                            statusMessage = "Routing set to $label ✓"
                        },
                        enabled = !editsLocked,
                        shape = RoundedCornerShape(10.dp),
                        colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text(label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                routingModes.drop(3).forEach { (id, label, _) ->
                    val isSelected = selectedMode == id
                    OutlinedButton(
                        onClick = {
                            selectedMode = id
                            AyraPreferences.setRoutingMode(context, id)
                            statusMessage = "Routing set to $label ✓"
                        },
                        enabled = !editsLocked,
                        shape = RoundedCornerShape(10.dp),
                        colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text(label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "🧠 Autonomous AI Memory",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isMemoryEnabled) "Active ($rememberedFactsCount facts remembered)" else "Disabled (Privacy mode)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rememberedFactsCount > 0) {
                        TextButton(
                            onClick = { showWipeMemoryDialog = true },
                            enabled = !editsLocked,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("wipe_memory_button")
                        ) {
                            Text("Wipe", color = Color(0xFFFF5252), fontSize = 11.sp)
                        }
                    }

                    Button(
                        onClick = {
                            isMemoryEnabled = !isMemoryEnabled
                            AyraPreferences.setMemoryEnabled(context, isMemoryEnabled)
                            statusMessage = if (isMemoryEnabled) "AI Memory Enabled ✓" else "AI Memory Disabled ✓"
                        },
                        enabled = !editsLocked,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isMemoryEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                        ),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(if (isMemoryEnabled) "Enabled" else "Disabled", fontSize = 11.sp)
                    }
                }
            }

            OutlinedTextField(
                value = userInstructions,
                onValueChange = {
                    userInstructions = it
                    AyraPreferences.setUserCustomInstructions(context, it)
                },
                readOnly = editsLocked,
                enabled = !editsLocked,
                label = { Text("Custom Instructions for Ayra AI (optional)", fontSize = 11.sp) },
                placeholder = { Text("e.g. Always respond in Bengali; prioritize concise code", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
                textStyle = MaterialTheme.typography.bodySmall
            )

            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (showWipeMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showWipeMemoryDialog = false },
            title = { Text("Wipe All Remembered Facts?") },
            text = { Text("This will permanently erase $rememberedFactsCount saved memory facts from encrypted preferences.") },
            confirmButton = {
                Button(
                    onClick = {
                        AyraPreferences.clearAllMemory(context)
                        rememberedFactsCount = 0
                        statusMessage = "All AI memory wiped clean ✓"
                        showWipeMemoryDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_wipe_memory_button")
                ) {
                    Text("Wipe Memory")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showWipeMemoryDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
