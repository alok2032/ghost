package com.ayra.ai.app.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.AndroidHardeningHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SingleProviderKeyCard(
    context: Context,
    provider: AiProviderType,
    portalLabel: String,
    portalUrl: String,
    hasExternalConsent: Boolean = AyraPreferences.hasExternalAiConsent(context),
    isLocalOnly: Boolean = AyraPreferences.isLocalOnlyAiModeEnabled(context),
    editsLocked: Boolean = false,
    onKeyChanged: () -> Unit = {}
) {
    val providersEngine = remember { ExternalAiProviders(context) }
    val scope = rememberCoroutineScope()

    var keyInput by remember {
        mutableStateOf(providersEngine.getProviderKey(provider))
    }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val isRetired = provider == AiProviderType.GITHUB_MODELS
    val isConfigured = keyInput.isNotBlank() && !isRetired
    val isActiveReady = isConfigured && !isLocalOnly && hasExternalConsent

    val (badgeLabel, badgeColor) = when {
        isRetired -> "Retired 2026-07-30" to Color(0xFFEF4444)
        !isConfigured -> "Not set" to MaterialTheme.colorScheme.onSurfaceVariant
        isLocalOnly -> "Local-Only Blocked" to Color(0xFFF59E0B)
        !hasExternalConsent -> "Enable Consent" to Color(0xFFF59E0B)
        else -> "Active ✓" to Color(0xFF10B981)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                when {
                    isActiveReady -> Color(0xFF10B981).copy(alpha = 0.45f)
                    isConfigured -> Color(0xFFF59E0B).copy(alpha = 0.45f)
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                }
            )
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = if (isActiveReady) Color(0xFF10B981) else if (isConfigured) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (badgeColor == MaterialTheme.colorScheme.onSurfaceVariant) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        badgeColor.copy(alpha = 0.15f)
                    }
                ) {
                    Text(
                        text = badgeLabel,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .testTag("provider_status_badge_${provider.id}"),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = badgeColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = keyInput,
                onValueChange = { if (!editsLocked) keyInput = it },
                readOnly = editsLocked,
                enabled = !editsLocked,
                modifier = Modifier.fillMaxWidth().testTag("provider_key_input_${provider.id}"),
                placeholder = {
                    Text(
                        "Enter ${provider.displayName} API Key",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                singleLine = true,
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(
                        onClick = { isPasswordVisible = !isPasswordVisible },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle visibility",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )

            Text(
                text = if (isRetired) {
                    "Notice: Azure GitHub Models endpoint shut down 2026-07-30 and cannot run inference."
                } else {
                    "Get key at: $portalLabel ($portalUrl)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )

            AnimatedVisibility(visible = testResult != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            if (isError) Color(0xFFEF4444).copy(alpha = 0.1f) else Color(0xFF10B981).copy(alpha = 0.1f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isError) Color(0xFFEF4444) else Color(0xFF10B981),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = testResult ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) Color(0xFFEF4444) else Color(0xFF10B981)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    enabled = !editsLocked,
                    onClick = {
                        AndroidHardeningHelper.readPlainTextFromClipboard(context)?.let {
                            if (it.isNotBlank()) keyInput = it.trim()
                        }
                    }
                ) {
                    Text("Paste", style = MaterialTheme.typography.labelMedium)
                }

                if (keyInput.isNotBlank()) {
                    TextButton(
                        enabled = !editsLocked,
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.testTag("provider_key_clear_${provider.id}")
                    ) {
                        Text("Clear", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isTesting = true
                            testResult = null
                            isError = false
                            try {
                                val latency = providersEngine.testProviderConnection(provider, keyInput.trim())
                                val discoveredCount = runCatching {
                                    providersEngine.discoverDynamicModels(provider).size
                                }.getOrDefault(0)
                                testResult = "✓ Connected in ${latency}ms ($discoveredCount models)"
                                isError = false
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                testResult = "Connection failed: ${e.message}"
                                isError = true
                            } finally {
                                isTesting = false
                            }
                        }
                    },
                    enabled = !isTesting && keyInput.isNotBlank() && !isRetired,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("provider_key_test_${provider.id}")
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Test", style = MaterialTheme.typography.labelMedium)
                }

                Spacer(modifier = Modifier.width(6.dp))

                Button(
                    onClick = {
                        saveProviderKey(context, provider, keyInput.trim())
                        onKeyChanged()
                        testResult = if (hasExternalConsent) {
                            "Key saved securely to Android Keystore ✓"
                        } else {
                            "Key saved securely. Enable External Cloud AI Consent above to activate."
                        }
                        isError = false
                    },
                    enabled = !isRetired && !editsLocked,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("provider_key_save_${provider.id}")
                ) {
                    Text("Save", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Remove ${provider.displayName} Key?") },
            text = { Text("This deletes the stored key from Android Keystore.") },
            confirmButton = {
                Button(
                    onClick = {
                        keyInput = ""
                        saveProviderKey(context, provider, "")
                        onKeyChanged()
                        testResult = null
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_clear_key_${provider.id}")
                ) {
                    Text("Remove Key")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirm = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun saveProviderKey(context: Context, provider: AiProviderType, key: String) {
    when (provider) {
        AiProviderType.GEMINI -> AyraPreferences.setCustomGeminiKey(context, key)
        AiProviderType.NVIDIA_NIM -> AyraPreferences.setNvidiaKey(context, key)
        AiProviderType.GROQ -> AyraPreferences.setGroqKey(context, key)
        AiProviderType.CEREBRAS -> AyraPreferences.setCerebrasKey(context, key)
        AiProviderType.SAMBANOVA -> AyraPreferences.setSambaNovaKey(context, key)
        AiProviderType.HUGGINGFACE -> AyraPreferences.setHuggingFaceKey(context, key)
        AiProviderType.GITHUB_MODELS -> AyraPreferences.setGitHubModelsKey(context, key)
        AiProviderType.POLLINATIONS_FREE -> {}
        AiProviderType.OPENAI -> AyraPreferences.setOpenAiKey(context, key)
        AiProviderType.ANTHROPIC -> AyraPreferences.setAnthropicKey(context, key)
        AiProviderType.DEEPSEEK -> AyraPreferences.setDeepSeekKey(context, key)
        AiProviderType.XAI -> AyraPreferences.setXAiKey(context, key)
        AiProviderType.MISTRAL -> AyraPreferences.setMistralKey(context, key)
        AiProviderType.PERPLEXITY -> AyraPreferences.setPerplexityKey(context, key)
        AiProviderType.OPENROUTER -> AyraPreferences.setCustomOpenRouterKey(context, key)
        AiProviderType.LOCAL_OLLAMA -> AyraPreferences.setCustomEndpointUrl(context, key)
        AiProviderType.OMNI_ROUTE -> {}
    }
    ExternalAiProviders.clearDynamicModelCache(provider)
    com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
    com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
    try {
        ExternalAiProviders(context).circuitBreaker.resetProvider(provider)
    } catch (e: Exception) {
        android.util.Log.w("ProviderKeysSection", "Circuit reset failed for ${provider.id}: ${e.message}")
    }
}
