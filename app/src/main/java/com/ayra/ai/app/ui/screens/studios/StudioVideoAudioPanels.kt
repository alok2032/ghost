package com.ayra.ai.app.ui.screens.studios

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ai.higgsfield.OpenHiggsfieldEngine
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.ai.studios.ComfyUiClient
import com.ayra.ai.app.ai.studios.ComfyUiResult
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.ui.components.SongPlayerCard
import com.ayra.ai.app.ui.components.VideoPlayerCard
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

@Composable
fun VideoStoryboardPanel(context: Context) {
    val scope = rememberCoroutineScope()
    val comfyClient = remember { ComfyUiClient() }
    var prompt by remember { mutableStateOf("") }
    var negativePrompt by remember { mutableStateOf("") }
    var aspectRatio by remember { mutableStateOf("16:9") }
    var preferComfyWanVideo by remember { mutableStateOf(true) }
    var videoPayloadJson by remember { mutableStateOf<String?>(null) }
    var statusNote by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurfaceDark)
            .border(1.dp, NeonPurple.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Cinematic Video & Motion Storyboard",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NeonPurple
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Scene, camera trajectory & lighting") },
            modifier = Modifier.fillMaxWidth().testTag("studio_video_prompt_input"),
            maxLines = 3
        )

        OutlinedTextField(
            value = negativePrompt,
            onValueChange = { negativePrompt = it },
            label = { Text("Negative prompt (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("16:9", "9:16").forEach { ratio ->
                FilterChip(
                    selected = aspectRatio == ratio,
                    onClick = { aspectRatio = ratio },
                    label = { Text(if (ratio == "16:9") "16:9 Cinema" else "9:16 Portrait") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
            FilterChip(
                selected = preferComfyWanVideo,
                onClick = { preferComfyWanVideo = !preferComfyWanVideo },
                label = { Text(if (preferComfyWanVideo) "ComfyUI Wan 2.1 + Fallback" else "Storyboard Only", fontSize = 11.sp) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }

        Button(
            onClick = {
                val clean = prompt.trim()
                if (clean.isBlank() || isBusy) return@Button
                isBusy = true
                statusNote = null
                scope.launch {
                    val comfyUrl = withContext(Dispatchers.IO) { AyraPreferences.getComfyUiUrl(context) }
                    if (preferComfyWanVideo && comfyUrl.isNotBlank()) {
                        val (w, h) = if (aspectRatio == "9:16") 480 to 832 else 832 to 480
                        val outDir = File(context.filesDir, "generated_images")
                        when (
                            val res = comfyClient.generateVideo(
                                baseUrl = comfyUrl,
                                prompt = clean,
                                outputDir = outDir,
                                width = w,
                                height = h,
                                negativePrompt = negativePrompt.ifBlank { "static, blurry, distorted, watermark, low fps" }
                            )
                        ) {
                            is ComfyUiResult.VideoSuccess -> {
                                val realDuration = withContext(Dispatchers.IO) {
                                    readStudioVideoDurationText(res.file)
                                } ?: "MP4"
                                videoPayloadJson = JSONObject().apply {
                                    put("title", clean.take(44))
                                    put("prompt", clean)
                                    put("aspectRatio", aspectRatio)
                                    put("localVideoPath", res.file.absolutePath)
                                    put("duration", realDuration)
                                    put("isStoryboardOnly", false)
                                    put("cameraMotion", "Wan 2.1 ComfyUI MP4")
                                }.toString()
                                statusNote = "Rendered MP4 via ComfyUI Wan 2.1 (${res.elapsedMs}ms)"
                                isBusy = false
                                return@launch
                            }
                            is ComfyUiResult.Failure -> {
                                statusNote = "ComfyUI video unavailable (${res.reason}); showing keyframe storyboard."
                            }
                            else -> {}
                        }
                    } else if (preferComfyWanVideo) {
                        statusNote = "Configure ComfyUI server URL in Tools for live Wan 2.1 MP4 rendering; showing keyframe storyboard."
                    }

                    val payload = withContext(Dispatchers.IO) {
                        OpenHiggsfieldEngine(context).synthesizeVideoCraftWithAi(clean, aspectRatio).jsonPayload
                    }
                    videoPayloadJson = payload
                    isBusy = false
                }
            },
            enabled = prompt.isNotBlank() && !isBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("studio_generate_video_button")
        ) {
            Text(if (isBusy) "Rendering Video..." else "Generate Video / Storyboard")
        }

        statusNote?.let { note ->
            Text(text = note, color = TextSecondary, fontSize = 11.sp)
        }

        videoPayloadJson?.let { json ->
            VideoPlayerCard(jsonPayload = json)
        }
    }
}

@Composable
fun MusicStudioPanel(context: Context) {
    val scope = rememberCoroutineScope()
    val pool = remember { ProviderPool(context) }

    var prompt by remember { mutableStateOf("") }
    var bpm by remember { mutableIntStateOf(112) }
    var songJson by remember { mutableStateOf<String?>(null) }
    var errorNote by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurfaceDark)
            .border(1.dp, NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Song Lyrics & WAV Synth Studio",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NeonCyan
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Theme, mood, genre or language") },
            modifier = Modifier.fillMaxWidth().testTag("studio_song_prompt_input"),
            maxLines = 3
        )

        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(90, 112, 128, 145).forEach { targetBpm ->
                FilterChip(
                    selected = bpm == targetBpm,
                    onClick = { bpm = targetBpm },
                    label = { Text("$targetBpm BPM") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }

        Button(
            onClick = {
                val clean = prompt.trim()
                if (clean.isBlank() || isBusy) return@Button
                isBusy = true
                errorNote = null
                scope.launch {
                    val (composed, failureMessage) = withContext(Dispatchers.IO) {
                        val req = ChatRequest(
                            prompt = "Write original song lyrics and a 4-chord progression for: $clean. Start with `KEY: ...`, `GENRE: ...`, and `CHORDS: ...` lines followed by sections [Verse], [Chorus].",
                            persona = "AI_ASSISTANT",
                            mode = ProviderPoolMode.FAST,
                            systemPromptOverride = "You are a songwriter and music producer. Include `KEY: ...`, `GENRE: ...`, and `CHORDS: ...` lines followed by complete lyrics."
                        )
                        val result = runCatching { pool.executeSync(req) }
                        val res = result.getOrNull()?.text?.trim().orEmpty()
                        if (res.isBlank()) {
                            val err = result.exceptionOrNull()?.message
                                ?: "Configure an AI provider or disable Local-Only mode to compose original song lyrics."
                            null to "Song composition unavailable: $err"
                        } else {
                            val keyLine = Regex("(?im)^KEY:\\s*(.+)$").find(res)?.groupValues?.get(1)?.trim() ?: "Custom Scale"
                            val genreLine = Regex("(?im)^GENRE:\\s*(.+)$").find(res)?.groupValues?.get(1)?.trim() ?: "Synth Studio Track"
                            val chordLine = Regex("(?im)^CHORDS:\\s*(.+)$").find(res)?.groupValues?.get(1)?.trim() ?: "Am - F - C - G"
                            val cleanedLyrics = res
                                .replace(Regex("(?im)^(?:KEY|GENRE|CHORDS):\\s*.+$\\r?\\n?"), "")
                                .trim()
                                .ifBlank { res }
                            JSONObject().apply {
                                put("title", clean.take(40).replaceFirstChar { it.uppercase() })
                                put("genre", genreLine)
                                put("tempo", "$bpm BPM")
                                put("key", keyLine)
                                put("chords", chordLine)
                                put("lyrics", cleanedLyrics)
                            }.toString() to null
                        }
                    }
                    if (composed != null) {
                        songJson = composed
                    } else {
                        errorNote = failureMessage
                    }
                    isBusy = false
                }
            },
            enabled = prompt.isNotBlank() && !isBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("studio_compose_song_button")
        ) {
            Text(if (isBusy) "Composing..." else "Compose Lyrics & Synth Preview")
        }

        errorNote?.let { note ->
            Text(text = note, color = TextSecondary, fontSize = 11.sp)
        }

        songJson?.let { payload ->
            SongPlayerCard(jsonPayload = payload)
        }
    }
}

private fun readStudioVideoDurationText(file: File): String? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
        val totalSecs = (ms / 1000L).coerceAtLeast(1L)
        val mins = totalSecs / 60L
        val secs = totalSecs % 60L
        String.format(Locale.US, "%d:%02d", mins, secs)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}
