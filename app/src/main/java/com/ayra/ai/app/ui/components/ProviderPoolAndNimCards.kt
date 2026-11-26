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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.data.AyraPreferences

private fun invalidateNvidiaNim(context: Context, onKeyChanged: () -> Unit) {
    ExternalAiProviders.clearDynamicModelCache(AiProviderType.NVIDIA_NIM)
    com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
    com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
    runCatching { ExternalAiProviders(context).circuitBreaker.resetProvider(AiProviderType.NVIDIA_NIM) }
    onKeyChanged()
}

@Composable
fun NvidiaNimMultiKeySection(
    context: Context,
    hasExternalConsent: Boolean = AyraPreferences.hasExternalAiConsent(context),
    isLocalOnly: Boolean = AyraPreferences.isLocalOnlyAiModeEnabled(context),
    editsLocked: Boolean = false,
    onKeyChanged: () -> Unit = {}
) {
    fun nimStatusBadge(key: String): String? = when {
        key.isBlank() -> "Not set"
        isLocalOnly -> "Local-Only Blocked"
        !hasExternalConsent -> "Enable Consent"
        else -> "Active ✓"
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SpecializedIntegrationKeyCard(
            context = context,
            title = "NVIDIA • Llama 3.3 70B Instruct (Verified Default)",
            description = "Verified NVIDIA NIM default model (meta/llama-3.3-70b-instruct) for general chat, coding & multilingual tasks.",
            portalLabel = "NVIDIA Build",
            portalUrl = "https://build.nvidia.com",
            icon = Icons.Default.Key,
            initialKey = AyraPreferences.getNvidiaKey(context),
            placeholder = "nvapi-...",
            testTagPrefix = "nim_llama",
            statusBadgeOverride = nimStatusBadge(AyraPreferences.getNvidiaKey(context)),
            editsLocked = editsLocked,
            onSave = {
                AyraPreferences.setNvidiaKey(context, it)
                invalidateNvidiaNim(context, onKeyChanged)
            },
            onClear = {
                AyraPreferences.setNvidiaKey(context, "")
                invalidateNvidiaNim(context, onKeyChanged)
            },
            onTest = { key ->
                ExternalAiProviders(context).testProviderConnection(
                    AiProviderType.NVIDIA_NIM,
                    customKey = key,
                    customModel = ExternalAiProviders.NVIDIA_LLAMA_MODEL
                )
            }
        )
        SpecializedIntegrationKeyCard(
            context = context,
            title = "NVIDIA • MiniMax M3.1 Flash (Vision + Fast)",
            description = "Dedicated NVIDIA NIM endpoint for multimodal image inspection & ultra-fast dialogue.",
            portalLabel = "NVIDIA Build",
            portalUrl = "https://build.nvidia.com",
            icon = Icons.Default.Key,
            initialKey = AyraPreferences.getNvidiaMinimaxKey(context),
            placeholder = "nvapi-...",
            testTagPrefix = "nim_minimax",
            statusBadgeOverride = nimStatusBadge(AyraPreferences.getNvidiaMinimaxKey(context)),
            editsLocked = editsLocked,
            onSave = {
                AyraPreferences.setNvidiaMinimaxKey(context, it)
                invalidateNvidiaNim(context, onKeyChanged)
            },
            onClear = {
                AyraPreferences.setNvidiaMinimaxKey(context, "")
                invalidateNvidiaNim(context, onKeyChanged)
            },
            onTest = { key ->
                ExternalAiProviders(context).testProviderConnection(
                    AiProviderType.NVIDIA_NIM,
                    customKey = key,
                    customModel = ExternalAiProviders.NVIDIA_MINIMAX_MODEL
                )
            }
        )
        SpecializedIntegrationKeyCard(
            context = context,
            title = "NVIDIA • GLM 5.3 Flash (Coding & Math)",
            description = "Specialist NIM channel for clean code synthesis, algorithms, and structured math.",
            portalLabel = "NVIDIA Build",
            portalUrl = "https://build.nvidia.com",
            icon = Icons.Default.Key,
            initialKey = AyraPreferences.getNvidiaGlmKey(context),
            placeholder = "nvapi-...",
            testTagPrefix = "nim_glm",
            statusBadgeOverride = nimStatusBadge(AyraPreferences.getNvidiaGlmKey(context)),
            editsLocked = editsLocked,
            onSave = {
                AyraPreferences.setNvidiaGlmKey(context, it)
                invalidateNvidiaNim(context, onKeyChanged)
            },
            onClear = {
                AyraPreferences.setNvidiaGlmKey(context, "")
                invalidateNvidiaNim(context, onKeyChanged)
            },
            onTest = { key ->
                ExternalAiProviders(context).testProviderConnection(
                    AiProviderType.NVIDIA_NIM,
                    customKey = key,
                    customModel = ExternalAiProviders.NVIDIA_GLM_MODEL
                )
            }
        )
        SpecializedIntegrationKeyCard(
            context = context,
            title = "NVIDIA • Meta Muse 30B (Creative & Chat)",
            description = "Dedicated 30B expressive multilingual prose, story, and natural companion engine.",
            portalLabel = "NVIDIA Build",
            portalUrl = "https://build.nvidia.com",
            icon = Icons.Default.Key,
            initialKey = AyraPreferences.getNvidiaMuseKey(context),
            placeholder = "nvapi-...",
            testTagPrefix = "nim_muse",
            statusBadgeOverride = nimStatusBadge(AyraPreferences.getNvidiaMuseKey(context)),
            editsLocked = editsLocked,
            onSave = {
                AyraPreferences.setNvidiaMuseKey(context, it)
                invalidateNvidiaNim(context, onKeyChanged)
            },
            onClear = {
                AyraPreferences.setNvidiaMuseKey(context, "")
                invalidateNvidiaNim(context, onKeyChanged)
            },
            onTest = { key ->
                ExternalAiProviders(context).testProviderConnection(
                    AiProviderType.NVIDIA_NIM,
                    customKey = key,
                    customModel = ExternalAiProviders.NVIDIA_MUSE_MODEL
                )
            }
        )
        SpecializedIntegrationKeyCard(
            context = context,
            title = "NVIDIA • Gemma 4 31B IT (Deep Reasoning)",
            description = "High-capacity 31B reasoning, science, and document comprehension specialist.",
            portalLabel = "NVIDIA Build",
            portalUrl = "https://build.nvidia.com",
            icon = Icons.Default.Key,
            initialKey = AyraPreferences.getNvidiaGemmaKey(context),
            placeholder = "nvapi-...",
            testTagPrefix = "nim_gemma",
            statusBadgeOverride = nimStatusBadge(AyraPreferences.getNvidiaGemmaKey(context)),
            editsLocked = editsLocked,
            onSave = {
                AyraPreferences.setNvidiaGemmaKey(context, it)
                invalidateNvidiaNim(context, onKeyChanged)
            },
            onClear = {
                AyraPreferences.setNvidiaGemmaKey(context, "")
                invalidateNvidiaNim(context, onKeyChanged)
            },
            onTest = { key ->
                ExternalAiProviders(context).testProviderConnection(
                    AiProviderType.NVIDIA_NIM,
                    customKey = key,
                    customModel = ExternalAiProviders.NVIDIA_GEMMA_MODEL
                )
            }
        )
    }
}

@Composable
fun FreeAiPoolCard(
    context: Context,
    hasExternalConsent: Boolean = AyraPreferences.hasExternalAiConsent(context),
    isLocalOnly: Boolean = AyraPreferences.isLocalOnlyAiModeEnabled(context),
    editsLocked: Boolean = false,
    onChanged: () -> Unit = {}
) {
    var freePoolEnabled by remember { mutableStateOf(AyraPreferences.isFreeAiPoolEnabled(context)) }
    var pollinationsKeyInput by remember { mutableStateOf(AyraPreferences.getPollinationsKey(context)) }
    var isKeyVisible by remember { mutableStateOf(false) }
    var keySavedMessage by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Global Free AI Pool (Auto-Active) 🌐",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            isLocalOnly && freePoolEnabled -> "Enabled, but Local-Only Privacy Mode blocks cloud requests."
                            freePoolEnabled && !hasExternalConsent -> "Paused — enable External Cloud AI Consent above to activate."
                            freePoolEnabled && pollinationsKeyInput.isNotBlank() -> "Active with Pollinations API key ✓ GPT/OSS, Qwen Coder & DeepSeek via gen.pollinations.ai."
                            freePoolEnabled -> "Active ✓ Pollinations pool ready (optional API key recommended at enter.pollinations.ai for higher quotas)."
                            else -> "Optional multi-AI fallback endpoints via gen.pollinations.ai when External Cloud AI is active."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = freePoolEnabled,
                    enabled = !editsLocked,
                    onCheckedChange = {
                        freePoolEnabled = it
                        AyraPreferences.setFreeAiPoolEnabled(context, it)
                        ExternalAiProviders.clearDynamicModelCache(AiProviderType.POLLINATIONS_FREE)
                        com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
                        com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
                        onChanged()
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = pollinationsKeyInput,
                    onValueChange = { if (!editsLocked) pollinationsKeyInput = it },
                    readOnly = editsLocked,
                    enabled = !editsLocked,
                    modifier = Modifier.weight(1f).testTag("pollinations_api_key_input"),
                    placeholder = { Text("Optional Pollinations key (enter.pollinations.ai)", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isKeyVisible = !isKeyVisible }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = if (isKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle Pollinations key visibility",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Button(
                    onClick = {
                        AyraPreferences.setPollinationsKey(context, pollinationsKeyInput.trim())
                        ExternalAiProviders.clearDynamicModelCache(AiProviderType.POLLINATIONS_FREE)
                        com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
                        com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
                        runCatching { ExternalAiProviders(context).circuitBreaker.resetProvider(AiProviderType.POLLINATIONS_FREE) }
                        keySavedMessage = if (pollinationsKeyInput.isBlank()) "Pollinations key cleared." else "Pollinations key saved ✓"
                        onChanged()
                    },
                    enabled = !editsLocked,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("pollinations_api_key_save")
                ) {
                    Text("Save", style = MaterialTheme.typography.labelMedium)
                }
            }

            keySavedMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }
        }
    }
}
