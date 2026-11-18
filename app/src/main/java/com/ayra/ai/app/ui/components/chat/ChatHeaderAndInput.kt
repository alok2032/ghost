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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
                maxLines = 1
            )
        }

        if (isSpeaking) {
            IconButton(onClick = onStopVoice) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Mute voice", tint = NeonRose)
            }
        }

        IconButton(
            onClick = onSessionsClick,
            modifier = Modifier.testTag("chat_sessions_button")
        ) {
            Icon(Icons.Default.History, contentDescription = "Chat history sessions", tint = NeonCyan)
        }

        IconButton(
            onClick = onNewSessionClick,
            modifier = Modifier.testTag("new_chat_session_button")
        ) {
            Icon(Icons.Default.AddComment, contentDescription = "New conversation", tint = NeonPurple)
        }

        IconButton(onClick = onClearChat) {
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
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAudioTranscribeClick: () -> Unit = {},
    onMicClick: () -> Unit,
    onAttachClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF1E1035), GlassSurfaceDark)))
                .border(1.4.dp, Brush.linearGradient(listOf(NeonCyan, NeonPurple, NeonRose)), CircleShape)
                .clickable { onAttachClick() }
                .testTag("bottom_attachment_hub_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Attach & Super-App Tools Hub",
                tint = NeonCyan,
                modifier = Modifier.size(22.dp)
            )
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("Ask Ayra AI, generate photo, open apps...", color = TextSecondary, fontSize = 13.sp) },
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(GlassSurfaceDark)
                .testTag("chat_input_text_field"),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = Color(0x3300FFFF),
                cursorColor = NeonCyan,
                focusedContainerColor = GlassSurfaceDark,
                unfocusedContainerColor = GlassSurfaceDark
            ),
            shape = RoundedCornerShape(24.dp),
            maxLines = 4
        )

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (isRecordingAudio) NeonRose else GlassSurfaceDark)
                .border(1.2.dp, if (isRecordingAudio) NeonRose else NeonPurple, CircleShape)
                .clickable { onAudioTranscribeClick() }
                .testTag("gemini_audio_transcribe_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "AI Audio Transcribe",
                tint = if (isRecordingAudio) Color.Black else NeonPurple,
                modifier = Modifier.size(20.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (isListening) NeonRose else GlassSurfaceDark)
                .border(1.2.dp, if (isListening) NeonRose else NeonCyan, CircleShape)
                .clickable { onMicClick() }
                .testTag("voice_mic_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = "Voice",
                tint = if (isListening) Color.Black else NeonCyan
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (isGenerating) Brush.linearGradient(listOf(Color(0xFFFF1744), Color(0xFFD50000)))
                    else Brush.linearGradient(listOf(NeonCyan, NeonPurple))
                )
                .clickable {
                    if (isGenerating) onStop()
                    else if (text.isNotBlank()) onSend()
                }
                .testTag("chat_send_button"),
            contentAlignment = Alignment.Center
        ) {
            if (isGenerating) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Stop", tint = Color.White, modifier = Modifier.size(22.dp))
            } else {
                Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.Black, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
internal fun EmptyCosmicState(currentPersona: String) {
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
            .padding(top = 18.dp, start = 14.dp, end = 14.dp),
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
                text = "Tap Top or Bottom '+' for Cyber Lab, Studios, Research & Tools",
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
