package com.ayra.ai.app.ui.components.chat

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.ayra.ai.app.ai.memory.MemoryCandidate
import com.ayra.ai.app.data.StorageHelper
import com.ayra.ai.app.ui.components.ProviderKeysSection
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary

@Composable
internal fun ChatAttachmentPreviewBanners(
    selectedImageUri: Uri?,
    onClearImage: () -> Unit,
    selectedDocumentName: String?,
    onClearDocument: () -> Unit,
    pendingMemoryCandidate: MemoryCandidate?,
    onDismissMemoryCandidate: () -> Unit,
    onConfirmMemoryCandidate: () -> Unit,
    exactAlarmBannerVisible: Boolean,
    onDismissExactAlarmBanner: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    showContactsInlineBanner: Boolean,
    onAllowContactsFromBanner: () -> Unit,
    isRecordingAudio: Boolean,
    isFallbackVoiceMode: Boolean,
    isTranscribingAudio: Boolean,
    audioSeconds: Int,
    audioAmplitude: Float,
    onCancelAudioRecording: () -> Unit
) {
    if (selectedImageUri != null) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GlassSurfaceDark)
                .border(1.dp, NeonCyan, RoundedCornerShape(12.dp))
                .padding(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AsyncImage(
                    model = selectedImageUri,
                    contentDescription = "Selected Image",
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Text("Visual attachment ready", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onClearImage) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = NeonRose)
                }
            }
        }
    }

    if (selectedDocumentName != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GlassSurfaceDark)
                .border(1.dp, NeonCyan.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Description, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(selectedDocumentName, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    Text("Ready for Q&A, summary, editing & insights", color = TextSecondary, fontSize = 10.sp)
                }
                IconButton(onClick = onClearDocument, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = NeonRose, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    pendingMemoryCandidate?.let { candidate ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GlassSurfaceDark)
                .border(1.dp, NeonPurple.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("opt_in_memory_banner"),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Remember for future chats (${candidate.category})?", color = NeonPurple, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            Text(candidate.content, color = TextPrimary, fontSize = 12.sp, maxLines = 2)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismissMemoryCandidate, modifier = Modifier.testTag("memory_banner_dismiss")) {
                    Text("Dismiss", color = TextSecondary, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(6.dp))
                TextButton(onClick = onConfirmMemoryCandidate, modifier = Modifier.testTag("memory_banner_save")) {
                    Text("Save to Memory", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }

    if (exactAlarmBannerVisible) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GlassSurfaceDark)
                .border(1.dp, NeonRose.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("exact_alarm_warning_banner"),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Exact Alarm Permission Needed for On-Time Reminders", color = NeonRose, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            Text("Reminders are using approximate timing fallback. Allow 'Alarms & reminders' in system settings for exact delivery.", color = TextPrimary, fontSize = 11.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismissExactAlarmBanner, modifier = Modifier.testTag("exact_alarm_banner_dismiss")) {
                    Text("Dismiss", color = TextSecondary, fontSize = 11.5.sp)
                }
                Spacer(modifier = Modifier.width(6.dp))
                TextButton(onClick = onOpenExactAlarmSettings, modifier = Modifier.testTag("exact_alarm_settings_button")) {
                    Text("Enable Exact Alarms", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                }
            }
        }
    }

    if (showContactsInlineBanner) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GlassSurfaceDark)
                .border(1.dp, NeonCyan.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("contacts_rationale_inline_banner"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Allow Contacts to look up phone numbers by name automatically.",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAllowContactsFromBanner, modifier = Modifier.testTag("contacts_rationale_allow_button")) {
                Text("Allow Contacts", color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if ((isRecordingAudio && !isFallbackVoiceMode) || isTranscribingAudio) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GlassSurfaceDark)
                .border(1.dp, NeonRose.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isTranscribingAudio) {
                    "🎧 Gemini Audio AI প্রতিলিপি তৈরি করছে..."
                } else {
                    "🎙️ Recording AI Audio (${audioSeconds}s • ${(audioAmplitude * 100).toInt()}%) • Tap Audio button to transcribe"
                },
                color = NeonRose,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (isRecordingAudio) {
                Text(
                    text = "Cancel",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onCancelAudioRecording() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
internal fun ChatExpandedImageDialog(
    context: Context,
    expandedImageUri: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            val resolvedExpandedModel = remember(expandedImageUri) {
                StorageHelper.resolveCoilImageModel(context, expandedImageUri)
            }
            AsyncImage(
                model = resolvedExpandedModel,
                contentDescription = "Expanded View",
                modifier = Modifier.fillMaxWidth(0.95f).clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
internal fun ChatPermissionRationaleDialog(
    title: String,
    body: String,
    actionLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GlassSurfaceDark,
        titleContentColor = NeonCyan,
        textContentColor = TextPrimary,
        title = { Text(text = title, fontWeight = FontWeight.Bold) },
        text = { Text(text = body, fontSize = 13.sp) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                modifier = Modifier.testTag("permission_rationale_confirm")
            ) {
                Text(actionLabel, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("permission_rationale_cancel")) {
                Text("Not Now", color = TextSecondary)
            }
        }
    )
}

@Composable
internal fun ChatClearConversationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear Current Conversation", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = { Text("Are you sure you want to clear all messages in this conversation? Saved sessions can still be managed in Conversations.", color = TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Clear Chat", color = NeonRose, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = Color(0xFF1E293B)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatKeyOnboardingModal(
    context: Context,
    onDismiss: () -> Unit
) {
    val keySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = keySheetState,
        containerColor = Color(0xFF0F172A),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔑 Connect Your AI Key", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }
            Text(
                text = "Add at least one free AI key (Google Gemini, OpenRouter, DeepSeek, OpenAI, or Anthropic) to activate real answers without limits.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            ProviderKeysSection(context = context)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
