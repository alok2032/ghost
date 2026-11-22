package com.ayra.ai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.cloud.GoogleAuthManager
import com.ayra.ai.app.cloud.GoogleSignInResult
import com.ayra.ai.app.ui.components.AmbientCornerGlow
import com.ayra.ai.app.ui.components.SolarSystem3DCanvas
import com.ayra.ai.app.ui.theme.CosmicBackground
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGreen
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    authManager: GoogleAuthManager,
    onSignedIn: (FirebaseUser) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSigningIn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val returningUser = authManager.trySilentReturnSignIn(context)
        if (returningUser != null) {
            onSignedIn(returningUser)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CosmicBackground)
    ) {
        SolarSystem3DCanvas(modifier = Modifier.fillMaxSize().graphicsLayer { })
        AmbientCornerGlow(modifier = Modifier.fillMaxSize().graphicsLayer { })

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .border(
                        width = 1.4.dp,
                        brush = Brush.linearGradient(listOf(NeonCyan, NeonPurple, NeonRose)),
                        shape = RoundedCornerShape(24.dp)
                    ),
                shape = RoundedCornerShape(24.dp),
                color = GlassSurfaceDark.copy(alpha = 0.92f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(NeonCyan, NeonPurple, Color.Black))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Ayra AI Emblem",
                            tint = Color.Black,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Ayra AI",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0F172A).copy(alpha = 0.85f))
                                .border(1.dp, NeonCyan.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "By Alok",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = "Unified Frontier • Enterprise AI Platform",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NeonCyan,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "একটি সুপার-এআই প্ল্যাটফর্মে Gemini, NVIDIA NIM, Nano Banana ইমেজ জেনারেশন, ভয়েস-টু-টেক্সট ও GitHub প্লাগইন ক্লাউড সিঙ্ক ব্যবহার করতে সাইন ইন করুন।",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF0B1224))
                            .border(1.dp, NeonCyan.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FeatureRow(
                            icon = Icons.Default.Extension,
                            tint = NeonCyan,
                            text = "GitHub Repo Plugins, Skills & OpenAPI সিঙ্ক"
                        )
                        FeatureRow(
                            icon = Icons.Default.CloudDone,
                            tint = NeonGreen,
                            text = "সকল চ্যাট, কোড ও এআই ক্রিয়েশন নিরাপদ ক্লাউড ব্যাকআপ"
                        )
                        FeatureRow(
                            icon = Icons.Default.Security,
                            tint = NeonPurple,
                            text = "Firebase App Check ও Google Credential Manager সুরক্ষা"
                        )
                    }

                    if (!errorMessage.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(NeonRose.copy(alpha = 0.14f))
                                .border(1.dp, NeonRose.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = errorMessage.orEmpty(),
                                color = NeonRose,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (!isSigningIn) {
                                isSigningIn = true
                                errorMessage = null
                                scope.launch {
                                    when (val res = authManager.signInWithGoogle(context)) {
                                        is GoogleSignInResult.Success -> {
                                            isSigningIn = false
                                            onSignedIn(res.user)
                                        }
                                        is GoogleSignInResult.Cancelled -> {
                                            isSigningIn = false
                                        }
                                        is GoogleSignInResult.Error -> {
                                            isSigningIn = false
                                            errorMessage = res.message
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isSigningIn,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("google_sign_in_button")
                            .semantics { contentDescription = "Sign in with Google" }
                    ) {
                        if (isSigningIn) {
                            CircularProgressIndicator(
                                color = Color.Black,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Text(
                                text = "Connecting Google Account...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Login,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Text(
                                text = "Continue with Google • গুগল দিয়ে প্রবেশ করুন",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 12.5.sp,
            lineHeight = 16.sp
        )
    }
}
