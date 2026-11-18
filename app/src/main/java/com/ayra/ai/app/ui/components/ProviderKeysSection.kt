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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
    modifier: Modifier = Modifier
) {
    val providers = remember {
        listOf(
            Triple(AiProviderType.GEMINI, "Google AI Studio", "https://aistudio.google.com/app/apikey"),
            Triple(AiProviderType.GROQ, "Groq Console (Free Tier)", "https://console.groq.com/keys"),
            Triple(AiProviderType.CEREBRAS, "Cerebras Cloud (Free Tier)", "https://cloud.cerebras.ai/"),
            Triple(AiProviderType.SAMBANOVA, "SambaNova Cloud (Free Tier)", "https://cloud.sambanova.ai/"),
            Triple(AiProviderType.HUGGINGFACE, "Hugging Face Tokens", "https://huggingface.co/settings/tokens"),
            Triple(AiProviderType.GITHUB_MODELS, "GitHub PAT (Models)", "https://github.com/settings/tokens"),
            Triple(AiProviderType.OPENAI, "OpenAI API", "https://platform.openai.com/api-keys"),
            Triple(AiProviderType.ANTHROPIC, "Anthropic Console", "https://console.anthropic.com/settings/keys"),
            Triple(AiProviderType.DEEPSEEK, "DeepSeek Platform", "https://platform.deepseek.com/api_keys"),
            Triple(AiProviderType.OPENROUTER, "OpenRouter (All Models)", "https://openrouter.ai/keys"),
            Triple(AiProviderType.XAI, "xAI Console", "https://console.x.ai/"),
            Triple(AiProviderType.MISTRAL, "Mistral Console", "https://console.mistral.ai/api-keys/"),
            Triple(AiProviderType.PERPLEXITY, "Perplexity AI", "https://www.perplexity.ai/settings/api")
        )
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "AI Providers & API Keys",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "All API keys are encrypted on-device via Android Keystore (AES-256-GCM). They are never sent to third-party telemetry servers or stored in plaintext.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Intelligent Routing & AI Memory Control
        AiRoutingAndMemoryCard(context = context)

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
                portalUrl = portalUrl
            )
        }

        Text(
            text = "NVIDIA NIM Multi-Model Engine",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )

        NvidiaNimMultiKeySection(context = context)
        FreeAiPoolCard(context = context)

        Text(
            text = "Search & Voice Integrations",
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
            onSave = { AyraPreferences.setTavilySearchKey(context, it) },
            onClear = { AyraPreferences.setTavilySearchKey(context, "") },
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
            onSave = { AyraPreferences.setElevenLabsKey(context, it) },
            onClear = { AyraPreferences.setElevenLabsKey(context, "") },
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
            onSave = { AyraPreferences.setCustomEndpointUrl(context, it) },
            onClear = { AyraPreferences.setCustomEndpointUrl(context, "") },
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
            onSave = { AyraPreferences.setComfyUiUrl(context, it) },
            onClear = { AyraPreferences.setComfyUiUrl(context, "") },
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

@Composable
fun AiRoutingAndMemoryCard(context: Context) {
    var selectedMode by remember { mutableStateOf(AyraPreferences.getRoutingMode(context)) }
    var isMemoryEnabled by remember { mutableStateOf(AyraPreferences.isMemoryEnabled(context)) }
    var userInstructions by remember { mutableStateOf(AyraPreferences.getUserCustomInstructions(context)) }
    var rememberedFactsCount by remember { mutableStateOf(AyraPreferences.getRememberedFacts(context).size) }
    var statusMessage by remember { mutableStateOf("") }

    val routingModes = remember {
        listOf(
            Triple(ModelTier.AUTO.id, "Auto ⚡", "Adaptive selection"),
            Triple("quality", "Best Quality 👑", "Gemini 4 Argon / Claude 3.7"),
            Triple("fast", "Ultra Fast 🚀", "Gemini 3.8 Flash / Groq"),
            Triple(ModelTier.OPEN_WEIGHTS.id, "Open-Weights 🌐", "Local Ollama / OpenRouter Qwen"),
            Triple("reasoning", "Deep Reasoning 🧠", "DeepSeek R1 / o3-mini"),
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
                        shape = RoundedCornerShape(10.dp),
                        colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.weight(1f)
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
                        shape = RoundedCornerShape(10.dp),
                        colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.weight(1f)
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
                Column {
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
                            onClick = {
                                AyraPreferences.clearAllMemory(context)
                                rememberedFactsCount = 0
                                statusMessage = "All AI memory wiped clean ✓"
                            }
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
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isMemoryEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                        )
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
}

@Composable
fun SingleProviderKeyCard(
    context: Context,
    provider: AiProviderType,
    portalLabel: String,
    portalUrl: String
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                        color = MaterialTheme.colorScheme.onSurface
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
                    }
                ) {
                    Text("Paste", style = MaterialTheme.typography.labelMedium)
                }

                if (keyInput.isNotBlank()) {
                    TextButton(
                        onClick = {
                            keyInput = ""
                            saveKey(context, provider, "")
                            testResult = null
                        }
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
                        testResult = "Key saved securely to Android Keystore ✓"
                        isError = false
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("provider_key_save_${provider.id}")
                ) {
                    Text("Save", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
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
    onTest: suspend (String) -> Long
) {
    val scope = rememberCoroutineScope()

    var keyInput by remember { mutableStateOf(initialKey) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

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
                modifier = Modifier.fillMaxWidth(),
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
                        onClick = {
                            keyInput = ""
                            onClear()
                            testResult = null
                        }
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
                    modifier = Modifier.heightIn(min = 48.dp)
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
                        testResult = "Key saved securely to Android Keystore ✓"
                        isError = false
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Save", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun NvidiaNimMultiKeySection(context: Context) {
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
            onSave = { AyraPreferences.setNvidiaMinimaxKey(context, it) },
            onClear = { AyraPreferences.setNvidiaMinimaxKey(context, "") },
            onTest = { key ->
                ExternalAiProviders(context).testProviderConnection(AiProviderType.NVIDIA_NIM, key)
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
            onSave = { AyraPreferences.setNvidiaGlmKey(context, it) },
            onClear = { AyraPreferences.setNvidiaGlmKey(context, "") },
            onTest = { key ->
                ExternalAiProviders(context).testProviderConnection(AiProviderType.NVIDIA_NIM, key)
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
            onSave = { AyraPreferences.setNvidiaMuseKey(context, it) },
            onClear = { AyraPreferences.setNvidiaMuseKey(context, "") },
            onTest = { key ->
                ExternalAiProviders(context).testProviderConnection(AiProviderType.NVIDIA_NIM, key)
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
            onSave = { AyraPreferences.setNvidiaGemmaKey(context, it) },
            onClear = { AyraPreferences.setNvidiaGemmaKey(context, "") },
            onTest = { key ->
                ExternalAiProviders(context).testProviderConnection(AiProviderType.NVIDIA_NIM, key)
            }
        )
    }
}

@Composable
fun FreeAiPoolCard(context: Context) {
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
                    text = "Global Free AI Pool 🌐",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Keyless cloud text & Flux image endpoints used automatically in the ensemble or as zero-key fallback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = freePoolEnabled,
                onCheckedChange = {
                    freePoolEnabled = it
                    AyraPreferences.setFreeAiPoolEnabled(context, it)
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
        AiProviderType.POLLINATIONS_FREE -> AyraPreferences.setFreeAiPoolEnabled(context, key.isNotBlank())
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
    } catch (_: Exception) {}
}
