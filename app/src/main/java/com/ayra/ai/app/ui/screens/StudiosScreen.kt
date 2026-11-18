package com.ayra.ai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ui.screens.studios.CodeBuilderPanel
import com.ayra.ai.app.ui.screens.studios.ImageStudioPanel
import com.ayra.ai.app.ui.screens.studios.MusicStudioPanel
import com.ayra.ai.app.ui.screens.studios.ThreeDStudioPanel
import com.ayra.ai.app.ui.screens.studios.VideoStoryboardPanel

private enum class StudioMode(val label: String, val icon: ImageVector) {
    IMAGE("Image", Icons.Default.Image),
    VIDEO("Storyboard", Icons.Default.Videocam),
    MUSIC("Music", Icons.Default.MusicNote),
    THREE_D("3D PBR", Icons.Default.ViewInAr),
    CODE("Code Builder", Icons.Default.Code)
}

@Composable
fun StudiosScreen(
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeMode by remember { mutableStateOf(StudioMode.IMAGE) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp).testTag("studios_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = "Ayra AI Creative & Code Studios",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f, fill = false)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "By Alok",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = "Image synthesis/editing, motion & Wan 2.1 video, real WAV composition, WebGL2 PBR 3D, and multi-file code editor/ZIP.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StudioMode.entries.forEach { mode ->
                FilterChip(
                    selected = activeMode == mode,
                    onClick = { activeMode = mode },
                    leadingIcon = {
                        Icon(mode.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text(mode.label, fontSize = 12.sp) },
                    modifier = Modifier.testTag("studio_tab_${mode.name.lowercase()}")
                )
            }
        }

        when (activeMode) {
            StudioMode.IMAGE -> ImageStudioPanel(context = context)
            StudioMode.VIDEO -> VideoStoryboardPanel(context = context)
            StudioMode.MUSIC -> MusicStudioPanel(context = context)
            StudioMode.THREE_D -> ThreeDStudioPanel(context = context)
            StudioMode.CODE -> CodeBuilderPanel(context = context)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
