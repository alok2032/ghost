package com.ayra.ai.app.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.data.AyraPreferences

@Composable
fun ProviderKeysSection(
    context: Context,
    modifier: Modifier = Modifier,
    editsLocked: Boolean = false,
    initiallyExpandDeveloperKeys: Boolean = true,
    externalRefreshTick: Int = 0,
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
    var hasExternalConsent by remember(externalRefreshTick) { mutableStateOf(AyraPreferences.hasExternalAiConsent(context)) }
    val isLocalOnly = remember(externalRefreshTick, activationRevision) { AyraPreferences.isLocalOnlyAiModeEnabled(context) }
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
                    "Hide Optional BYOK API Keys & Custom Endpoints"
                } else {
                    "Optional BYOK API Keys & Custom Endpoints"
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

            SpecializedEndpointsGroup(
                context = context,
                hasExternalConsent = hasExternalConsent,
                isLocalOnly = isLocalOnly,
                editsLocked = editsLocked,
                onChanged = notifyActivationChanged
            )
        }
    }
}
