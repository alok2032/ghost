package com.ayra.ai.app.ui.components.control

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ayra.ai.app.automation.DeviceAutomationController
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.ScheduledTask
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGreen
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import java.util.Locale

@Composable
internal fun ControlCenterPermissionsCard(
    onRequestSinglePermission: (String) -> Unit,
    canScheduleExactAlarms: Boolean,
    onOpenExactAlarmSettings: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassSurfaceDark)
            .border(1.2.dp, NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Just-in-Time Device Permissions",
                    color = TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "প্রয়োজন অনুযায়ী শুধুমাত্র নির্দিষ্ট ফিচারের পারমিশন চালু বা যাচাই করুন",
                    color = TextSecondary,
                    fontSize = 10.5.sp
                )
            }
        }
        val permissionEntries = buildList {
            add("Mic" to Manifest.permission.RECORD_AUDIO)
            add("Camera" to Manifest.permission.CAMERA)
            add("Location" to Manifest.permission.ACCESS_FINE_LOCATION)
            add("Contacts" to Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("Alerts" to Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            permissionEntries.forEach { (label, perm) ->
                val granted = if (perm == Manifest.permission.ACCESS_FINE_LOCATION) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                } else {
                    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (granted) NeonGreen.copy(alpha = 0.16f) else Color(0xFF1A2236))
                        .border(
                            1.dp,
                            if (granted) NeonGreen.copy(alpha = 0.6f) else NeonCyan.copy(alpha = 0.35f),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(enabled = !granted) { onRequestSinglePermission(perm) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("jit_perm_${label.lowercase(Locale.ROOT)}")
                ) {
                    Text(
                        text = if (granted) "$label ✓" else "$label • Allow",
                        color = if (granted) NeonGreen else NeonCyan,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (canScheduleExactAlarms) NeonGreen.copy(alpha = 0.16f) else NeonRose.copy(alpha = 0.18f))
                        .border(
                            1.dp,
                            if (canScheduleExactAlarms) NeonGreen.copy(alpha = 0.6f) else NeonRose.copy(alpha = 0.7f),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(enabled = !canScheduleExactAlarms) { onOpenExactAlarmSettings() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("jit_perm_exact_alarm")
                ) {
                    Text(
                        text = if (canScheduleExactAlarms) "Exact Alarms ✓" else "Exact Alarms • Fix",
                        color = if (canScheduleExactAlarms) NeonGreen else NeonRose,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
internal fun ControlCenterCloudAccountCard(
    userDisplayName: String?,
    userEmail: String?,
    onSignOut: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    if (!userEmail.isNullOrBlank() || !userDisplayName.isNullOrBlank()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(GlassSurfaceDark)
                .border(1.dp, NeonCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = userDisplayName?.ifBlank { "Google Account" } ?: "Google Account",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                if (!userEmail.isNullOrBlank()) {
                    Text(
                        text = "$userEmail • Cloud Sync Active ☁️",
                        color = NeonCyan,
                        fontSize = 10.5.sp
                    )
                }
            }
            if (onSignOut != null) {
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onSignOut()
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("sign_out_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Sign Out", color = NeonRose, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun ControlCenterInstantControlsSection(
    automationController: DeviceAutomationController,
    isFlashlightOn: Boolean,
    onFlashlightChange: (Boolean) -> Unit,
    onQuickFeedback: (String) -> Unit
) {
    Text(
        text = "⚡ Instant Phone Controls / সরাসরি মোবাইল কন্ট্রোল",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = NeonCyan,
        fontSize = 13.sp
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickControlCard(
            modifier = Modifier.weight(1f),
            icon = if (isFlashlightOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
            title = "Flashlight",
            status = if (isFlashlightOn) "ON 🔦" else "OFF",
            isActive = isFlashlightOn,
            activeColor = Color(0xFFFFD700),
            onClick = {
                val newState = !isFlashlightOn
                automationController.toggleFlashlight(newState)
                onFlashlightChange(newState)
                onQuickFeedback(if (newState) "🔦 Flashlight turned ON" else "🔦 Flashlight turned OFF")
            }
        )
        QuickControlCard(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            title = "Max Sound",
            status = "100% 🔊",
            isActive = false,
            activeColor = NeonCyan,
            onClick = { onQuickFeedback(automationController.maxVolume()) }
        )
        QuickControlCard(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Filled.VolumeMute,
            title = "Mute Sound",
            status = "Mute 🔇",
            isActive = false,
            activeColor = NeonRose,
            onClick = { onQuickFeedback(automationController.muteVolume()) }
        )
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickControlCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Wifi,
            title = "Wi-Fi",
            status = "Settings 📶",
            isActive = false,
            activeColor = NeonCyan,
            onClick = {
                automationController.openWifiSettings()
                onQuickFeedback("📶 Opening Wi-Fi settings...")
            }
        )
        QuickControlCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Bluetooth,
            title = "Bluetooth",
            status = "Settings ᛒ",
            isActive = false,
            activeColor = NeonPurple,
            onClick = {
                automationController.openBluetoothSettings()
                onQuickFeedback("ᛒ Opening Bluetooth settings...")
            }
        )
        QuickControlCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.CameraAlt,
            title = "Camera",
            status = "Photo 📸",
            isActive = false,
            activeColor = NeonGreen,
            onClick = {
                automationController.openCamera()
                onQuickFeedback("📸 Launching Camera...")
            }
        )
    }

    Text(
        text = "📱 Quick App Launcher / ফোন অ্যাপ লঞ্চার",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = NeonCyan,
        fontSize = 12.sp
    )

    val popularApps = listOf(
        Triple("YouTube", "▶️", "youtube"),
        Triple("WhatsApp", "💬", "whatsapp"),
        Triple("Facebook", "📘", "facebook"),
        Triple("Instagram", "📷", "instagram"),
        Triple("Spotify", "🎵", "spotify"),
        Triple("Chrome", "🌐", "chrome"),
        Triple("Maps", "🗺️", "maps"),
        Triple("Settings", "⚙️", "settings")
    )

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        popularApps.chunked(4).forEach { rowApps ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowApps.forEach { (appName, emoji, commandKey) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 70.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF131B2E))
                            .border(1.dp, Color(0x3300FFFF), RoundedCornerShape(12.dp))
                            .clickable {
                                if (commandKey == "settings") {
                                    automationController.openSettings()
                                    onQuickFeedback("⚙️ Opening Settings...")
                                } else {
                                    automationController.tryExecuteAutomation("open $commandKey")
                                    onQuickFeedback("🚀 Opening $appName...")
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(text = emoji, fontSize = 22.sp)
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = appName,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlassSurfaceDark)
            .border(1.dp, NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Media Volume Adjustment",
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                onClick = { onQuickFeedback(automationController.adjustVolume(false)) },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E283F))
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "Down", tint = NeonCyan, modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { onQuickFeedback(automationController.adjustVolume(true)) },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E283F))
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Up", tint = NeonCyan, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun ControlCenterScheduledRoutinesSection(
    scheduledTasks: List<ScheduledTask>,
    canScheduleExactAlarms: Boolean,
    onOpenExactAlarmSettings: () -> Unit,
    onDeleteScheduledTask: (Long) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "⏰ Autonomous Scheduled Routines (${scheduledTasks.size})",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = NeonCyan,
            fontSize = 13.sp
        )
        Text(
            text = if (canScheduleExactAlarms) "AlarmManager RTC (Exact)" else "AlarmManager (Inexact Fallback)",
            color = if (canScheduleExactAlarms) NeonGreen else NeonRose,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(NeonRose.copy(alpha = 0.12f))
                .border(1.dp, NeonRose.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(12.dp)
                .testTag("exact_alarm_disabled_banner"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("⚠️ Exact Alarms Permission Disabled", color = NeonRose, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "Scheduled reminders & call prompts currently fall back to inexact battery-deferred timing. Grant 'Alarms & reminders' access for exact trigger timing.",
                color = TextPrimary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
            OutlinedButton(
                onClick = onOpenExactAlarmSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("open_exact_alarm_settings_button")
            ) {
                Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open Alarm Settings", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (scheduledTasks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GlassSurfaceDark)
                .border(1.dp, Color(0x2200FFFF), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("কোনো অ্যাক্টিভ শিডিউল নেই।", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "💡 আপনি সরাসরি বলতে বা টাইপ করতে পারেন: \"রাত ১২টায় রাহুলকে হোয়াটসঅ্যাপে শুভ জন্মদিন উইশ করো\" — নির্ধারিত সময়ে AlarmManager অ্যালার্ম বাজিয়ে মেসেজ রিভিউ ও পাঠানোর নোটিফিকেশন দেখাবে।",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            scheduledTasks.forEach { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassSurfaceDark)
                        .border(
                            1.dp,
                            if (task.status == "PENDING") NeonCyan.copy(alpha = 0.6f) else NeonGreen.copy(alpha = 0.6f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = if (task.status == "PENDING") NeonCyan else NeonGreen,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(task.title, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF1E293B))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(task.targetApp, color = NeonCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            text = "সময়: ${task.formattedTime} • স্ট্যাটাস: ${task.status}",
                            color = if (task.status == "PENDING") NeonCyan else NeonGreen,
                            fontSize = 10.5.sp
                        )
                        Text(task.messageText, color = TextSecondary, fontSize = 10.sp, maxLines = 1)
                    }
                    IconButton(onClick = { onDeleteScheduledTask(task.id) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Cancel Task", tint = NeonRose, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ControlCenterPromptHistorySection(
    userMessages: List<ChatMessage>,
    onSelectPrompt: (String) -> Unit,
    onCopyPrompt: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "🔍 Search & Prompt History (${userMessages.size})",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = NeonCyan,
            fontSize = 13.sp
        )
        if (userMessages.isNotEmpty()) {
            Text("Tap to re-run", color = TextSecondary, fontSize = 10.sp)
        }
    }

    if (userMessages.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GlassSurfaceDark)
                .border(1.dp, Color(0x2200FFFF), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text("এখনো কোনো অতীত প্রশ্ন বা প্রম্পট সেভ করা নেই।", color = TextSecondary, fontSize = 11.sp)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            userMessages.takeLast(8).reversed().forEach { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GlassSurfaceDark)
                        .border(0.8.dp, Color(0x1F00FFFF), RoundedCornerShape(10.dp))
                        .clickable { onSelectPrompt(msg.content) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(msg.content, color = TextPrimary, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onCopyPrompt(msg.content) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy prompt", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { onSelectPrompt(msg.content) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Re-run prompt", tint = NeonGreen, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ControlCenterStorageSection(
    messageCount: Int,
    onExportHistory: () -> Unit,
    onClearHistory: () -> Unit
) {
    Text(
        text = "💾 Storage & Privacy / মোবাইলের মেমোরি ও ডেটা",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = NeonCyan,
        fontSize = 13.sp
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlassSurfaceDark)
            .border(1.dp, Color(0x2200FFFF), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Storage, contentDescription = null, tint = NeonPurple)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Local SQLCipher Storage: $messageCount messages saved",
                color = TextPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Private encrypted Room database (hardware Keystore protected)",
                color = TextSecondary,
                fontSize = 10.5.sp
            )
        }
        IconButton(onClick = onExportHistory, modifier = Modifier.testTag("export_history_in_settings")) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Export History to Storage", tint = NeonCyan)
        }
        IconButton(onClick = onClearHistory, modifier = Modifier.testTag("clear_history_in_settings")) {
            Icon(Icons.Default.Delete, contentDescription = "Clear", tint = NeonRose)
        }
    }
}

@Composable
internal fun QuickControlCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    status: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isActive) activeColor.copy(alpha = 0.2f) else GlassSurfaceDark)
            .border(1.dp, if (isActive) activeColor else Color(0x2200FFFF), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(imageVector = icon, contentDescription = title, tint = if (isActive) activeColor else NeonCyan, modifier = Modifier.size(24.dp))
            Text(text = title, color = TextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
            Text(text = status, color = if (isActive) activeColor else TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
internal fun SettingToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlassSurfaceDark)
            .border(1.dp, Color(0x2200FFFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = if (checked) NeonCyan else TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = NeonCyan,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Color(0xFF1E283F)
            )
        )
    }
}

@Composable
internal fun ControlCenterHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(NeonCyan, com.ayra.ai.app.ui.theme.NeonPurple, Color.Black))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Ayra AI Logo",
                tint = Color.Black,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Ayra AI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF131B2E))
                        .border(0.8.dp, NeonCyan.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "By Alok",
                        color = NeonCyan,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "• Control Center",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
            }
            Text(
                text = "সম্পূর্ণ ডিভাইস কন্ট্রোল, AI মডেল নির্বাচন ও পারসোনা",
                style = MaterialTheme.typography.bodySmall,
                color = NeonCyan,
                fontSize = 11.5.sp
            )
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("close_control_center_button")
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
        }
    }
}
