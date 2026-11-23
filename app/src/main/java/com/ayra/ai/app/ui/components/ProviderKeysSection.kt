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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
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
import com.ayra.ai.app.ai.providers.ModelTier
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
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.search.TavilySearchEngine
import com.ayra.ai.app.security.AndroidHardeningHelper
import com.ayra.ai.app.voice.ElevenLabsVoiceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ProviderKeysSection(
    context: Context,
    modifier: Modifier = Modifier,
    editsLocked: Boolean = false,
    initiallyExpandDeveloperKeys: Boolean = true,
    onActivationChanged: () -> Unit = {}
) {
    val providers = remember {
        listOf(
            Triple(AiProviderType.GEMINI, "Google AI Studio", "https://aistudio.google.com/app/apikey"),
            Triple(AiProviderType.GROQ, "Groq Console (Free Tier)", "https://console.groq.com/keys"),
            Triple(AiProviderType.CEREBRAS, "Cerebras Cloud (Free Tier)", "https://cloud.cerebras.ai/"),
            Triple(AiProviderType.SAMBANOVA, "SambaNova Cloud (Free Tier)", "https://cloud.sambanova.ai/"),
            Triple(AiProviderType.HUGGINGFACE, "Hugging Face Tokens", "https://huggingface.co/settings/tokens"),
            Triple(AiProviderType.OPENAI, "OpenAI API", "https://platform.openai.com/api-keys"),
            Triple(AiProviderType.ANTHROPIC, "Anthropic Console", "https://console.anthropic.com/settings/keys"),
            Triple(AiProviderType.DEEPSEEK, "DeepSeek Platform", "https://platform.deepseek.com/api_keys"),
            Triple(AiProviderType.OPENROUTER, "OpenRouter (All Models)", "https://openrouter.ai/keys"),
            Triple(AiProviderType.XAI, "xAI Console", "https://console.x.ai/"),
            Triple(AiProviderType.MISTRAL, "Mistral Console", "https://console.mistral.ai/api-keys/"),
            Triple(AiProviderType.PERPLEXITY, "Perplexity AI", "https://www.perplexity.ai/settings/api")
        )
    }

    var activationRevision by remember { mutableStateOf(0) }
    var hasExternalConsent by remember { mutableStateOf(AyraPreferences.hasExternalAiConsent(context)) }
    val isLocalOnly = remember(activationRevision) { AyraPreferences.isLocalOnlyAiModeEnabled(context) }
    var showDeveloperByok by remember { mutableStateOf(initiallyExpandDeveloperKeys) }

    val notifyActivationChanged = {
        activationRevision++
        onActivationChanged()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "AI Providers & API Keys",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "All API keys are encrypted on-device via Android Keystore (AES-256-GCM). Saved cloud keys remain inactive until External Cloud AI Consent is enabled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        AiActivationAndConsentCard(
            context = context,
            hasExternalConsent = hasExternalConsent,
            isLocalOnly = isLocalOnly,
            activationRevision = activationRevision,
            editsLocked = editsLocked,
            onConsentChanged = { enabled ->
                if (editsLocked) return@AiActivationAndConsentCard
                hasExternalConsent = enabled
                AyraPreferences.setExternalAiConsent(context, enabled)
                ExternalAiProviders.clearDynamicModelCache(null)
                com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
                com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
                notifyActivationChanged()
            },
            onSynced = {
                notifyActivationChanged()
            }
        )

        // Intelligent Routing & AI Memory Control
        AiRoutingAndMemoryCard(context = context, editsLocked = editsLocked)

        OutlinedButton(
            onClick = { showDeveloperByok = !showDeveloperByok },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("developer_byok_expand_button")
        ) {
            Icon(
                imageVector = if (editsLocked) Icons.Default.Lock else Icons.Default.Key,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (showDeveloperByok) {
                    "Hide Developer / BYOK Keys & Custom Endpoints"
                } else {
                    "Developer / BYOK API Keys & Custom Endpoints"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (showDeveloperByok) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }

        if (showDeveloperByok) {
            Text(
                text = "Core LLM Providers",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )

            providers.forEach { (provider, label, portalUrl) ->
                SingleProviderKeyCard(
                    context = context,
                    provider = provider,
                    portalLabel = label,
                    portalUrl = portalUrl,
                    hasExternalConsent = hasExternalConsent,
                    isLocalOnly = isLocalOnly,
                    editsLocked = editsLocked,
                    onKeyChanged = notifyActivationChanged
                )
            }

            Text(
                text = "NVIDIA NIM Multi-Model Engine",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )

            NvidiaNimMultiKeySection(
                context = context,
                hasExternalConsent = hasExternalConsent,
                isLocalOnly = isLocalOnly,
                editsLocked = editsLocked,
                onKeyChanged = notifyActivationChanged
            )
            FreeAiPoolCard(
                context = context,
                hasExternalConsent = hasExternalConsent,
                isLocalOnly = isLocalOnly,
                editsLocked = editsLocked,
                onChanged = notifyActivationChanged
            )

            Text(
                text = "Backend Gateway (Route B), Local Models & Integrations",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )

            // AYRA Zero-Trust Node/TS Backend Gateway (Route B)
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
                        val urlCheck = com.ayra.ai.app.security.NetworkSecurityHelper.validatePublicHttpsUrl(clean)
                        if (!urlCheck.allowed) {
                            throw IllegalArgumentException(urlCheck.reason ?: "Public HTTPS URL required for Route B Gateway.")
                        }
                    }
                    AyraPreferences.setBackendGatewayUrl(context, clean)
                    com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
                    com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
                    notifyActivationChanged()
                },
                onClear = {
                    AyraPreferences.setBackendGatewayUrl(context, "")
                    com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
                    com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
                    notifyActivationChanged()
                },
                onTest = { endpoint ->
                    com.ayra.ai.app.security.SecureAiGatewayClient.probeBackendGatewayHealth(
                        context = context,
                        backendBaseUrl = endpoint.trim()
                    )
                }
            )

            // Custom Proxy Bearer Token (paired with Custom Proxy / LAN Gateway)
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
                    notifyActivationChanged()
                },
                onClear = {
                    AyraPreferences.setCustomEndpointKey(context, "")
                    notifyActivationChanged()
                },
                onTest = {
                    val customUrl = AyraPreferences.getCustomEndpointUrl(context)
                    if (customUrl.isBlank()) {
                        throw IllegalStateException("Save a Custom Proxy / LAN Gateway URL first.")
                    }
                    val urlCheck = com.ayra.ai.app.security.NetworkSecurityHelper.validateLocalAiEndpointUrl(context, customUrl)
                    if (!urlCheck.allowed) {
                        throw IllegalArgumentException(urlCheck.reason ?: "Blocked unsafe endpoint URL.")
                    }
                    val start = System.currentTimeMillis()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val client = com.ayra.ai.app.security.NetworkSecurityHelper.localLanClient
                        val req = okhttp3.Request.Builder()
                            .url("${customUrl.trimEnd('/')}/v1/models")
                            .header("Authorization", "Bearer ${it.trim()}")
                            .get()
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        }
                    }
                    (System.currentTimeMillis() - start).coerceAtLeast(1L)
                }
            )

        // Local Ollama / LM Studio Host
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
                notifyActivationChanged()
            },
            onClear = {
                AyraPreferences.setOllamaHost(context, "")
                ExternalAiProviders.clearDynamicModelCache(AiProviderType.LOCAL_OLLAMA)
                com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
                com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
                notifyActivationChanged()
            },
            onTest = { endpoint ->
                val urlCheck = com.ayra.ai.app.security.NetworkSecurityHelper.validateLocalAiEndpointUrl(context, endpoint)
                if (!urlCheck.allowed) {
                    throw IllegalArgumentException(urlCheck.reason ?: "Blocked unsafe Ollama endpoint URL.")
                }
                val latency = com.ayra.ai.app.ai.openmodel.OpenModelEngine(context).testConnection(endpoint)
                ExternalAiProviders.clearDynamicModelCache(AiProviderType.LOCAL_OLLAMA)
                runCatching { ExternalAiProviders(context).discoverDynamicModels(AiProviderType.LOCAL_OLLAMA) }
                latency
            }
        )

        // Tavily Live Web Search
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
            onSave = { AyraPreferences.setTavilySearchKey(context, it); notifyActivationChanged() },
            onClear = { AyraPreferences.setTavilySearchKey(context, ""); notifyActivationChanged() },
            onTest = { key -> TavilySearchEngine(context).testConnection(key) }
        )

        // ElevenLabs Neural Voice
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
            onSave = { AyraPreferences.setElevenLabsKey(context, it); notifyActivationChanged() },
            onClear = { AyraPreferences.setElevenLabsKey(context, ""); notifyActivationChanged() },
            onTest = { key -> ElevenLabsVoiceEngine(context).testConnection(key) }
        )

        // Custom Proxy / LAN Gateway
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
                notifyActivationChanged()
            },
            onClear = {
                AyraPreferences.setCustomEndpointUrl(context, "")
                ExternalAiProviders.clearDynamicModelCache(AiProviderType.LOCAL_OLLAMA)
                com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
                com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
                notifyActivationChanged()
            },
            onTest = { endpoint ->
                val urlCheck = com.ayra.ai.app.security.NetworkSecurityHelper.validateLocalAiEndpointUrl(context, endpoint)
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

        // Self-hosted ComfyUI Image / Video Workflow Server
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
            onSave = { AyraPreferences.setComfyUiUrl(context, it); notifyActivationChanged() },
            onClear = { AyraPreferences.setComfyUiUrl(context, ""); notifyActivationChanged() },
            onTest = { endpoint ->
                val urlCheck = com.ayra.ai.app.security.NetworkSecurityHelper.validateLocalAiEndpointUrl(context, endpoint)
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
    }
}

@Composable
private fun AiActivationAndConsentCard(
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
            Triple("quality", "Best Quality 👑", "Gemini 4 Argon / Claude 5.5"),
            Triple("fast", "Ultra Fast 🚀", "Gemini 3.8 Flash / Groq"),
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

            // AI Memory Section
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

            // Custom Instructions Input
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
                        saveKey(context, provider, keyInput.trim())
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
                        saveKey(context, provider, "")
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
    onSave: (String) -> Unit,
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
                        try {
                            onSave(keyInput.trim())
                            testResult = "Saved securely to Android Keystore ✓"
                            isError = false
                        } catch (e: Exception) {
                            testResult = e.message ?: "Validation failed"
                            isError = true
                        }
                    },
                    enabled = !editsLocked,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .let { if (testTagPrefix != null) it.testTag("${testTagPrefix}_save") else it }
                ) {
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = "Global Free AI Pool (Opt-In) 🌐",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when {
                        isLocalOnly && freePoolEnabled -> "Opted in, but Local-Only Privacy Mode blocks cloud requests."
                        freePoolEnabled && !hasExternalConsent -> "Opted in — enable External Cloud AI Consent above to activate."
                        freePoolEnabled -> "Active ✓ Optional community fallback models ready."
                        else -> "Optional community fallback models when External Cloud AI consent is enabled."
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
    }
}

private fun saveKey(context: Context, provider: AiProviderType, key: String) {
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
