package com.ayra.ai.app.ui.components.chat

import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.StorageHelper
import com.ayra.ai.app.security.AndroidHardeningHelper
import com.ayra.ai.app.ui.components.CyberTerminalCard
import com.ayra.ai.app.ui.components.GitHubPluginCard
import com.ayra.ai.app.ui.components.PdfDocumentDownloadCard
import com.ayra.ai.app.ui.components.SongPlayerCard
import com.ayra.ai.app.ui.components.ThreeDModelCard
import com.ayra.ai.app.ui.components.VideoPlayerCard
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGreen
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import com.ayra.ai.app.ui.theme.UserBubble
import org.json.JSONObject

@Composable
internal fun GlassMessageBubble(
    message: ChatMessage,
    onImageClick: (String) -> Unit,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onEdit: (String) -> Unit,
    onRunPluginPrompt: (String) -> Unit = {},
    onRegenerate: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val isUser = message.role == "user"
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.82f else 1f)
                .clip(RoundedCornerShape(18.dp))
                .background(if (isUser) UserBubble else GlassSurfaceDark)
                .border(
                    1.2.dp,
                    if (isUser) {
                        Brush.linearGradient(listOf(NeonCyan.copy(alpha = 0.5f), Color(0x3300FFFF)))
                    } else {
                        Brush.linearGradient(listOf(NeonCyan.copy(alpha = 0.7f), NeonPurple.copy(alpha = 0.5f), NeonRose.copy(alpha = 0.6f)))
                    },
                    RoundedCornerShape(18.dp)
                )
                .padding(14.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (isUser) NeonCyan else NeonRose))
                        Text(
                            text = if (isUser) "You" else "Ayra AI",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isUser) NeonCyan else NeonRose
                        )
                    }

                    if (!isUser && !message.actionCommand.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF0F172A))
                                .border(0.8.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("🤖 ${message.actionCommand}", color = NeonCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (!message.attachedImagePath.isNullOrBlank()) {
                    val resolvedImageModel = remember(message.attachedImagePath) {
                        StorageHelper.resolveCoilImageModel(context, message.attachedImagePath)
                    }
                    AsyncImage(
                        model = resolvedImageModel,
                        contentDescription = "Image preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable { onImageClick(message.attachedImagePath) },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (message.craftType == "IMAGE" && !message.craftPayload.isNullOrBlank()) {
                    var imgRetryTick by remember(message.craftPayload) { mutableStateOf(0) }
                    val rawCraftPayload = message.craftPayload
                    val isRemoteImageUrl = rawCraftPayload.startsWith("http://", ignoreCase = true) ||
                        rawCraftPayload.startsWith("https://", ignoreCase = true)
                    val activeImgModel = remember(rawCraftPayload, imgRetryTick) {
                        if (isRemoteImageUrl) {
                            if (imgRetryTick == 0) rawCraftPayload else {
                                val sep = if (rawCraftPayload.contains("?")) "&" else "?"
                                "${rawCraftPayload}${sep}retry=$imgRetryTick"
                            }
                        } else {
                            StorageHelper.resolveCoilImageModel(context, rawCraftPayload) ?: rawCraftPayload
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.2.dp, NeonCyan, RoundedCornerShape(14.dp))
                    ) {
                        SubcomposeAsyncImage(
                            model = activeImgModel,
                            contentDescription = "AI Generated Photo",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .clickable { onImageClick(message.craftPayload) },
                            contentScale = ContentScale.Crop,
                            loading = {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(250.dp).background(Color(0xFF0F172A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = NeonCyan, strokeWidth = 2.5.dp)
                                        Text("Crafting 8K Ultra-HD Masterpiece...", color = TextSecondary, fontSize = 12.sp)
                                    }
                                }
                            },
                            error = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(250.dp)
                                        .background(Color(0xFF1E293B))
                                        .clickable { imgRetryTick++ },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Could not render image preview • Tap to retry", color = TextSecondary, fontSize = 12.sp)
                                }
                            }
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.75f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("HD 8K Masterpiece • Tap to Expand 🔍", color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if ((message.craftType == "VIDEO" || message.craftType == "STORYBOARD") && !message.craftPayload.isNullOrBlank()) {
                    VideoPlayerCard(jsonPayload = message.craftPayload, onExpand = onImageClick)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (message.craftType == "SONG" && !message.craftPayload.isNullOrBlank()) {
                    SongPlayerCard(jsonPayload = message.craftPayload)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (message.craftType == "3D") {
                    ThreeDModelCard(craftPayload = message.craftPayload, title = "3D Interactive Hologram")
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if ((message.craftType == "SCHEDULED_ROUTINE" || message.craftType == "SCHEDULED_CALL" ||
                            message.craftType == "SCHEDULED_MESSAGE" || message.craftType == "SCHEDULED_REMINDER") &&
                    !message.craftPayload.isNullOrBlank()
                ) {
                    ScheduledRoutineBannerCard(jsonPayload = message.craftPayload)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (message.craftType == "PDF_DOWNLOAD" && !message.craftPayload.isNullOrBlank()) {
                    PdfDocumentDownloadCard(filePath = message.craftPayload)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (message.craftType == "CYBER_TERMINAL") {
                    CyberTerminalCard(initialCommand = message.craftPayload)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (message.craftType == "GITHUB_PLUGIN" && !message.craftPayload.isNullOrBlank()) {
                    GitHubPluginCard(jsonPayload = message.craftPayload, onRunPluginPrompt = onRunPluginPrompt)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                MarkdownCodeContentView(
                    content = message.content,
                    onCopyCode = { codeToCopy ->
                        AndroidHardeningHelper.copyWithSensitivityProtection(
                            context = context,
                            label = "Ayra AI Code",
                            text = codeToCopy,
                            isSensitive = true,
                            autoClearMs = if (AyraPreferences.isClipboardAutoClearEnabled(context)) 60_000L else 0L
                        )
                        Toast.makeText(context, "Code copied securely! / কোড কপি করা হয়েছে 📋", Toast.LENGTH_SHORT).show()
                    }
                )

                if (isUser) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        if (onDelete != null) {
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(48.dp).testTag("delete_user_message_button")
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete message / বার্তা মুছুন", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0B132B).copy(alpha = 0.85f))
                                .border(1.dp, Brush.linearGradient(listOf(NeonCyan.copy(alpha = 0.7f), NeonPurple.copy(alpha = 0.5f))), RoundedCornerShape(10.dp))
                                .clickable { onEdit(message.content) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .testTag("edit_question_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Question / প্রশ্ন এডিট", tint = NeonCyan, modifier = Modifier.size(13.dp))
                            Text("Edit Question / প্রশ্ন এডিট", color = NeonCyan, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onSpeak, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Listen", tint = NeonCyan, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onEdit(message.content) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Note / নোট এডিট", tint = NeonPurple, modifier = Modifier.size(18.dp))
                        }
                        if (onRegenerate != null) {
                            IconButton(
                                onClick = onRegenerate,
                                modifier = Modifier.size(48.dp).testTag("regenerate_message_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Regenerate / উত্তর পুনরায় তৈরি", tint = NeonCyan, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (onDelete != null) {
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(48.dp).testTag("delete_message_button")
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete message / বার্তা মুছুন", tint = TextSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScheduledRoutineBannerCard(jsonPayload: String) {
    val data = remember(jsonPayload) {
        try {
            JSONObject(jsonPayload)
        } catch (_: Exception) {
            null
        }
    }
    val taskType = data?.optString("taskType", "ROUTINE") ?: "ROUTINE"
    val recipient = data?.optString("recipient", "Friend") ?: "Friend"
    val targetApp = data?.optString("targetApp", "WhatsApp") ?: "WhatsApp"
    val time = data?.optString("time", "12:00 AM") ?: "12:00 AM"
    val phone = data?.optString("phone", "") ?: ""
    val spokenMessage = data?.optString("spokenMessage", "") ?: ""
    val reminder = data?.optString("reminder", "") ?: ""
    val message = data?.optString("message", "") ?: ""

    val isCall = taskType == "CALL" || targetApp.equals("CALL", ignoreCase = true)
    val isReminder = taskType == "REMINDER" || targetApp.equals("REMINDER", ignoreCase = true)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    when {
                        isCall -> listOf(Color(0xFF0F172A), Color(0xFF1E1B4B))
                        isReminder -> listOf(Color(0xFF2E1065), Color(0xFF0F172A))
                        else -> listOf(Color(0xFF0D2520), Color(0xFF0A1526))
                    }
                )
            )
            .border(
                1.2.dp,
                Brush.linearGradient(
                    when {
                        isCall -> listOf(NeonCyan, NeonPurple)
                        isReminder -> listOf(Color(0xFFFFD700), NeonRose)
                        else -> listOf(NeonGreen, NeonCyan)
                    }
                ),
                RoundedCornerShape(14.dp)
            )
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (isCall) NeonCyan else if (isReminder) Color(0xFFFFD700) else NeonGreen))
                    Text(
                        text = if (isCall) "VOICE CALL ARMED" else if (isReminder) "REMINDER ARMED" else "SCHEDULED MESSAGE ARMED",
                        color = if (isCall) NeonCyan else if (isReminder) Color(0xFFFFD700) else NeonGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                }
                Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(NeonCyan.copy(alpha = 0.2f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(
                        text = if (isCall) "PHONE CALL" else if (isReminder) "REMINDER" else targetApp.uppercase(),
                        color = NeonCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isCall) {
                Text("📞 কল প্রাপক: $recipient ${if (phone.isNotBlank()) "($phone)" else ""} • সময়: $time", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (spokenMessage.isNotBlank()) {
                    Text("🎙️ কলে যে বার্তাটি বলবে: \"$spokenMessage\"", color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Text("🛡️ মোবাইল স্লিপ বা লক থাকলেও AlarmManager অ্যালার্ম বাজিয়ে বার্তাটি মনে করিয়ে দেবে এবং এক-ট্যাপ ডায়ালার (ACTION_DIAL) নোটিফিকেশন দেখাবে।", color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
            } else if (isReminder) {
                Text("⏰ রিমাইন্ডার সময়: $time", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (reminder.isNotBlank()) {
                    Text("📌 কাজ: \"$reminder\"", color = Color(0xFFFFD700), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Text("🔔 নির্ধারিত সময়ে রিংটোন বাজিয়ে এবং ভয়েস অ্যাসিস্ট্যান্টের মাধ্যমে স্পষ্ট কণ্ঠে আপনাকে কাজটি মনে করিয়ে দেওয়া হবে।", color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
            } else {
                Text("🎯 প্রাপক: $recipient ${if (phone.isNotBlank()) "($phone)" else ""} • সময়: $time", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (message.isNotBlank()) {
                    Text("✉️ বার্তা: \"$message\"", color = NeonGreen, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Text("🔔 নির্ধারিত সময়ে AlarmManager আপনাকে মনে করিয়ে দেবে এবং এক-ট্যাপে মেসেজ রিভিউ ও পাঠানোর নোটিফিকেশন দেখাবে।", color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
fun MarkdownCodeContentView(
    content: String,
    onCopyCode: (String) -> Unit
) {
    if (!content.contains("```")) {
        Text(text = content, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontSize = 14.5.sp, lineHeight = 22.sp)
        return
    }

    val parts = remember(content) {
        val list = mutableListOf<Pair<Boolean, String>>()
        val regex = Regex("```([a-zA-Z0-9_+-]*)\n?([\\s\\S]*?)```")
        var lastIndex = 0
        regex.findAll(content).forEach { match ->
            if (match.range.first > lastIndex) {
                val textChunk = content.substring(lastIndex, match.range.first).trim()
                if (textChunk.isNotBlank()) list.add(false to textChunk)
            }
            val lang = match.groupValues[1].ifBlank { "code" }
            val codeBody = match.groupValues[2]
            list.add(true to "$lang\n$codeBody")
            lastIndex = match.range.last + 1
        }
        if (lastIndex < content.length) {
            val trailingText = content.substring(lastIndex).trim()
            if (trailingText.isNotBlank()) list.add(false to trailingText)
        }
        list
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { (isCode, partContent) ->
            if (!isCode) {
                Text(text = partContent, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontSize = 14.5.sp, lineHeight = 22.sp)
            } else {
                val firstLineBreak = partContent.indexOf('\n')
                val langTag = if (firstLineBreak != -1) partContent.substring(0, firstLineBreak).trim() else "code"
                val rawCode = if (firstLineBreak != -1) partContent.substring(firstLineBreak + 1) else partContent

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF090D18))
                        .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF111827)).padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Code, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                                Text(langTag.uppercase(), color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1E293B))
                                    .clickable { onCopyCode(rawCode) }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Code", tint = TextPrimary, modifier = Modifier.size(13.dp))
                                Text("Copy Code", color = TextPrimary, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        val scrollState = rememberScrollState()
                        Box(modifier = Modifier.fillMaxWidth().padding(12.dp).horizontalScroll(scrollState)) {
                            Text(
                                text = rawCode,
                                color = Color(0xFFE2E8F0),
                                fontSize = 12.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
