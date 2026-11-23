package com.ayra.ai.app.ui.components.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ai.agent.AgentCitation
import com.ayra.ai.app.ai.agent.AgentPlanStep
import com.ayra.ai.app.ai.agent.StepStatus
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGreen
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import com.ayra.ai.app.voice.UserSentiment

@Composable
internal fun GlassHeaderBar(
    isListening: Boolean,
    isSpeaking: Boolean,
    currentPersona: String,
    currentSentiment: UserSentiment = UserSentiment.NEUTRAL_CARING,
    activeSessionTitle: String? = null,
    onOpenDrawer: (() -> Unit)? = null,
    onLogoClick: () -> Unit,
    onSessionsClick: () -> Unit = {},
    onNewSessionClick: () -> Unit = {},
    onClearChat: () -> Unit,
    onStopVoice: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "header_text_anim")
    val animOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 700f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "anim_offset"
    )

    val animatedGradient = Brush.linearGradient(
        colors = listOf(NeonCyan, NeonPurple, NeonRose, NeonCyan),
        start = Offset(animOffset, 0f),
        end = Offset(animOffset + 350f, 150f)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurfaceDark)
            .border(
                1.dp,
                Brush.linearGradient(
                    if (currentPersona == "GF_COMPANION")
                        listOf(NeonRose.copy(alpha = 0.6f), NeonPurple.copy(alpha = 0.4f), NeonCyan.copy(alpha = 0.3f))
                    else
                        listOf(NeonCyan.copy(alpha = 0.6f), NeonPurple.copy(alpha = 0.4f))
                ),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onOpenDrawer != null) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.size(48.dp).testTag("chat_open_drawer_button")
            ) {
                Icon(Icons.Default.Menu, contentDescription = "Open Workspace Menu", tint = NeonCyan)
            }
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        if (currentPersona == "GF_COMPANION")
                            listOf(NeonRose, NeonPurple, Color.Black)
                        else
                            listOf(NeonCyan, NeonPurple, Color.Black)
                    )
                )
                .clickable { onLogoClick() }
                .testTag("ayra_logo_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Ayra AI Control Center & Settings",
                tint = Color.Black,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onLogoClick() }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Ayra AI",
                    style = MaterialTheme.typography.titleMedium.copy(brush = animatedGradient),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF131B2E).copy(alpha = 0.85f))
                        .border(1.dp, animatedGradient, RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "By Alok",
                        style = MaterialTheme.typography.labelSmall.copy(brush = animatedGradient),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Text(
                text = when {
                    isListening -> "Listening... 🎙️ Speak command"
                    isSpeaking -> if (currentPersona == "GF_COMPANION") {
                        "Speaking • ${currentSentiment.emoji} ${currentSentiment.displayName} 🔊"
                    } else {
                        "Speaking (Clear Voice) 🔊"
                    }
                    !activeSessionTitle.isNullOrBlank() -> activeSessionTitle
                    currentPersona == "GF_COMPANION" -> "${currentSentiment.emoji} ${currentSentiment.bengaliName} Voice • Ready"
                    else -> "Voice Trigger Ready (Say 'Ayra')"
                },
                style = MaterialTheme.typography.bodySmall.copy(brush = animatedGradient),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isSpeaking) {
            IconButton(
                onClick = onStopVoice,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Mute voice", tint = NeonRose)
            }
        }

        IconButton(
            onClick = onSessionsClick,
            modifier = Modifier.size(48.dp).testTag("chat_sessions_button")
        ) {
            Icon(Icons.Default.History, contentDescription = "Chat history sessions", tint = NeonCyan)
        }

        IconButton(
            onClick = onNewSessionClick,
            modifier = Modifier.size(48.dp).testTag("new_chat_session_button")
        ) {
            Icon(Icons.Default.AddComment, contentDescription = "New conversation", tint = NeonPurple)
        }

        IconButton(
            onClick = onClearChat,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Clear Chat", tint = TextSecondary)
        }
    }
}

@Composable
internal fun GlassInputRow(
    text: String,
    onTextChange: (String) -> Unit,
    isListening: Boolean,
    isRecordingAudio: Boolean = false,
    isTranscribingAudio: Boolean = false,
    canSendWithAttachment: Boolean = false,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAudioTranscribeClick: () -> Unit = {},
    onMicClick: () -> Unit,
    onAttachClick: () -> Unit
) {
    val canSendNow = text.isNotBlank() || canSendWithAttachment
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(GlassSurfaceDark)
            .border(
                width = 1.4.dp,
                brush = Brush.linearGradient(
                    if (isListening || isRecordingAudio) listOf(NeonRose, NeonPurple, NeonCyan)
                    else listOf(NeonCyan.copy(alpha = 0.75f), NeonPurple.copy(alpha = 0.65f))
                ),
                shape = RoundedCornerShape(28.dp)
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable { onAttachClick() }
                .testTag("bottom_attachment_hub_button"),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF1E1035), Color(0xFF0E162B))))
                    .border(1.2.dp, Brush.linearGradient(listOf(NeonCyan, NeonPurple, NeonRose)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Attach & Super-App Tools Hub",
                    tint = NeonCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (text.isEmpty()) {
                Text(
                    text = if (canSendWithAttachment) "Add a note or send..." else "Ask Ayra AI...",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 14.5.sp,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(NeonCyan),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    imeAction = if (canSendNow && !isGenerating) ImeAction.Send else ImeAction.Default
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (canSendNow && !isGenerating) onSend()
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chat_input_text_field")
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(enabled = !isTranscribingAudio) { onAudioTranscribeClick() }
                .testTag("gemini_audio_transcribe_button"),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isRecordingAudio) NeonRose else Color(0xFF0E162B))
                    .border(1.1.dp, if (isRecordingAudio) NeonRose else NeonPurple.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isTranscribingAudio) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = NeonPurple
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "AI Audio Transcribe",
                        tint = if (isRecordingAudio) Color.Black else NeonPurple,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable { onMicClick() }
                .testTag("voice_mic_button"),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isListening) NeonRose else Color(0xFF0E162B))
                    .border(1.1.dp, if (isListening) NeonRose else NeonCyan.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Voice",
                    tint = if (isListening) Color.Black else NeonCyan,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .alpha(if (isGenerating || canSendNow) 1f else 0.45f)
                .clip(CircleShape)
                .clickable(enabled = isGenerating || canSendNow) {
                    if (isGenerating) onStop()
                    else if (canSendNow) onSend()
                }
                .testTag("chat_send_button"),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (isGenerating) Brush.linearGradient(listOf(Color(0xFFFF1744), Color(0xFFD50000)))
                        else Brush.linearGradient(listOf(NeonCyan, NeonPurple))
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isGenerating) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Stop", tint = Color.White, modifier = Modifier.size(20.dp))
                } else {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
internal fun EmptyCosmicState(
    currentPersona: String,
    readinessRefreshKey: Any = Unit,
    onOpenSetup: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "empty_cosmic_anim")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    val animatedGradient = Brush.linearGradient(
        colors = listOf(NeonCyan, NeonPurple, NeonRose, Color(0xFFFFD700), NeonGreen, NeonCyan),
        start = Offset(shimmerOffset, 0f),
        end = Offset(shimmerOffset + 450f, 200f)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 18.dp, start = 14.dp, end = 14.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Ayra AI",
                style = MaterialTheme.typography.headlineLarge.copy(brush = animatedGradient),
                fontWeight = FontWeight.Black,
                fontSize = 30.sp,
                letterSpacing = 1.2.sp
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.85f))
                    .border(1.2.dp, animatedGradient, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "By Alok",
                    style = MaterialTheme.typography.labelSmall.copy(brush = animatedGradient),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (currentPersona == "GF_COMPANION") {
                "Sweet Companion & All-in-One Autonomous AI Master"
            } else {
                "Next-Gen Frontier AI • Multi-Model Unified (Gemini 3.8/4.0 + Groq + DeepSeek R1 + Claude)"
            },
            color = TextSecondary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(14.dp))

        val context = androidx.compose.ui.platform.LocalContext.current
        val localTickState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val (readinessSummary, badgeAccentColor, showQuickEnable) = remember(currentPersona, readinessRefreshKey, localTickState.intValue) {
            val ext = com.ayra.ai.app.ai.providers.ExternalAiProviders(context)
            val extCount = runCatching { ext.getActiveChannels().size }.getOrDefault(0)
            val savedCount = runCatching { ext.getSavedKeyProvidersIgnoringConsent().size }.getOrDefault(0)
            val fbReady = runCatching { com.ayra.ai.app.ai.FirebaseAiEngine.isAvailable(context) }.getOrDefault(false)
            val hasConsent = com.ayra.ai.app.data.AyraPreferences.hasExternalAiConsent(context)
            val freePoolOn = com.ayra.ai.app.data.AyraPreferences.isFreeAiPoolEnabled(context)
            val localOnly = com.ayra.ai.app.data.AyraPreferences.isLocalOnlyAiModeEnabled(context)
            val totalEngines = extCount + if (fbReady) 1 else 0
            val needsQuickEnable = !localOnly && (!hasConsent || !freePoolOn)
            when {
                localOnly -> Triple("Local-Only Privacy Active ($extCount local engines)", NeonCyan, false)
                totalEngines > 0 -> {
                    val label = if (fbReady && extCount == 0) {
                        "Built-in Ayra AI Active (Tap + Free Pool / Keys for Ensemble)"
                    } else {
                        "$totalEngines AI Engines Active • Parallel Search & Ensemble Ready"
                    }
                    Triple(label, NeonGreen, needsQuickEnable)
                }
                savedCount > 0 && !hasConsent -> Triple("$savedCount Key(s) Saved • Tap to enable Cloud Consent", Color(0xFFF59E0B), true)
                else -> Triple("Tap below to Activate All AIs & Live Search or configure Keys", NeonRose, true)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(GlassSurfaceDark.copy(alpha = 0.7f))
                .border(1.dp, badgeAccentColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .clickable { onOpenSetup() }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("ai_readiness_badge")
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(badgeAccentColor))
            Text(
                text = readinessSummary,
                color = badgeAccentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (showQuickEnable) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(NeonCyan.copy(alpha = 0.22f), NeonPurple.copy(alpha = 0.22f))))
                    .border(1.dp, NeonCyan.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                    .clickable {
                        com.ayra.ai.app.data.AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
                        com.ayra.ai.app.data.AyraPreferences.setExternalAiConsent(context, true)
                        com.ayra.ai.app.data.AyraPreferences.setFreeAiPoolEnabled(context, true)
                        com.ayra.ai.app.ai.providers.ExternalAiProviders.clearDynamicModelCache(null)
                        com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
                        com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(context)
                        localTickState.intValue++
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("quick_enable_all_ai_button")
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                Text(
                    text = "Enable All Active AIs & Live Search ⚡",
                    color = NeonCyan,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(GlassSurfaceDark.copy(alpha = 0.6f))
                .border(1.dp, NeonCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(NeonCyan))
            Text(
                text = "Tap '+' for Photo/Video/PDF Prompts, Cyber Lab & Tools",
                color = NeonCyan,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
internal fun AssistantGeneratingIndicator(
    statusText: String? = null,
    streamingText: String? = null,
    planSteps: List<AgentPlanStep> = emptyList(),
    citations: List<AgentCitation> = emptyList()
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurfaceDark)
            .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = NeonCyan)
            Text(
                text = statusText ?: "Ayra AI is thinking & synthesizing...",
                style = MaterialTheme.typography.bodySmall,
                color = NeonCyan,
                fontWeight = FontWeight.Medium
            )
        }

        if (planSteps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                planSteps.forEach { step ->
                    val prefix = when (step.status) {
                        StepStatus.DONE -> "✅"
                        StepStatus.IN_PROGRESS -> "⏳"
                        StepStatus.FAILED -> "❌"
                        StepStatus.SKIPPED -> "⏭️"
                        StepStatus.PENDING -> "•"
                    }
                    Text(
                        text = "$prefix Step ${step.id}: ${step.title}",
                        color = if (step.status == StepStatus.IN_PROGRESS) NeonCyan else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (step.status == StepStatus.IN_PROGRESS) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        if (citations.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                citations.take(6).forEach { cite ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F172A))
                            .border(0.8.dp, NeonPurple.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .clickable { runCatching { uriHandler.openUri(cite.url) } }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = cite.domain.ifBlank { cite.title.take(24) },
                            color = NeonCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        if (!streamingText.isNullOrBlank()) {
            Text(
                text = streamingText,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                lineHeight = 20.sp
            )
        }
    }
}
