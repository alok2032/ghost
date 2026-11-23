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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
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
import com.ayra.ai.app.ai.providers.ModelTier
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGold
import com.ayra.ai.app.ui.theme.NeonGreen
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
    onSelectModelTierAndPrompt: (tierId: String, prompt: String) -> Unit,
    onFillPromptOnly: (String) -> Unit,
    onDispatchPromptImmediately: (String) -> Unit,
    onOpenControlCenter: () -> Unit,
    onShareGpsLocation: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Color(0xFF070B16),
            border = BorderStroke(1.5.dp, Brush.linearGradient(listOf(NeonCyan, NeonPurple, NeonRose))),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .heightIn(max = 620.dp)
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
                                text = "Ayra AI Prompt & Super-Hub ⚡",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan,
                                fontSize = 16.sp
                            )
                            Text("OmniRoute • DeepSeek • Higgsfield • Qwen • Defense Lab", color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }

                Text("📸 মিডিয়া ও ডকুমেন্ট ইনপুট (Multimodal Capture):", color = NeonCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)

                AttachmentHubRow(
                    icon = Icons.Default.CameraAlt,
                    tint = NeonCyan,
                    bgColor = Color(0xFF0F172A),
                    title = "Take Photo with Camera",
                    subtitle = "ছবি তুলে সরাসরি এআইকে অ্যানালাইসিস করতে বলুন",
                    onClick = { onDismiss(); onTakePhoto() }
                )
                AttachmentHubRow(
                    icon = Icons.Default.Image,
                    tint = NeonPurple,
                    bgColor = Color(0xFF0F172A),
                    title = "Upload from Photos / Gallery",
                    subtitle = "মাল্টি-এআই ভিশন, অবজেক্ট ডিটেকশন ও স্টাইল এডিট",
                    onClick = { onDismiss(); onPickGallery() }
                )
                AttachmentHubRow(
                    icon = Icons.Default.Description,
                    tint = NeonCyan,
                    bgColor = Color(0xFF0F172A),
                    title = "Upload Document / PDF File",
                    subtitle = "PDF বা ডকুমেন্টের সম্পূর্ণ সারাংশ ও প্রশ্নোত্তর",
                    onClick = { onDismiss(); onPickDocument() }
                )

                Text("🧠 ফ্রন্টিয়ার এআই ইঞ্জিন (Multi-Model Frontier Suite):", color = NeonGold, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)

                AttachmentHubRow(
                    icon = Icons.Default.AutoAwesome,
                    tint = NeonGold,
                    bgColor = Color(0xFF131B2E),
                    title = "OmniRoute Multi-Model Ensemble ⚡👑",
                    subtitle = "সব এআই একসাথে সমন্বিত হয়ে বিশ্বের সেরা ফলাফল দেয়",
                    onClick = {
                        onDismiss()
                        onSelectModelTierAndPrompt("omniroute", "OmniRoute Ensemble: সব এআই মডেলের সম্মিলিত জ্ঞান দিয়ে গভীর বিশ্লেষণ দাও")
                    }
                )
                AttachmentHubRow(
                    icon = Icons.Default.Code,
                    tint = NeonRose,
                    bgColor = Color(0xFF131B2E),
                    title = "DeepSeek-R1 Reasoning Harness 🔬",
                    subtitle = "Chain-of-thought যুক্তি, গণিত ও স্বয়ংক্রিয় টেস্ট হারনেস",
                    onClick = {
                        onDismiss()
                        onSelectModelTierAndPrompt(ModelTier.DEEPSEEK_HARNESS.id, "DeepSeek Reasoning Harness: ধাপে ধাপে গভীর গাণিতিক যুক্তি সহ প্রমাণ করো")
                    }
                )
                AttachmentHubRow(
                    icon = Icons.Default.AutoAwesome,
                    tint = Color(0xFFFF5722),
                    bgColor = Color(0xFF131B2E),
                    title = "Qwen 2.5 / 3 Open-Weights 🌐",
                    subtitle = "সরাসরি ও বস্তুনিষ্ঠ ওপেন-ওয়েটস লোকাল ও ক্লাউড বিশ্লেষণ",
                    onClick = {
                        onDismiss()
                        onSelectModelTierAndPrompt(ModelTier.OPEN_WEIGHTS.id, "Open-Weights Engine: প্রযুক্তি ও বিজ্ঞানের প্রশ্নের সরাসরি ও বস্তুনিষ্ঠ উত্তর দাও")
                    }
                )
                AttachmentHubRow(
                    icon = Icons.Default.Code,
                    tint = NeonCyan,
                    bgColor = Color(0xFF131B2E),
                    title = "Defensive Security Research Lab 🛡️",
                    subtitle = "ডিফেনসিভ সাইবার সিকিউরিটি, OWASP, CVE ও সিস্টেম অডিট",
                    onClick = {
                        onDismiss()
                        onSelectModelTierAndPrompt(ModelTier.SECURITY_RESEARCH.id, "Security Research: ডিপ সাইবার ডিফেন্স, OWASP ও CVE মিটিগেশন অডিট")
                    }
                )
                AttachmentHubRow(
                    icon = Icons.Default.AutoAwesome,
                    tint = NeonRose,
                    bgColor = Color(0xFF131B2E),
                    title = "Open-Higgsfield-AI Cinematic 4K Video 🎬",
                    subtitle = "ক্যামেরা মোশন ফিজিক্স ও অ্যানামরফিক অপটিক্যাল রেন্ডারিং",
                    onClick = {
                        onDismiss()
                        onFillPromptOnly("Generate a cinematic 4K video of a spaceship entering a colorful nebula with FPV drone motion")
                    }
                )
                AttachmentHubRow(
                    icon = Icons.Default.AutoAwesome,
                    tint = Color(0xFF7C4DFF),
                    bgColor = Color(0xFF131B2E),
                    title = "ECC Enterprise Cognitive 🌐",
                    subtitle = "মাল্টি-টার্ন মেমরি গ্রাফ, ইন্টেন্ট রেজোলিউশন ও ফ্যাক্ট চেকিং",
                    onClick = {
                        onDismiss()
                        onSelectModelTierAndPrompt(ModelTier.ECC_COGNITIVE.id, "ECC Cognitive: কনটেক্সট মেমরি ও ইন্টেন্ট ডিসঅ্যাম্বিগিউয়েশন গ্রাউন্ড করো")
                    }
                )
                AttachmentHubRow(
                    icon = Icons.Default.Code,
                    tint = NeonGreen,
                    bgColor = Color(0xFF131B2E),
                    title = "Superpowers Autonomous Agent ⚙️",
                    subtitle = "TDD টেস্ট ড্রাইভেন কোডিং, সিস্টেমেটিক ডিবাগিং ও কোড রিভিউ",
                    onClick = {
                        onDismiss()
                        onFillPromptOnly("Superpower: TDD এবং সিস্টেমেটিক ডিবাগিং দিয়ে কোড টেস্ট ও আর্কিটেকচার তৈরি করো")
                    }
                )

                Text("⚡ পাওয়ার টুলস ও সিকিউরিটি (Security & Utility Hub):", color = NeonCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)

                AttachmentHubRow(
                    icon = Icons.Default.Code,
                    tint = NeonGreen,
                    bgColor = Color(0xFF131B2E),
                    title = "Cyber Security Defensive Lab 💻",
                    subtitle = "Ping, Traceroute, Whois, Hash, CVE ও OWASP অডিট",
                    onClick = {
                        onDismiss()
                        onDispatchPromptImmediately("Open Cyber Terminal")
                    }
                )
                AttachmentHubRow(
                    icon = Icons.Default.Edit,
                    tint = NeonGold,
                    bgColor = Color(0xFF131B2E),
                    title = "Create New PDF Document 📄",
                    subtitle = "ফরম্যাটেড প্রফেশনাল PDF তৈরি ও ডিভাইসে সেভ",
                    onClick = {
                        onDismiss()
                        onFillPromptOnly("Create a professional PDF document about Artificial Intelligence")
                    }
                )
                AttachmentHubRow(
                    icon = Icons.Default.Image,
                    tint = NeonPurple,
                    bgColor = Color(0xFF131B2E),
                    title = "AI Image Creator (8K Masterpiece) 🎨",
                    subtitle = "DALL-E 3 + Gemini + Flux.1 আল্ট্রা-রিয়েল ভিজ্যুয়াল",
                    onClick = {
                        onDismiss()
                        onFillPromptOnly("একটি সুন্দর রঙিন ড্রাগনের 8K ছবি আঁকো")
                    }
                )
                AttachmentHubRow(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    tint = NeonCyan,
                    bgColor = Color(0xFF131B2E),
                    title = "AI Music & Song Creator 🎵",
                    subtitle = "নতুন সুর, লিরিক্স ও অডিও প্লেয়ার সিন্থেসিস",
                    onClick = {
                        onDismiss()
                        onFillPromptOnly("একটি রোমান্টিক মিষ্টি প্রেমের গান বানাও")
                    }
                )
                AttachmentHubRow(
                    icon = Icons.Default.Code,
                    tint = NeonGold,
                    bgColor = Color(0xFF131B2E),
                    title = "Build-Your-Own-X Master Engine ⚙️",
                    subtitle = "Git, Docker, Redis, SQLite, Transformer স্ক্র্যাচ থেকে কোড",
                    onClick = {
                        onDismiss()
                        onFillPromptOnly("Build your own Redis server from scratch with complete architecture and code")
                    }
                )
                AttachmentHubRow(
                    icon = Icons.Default.Code,
                    tint = NeonGreen,
                    bgColor = Color(0xFF131B2E),
                    title = "Install GitHub Plugin / Skill / MCP 🔌",
                    subtitle = "যেকোনো GitHub repo URL দিয়ে ChatGPT/Claude-এর মতো প্লাগইন অ্যাড করুন",
                    onClick = {
                        onDismiss()
                        onOpenControlCenter()
                    }
                )
                AttachmentHubRow(
                    icon = Icons.Default.AutoAwesome,
                    tint = NeonCyan,
                    bgColor = Color(0xFF131B2E),
                    title = "Schedule Routine / Autonomous Call ⏰",
                    subtitle = "নির্দিষ্ট সময়ে কল, মেসেজ বা উইশ করার শিডিউলার",
                    onClick = {
                        onDismiss()
                        onFillPromptOnly("কাল সকাল ১০টায় বাবাকে কল করে বলো ওষুধ খেয়ে নিতে")
                    }
                )
                AttachmentHubRow(
                    icon = Icons.Default.LocationOn,
                    tint = NeonRose,
                    bgColor = Color(0xFF131B2E),
                    title = "Share Current GPS Location 📍",
                    subtitle = "রিয়েল-টাইম অক্ষাংশ, দ্রাঘিমাংশ ও মানচিত্র লিঙ্ক",
                    onClick = {
                        onDismiss()
                        onShareGpsLocation()
                    }
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
