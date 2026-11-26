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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RecordVoiceOver
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.search.TavilySearchEngine
import com.ayra.ai.app.security.AndroidHardeningHelper
import com.ayra.ai.app.voice.ElevenLabsVoiceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SpecializedEndpointsGroup(
    context: Context,
    hasExternalConsent: Boolean,
    isLocalOnly: Boolean,
    editsLocked: Boolean,
    onChanged: () -> Unit
) {
    SpecializedIntegrationKeyCard(
        context = context,
        title = "Ayra Zero-Trust Backend Gateway (Route B)",
        description = "Optional public HTTPS Cloud Run gateway authenticated with your Firebase ID token & App Check.",
        portalLabel = "HTTPS Gateway URL",
        portalUrl = "https://your-ayra-gateway.run.app",
        icon = Icons.Default.Cloud,
        initialKey = AyraPreferences.getBackendGatewayUrl(context),
        placeholder = "https://ayra-gateway.example.com",
        testTagPrefix = "backend_gateway",
        editsLocked = editsLocked,
        statusBadgeOverride = when {
            AyraPreferences.getBackendGatewayUrl(context).isBlank() -> "Not set"
            isLocalOnly -> "Local-Only Blocked"
            !hasExternalConsent -> "Enable Consent"
            else -> "Gateway Ready ✓"
        },
        onSave = { endpoint ->
            val clean = endpoint.trim().trimEnd('/')
            if (clean.isNotEmpty()) {
                val urlCheck = com.ayra.ai.app.security.NetworkSecurityHelper.validatePublicHttpsUrlAsync(clean)
                if (!urlCheck.allowed) {
                    throw IllegalArgumentException(urlCheck.reason ?: "Public HTTPS URL required for Route B Gateway.")
                }
            }
            AyraPreferences.setBackendGatewayUrl(context, clean)
            if (clean.isNotEmpty()) {
                com.ayra.ai.app.security.SecureAiGatewayClient.initialize(context)
            }
            com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
            com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
            onChanged()
        },
        onClear = {
            AyraPreferences.setBackendGatewayUrl(context, "")
            com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
            com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
            onChanged()
        },
        onTest = { endpoint ->
            com.ayra.ai.app.security.SecureAiGatewayClient.probeBackendGatewayHealth(
                context = context,
                backendBaseUrl = endpoint.trim()
            )
        }
    )

    SpecializedIntegrationKeyCard(
        context = context,
        title = "Custom Proxy Bearer Token (Optional)",
        description = "Bearer token sent in Authorization header when querying your Custom Proxy / LAN Gateway.",
        portalLabel = "Proxy Secret",
        portalUrl = "Android Keystore AES-256",
        icon = Icons.Default.Key,
        initialKey = AyraPreferences.getCustomEndpointKey(context),
        placeholder = "Bearer token...",
        testTagPrefix = "custom_proxy_key",
        editsLocked = editsLocked,
        onSave = { token ->
            AyraPreferences.setCustomEndpointKey(context, token)
            onChanged()
        },
        onClear = {
            AyraPreferences.setCustomEndpointKey(context, "")
            onChanged()
        },
        onTest = {
            val customUrl = AyraPreferences.getCustomEndpointUrl(context)
            if (customUrl.isBlank()) {
                throw IllegalStateException("Save a Custom Proxy / LAN Gateway URL first.")
            }
            val urlCheck = com.ayra.ai.app.security.NetworkSecurityHelper.validateLocalAiEndpointUrlAsync(context, customUrl)
            if (!urlCheck.allowed) {
                throw IllegalArgumentException(urlCheck.reason ?: "Blocked unsafe endpoint URL.")
            }
            val start = System.currentTimeMillis()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val client = com.ayra.ai.app.security.NetworkSecurityHelper.localLanClient
                val modelsUrl = com.ayra.ai.app.security.NetworkSecurityHelper.normalizeOpenAiModelsUrl(customUrl)
                val req = okhttp3.Request.Builder()
                    .url(modelsUrl)
                    .header("Authorization", "Bearer ${it.trim()}")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    com.ayra.ai.app.security.NetworkSecurityHelper.run {
                        resp.readBounded(maxBytes = 64 * 1024L, truncateOnOverflow = true)
                    }
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                }
            }
            (System.currentTimeMillis() - start).coerceAtLeast(1L)
        }
    )

    SpecializedIntegrationKeyCard(
        context = context,
        title = "Local Ollama / LM Studio Host",
        description = "Zero-cloud local LLM endpoint on LAN or emulator host (auto-discovers /api/tags & /v1/models).",
        portalLabel = "Ollama Local Server",
        portalUrl = "http://10.0.2.2:11434",
        icon = Icons.Default.AutoAwesome,
        initialKey = if (AyraPreferences.hasExplicitOllamaHost(context)) AyraPreferences.getOllamaHost(context) else "",
        placeholder = "http://10.0.2.2:11434",
        testTagPrefix = "local_ollama",
        editsLocked = editsLocked,
        statusBadgeOverride = if (AyraPreferences.hasExplicitOllamaHost(context)) "Local Ready ✓" else null,
        onSave = { endpoint ->
            AyraPreferences.setOllamaHost(context, endpoint)
            ExternalAiProviders.clearDynamicModelCache(AiProviderType.LOCAL_OLLAMA)
            com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
            com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
            onChanged()
        },
        onClear = {
            AyraPreferences.setOllamaHost(context, "")
            ExternalAiProviders.clearDynamicModelCache(AiProviderType.LOCAL_OLLAMA)
            com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
            com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
            onChanged()
        },
        onTest = { endpoint ->
            val urlCheck = com.ayra.ai.app.security.NetworkSecurityHelper.validateLocalAiEndpointUrlAsync(context, endpoint)
            if (!urlCheck.allowed) {
                throw IllegalArgumentException(urlCheck.reason ?: "Blocked unsafe Ollama endpoint URL.")
            }
            val latency = com.ayra.ai.app.ai.openmodel.OpenModelEngine(context).testConnection(endpoint)
            ExternalAiProviders.clearDynamicModelCache(AiProviderType.LOCAL_OLLAMA)
            runCatching { ExternalAiProviders(context).discoverDynamicModels(AiProviderType.LOCAL_OLLAMA) }
            latency
        }
    )

    SpecializedIntegrationKeyCard(
        context = context,
        title = "Tavily Search Engine",
        description = "Provides live web grounding with real citations for news, facts, and research.",
        portalLabel = "Tavily Platform",
        portalUrl = "https://app.tavily.com",
        icon = Icons.Default.Language,
        initialKey = AyraPreferences.getTavilySearchKey(context),
        placeholder = "tvly-...",
        editsLocked = editsLocked,
        onSave = { AyraPreferences.setTavilySearchKey(context, it); onChanged() },
        onClear = { AyraPreferences.setTavilySearchKey(context, ""); onChanged() },
        onTest = { key -> TavilySearchEngine(context).testConnection(key) }
    )

    SpecializedIntegrationKeyCard(
        context = context,
        title = "ElevenLabs Neural Voice",
        description = "High-fidelity AI voice synthesis with natural human cadence and tone.",
        portalLabel = "ElevenLabs Console",
        portalUrl = "https://elevenlabs.io",
        icon = Icons.Default.RecordVoiceOver,
        initialKey = AyraPreferences.getElevenLabsKey(context),
        placeholder = "sk_...",
        editsLocked = editsLocked,
        onSave = { AyraPreferences.setElevenLabsKey(context, it); onChanged() },
        onClear = { AyraPreferences.setElevenLabsKey(context, ""); onChanged() },
        onTest = { key -> ElevenLabsVoiceEngine(context).testConnection(key) }
    )

    SpecializedIntegrationKeyCard(
        context = context,
        title = "Custom Proxy / LAN Gateway",
        description = "Custom OpenAI-compatible HTTP inference proxy or gateway endpoint for local network servers.",
        portalLabel = "Local Gateway",
        portalUrl = "http://10.0.2.2:8000",
        icon = Icons.Default.AutoAwesome,
        initialKey = AyraPreferences.getCustomEndpointUrl(context),
        placeholder = "http://10.0.2.2:8000/v1",
        editsLocked = editsLocked,
        onSave = {
            AyraPreferences.setCustomEndpointUrl(context, it)
            ExternalAiProviders.clearDynamicModelCache(AiProviderType.LOCAL_OLLAMA)
            com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
            com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
            onChanged()
        },
        onClear = {
            AyraPreferences.setCustomEndpointUrl(context, "")
            ExternalAiProviders.clearDynamicModelCache(AiProviderType.LOCAL_OLLAMA)
            com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
            com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
            onChanged()
        },
        onTest = { endpoint ->
            val urlCheck = com.ayra.ai.app.security.NetworkSecurityHelper.validateLocalAiEndpointUrlAsync(context, endpoint)
            if (!urlCheck.allowed) {
                throw IllegalArgumentException(urlCheck.reason ?: "Blocked unsafe endpoint URL.")
            }
            val start = System.currentTimeMillis()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val client = com.ayra.ai.app.security.NetworkSecurityHelper.localLanClient.newBuilder()
                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val clean = endpoint.trim().trimEnd('/')
                val base = clean.removeSuffix("/v1")
                val probes = listOf("$base/v1/models", "$base/api/tags", "$clean/", clean).distinct()
                var verified = false
                var lastCode = 0
                for (probeUrl in probes) {
                    val req = okhttp3.Request.Builder().url(probeUrl).get().build()
                    val code = runCatching {
                        client.newCall(req).execute().use { resp -> resp.code }
                    }.getOrDefault(0)
                    if (code in 200..299 || ((code == 401 || code == 403) && probeUrl.endsWith("/v1/models"))) {
                        verified = true
                        break
                    }
                    if (code > 0) lastCode = code
                }
                if (!verified) {
                    throw IllegalStateException(
                        if (lastCode > 0) "Proxy health check failed (HTTP $lastCode)" else "Proxy unreachable"
                    )
                }
            }
            (System.currentTimeMillis() - start).coerceAtLeast(1L)
        }
    )

    SpecializedIntegrationKeyCard(
        context = context,
        title = "ComfyUI Media Server (Optional)",
        description = "Connect a local/LAN or cloud ComfyUI server for real Flux/SDXL/Wan image & video workflows.",
        portalLabel = "ComfyUI Endpoint",
        portalUrl = "http://10.0.2.2:8188",
        icon = Icons.Default.AutoAwesome,
        initialKey = AyraPreferences.getComfyUiUrl(context),
        placeholder = "http://10.0.2.2:8188",
        editsLocked = editsLocked,
        onSave = { AyraPreferences.setComfyUiUrl(context, it); onChanged() },
        onClear = { AyraPreferences.setComfyUiUrl(context, ""); onChanged() },
        onTest = { endpoint ->
            val urlCheck = com.ayra.ai.app.security.NetworkSecurityHelper.validateLocalAiEndpointUrlAsync(context, endpoint)
            if (!urlCheck.allowed) {
                throw IllegalArgumentException(urlCheck.reason ?: "Blocked unsafe ComfyUI URL.")
            }
            val start = System.currentTimeMillis()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val client = com.ayra.ai.app.security.NetworkSecurityHelper.localLanClient.newBuilder()
                    .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val clean = endpoint.trimEnd('/')
                val req = okhttp3.Request.Builder().url("$clean/system_stats").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                }
            }
            (System.currentTimeMillis() - start).coerceAtLeast(1L)
        }
    )
}

@Composable
fun SpecializedIntegrationKeyCard(
    context: Context,
    title: String,
    description: String,
    portalLabel: String,
    portalUrl: String,
    icon: ImageVector,
    initialKey: String,
    placeholder: String,
    onSave: suspend (String) -> Unit,
    onClear: () -> Unit,
    onTest: suspend (String) -> Long,
    testTagPrefix: String? = null,
    statusBadgeOverride: String? = null,
    editsLocked: Boolean = false
) {
    val scope = rememberCoroutineScope()

    var keyInput by remember { mutableStateOf(initialKey) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val isConfigured = keyInput.isNotBlank()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isConfigured) Color(0xFF10B981).copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
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
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isConfigured) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isConfigured) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = statusBadgeOverride ?: if (isConfigured) "Configured ✓" else "Not set",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isConfigured) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = keyInput,
                onValueChange = { if (!editsLocked) keyInput = it },
                readOnly = editsLocked,
                enabled = !editsLocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (testTagPrefix != null) it.testTag("${testTagPrefix}_input") else it },
                placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
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
                text = "Get key at: $portalLabel ($portalUrl)",
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
                        onClick = { showClearConfirm = true }
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
                                val latency = onTest(keyInput.trim())
                                testResult = "✓ Connected successfully in ${latency}ms!"
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
                    enabled = !isTesting && keyInput.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .let { if (testTagPrefix != null) it.testTag("${testTagPrefix}_test") else it }
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
                        scope.launch {
                            isSaving = true
                            testResult = null
                            isError = false
                            try {
                                onSave(keyInput.trim())
                                testResult = "Saved securely to Android Keystore ✓"
                                isError = false
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                testResult = e.message ?: "Validation failed"
                                isError = true
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    enabled = !editsLocked && !isSaving,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .let { if (testTagPrefix != null) it.testTag("${testTagPrefix}_save") else it }
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Save", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Remove $title?") },
            text = { Text("This deletes the saved configuration from Android Keystore.") },
            confirmButton = {
                Button(
                    onClick = {
                        keyInput = ""
                        onClear()
                        testResult = null
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Remove")
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
