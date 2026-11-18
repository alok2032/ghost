package com.ayra.ai.app.ui.components.control

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelTier
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.ui.components.GitHubPluginHubSection
import com.ayra.ai.app.ui.components.ProviderKeysSection
import com.ayra.ai.app.ui.components.SuperpowersSuiteCard
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGreen
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import com.ayra.ai.app.voice.UserSentiment
import java.util.Locale

@Composable
internal fun ControlCenterModelsAndProvidersSection(
    context: Context,
    selectedModelTier: String,
    isKeySectionExpanded: Boolean,
    onToggleKeySection: () -> Unit,
    onModelTierChange: (String) -> Unit,
    onSelectPrompt: (String) -> Unit,
    onDismiss: () -> Unit,
    onQuickFeedback: (String) -> Unit
) {
    Text(
        text = "⚡ AI Model Selection / স্পিড বনাম রিজনিং ডেপথ",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = NeonCyan,
        fontSize = 13.sp
    )

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelTier.entries.filter { it.isSelectableTier }.forEach { tier ->
            val isSelected = ModelTier.canonicalizeId(selectedModelTier) == tier.id
            val tierColor = Color(tier.accentColorHex)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSelected) tierColor.copy(alpha = 0.16f) else GlassSurfaceDark)
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) tierColor else Color(0x2200FFFF),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable {
                        onModelTierChange(tier.id)
                        onQuickFeedback("মডেল সক্রিয়: ${tier.displayName}")
                    }
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) tierColor else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tier.displayName,
                                color = if (isSelected) tierColor else TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(text = tier.providerName, color = TextSecondary, fontSize = 10.5.sp)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1E283F))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(text = tier.speedLabel, color = NeonCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(tierColor.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(text = tier.reasoningLabel, color = tierColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text(
                        text = tier.description,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(start = 28.dp)
                    )
                }
            }
        }
    }

    val providersEngine = remember(isKeySectionExpanded) { ExternalAiProviders(context) }
    val activeChannels = remember(isKeySectionExpanded) { providersEngine.getActiveChannels() }
    val activeCount = activeChannels.size
    val isMultiAiReady = activeCount > 0

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isMultiAiReady) Color(0x1A00FF7F) else GlassSurfaceDark)
            .border(
                1.dp,
                if (isMultiAiReady) NeonGreen.copy(alpha = 0.5f) else Color(0x2200FFFF),
                RoundedCornerShape(14.dp)
            )
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = if (isMultiAiReady) NeonGreen else NeonCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isMultiAiReady) "🟢 Multi-AI Ensemble সক্রিয় ($activeCount চ্যানেল)" else "⚠️ কোনো AI চ্যানেল সক্রিয় নেই",
                        color = if (isMultiAiReady) NeonGreen else NeonCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp
                    )
                    Text(
                        text = if (isMultiAiReady) {
                            "Gemini 3.8/4.0 + Groq + NVIDIA NIM + Hugging Face + GitHub Models + Free AI সক্রিয়"
                        } else {
                            "উত্তর পেতে Settings -> API Keys থেকে কী যোগ করুন বা Free AI Pool অন করুন"
                        },
                        color = TextSecondary,
                        fontSize = 10.5.sp
                    )
                }

                Button(
                    onClick = onToggleKeySection,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E283F)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = if (isKeySectionExpanded) "হাইড" else "কী কনফিগার",
                        color = NeonCyan,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isKeySectionExpanded) {
                ProviderKeysSection(context = context, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    SuperpowersSuiteCard(
        onSelectSuperpowerPrompt = { prompt ->
            onSelectPrompt(prompt)
            onDismiss()
        }
    )

    GitHubPluginHubSection(
        context = context,
        onUsePluginPrompt = { prompt ->
            onSelectPrompt(prompt)
            onDismiss()
        },
        onQuickFeedback = onQuickFeedback
    )
}

@Composable
internal fun ControlCenterPrivacySummaryCard(
    onOpenSecurityCenter: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "🛡️ Device Privacy & Security",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = NeonCyan,
            fontSize = 13.sp
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(GlassSurfaceDark)
                .border(1.2.dp, Color(0x2200FFFF), RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(NeonCyan.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Security, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Device Privacy & Security", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        Text("Transparent local execution & direct TLS AI requests", color = TextSecondary, fontSize = 10.5.sp)
                    }
                }

                Text(
                    text = "• Local Vault: SQLCipher database & AES-256-GCM encrypted file vault.\n• ActionGate: Calls, SMS & AI tool calls require explicit user policy checks.\n• E2EE & Zero-Trust: Keystore hardware keys, anti-SSRF, biometric lock & encrypted backups.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F172A))
                        .padding(10.dp)
                )

                if (onOpenSecurityCenter != null) {
                    Button(
                        onClick = {
                            onDismiss()
                            onOpenSecurityCenter()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("open_security_center_button")
                    ) {
                        Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Zero-Trust Security Center", color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ControlCenterPersonaSection(
    context: Context,
    currentPersona: String,
    showCompanionAgeGateDialog: Boolean,
    onShowAgeGateChange: (Boolean) -> Unit,
    onPersonaChange: (String) -> Unit,
    onQuickFeedback: (String) -> Unit
) {
    Text(
        text = "🎭 AI Persona / সহকারীর আচরণ ও ব্যক্তিত্ব",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = NeonCyan,
        fontSize = 13.sp
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val isNeutral = currentPersona == "AI_ASSISTANT" || currentPersona == "PROFESSIONAL"
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isNeutral) NeonCyan.copy(alpha = 0.22f) else GlassSurfaceDark)
                .border(1.2.dp, if (isNeutral) NeonCyan else Color(0x3300FFFF), RoundedCornerShape(14.dp))
                .clickable {
                    onPersonaChange("AI_ASSISTANT")
                    onQuickFeedback("🤖 AI Assistant Mode সক্রিয় করা হলো!")
                }
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("AI Assistant", color = if (isNeutral) NeonCyan else TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                }
                Text(
                    text = "নির্ভরযোগ্য ও সরাসরি পেশাদার সহকারী। কোডিং, গবেষণা ও দৈনন্দিন কাজের জন্য ডিফল্ট মোড।",
                    color = TextSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp
                )
            }
        }

        val isGf = currentPersona == "GF_COMPANION"
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isGf) NeonRose.copy(alpha = 0.22f) else GlassSurfaceDark)
                .border(1.2.dp, if (isGf) NeonRose else Color(0x33FF007F), RoundedCornerShape(14.dp))
                .clickable {
                    if (AyraPreferences.isCompanionAgeVerified(context)) {
                        onPersonaChange("GF_COMPANION")
                        onQuickFeedback("💖 Companion Mode (18+) সক্রিয় করা হলো!")
                    } else {
                        onShowAgeGateChange(true)
                    }
                }
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = NeonRose, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Companion (18+)", color = if (isGf) NeonRose else TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                }
                Text(
                    text = "আন্তরিক ও যত্নশীল কথোপকথন মোড (১৮+ বছর বয়সীদের জন্য ঐচ্ছিক অপ্ট-ইন)।",
                    color = TextSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }

    if (showCompanionAgeGateDialog) {
        AlertDialog(
            onDismissRequest = { onShowAgeGateChange(false) },
            title = { Text("18+ Companion Persona Confirmation", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Companion Mode uses affectionate, personal conversational tones intended only for adults (18+). Please confirm you are 18 or older to enable this optional persona.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        AyraPreferences.setCompanionAgeVerified(context, true)
                        onShowAgeGateChange(false)
                        onPersonaChange("GF_COMPANION")
                        onQuickFeedback("💖 Companion Mode (18+) সক্রিয় করা হলো!")
                    },
                    modifier = Modifier.testTag("confirm_18_plus_companion_button")
                ) {
                    Text("Confirm 18+ & Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { onShowAgeGateChange(false) }) {
                    Text("Keep Neutral AI", color = TextSecondary)
                }
            },
            containerColor = Color(0xFF131B2E)
        )
    }
}

@Composable
internal fun ControlCenterVoiceSentimentAndLanguageSection(
    context: Context,
    currentSentiment: UserSentiment,
    dynamicVoiceModulationEnabled: Boolean,
    onToggleDynamicVoiceModulation: (Boolean) -> Unit,
    selectedSentimentLock: UserSentiment?,
    onSentimentLockChange: (UserSentiment?) -> Unit,
    onTestSentimentVoice: (UserSentiment) -> Unit,
    lockScreenWakeEnabled: Boolean,
    onLockScreenWakeChange: (Boolean) -> Unit,
    wakeWordEnabled: Boolean,
    onWakeWordToggle: (Boolean) -> Unit,
    selectedLanguage: String,
    onLanguageChange: (String) -> Unit,
    onQuickFeedback: (String) -> Unit
) {
    Text(
        text = "🎭 Dynamic Voice Tone & Sentiment / কণ্ঠের সুর ও আবেগ রূপান্তর",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = NeonRose,
        fontSize = 13.sp
    )

    SettingToggleRow(
        icon = Icons.Default.RecordVoiceOver,
        title = "Dynamic Voice Modulation",
        subtitle = "ইউজারের আবেগ (ভালোবাসা, কষ্ট, আনন্দ, ক্ষোভ) অনুযায়ী কণ্ঠের সুর, পিচ ও গতি রূপান্তর করবে",
        checked = dynamicVoiceModulationEnabled,
        onCheckedChange = {
            onToggleDynamicVoiceModulation(it)
            onQuickFeedback(if (it) "🎭 Dynamic Voice Modulation সক্রিয়" else "স্বাভাবিক কণ্ঠে স্থিতিশীল করা হলো")
        }
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(currentSentiment.glowColorHex).copy(alpha = 0.12f))
            .border(1.2.dp, Color(currentSentiment.glowColorHex).copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = currentSentiment.emoji, fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(currentSentiment.displayName, color = Color(currentSentiment.glowColorHex), fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        Text(currentSentiment.bengaliName, color = TextSecondary, fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E283F)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("Pitch ${String.format(Locale.US, "%.2f", currentSentiment.pitch)}x", color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E283F)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("Rate ${String.format(Locale.US, "%.2f", currentSentiment.speechRate)}x", color = NeonRose, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Text(currentSentiment.description, color = TextSecondary, fontSize = 11.5.sp, lineHeight = 15.sp)

            Button(
                onClick = {
                    onTestSentimentVoice(currentSentiment)
                    onQuickFeedback("🔊 টেস্টিং ভয়েস: ${currentSentiment.displayName}")
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(currentSentiment.glowColorHex).copy(alpha = 0.25f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Color(currentSentiment.glowColorHex), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Test This Voice Tone (কণ্ঠে শুনে দেখুন) 🔊", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    Text(
        text = "🎵 Test / Lock Specific Voice Tone (ভয়েস মুড পরীক্ষা বা লক করুন):",
        color = TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium
    )

    if (selectedSentimentLock != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(NeonCyan.copy(alpha = 0.2f))
                .clickable {
                    onSentimentLockChange(null)
                    onQuickFeedback("⚡ স্বয়ংক্রিয় আবেগ সনাক্তকরণ সক্রিয় করা হলো")
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("🔒 Locked to: ${selectedSentimentLock.displayName}", color = NeonCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            Text("Reset to Auto Dynamic ⚡", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        UserSentiment.entries.chunked(2).forEach { rowSentiments ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowSentiments.forEach { sentiment ->
                    val isSelected = (selectedSentimentLock == sentiment) || (selectedSentimentLock == null && currentSentiment == sentiment)
                    val chipColor = Color(sentiment.glowColorHex)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) chipColor.copy(alpha = 0.22f) else GlassSurfaceDark)
                            .border(1.dp, if (isSelected) chipColor else Color(0x2200FFFF), RoundedCornerShape(10.dp))
                            .clickable {
                                onSentimentLockChange(sentiment)
                                onTestSentimentVoice(sentiment)
                                onQuickFeedback("${sentiment.emoji} ${sentiment.displayName} লক ও টেস্ট করা হলো!")
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = sentiment.emoji, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = sentiment.displayName.substringBefore(" &"),
                                        color = if (isSelected) chipColor else TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text("${String.format(Locale.US, "%.2f", sentiment.pitch)}x", color = TextSecondary, fontSize = 9.sp)
                                }
                            }
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Test", tint = if (isSelected) chipColor else TextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    Text(
        text = "🎙️ Voice Wake & Lock Screen / লক স্ক্রিন ও ভয়েস সেটিংস",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = NeonCyan,
        fontSize = 13.sp
    )

    SettingToggleRow(
        icon = Icons.Default.Lock,
        title = "Lock Screen Wake ('Ayra')",
        subtitle = "ফোন লক থাকলেও 'Ayra' বললেই সহকারী অন হবে",
        checked = lockScreenWakeEnabled,
        onCheckedChange = {
            onLockScreenWakeChange(it)
            AyraPreferences.setLockScreenWakeEnabled(context, it)
            onQuickFeedback(if (it) "Lock Screen Wake Enabled 🔓" else "Lock Screen Wake Disabled")
        }
    )

    SettingToggleRow(
        icon = Icons.Default.Mic,
        title = "Always-On Voice Trigger",
        subtitle = "গুগল বা সিরি-র মত 'Ayra' ডাকলে স্বয়ংক্রিয় সক্রিয়",
        checked = wakeWordEnabled,
        onCheckedChange = {
            onWakeWordToggle(it)
            onQuickFeedback(if (it) "Voice Trigger Enabled 🎙️" else "Voice Trigger Disabled")
        }
    )

    Text(
        text = "🗣️ Voice Language / কথা বলার ভাষা",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = NeonCyan,
        fontSize = 13.sp
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Auto" to "🌐 Auto", "bn" to "বাংলা", "hi" to "हिन्दी", "en" to "English").forEach { (code, label) ->
            val isSelected = selectedLanguage == code
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) NeonCyan.copy(alpha = 0.25f) else Color(0xFF131B2E))
                    .border(1.dp, if (isSelected) NeonCyan else Color(0x2200FFFF), RoundedCornerShape(10.dp))
                    .clickable {
                        onLanguageChange(code)
                        AyraPreferences.setPreferredLanguage(context, code)
                        onQuickFeedback("Language preference: $label")
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (isSelected) NeonCyan else TextSecondary,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp
                )
            }
        }
    }
}
