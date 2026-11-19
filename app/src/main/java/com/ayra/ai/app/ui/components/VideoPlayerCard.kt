package com.ayra.ai.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGreen
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import org.json.JSONObject

import android.net.Uri
import android.widget.VideoView
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.Locale

@Immutable
private data class ParsedVideoPayload(
    val title: String = "AI Cinematic Video",
    val previewUrl: String = "",
    val localVideoPath: String = "",
    val videoUrl: String = "",
    val keyframeUrls: List<String> = emptyList(),
    val cameraMotion: String = "",
    val lensType: String = "",
    val isPortraitAspect: Boolean = false,
    val durationTotalSeconds: Int = 15
)

@Composable
fun VideoPlayerCard(
    jsonPayload: String,
    onExpand: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val parsed = remember(jsonPayload) {
        try {
            val json = JSONObject(jsonPayload)
            val parsedPreview = json.optString("previewUrl", "")
            val arr = json.optJSONArray("keyframes")
            val parsedFrames = mutableListOf<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i, "")
                    if (u.isNotBlank()) parsedFrames.add(u)
                }
            }
            if (parsedFrames.isEmpty() && parsedPreview.isNotBlank()) {
                parsedFrames.add(parsedPreview)
            }
            val dur = json.optString("duration", "0:15")
            val parsedDurSec = if (dur.contains(":")) {
                val parts = dur.split(":")
                parts[0].toIntOrNull()?.times(60)?.plus(parts.getOrNull(1)?.toIntOrNull() ?: 15) ?: 15
            } else 15
            ParsedVideoPayload(
                title = json.optString("title", "AI Cinematic Video"),
                previewUrl = parsedPreview,
                localVideoPath = json.optString("localVideoPath", ""),
                videoUrl = json.optString("videoUrl", ""),
                keyframeUrls = parsedFrames,
                cameraMotion = json.optString("cameraMotion", ""),
                lensType = json.optString("lensType", ""),
                isPortraitAspect = json.optString("aspectRatio", "16:9") == "9:16",
                durationTotalSeconds = parsedDurSec
            )
        } catch (_: Exception) {
            ParsedVideoPayload()
        }
    }

    val title = parsed.title
    val previewUrl = parsed.previewUrl
    val localVideoPath = parsed.localVideoPath
    val videoUrl = parsed.videoUrl
    val keyframeUrls = parsed.keyframeUrls
    val cameraMotion = parsed.cameraMotion
    val lensType = parsed.lensType
    var isPortraitAspect by remember(parsed) { mutableStateOf(parsed.isPortraitAspect) }
    var durationTotalSeconds by remember(parsed) { mutableIntStateOf(parsed.durationTotalSeconds) }
    var retryTick by remember(parsed) { mutableIntStateOf(0) }

    val playableVideoUri = remember(localVideoPath, videoUrl) {
        val localFile = localVideoPath.takeIf { it.isNotBlank() }?.let { File(it) }
        when {
            localFile != null && localFile.exists() -> Uri.fromFile(localFile)
            videoUrl.startsWith("https://") || videoUrl.startsWith("http://") || videoUrl.startsWith("file://") -> Uri.parse(videoUrl)
            else -> null
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var currentSeconds by remember { mutableIntStateOf(0) }
    val activeFrameUrl = remember(keyframeUrls, previewUrl, currentSeconds) {
        if (keyframeUrls.isNotEmpty()) {
            val idx = (currentSeconds / 5) % keyframeUrls.size
            keyframeUrls[idx]
        } else previewUrl
    }

    // Live running timecode counter when playing
    LaunchedEffect(isPlaying, durationTotalSeconds) {
        while (isPlaying) {
            delay(1000L)
            currentSeconds = (currentSeconds + 1) % (durationTotalSeconds + 1)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "video_motion")
    // Dynamic Ken Burns zoom effect when playing
    val motionScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "motion_scale"
    )

    // Animated 60fps laser scanline
    val scanOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(GlassSurfaceDark)
            .border(
                1.3.dp,
                Brush.linearGradient(listOf(NeonCyan, NeonPurple, NeonRose)),
                RoundedCornerShape(18.dp)
            )
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(NeonPurple.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 13.5.sp
                    )
                    Text(
                        text = if (playableVideoUri != null) "Real Video Stream Playback" else "AI Motion Storyboard Preview",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonCyan,
                        fontSize = 10.5.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF131B2E))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (isPortraitAspect) "Storyboard 9:16" else "Storyboard 16:9",
                        color = NeonGreen,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Aspect Ratio & Camera HUD
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = listOfNotNull(
                        cameraMotion.takeIf { it.isNotBlank() },
                        lensType.takeIf { it.isNotBlank() }
                    ).joinToString(" • ").ifBlank { "AI Motion Storyboard Preview" },
                    color = TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1E293B))
                        .border(0.8.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .clickable { isPortraitAspect = !isPortraitAspect }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isPortraitAspect) "9:16 Reels" else "16:9 Cinema",
                        color = NeonCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Video Screen Viewport
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isPortraitAspect) 300.dp else 210.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
                    .border(1.dp, Color(0x3300FFFF), RoundedCornerShape(14.dp))
                    .clickable { isPlaying = !isPlaying }
            ) {
                if (playableVideoUri != null) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(playableVideoUri)
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    val sec = (mp.duration / 1000).coerceAtLeast(1)
                                    durationTotalSeconds = sec
                                    if (isPlaying) start()
                                }
                            }
                        },
                        update = { view ->
                            if (view.tag != playableVideoUri.toString()) {
                                view.tag = playableVideoUri.toString()
                                view.setVideoURI(playableVideoUri)
                            }
                            if (isPlaying && !view.isPlaying) {
                                view.start()
                            } else if (!isPlaying && view.isPlaying) {
                                view.pause()
                            }
                        },
                        modifier = Modifier.fillMaxSize().testTag("real_video_player_view")
                    )
                } else if (activeFrameUrl.isNotBlank()) {
                    val activeModelUrl = if (retryTick == 0) activeFrameUrl else {
                        val sep = if (activeFrameUrl.contains("?")) "&" else "?"
                        "${activeFrameUrl}${sep}retry=$retryTick"
                    }
                    SubcomposeAsyncImage(
                        model = activeModelUrl,
                        contentDescription = "Video Keyframe",
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(if (isPlaying) motionScale else 1.0f),
                        contentScale = ContentScale.Crop,
                        loading = {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = NeonCyan,
                                    strokeWidth = 2.5.dp
                                )
                            }
                        },
                        error = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.radialGradient(
                                            listOf(Color(0xFF1E1B4B), Color(0xFF030712))
                                        )
                                    )
                                    .clickable { retryTick++ },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Videocam,
                                        contentDescription = null,
                                        tint = NeonCyan,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Preview image unavailable • Tap to retry",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    )
                }

                // Laser scanline animation when playing
                if (isPlaying) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val y = size.height * scanOffset
                        drawLine(
                            brush = Brush.horizontalGradient(
                                listOf(Color.Transparent, NeonCyan, NeonPurple, Color.Transparent)
                            ),
                            start = androidx.compose.ui.geometry.Offset(0f, y),
                            end = androidx.compose.ui.geometry.Offset(size.width, y),
                            strokeWidth = 2.5f
                        )
                    }
                }

                // Centered Play Overlay Icon when paused
                if (!isPlaying) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .border(1.5.dp, NeonCyan, CircleShape)
                            .align(Alignment.Center),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = NeonCyan,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                // Top badges
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isPlaying) NeonRose.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isPlaying) "▶ PREVIEW" else "PAUSED",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (onExpand != null && previewUrl.isNotBlank()) {
                        IconButton(
                            onClick = { onExpand(previewUrl) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .testTag("video_fullscreen_button")
                        ) {
                            Icon(
                                Icons.Default.Fullscreen,
                                contentDescription = "Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Timeline Scrub & Controls Bar
            val progress = (currentSeconds.toFloat() / durationTotalSeconds.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = NeonCyan,
                trackColor = Color(0xFF1E283F)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { isPlaying = !isPlaying },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF131B2E))
                            .testTag("video_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Toggle Play",
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    val curStr = String.format(Locale.US, "0:%02d", currentSeconds)
                    val totStr = String.format(Locale.US, "0:%02d", durationTotalSeconds)
                    Text(
                        text = "$curStr / $totStr",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Text(
                    text = "Tap video to play/pause",
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }
        }
    }
}
