package com.ayra.ai.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import com.ayra.ai.app.voice.VoiceUiState
import kotlin.math.abs

private data class VoiceLanguageOption(
    val tag: String,
    val label: String
)

private val VOICE_LANGUAGES = listOf(
    VoiceLanguageOption("auto", "Auto"),
    VoiceLanguageOption("en-US", "English"),
    VoiceLanguageOption("bn-IN", "বাংলা"),
    VoiceLanguageOption("hi-IN", "हिन्दी")
)

private val QUICK_VOICE_COMMANDS = listOf(
    "Generate a 4K cyberpunk sunset",
    "Deep research quantum computing",
    "Write clean Kotlin coroutine code",
    "Open YouTube"
)

/**
 * Real-time Voice-to-Text command HUD overlay with reactive acoustic waveform bars,
 * streaming live transcript display, language switcher, quick command chips, and
 * accessible Cancel / Edit / Send Now controls.
 */
@Composable
fun VoiceCommandOverlay(
    visible: Boolean,
    voiceState: VoiceUiState,
    liveSpokenText: String,
    rmsLevel: Float,
    selectedLanguageTag: String,
    isFallbackRecording: Boolean = false,
    fallbackSeconds: Int = 0,
    fallbackAmplitude: Float = 0f,
    onLanguageSelected: (String) -> Unit,
    onSendCommandNow: (String) -> Unit,
    onUseTranscriptInInput: (String) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + slideInVertically(initialOffsetY = { it / 3 }),
        exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { it / 3 }),
        modifier = modifier
    ) {
        val activeLevel = if (isFallbackRecording) fallbackAmplitude else rmsLevel
        val isActiveListening = voiceState is VoiceUiState.Listening ||
            voiceState is VoiceUiState.Connecting ||
            isFallbackRecording

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(GlassSurfaceDark.copy(alpha = 0.96f))
                .border(
                    width = 1.4.dp,
                    brush = Brush.linearGradient(
                        if (voiceState is VoiceUiState.Error && !isFallbackRecording) {
                            listOf(NeonRose, NeonPurple)
                        } else {
                            listOf(NeonCyan, NeonPurple, NeonRose)
                        }
                    ),
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(14.dp)
                .testTag("voice_command_overlay")
        ) {
            // Top Status Row + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        if (isActiveListening) NeonCyan else NeonRose,
                                        NeonPurple.copy(alpha = 0.6f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isActiveListening) Icons.Default.GraphicEq else Icons.Default.Mic,
                            contentDescription = "Voice command status indicator",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = when {
                                isFallbackRecording -> "Recording Voice Command (${fallbackSeconds}s)"
                                voiceState is VoiceUiState.Connecting -> "Connecting Microphone..."
                                voiceState is VoiceUiState.Listening -> "Listening Live • Speak Your Command"
                                voiceState is VoiceUiState.Processing -> "Processing Voice Command..."
                                voiceState is VoiceUiState.Error -> "Voice Input Notice"
                                else -> "Voice Command Ready"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isFallbackRecording) {
                                "Tap Send to transcribe & run AI inference"
                            } else {
                                "Real-time speech-to-text triggers Ghost AI automatically"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("voice_cancel_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel voice command",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Real-Time Reactive Equalizer Bars
            if (isActiveListening) {
                ReactiveWaveformBar(
                    level = activeLevel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Live Transcription / Error Preview Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0A1020).copy(alpha = 0.85f))
                    .border(
                        1.dp,
                        NeonCyan.copy(alpha = if (liveSpokenText.isNotBlank()) 0.55f else 0.2f),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("voice_live_transcript_box")
            ) {
                val displayText = when {
                    voiceState is VoiceUiState.Error && !isFallbackRecording -> voiceState.message
                    liveSpokenText.isNotBlank() -> liveSpokenText
                    isFallbackRecording -> "Capturing voice audio... speak clearly near your microphone."
                    else -> "Say anything — e.g. \"Explain black holes\", \"Generate a sunset\", or \"Open YouTube\"..."
                }
                Text(
                    text = displayText,
                    color = when {
                        voiceState is VoiceUiState.Error && !isFallbackRecording -> NeonRose
                        liveSpokenText.isNotBlank() -> TextPrimary
                        else -> TextSecondary
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Language Switcher Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VOICE_LANGUAGES.forEach { lang ->
                    val selected = selectedLanguageTag == lang.tag
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) NeonCyan.copy(alpha = 0.22f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .border(
                                1.dp,
                                if (selected) NeonCyan else Color.White.copy(alpha = 0.14f),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onLanguageSelected(lang.tag) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .testTag("voice_lang_${lang.tag}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lang.label,
                            color = if (selected) NeonCyan else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            // Quick Sample Voice Prompt Chips (clickable for one-tap voice-style dispatch)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QUICK_VOICE_COMMANDS.forEach { suggestion ->
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(NeonPurple.copy(alpha = 0.14f))
                            .border(1.dp, NeonPurple.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .clickable { onSendCommandNow(suggestion) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "\"$suggestion\"",
                            color = TextPrimary,
                            fontSize = 11.5.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary Action Row (>= 48.dp touch targets)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (voiceState is VoiceUiState.Error && !isFallbackRecording) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("voice_retry_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry voice input",
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry", color = NeonCyan, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onUseTranscriptInInput(liveSpokenText) },
                        enabled = liveSpokenText.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("voice_stop_edit_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit transcript in input box",
                            tint = TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Edit", color = TextPrimary)
                    }
                }

                Button(
                    onClick = { onSendCommandNow(liveSpokenText) },
                    enabled = liveSpokenText.isNotBlank() || isFallbackRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier
                        .weight(1.3f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("voice_send_now_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send voice command to AI",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isFallbackRecording) "Transcribe & Send" else "Send Command",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ReactiveWaveformBar(
    level: Float,
    modifier: Modifier = Modifier
) {
    val smoothedLevel by animateFloatAsState(
        targetValue = level.coerceIn(0.05f, 1f),
        animationSpec = tween(durationMillis = 90),
        label = "rms_smooth"
    )
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_idle_pulse")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wave_phase"
    )

    val barCount = 18
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A1020).copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        for (i in 0 until barCount) {
            val centerFactor = 1f - (abs(i - (barCount - 1) / 2f) / (barCount / 2f))
            val shimmer = ((i % 3) * 0.14f + phase * 0.25f)
            val heightFraction = (0.16f + (smoothedLevel * 0.84f * (0.45f + 0.55f * centerFactor)) + shimmer * 0.18f)
                .coerceIn(0.14f, 1f)

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((30f * heightFraction).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.verticalGradient(
                            if (i % 2 == 0) listOf(NeonCyan, NeonPurple)
                            else listOf(NeonRose, NeonPurple)
                        )
                    )
            )
        }
    }
}
