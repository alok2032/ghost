package com.ayra.ai.app.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.AiRoutingMode
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.search.TavilySearchEngine
import com.ayra.ai.app.security.AndroidHardeningHelper
import com.ayra.ai.app.security.NetworkSecurityHelper
import com.ayra.ai.app.voice.ElevenLabsVoiceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Composable
fun ProviderKeysSection(
    context: Context,
    modifier: Modifier = Modifier,
    editsLocked: Boolean = false
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

    var refreshTick by remember { mutableIntStateOf(0) }
    var externalConsent by remember(refreshTick) { mutableStateOf(AyraPreferences.hasExternalAiConsent(context)) }
    var preferByok by remember(refreshTick) { mutableStateOf(AyraPreferences.isByokPreferred(context)) }
    val isLocalOnly = AyraPreferences.isLocalOnlyAiModeEnabled(context)

    val scope = rememberCoroutineScope()
    var isTestingAll by remember { mutableStateOf(false) }
    var batchTestSummary by remember { mutableStateOf<String?>(null) }

    val configuredCount = remember(refreshTick, externalConsent, isLocalOnly) {
        ExternalAiProviders(context).getActiveConfiguredProviders().size +
            if (FirebaseAiEngine.isAvailable(context)) 1 else 0
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "AI Providers & API Keys",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "All API keys are encrypted on-device via Android Keystore (AES-256-GCM). They are never sent to telemetry servers or stored in plaintext.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (editsLocked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                Text(
                    text = "Safe Edit Lock is ON — AI keys, consent, and routing cannot be modified accidentally. Connection tests remain available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        // Cloud Consent & Routing Policy Strip
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (isLocalOnly) MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                    RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Active AI Engines Ready: $configuredCount",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (configuredCount > 0) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = when {
                                isLocalOnly -> "Local-Only Privacy Mode is active (cloud providers paused)."
                                !externalConsent -> "Cloud consent is OFF. Save your keys and enable External Cloud AI Consent below to send requests."
                                else -> "Outbound Cloud AI consent active. Multi-AI Best-Answer auto-selection enabled."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isLocalOnly || !externalConsent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isTestingAll = true
                                batchTestSummary = null
                                val engine = ExternalAiProviders(context)
                                val report = StringBuilder()
                                val llmTypes = listOf(
                                    AiProviderType.GEMINI, AiProviderType.GROQ, AiProviderType.CEREBRAS,
                                    AiProviderType.SAMBANOVA, AiProviderType.NVIDIA_NIM, AiProviderType.HUGGINGFACE,
                                    AiProviderType.OPENAI, AiProviderType.ANTHROPIC, AiProviderType.DEEPSEEK,
                                    AiProviderType.OPENROUTER, AiProviderType.XAI, AiProviderType.MISTRAL,
                                    AiProviderType.PERPLEXITY, AiProviderType.LOCAL_OLLAMA
                                )
                                var passed = 0
                                var failed = 0
                                for (prov in llmTypes) {
                                    val key = engine.getProviderKey(prov)
                                    val configured = if (prov == AiProviderType.LOCAL_OLLAMA) {
                                        AyraPreferences.getCustomEndpointUrl(context).isNotBlank() || AyraPreferences.hasExplicitOllamaHost(context)
                                    } else {
                                        key.isNotBlank()
                                    }
                                    if (!configured) continue
                                    try {
                                        val ms = engine.testProviderConnection(prov)
                                        passed++
                                        report.appendLine("✓ ${prov.displayName}: ${ms}ms")
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        failed++
                                        report.appendLine("✗ ${prov.displayName}: ${e.message ?: "Error"}")
                                    }
                                }
                                if (AyraPreferences.getTavilySearchKey(context).isNotBlank()) {
                                    try {
                                        val ms = TavilySearchEngine(context).testConnection()
                                        passed++
                                        report.appendLine("✓ Tavily Search: ${ms}ms")
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        failed++
                                        report.appendLine("✗ Tavily Search: ${e.message}")
                                    }
                                }
                                if (AyraPreferences.getElevenLabsKey(context).isNotBlank()) {
                                    try {
                                        val ms = ElevenLabsVoiceEngine(context).testConnection()
                                        passed++
                                        report.appendLine("✓ ElevenLabs Voice: ${ms}ms")
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        failed++
                                        report.appendLine("✗ ElevenLabs Voice: ${e.message}")
                                    }
                                }
                                batchTestSummary = if (passed == 0 && failed == 0) {
                                    "No configured provider keys or local endpoints found to test yet."
                                } else {
                                    "Batch Check Complete ($passed passed, $failed failed):\n${report.toString().trim()}"
                                }
                                isTestingAll = false
                            }
                        },
                        enabled = !isTestingAll,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("test_all_providers_button")
                    ) {
                        if (isTestingAll) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Testing...", fontSize = 11.5.sp)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Test All Active", fontSize = 11.5.sp)
                        }
                    }
                }

                AnimatedVisibility(visible = batchTestSummary != null) {
                    Text(
                        text = batchTestSummary ?: "",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("External Cloud AI Consent", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            "Required opt-in before sending prompts to external cloud AI keys.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = !isLocalOnly && externalConsent,
                        enabled = !editsLocked && !isLocalOnly,
                        onCheckedChange = { enable ->
                            AyraPreferences.setExternalAiConsent(context, enable)
                            externalConsent = AyraPreferences.hasExternalAiConsent(context)
                            refreshTick++
                        },
                        modifier = Modifier.testTag("provider_consent_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Prefer Direct BYOK Keys Over Proxy", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            "Uses your on-device API keys directly when available instead of routing through the backend proxy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = preferByok,
                        enabled = !editsLocked,
                        onCheckedChange = { enable ->
                            AyraPreferences.setByokPreferred(context, enable)
                            preferByok = AyraPreferences.isByokPreferred(context)
                            refreshTick++
                        },
                        modifier = Modifier.testTag("provider_byok_switch")
                    )
                }
            }
        }

        // Intelligent Routing & AI Memory Control
        AiRoutingAndMemoryCard(context = context, editsLocked = editsLocked)

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
                editsLocked = editsLocked,
                onKeyUpdated = { refreshTick++ }
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
            editsLocked = editsLocked,
            onKeyUpdated = { refreshTick++ }
        )
        FreeAiPoolCard(
            context = context,
            editsLocked = editsLocked,
            onPoolChanged = { refreshTick++ }
        )

        Text(
            text = "Search, Voice, Local & Backend Integrations",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
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
            tagPrefix = "tavily",
            onSave = {
                AyraPreferences.setTavilySearchKey(context, it)
                refreshTick++
            },
            onClear = {
                AyraPreferences.setTavilySearchKey(context, "")
                refreshTick++
            },
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
            tagPrefix = "elevenlabs",
            onSave = {
                AyraPreferences.setElevenLabsKey(context, it)
                refreshTick++
            },
            onClear = {
                AyraPreferences.setElevenLabsKey(context, "")
                refreshTick++
            },
            onTest = { key -> ElevenLabsVoiceEngine(context).testConnection(key) }
        )

        // Local Ollama / LM Studio Server URL
        SpecializedIntegrationKeyCard(
            context = context,
            title = "Local Ollama / LM Studio URL",
            description = "Offline private LLM host (probes /api/tags & /v1/models). Used automatically in Private / Local LAN mode.",
            portalLabel = "Ollama Local Server",
            portalUrl = "http://10.0.2.2:11434",
            icon = Icons.Default.Computer,
            initialKey = if (AyraPreferences.hasExplicitOllamaHost(context)) AyraPreferences.getOllamaHost(context) else "",
            placeholder = "http://10.0.2.2:11434",
            editsLocked = editsLocked,
            tagPrefix = "local_ollama_host",
            maskInputByDefault = false,
            onSave = { url ->
                AyraPreferences.setOllamaHost(context, url)
                ExternalAiProviders(context).circuitBreaker.resetProvider(AiProviderType.LOCAL_OLLAMA)
                refreshTick++
            },
            onClear = {
                AyraPreferences.setOllamaHost(context, "")
                refreshTick++
            },
            onTest = { endpoint ->
                val clean = endpoint.trim().trimEnd('/')
                val urlCheck = NetworkSecurityHelper.validateLocalAiEndpointUrl(context, clean)
                if (!urlCheck.allowed) {
                    throw IllegalArgumentException(urlCheck.reason ?: "Blocked unsafe Ollama URL.")
                }
                val start = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    val client = NetworkSecurityHelper.localLanClient.newBuilder()
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(4, TimeUnit.SECONDS)
                        .build()
                    val base = clean.removeSuffix("/v1")
                    var verified = false
                    var lastCode = 0
                    for (probeUrl in listOf("$base/api/tags", "$base/v1/models")) {
                        val req = Request.Builder().url(probeUrl).get().build()
                        val code = runCatching { client.newCall(req).execute().use { it.code } }.getOrDefault(0)
                        if (code in 200..299 || ((code == 401 || code == 403) && probeUrl.endsWith("/v1/models"))) {
                            verified = true
                            break
                        }
                        if (code > 0) lastCode = code
                    }
                    if (!verified) {
                        throw IllegalStateException(
                            if (lastCode > 0) "Ollama probe failed (HTTP $lastCode)" else "Ollama host unreachable"
                        )
                    }
                }
                (System.currentTimeMillis() - start).coerceAtLeast(1L)
            }
        )

        // Custom Proxy / LAN Gateway URL
        SpecializedIntegrationKeyCard(
            context = context,
            title = "Custom Proxy / LAN Gateway URL",
            description = "Custom OpenAI-compatible HTTP inference proxy or gateway endpoint for local network servers.",
            portalLabel = "Local Gateway",
            portalUrl = "http://10.0.2.2:8000",
            icon = Icons.Default.AutoAwesome,
            initialKey = AyraPreferences.getCustomEndpointUrl(context),
            placeholder = "http://10.0.2.2:8000/v1",
            editsLocked = editsLocked,
            tagPrefix = "custom_proxy_url",
            maskInputByDefault = false,
            onSave = { url ->
                AyraPreferences.setCustomEndpointUrl(context, url)
                ExternalAiProviders(context).circuitBreaker.resetProvider(AiProviderType.LOCAL_OLLAMA)
                refreshTick++
            },
            onClear = {
                AyraPreferences.setCustomEndpointUrl(context, "")
                refreshTick++
            },
            onTest = { endpoint ->
                val urlCheck = NetworkSecurityHelper.validateLocalAiEndpointUrl(context, endpoint)
                if (!urlCheck.allowed) {
                    throw IllegalArgumentException(urlCheck.reason ?: "Blocked unsafe endpoint URL.")
                }
                val start = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    val client = NetworkSecurityHelper.localLanClient.newBuilder()
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(3, TimeUnit.SECONDS)
                        .build()
                    val clean = endpoint.trim().trimEnd('/')
                    val base = clean.removeSuffix("/v1")
                    val bearerKey = AyraPreferences.getCustomEndpointKey(context)
                    val probes = listOf("$base/v1/models", "$base/api/tags", "$clean/", clean).distinct()
                    var verified = false
                    var lastCode = 0
                    for (probeUrl in probes) {
                        val reqBuilder = Request.Builder().url(probeUrl).get()
                        if (bearerKey.isNotBlank()) reqBuilder.header("Authorization", "Bearer $bearerKey")
                        val code = runCatching {
                            client.newCall(reqBuilder.build()).execute().use { resp -> resp.code }
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

        // Custom Proxy / LAN Gateway Bearer API Key
        SpecializedIntegrationKeyCard(
            context = context,
            title = "Custom Proxy / LAN Bearer API Key",
            description = "Optional Bearer token sent when authenticating against your Custom Proxy / vLLM / LiteLLM LAN endpoint.",
            portalLabel = "Proxy Token (Local Keystore)",
            portalUrl = "Bearer Token",
            icon = Icons.Default.Key,
            initialKey = AyraPreferences.getCustomEndpointKey(context),
            placeholder = "sk-proxy-... (optional)",
            editsLocked = editsLocked,
            tagPrefix = "custom_proxy_key",
            onSave = { token ->
                AyraPreferences.setCustomEndpointKey(context, token)
                ExternalAiProviders(context).circuitBreaker.resetProvider(AiProviderType.LOCAL_OLLAMA)
                refreshTick++
            },
            onClear = {
                AyraPreferences.setCustomEndpointKey(context, "")
                refreshTick++
            },
            onTest = { token ->
                val targetUrl = AyraPreferences.getCustomEndpointUrl(context)
                    .ifBlank { if (AyraPreferences.hasExplicitOllamaHost(context)) AyraPreferences.getOllamaHost(context) else "" }
                if (targetUrl.isBlank()) {
                    throw IllegalStateException("Save a Custom Proxy URL or Ollama Host above before testing the token.")
                }
                val urlCheck = NetworkSecurityHelper.validateLocalAiEndpointUrl(context, targetUrl)
                if (!urlCheck.allowed) {
                    throw IllegalArgumentException(urlCheck.reason ?: "Blocked unsafe endpoint URL.")
                }
                val start = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    val clean = targetUrl.trim().trimEnd('/').removeSuffix("/v1")
                    val req = Request.Builder()
                        .url("$clean/v1/models")
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()
                    val code = NetworkSecurityHelper.localLanClient.newCall(req).execute().use { it.code }
                    if (code !in 200..299) {
                        throw IllegalStateException("Bearer token rejected (HTTP $code)")
                    }
                }
                (System.currentTimeMillis() - start).coerceAtLeast(1L)
            }
        )

        // Zero-Trust Backend AI Gateway URL (Route B)
        SpecializedIntegrationKeyCard(
            context = context,
            title = "Ayra Zero-Trust Backend Gateway (Route B)",
            description = "HTTPS backend gateway URL authenticated with Firebase ID Token & App Check. Active when Direct BYOK preference is OFF.",
            portalLabel = "Zero-Trust HTTPS Server",
            portalUrl = "https://your-gateway.example.com",
            icon = Icons.Default.Cloud,
            initialKey = AyraPreferences.getBackendGatewayUrl(context),
            placeholder = "https://your-ayra-backend.example.com",
            editsLocked = editsLocked,
            tagPrefix = "backend_gateway_url",
            maskInputByDefault = false,
            onSave = { url ->
                AyraPreferences.setBackendGatewayUrl(context, url)
                refreshTick++
            },
            onClear = {
                AyraPreferences.setBackendGatewayUrl(context, "")
                refreshTick++
            },
            onTest = { url ->
                val clean = url.trim().trimEnd('/')
                val urlCheck = NetworkSecurityHelper.validatePublicHttpsUrl(clean)
                if (!urlCheck.allowed) {
                    throw IllegalArgumentException(urlCheck.reason ?: "Backend Gateway URL must be a valid public HTTPS endpoint.")
                }
                val start = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    val req = Request.Builder().url("$clean/health").get().build()
                    NetworkSecurityHelper.hardenedPooledClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("Backend /health returned HTTP ${resp.code}")
                        }
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
            icon = Icons.Default.Security,
            initialKey = AyraPreferences.getComfyUiUrl(context),
            placeholder = "http://10.0.2.2:8188",
            editsLocked = editsLocked,
            tagPrefix = "comfyui_url",
            maskInputByDefault = false,
            onSave = {
                AyraPreferences.setComfyUiUrl(context, it)
                refreshTick++
            },
            onClear = {
                AyraPreferences.setComfyUiUrl(context, "")
                refreshTick++
            },
            onTest = { endpoint ->
                val urlCheck = NetworkSecurityHelper.validateLocalAiEndpointUrl(context, endpoint)
                if (!urlCheck.allowed) {
                    throw IllegalArgumentException(urlCheck.reason ?: "Blocked unsafe ComfyUI URL.")
                }
                val start = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    val client = NetworkSecurityHelper.localLanClient.newBuilder()
                        .connectTimeout(4, TimeUnit.SECONDS)
                        .readTimeout(4, TimeUnit.SECONDS)
                        .build()
                    val clean = endpoint.trimEnd('/')
                    val req = Request.Builder().url("$clean/system_stats").build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    }
                }
                (System.currentTimeMillis() - start).coerceAtLeast(1L)
            }
        )
    }
}

@Composable
fun AiRoutingAndMemoryCard(
    context: Context,
    editsLocked: Boolean = false
) {
    var selectedMode by remember { mutableStateOf(AyraPreferences.getRoutingMode(context)) }
    var isMemoryEnabled by remember { mutableStateOf(AyraPreferences.isMemoryEnabled(context)) }
    var userInstructions by remember { mutableStateOf(AyraPreferences.getUserCustomInstructions(context)) }
    var rememberedFactsCount by remember { mutableIntStateOf(AyraPreferences.getRememberedFacts(context).size) }
    var statusMessage by remember { mutableStateOf("") }
    var showWipeConfirm by remember { mutableStateOf(false) }

    val routingModes = remember {
        AiRoutingMode.entries.map { mode ->
            Triple(mode.id, "${mode.title} ${mode.icon}", mode.description)
        }
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

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                routingModes.chunked(2).forEach { rowModes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowModes.forEach { (id, label, desc) ->
                            val isSelected = selectedMode == id
                            OutlinedButton(
                                onClick = {
                                    selectedMode = id
                                    AyraPreferences.setRoutingMode(context, id)
                                    statusMessage = "Routing set to $label ✓ ($desc)"
                                },
                                enabled = !editsLocked,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("routing_mode_btn_$id")
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (rowModes.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (rememberedFactsCount > 0) {
                        TextButton(
                            onClick = { showWipeConfirm = true },
                            enabled = !editsLocked,
                            modifier = Modifier.heightIn(min = 48.dp)
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
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("Wipe All AI Memory Facts?") },
            text = { Text("This will permanently erase all $rememberedFactsCount remembered preferences and profile facts from encrypted storage.") },
            confirmButton = {
                Button(
                    onClick = {
                        AyraPreferences.clearAllMemory(context)
                        rememberedFactsCount = 0
                        statusMessage = "All AI memory wiped clean ✓"
                        showWipeConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Wipe Memory") }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }, modifier = Modifier.heightIn(min = 48.dp)) {
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
    editsLocked: Boolean = false,
    onKeyUpdated: () -> Unit = {}
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

    val isConfigured = keyInput.isNotBlank()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
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
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = if (isConfigured) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isConfigured) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (isConfigured) "Configured ✓" else "Not set",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isConfigured) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
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
                    onClick = {
                        AndroidHardeningHelper.readPlainTextFromClipboard(context)?.let {
                            if (it.isNotBlank()) keyInput = it.trim()
                        }
                    },
                    enabled = !editsLocked,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Paste", style = MaterialTheme.typography.labelMedium)
                }

                if (keyInput.isNotBlank()) {
                    TextButton(
                        onClick = { showClearConfirm = true },
                        enabled = !editsLocked,
                        modifier = Modifier.heightIn(min = 48.dp)
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
                        onKeyUpdated()
                        testResult = if (!AyraPreferences.hasExternalAiConsent(context)) {
                            "Key saved securely ✓ Enable 'External Cloud AI Consent' above to allow requests."
                        } else {
                            "Key saved securely to Android Keystore ✓"
                        }
                        isError = false
                    },
                    enabled = !editsLocked,
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
            text = { Text("This clears the stored API key from Android Keystore.") },
            confirmButton = {
                Button(
                    onClick = {
                        keyInput = ""
                        saveKey(context, provider, "")
                        onKeyUpdated()
                        testResult = null
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Clear Key") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }, modifier = Modifier.heightIn(min = 48.dp)) {
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
    editsLocked: Boolean = false,
    tagPrefix: String? = null,
    maskInputByDefault: Boolean = true
) {
    val scope = rememberCoroutineScope()

    var keyInput by remember { mutableStateOf(initialKey) }
    var isPasswordVisible by remember { mutableStateOf(!maskInputByDefault) }
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
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isConfigured) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (isConfigured) "Configured ✓" else "Not set",
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
                onValueChange = { keyInput = it },
                enabled = !editsLocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (tagPrefix != null) Modifier.testTag("integration_input_$tagPrefix") else Modifier),
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
                text = "Endpoint / Portal: $portalLabel ($portalUrl)",
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
                    onClick = {
                        AndroidHardeningHelper.readPlainTextFromClipboard(context)?.let {
                            if (it.isNotBlank()) keyInput = it.trim()
                        }
                    },
                    enabled = !editsLocked,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Paste", style = MaterialTheme.typography.labelMedium)
                }

                if (keyInput.isNotBlank()) {
                    TextButton(
                        onClick = { showClearConfirm = true },
                        enabled = !editsLocked,
                        modifier = Modifier.heightIn(min = 48.dp)
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
                        .then(if (tagPrefix != null) Modifier.testTag("integration_test_$tagPrefix") else Modifier)
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
                        onSave(keyInput.trim())
                        testResult = "Saved securely to Android Keystore ✓"
                        isError = false
                    },
                    enabled = !editsLocked,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .then(if (tagPrefix != null) Modifier.testTag("integration_save_$tagPrefix") else Modifier)
                ) {
                    Text("Save", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear $title?") },
            text = { Text("This clears the stored configuration from encrypted device storage.") },
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
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun NvidiaNimMultiKeySection(
    context: Context,
    editsLocked: Boolean = false,
    onKeyUpdated: () -> Unit = {}
) {
    val resetNimBreaker = {
        ExternalAiProviders(context).circuitBreaker.resetProvider(AiProviderType.NVIDIA_NIM)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SpecializedIntegrationKeyCard(
            context = context,
            title = "NVIDIA • MiniMax M3.1 Flash (Vision + Fast)",
            description = "Dedicated NVIDIA NIM endpoint for multimodal image inspection & ultra-fast dialogue.",
            portalLabel = "NVIDIA Build",
            portalUrl = "https://build.nvidia.com",
            icon = Icons.Default.Key,
            initialKey = AyraPreferences.getNvidiaMinimaxKey(context),
            placeholder = "nvapi-...",
            editsLocked = editsLocked,
            tagPrefix = "nvidia_minimax",
            onSave = {
                AyraPreferences.setNvidiaMinimaxKey(context, it)
                resetNimBreaker()
                onKeyUpdated()
            },
            onClear = {
                AyraPreferences.setNvidiaMinimaxKey(context, "")
                onKeyUpdated()
            },
            onTest = { key ->
                ExternalAiProviders(context).testProviderConnection(
                    provider = AiProviderType.NVIDIA_NIM,
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
            editsLocked = editsLocked,
            tagPrefix = "nvidia_glm",
            onSave = {
                AyraPreferences.setNvidiaGlmKey(context, it)
                resetNimBreaker()
                onKeyUpdated()
            },
            onClear = {
                AyraPreferences.setNvidiaGlmKey(context, "")
                onKeyUpdated()
            },
            onTest = { key ->
                ExternalAiProviders(context).testProviderConnection(
                    provider = AiProviderType.NVIDIA_NIM,
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
            editsLocked = editsLocked,
            tagPrefix = "nvidia_muse",
            onSave = {
                AyraPreferences.setNvidiaMuseKey(context, it)
                resetNimBreaker()
                onKeyUpdated()
            },
            onClear = {
                AyraPreferences.setNvidiaMuseKey(context, "")
                onKeyUpdated()
            },
            onTest = { key ->
                ExternalAiProviders(context).testProviderConnection(
                    provider = AiProviderType.NVIDIA_NIM,
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
            editsLocked = editsLocked,
            tagPrefix = "nvidia_gemma",
            onSave = {
                AyraPreferences.setNvidiaGemmaKey(context, it)
                resetNimBreaker()
                onKeyUpdated()
            },
            onClear = {
                AyraPreferences.setNvidiaGemmaKey(context, "")
                onKeyUpdated()
            },
            onTest = { key ->
                ExternalAiProviders(context).testProviderConnection(
                    provider = AiProviderType.NVIDIA_NIM,
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
    editsLocked: Boolean = false,
    onPoolChanged: () -> Unit = {}
) {
    val isLocalOnly = AyraPreferences.isLocalOnlyAiModeEnabled(context)
    var freePoolEnabled by remember { mutableStateOf(AyraPreferences.isFreeAiPoolEnabled(context)) }
    var showConsentPrompt by remember { mutableStateOf(false) }

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
                        isLocalOnly -> "Blocked while Local-Only Privacy Mode is active in Security Center."
                        !AyraPreferences.hasExternalAiConsent(context) ->
                            "External Cloud AI consent is currently off. Tap toggle to review & opt in explicitly."
                        else -> "Optional community fallback models active when opted in."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isLocalOnly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = !isLocalOnly && freePoolEnabled && AyraPreferences.hasExternalAiConsent(context),
                enabled = !isLocalOnly && !editsLocked,
                onCheckedChange = { enable ->
                    if (!enable) {
                        AyraPreferences.setFreeAiPoolEnabled(context, false)
                        freePoolEnabled = false
                        onPoolChanged()
                    } else if (AyraPreferences.hasExternalAiConsent(context)) {
                        AyraPreferences.setFreeAiPoolEnabled(context, true)
                        freePoolEnabled = AyraPreferences.isFreeAiPoolEnabled(context)
                        onPoolChanged()
                    } else {
                        showConsentPrompt = true
                    }
                },
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("free_ai_pool_card_switch")
            )
        }
    }

    if (showConsentPrompt) {
        AlertDialog(
            onDismissRequest = { showConsentPrompt = false },
            title = { Text("Enable External Cloud AI & Free Pool?") },
            text = {
                Text(
                    "Using the Global Free AI Pool sends prompts to external community AI endpoints. Confirm explicit opt-in to enable External Cloud AI Provider Consent and the Free AI Pool.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        AyraPreferences.setExternalAiConsent(context, true)
                        AyraPreferences.setFreeAiPoolEnabled(context, true)
                        freePoolEnabled = AyraPreferences.isFreeAiPoolEnabled(context)
                        onPoolChanged()
                        showConsentPrompt = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_free_pool_consent_button")
                ) {
                    Text("Enable Both")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConsentPrompt = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Keep Disabled")
                }
            }
        )
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
        AiProviderType.GITHUB_MODELS -> AyraPreferences.setGitHubModelsKey(context, "")
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
    try {
        ExternalAiProviders(context).circuitBreaker.resetProvider(provider)
    } catch (e: Exception) {
        Log.w("ProviderKeysSection", "Failed resetting circuit breaker for ${provider.id}", e)
    }
}
