package com.ayra.ai.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.ui.theme.LocalAyraUiConfig

private data class OnboardingStepSpec(
    val title: String,
    val subtitle: String,
    val description: String,
    val bullets: List<String>
)

@Composable
fun OnboardingOverlay(
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiConfig = LocalAyraUiConfig.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var localOnlyEnabled by remember { mutableStateOf(AyraPreferences.isLocalOnlyAiModeEnabled(context)) }
    var externalAiConsent by remember { mutableStateOf(AyraPreferences.hasExternalAiConsent(context)) }
    var freePoolEnabled by remember { mutableStateOf(AyraPreferences.isFreeAiPoolEnabled(context)) }
    var quickActivatedBanner by remember { mutableStateOf(false) }

    val steps = remember {
        listOf(
            OnboardingStepSpec(
                title = "Multi-Model Consensus & Deep Wiki",
                subtitle = "Step 1 of 3 • Unified Intelligence Engine",
                description = "Query top cloud & open-weights models together with verified multi-layer research.",
                bullets = listOf(
                    "Orchestrates up to 16+ providers with smart routing & verification",
                    "Searches Surface, Academic, Tech & encrypted local Vector RAG",
                    "Synthesizes bilingual English, Bengali & Banglish answers cleanly"
                )
            ),
            OnboardingStepSpec(
                title = "Your Keys, Local Ollama & Privacy Control",
                subtitle = "Step 2 of 3 • Zero-Trust Keystore Vault",
                description = "Keep credentials Hardware-Keystore encrypted or run 100% locally via Ollama / LAN.",
                bullets = listOf(
                    "API keys are AES-256-GCM wrapped — never plaintext on disk",
                    "Connect local Ollama instances for zero-cloud private inference",
                    "Enable Local-Only Privacy Mode anytime below or in Security Center"
                )
            ),
            OnboardingStepSpec(
                title = "Voice Assistant & ActionGate Safety",
                subtitle = "Step 3 of 3 • Human-in-the-Loop Automation",
                description = "Run coding, files, voice commands & device tasks without silent surprises.",
                bullets = listOf(
                    "Hands-free voice input with bilingual emotional & crisis safety",
                    "Phone calls, SMS, alarms & external MCP tools require explicit tap confirmation",
                    "SQLCipher AES-256 encrypted chat history with .ayrabak recovery"
                )
            )
        )
    }

    fun completeAndDismiss() {
        AyraPreferences.setOnboardingCompleted(context, true)
        onDismissed()
    }

    BackHandler {
        if (step > 0) {
            step--
        } else {
            completeAndDismiss()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.68f))
            .pointerInput(Unit) { detectTapGestures {} }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp)
            .testTag("onboarding_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(steps.size) { index ->
                            val pillColor by animateColorAsState(
                                targetValue = if (index == step) uiConfig.accentColor
                                else MaterialTheme.colorScheme.outlineVariant,
                                animationSpec = tween(220),
                                label = "onboarding_pill_$index"
                            )
                            Box(
                                modifier = Modifier
                                    .height(8.dp)
                                    .width(if (index == step) 28.dp else 10.dp)
                                    .clip(CircleShape)
                                    .background(pillColor)
                            )
                        }
                    }
                    TextButton(
                        onClick = { completeAndDismiss() },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("onboarding_skip_button")
                    ) {
                        Text("Skip")
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    AnimatedContent(
                        targetState = step,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                        label = "onboarding_step_content"
                    ) { activeStep ->
                        val spec = steps[activeStep]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(uiConfig.accentColor.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when (activeStep) {
                                        0 -> Icons.Default.Hub
                                        1 -> Icons.Default.VpnKey
                                        else -> Icons.Default.Security
                                    },
                                    contentDescription = spec.title,
                                    tint = uiConfig.accentColor,
                                    modifier = Modifier.size(30.dp)
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = spec.subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = uiConfig.accentColor
                                )
                                Text(
                                    text = spec.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = spec.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    spec.bullets.forEach { bullet ->
                                        Row(
                                            verticalAlignment = Alignment.Top,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                tint = uiConfig.accentColor,
                                                modifier = Modifier
                                                    .padding(top = 2.dp)
                                                    .size(16.dp)
                                            )
                                            Text(
                                                text = bullet,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }

                            if (activeStep == 0) {
                                Button(
                                    onClick = {
                                        localOnlyEnabled = false
                                        externalAiConsent = true
                                        freePoolEnabled = true
                                        quickActivatedBanner = true
                                        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
                                        AyraPreferences.setExternalAiConsent(context, true)
                                        AyraPreferences.setFreeAiPoolEnabled(context, true)
                                        com.ayra.ai.app.ai.providers.ExternalAiProviders.clearDynamicModelCache(null)
                                        com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
                                        com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = uiConfig.accentColor),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("onboarding_enable_all_ai_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (quickActivatedBanner || (externalAiConsent && freePoolEnabled && !localOnlyEnabled)) {
                                            "All Active AIs & Live Search Activated ✓"
                                        } else {
                                            "Enable All Active AIs & Live Search (1-Tap)"
                                        },
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (activeStep == 1) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Local-Only AI Privacy Mode",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Restrict inference & tools strictly to local Ollama / LAN",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Switch(
                                            checked = localOnlyEnabled,
                                            onCheckedChange = { checked ->
                                                localOnlyEnabled = checked
                                                AyraPreferences.setLocalOnlyAiModeEnabled(context, checked)
                                                if (checked) {
                                                    freePoolEnabled = false
                                                }
                                            },
                                            modifier = Modifier.testTag("onboarding_local_only_switch")
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "External Cloud AI Consent (Opt-In)",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Allow prompts to reach cloud models (Gemini, OpenAI, Anthropic, Groq) when Local-Only is off",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Switch(
                                            checked = externalAiConsent,
                                            onCheckedChange = { checked ->
                                                externalAiConsent = checked
                                                AyraPreferences.setExternalAiConsent(context, checked)
                                                if (!checked) {
                                                    freePoolEnabled = false
                                                    AyraPreferences.setFreeAiPoolEnabled(context, false)
                                                }
                                            },
                                            modifier = Modifier.testTag("onboarding_external_consent_switch")
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Public Free AI Pool (Opt-In)",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Use anonymous community specialists when you have not added personal API keys",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Switch(
                                            checked = freePoolEnabled,
                                            enabled = !localOnlyEnabled && externalAiConsent,
                                            onCheckedChange = { checked ->
                                                freePoolEnabled = checked
                                                AyraPreferences.setFreeAiPoolEnabled(context, checked)
                                            },
                                            modifier = Modifier.testTag("onboarding_free_pool_switch")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step > 0) {
                        TextButton(
                            onClick = { step-- },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Back")
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    if (step < steps.lastIndex) {
                        Button(
                            onClick = { step++ },
                            colors = ButtonDefaults.buttonColors(containerColor = uiConfig.accentColor),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("onboarding_next_button")
                        ) {
                            Text("Next")
                        }
                    } else {
                        Button(
                            onClick = { completeAndDismiss() },
                            colors = ButtonDefaults.buttonColors(containerColor = uiConfig.accentColor),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("onboarding_finish_button")
                        ) {
                            Text("Finish")
                        }
                    }
                }
            }
        }
    }
}
