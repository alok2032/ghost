package com.ayra.ai.app.ui.components.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary

@Composable
internal fun ChatAttachmentHubDialog(
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit,
    onPickDocument: () -> Unit,
    onShareGpsLocation: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF070B16),
            border = BorderStroke(1.5.dp, Brush.linearGradient(listOf(NeonCyan, NeonPurple, NeonRose))),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 520.dp)
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Brush.radialGradient(listOf(NeonCyan, NeonPurple)))
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp).align(Alignment.Center)
                            )
                        }
                        Column {
                            Text(
                                text = "Attach Media & Location",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan,
                                fontSize = 16.sp
                            )
                            Text("Photo, Video, PDF, Music & Code work directly by chat prompt", color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }

                AttachmentHubRow(
                    icon = Icons.Default.CameraAlt,
                    tint = NeonCyan,
                    bgColor = Color(0xFF0F172A),
                    title = "Take Photo with Camera",
                    subtitle = "ছবি তুলে সরাসরি এআই অ্যানালাইসিস করুন",
                    onClick = { onDismiss(); onTakePhoto() }
                )
                AttachmentHubRow(
                    icon = Icons.Default.Image,
                    tint = NeonPurple,
                    bgColor = Color(0xFF0F172A),
                    title = "Upload from Photos / Gallery",
                    subtitle = "গ্যালারির ছবি দিয়ে ভিশন, অবজেক্ট বা লেখা পড়ুন",
                    onClick = { onDismiss(); onPickGallery() }
                )
                AttachmentHubRow(
                    icon = Icons.Default.Description,
                    tint = NeonCyan,
                    bgColor = Color(0xFF0F172A),
                    title = "Upload Document / PDF File",
                    subtitle = "PDF বা ডকুমেন্টের সারাংশ ও প্রশ্নোত্তর",
                    onClick = { onDismiss(); onPickDocument() }
                )
                AttachmentHubRow(
                    icon = Icons.Default.LocationOn,
                    tint = NeonRose,
                    bgColor = Color(0xFF131B2E),
                    title = "Share Current GPS Location",
                    subtitle = "বর্তমান অক্ষাংশ, দ্রাঘিমাংশ ও ম্যাপ লিঙ্ক যোগ করুন",
                    onClick = { onDismiss(); onShareGpsLocation() }
                )
            }
        }
    }
}

@Composable
private fun AttachmentHubRow(
    icon: ImageVector,
    tint: Color,
    bgColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(0.8.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextSecondary, fontSize = 10.5.sp)
        }
    }
}
