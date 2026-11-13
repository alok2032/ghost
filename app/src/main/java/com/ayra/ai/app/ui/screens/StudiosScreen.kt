package com.ayra.ai.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import com.ayra.ai.app.ai.code.SavedCodeProject
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.SubcomposeAsyncImage
import com.ayra.ai.app.ai.AiMediaPromptSynthesizer
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.FirebaseImageResult
import com.ayra.ai.app.ai.Model3dMeshCatalog
import com.ayra.ai.app.ai.code.GeneratedProjectFile
import com.ayra.ai.app.ai.code.ProjectWorkspaceManager
import com.ayra.ai.app.ai.higgsfield.OpenHiggsfieldEngine
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.ai.studios.ComfyUiClient
import com.ayra.ai.app.ai.studios.ComfyUiResult
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.ui.components.SongPlayerCard
import com.ayra.ai.app.ui.components.ThreeDModelCard
import com.ayra.ai.app.ui.components.VideoPlayerCard
import com.ayra.ai.app.ui.theme.CosmicBackground
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGreen
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private enum class StudioMode(val label: String, val icon: ImageVector) {
    IMAGE("Image", Icons.Default.Image),
    VIDEO("Storyboard", Icons.Default.Videocam),
    MUSIC("Music", Icons.Default.MusicNote),
    THREE_D("3D PBR", Icons.Default.ViewInAr),
    CODE("Code Builder", Icons.Default.Code)
}

@Composable
fun StudiosScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
        Text(
            text = "Ayra Creative & Code Studios",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
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

@Composable
private fun ImageStudioPanel(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    val firebaseAi = remember { FirebaseAiEngine(context) }
    val comfyClient = remember { ComfyUiClient() }

    var prompt by remember { mutableStateOf("") }
    var negativePrompt by remember { mutableStateOf("") }
    var imageAspect by remember { mutableStateOf("1:1") }
    var pickedPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var generatedFile by remember { mutableStateOf<File?>(null) }
    var generatedUrl by remember { mutableStateOf<String?>(null) }
    var statusLabel by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> pickedPhotoUri = uri }

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
            text = "Image Creation & Editing",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NeonCyan
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Describe the image or edit instruction") },
            modifier = Modifier.fillMaxWidth().testTag("studio_image_prompt_input"),
            maxLines = 4
        )

        OutlinedTextField(
            value = negativePrompt,
            onValueChange = { negativePrompt = it },
            label = { Text("Negative prompt (optional, e.g. blurry, watermark)") },
            modifier = Modifier.fillMaxWidth().testTag("studio_image_negative_input"),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1:1", "16:9", "9:16").forEach { ratio ->
                FilterChip(
                    selected = imageAspect == ratio,
                    onClick = { imageAspect = ratio },
                    label = { Text(ratio, fontSize = 11.5.sp) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.weight(1f).testTag("studio_pick_photo_button")
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (pickedPhotoUri != null) "Photo Attached" else "Pick Photo to Edit", fontSize = 12.sp)
            }

            Button(
                onClick = {
                    val clean = prompt.trim()
                    if (clean.isBlank() || isBusy) return@Button
                    isBusy = true
                    statusLabel = "Generating image..."
                    generatedFile = null
                    generatedUrl = null

                    scope.launch {
                        val refBase64 = pickedPhotoUri?.let { uri ->
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                        ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                                }.getOrNull()
                            }
                        }

                        // 1. Try Firebase AI Logic Nano Banana 2.1
                        if (FirebaseAiEngine.isAvailable(context)) {
                            val fbPrompt = if (negativePrompt.isNotBlank()) {
                                "$clean (Avoid: ${negativePrompt.trim()})"
                            } else clean
                            when (val fbRes = firebaseAi.generateOrEditImage(fbPrompt, refBase64)) {
                                is FirebaseImageResult.Success -> {
                                    generatedFile = fbRes.file
                                    statusLabel = "Generated via ${fbRes.modelUsed}"
                                    isBusy = false
                                    return@launch
                                }
                                is FirebaseImageResult.Failure -> {
                                    statusLabel = "Firebase fallback: ${fbRes.userMessage}"
                                }
                            }
                        }

                        // 2. Try self-hosted ComfyUI (T2I or Img2Img) if user configured URL
                        val comfyUrl = withContext(Dispatchers.IO) { AyraPreferences.getComfyUiUrl(context) }
                        if (comfyUrl.isNotBlank()) {
                            val outDir = File(context.filesDir, "generated_images")
                            val neg = negativePrompt.ifBlank { "blurry, low quality, distorted, watermark" }
                            val comfyRes = if (refBase64 != null) {
                                val refBytes = runCatching { Base64.decode(refBase64, Base64.DEFAULT) }.getOrNull()
                                if (refBytes != null && refBytes.isNotEmpty()) {
                                    comfyClient.generateImageToImage(
                                        baseUrl = comfyUrl,
                                        prompt = clean,
                                        referenceImageBytes = refBytes,
                                        outputDir = outDir,
                                        negativePrompt = neg
                                    )
                                } else ComfyUiResult.Failure("Invalid reference image bytes.")
                            } else {
                                val (w, h) = when (imageAspect) {
                                    "16:9" -> 1024 to 576
                                    "9:16" -> 576 to 1024
                                    else -> 768 to 768
                                }
                                comfyClient.generateImage(
                                    baseUrl = comfyUrl,
                                    prompt = clean,
                                    outputDir = outDir,
                                    width = w,
                                    height = h,
                                    negativePrompt = neg
                                )
                            }
                            when (comfyRes) {
                                is ComfyUiResult.ImageSuccess -> {
                                    generatedFile = comfyRes.file
                                    statusLabel = if (refBase64 != null) {
                                        "Edited via ComfyUI Img2Img (${comfyRes.elapsedMs}ms)"
                                    } else {
                                        "Generated via ComfyUI (${comfyRes.elapsedMs}ms)"
                                    }
                                    isBusy = false
                                    return@launch
                                }
                                is ComfyUiResult.Failure -> {
                                    statusLabel = "ComfyUI skipped: ${comfyRes.reason}"
                                }
                                else -> {}
                            }
                        }

                        // 3. Fallback to Pollinations Flux URL
                        val url = withContext(Dispatchers.IO) {
                            AiMediaPromptSynthesizer.buildImageUrl(clean, context = context)
                        }
                        generatedUrl = url
                        statusLabel = "Generated via Pollinations Flux (Community Tier)"
                        isBusy = false
                    }
                },
                enabled = prompt.isNotBlank() && !isBusy,
                modifier = Modifier.weight(1f).testTag("studio_generate_image_button")
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (isBusy) "Working..." else "Generate", fontSize = 12.sp)
            }
        }

        statusLabel?.let {
            Text(text = it, color = TextSecondary, fontSize = 11.sp)
        }

        val displayModel: Any? = generatedFile ?: generatedUrl
        if (displayModel != null) {
            SubcomposeAsyncImage(
                model = displayModel,
                contentDescription = "Generated Studio Image",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = NeonCyan)
                    }
                }
            )

            generatedFile?.let { file ->
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val uri = FileProvider.getUriForFile(context, "com.ayra.ai.app.fileprovider", file)
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Share Generated Image"))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share Saved PNG")
                }
            }
        }
    }
}

@Composable
private fun VideoStoryboardPanel(context: android.content.Context) {
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("16:9", "9:16").forEach { ratio ->
                FilterChip(
                    selected = aspectRatio == ratio,
                    onClick = { aspectRatio = ratio },
                    label = { Text(if (ratio == "16:9") "16:9 Cinema" else "9:16 Portrait") }
                )
            }
            FilterChip(
                selected = preferComfyWanVideo,
                onClick = { preferComfyWanVideo = !preferComfyWanVideo },
                label = { Text(if (preferComfyWanVideo) "ComfyUI Wan 2.1 + Fallback" else "Storyboard Only", fontSize = 11.sp) }
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
                                videoPayloadJson = JSONObject().apply {
                                    put("title", clean.take(44))
                                    put("prompt", clean)
                                    put("aspectRatio", aspectRatio)
                                    put("localVideoPath", res.file.absolutePath)
                                    put("duration", "0:04")
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
            modifier = Modifier.fillMaxWidth().testTag("studio_generate_video_button")
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
private fun MusicStudioPanel(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    val pool = remember { ProviderPool(context) }

    var prompt by remember { mutableStateOf("") }
    var bpm by remember { mutableIntStateOf(112) }
    var songJson by remember { mutableStateOf<String?>(null) }
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(90, 112, 128, 145).forEach { targetBpm ->
                FilterChip(
                    selected = bpm == targetBpm,
                    onClick = { bpm = targetBpm },
                    label = { Text("$targetBpm BPM") }
                )
            }
        }

        Button(
            onClick = {
                val clean = prompt.trim()
                if (clean.isBlank() || isBusy) return@Button
                isBusy = true
                scope.launch {
                    val composed = withContext(Dispatchers.IO) {
                        val req = ChatRequest(
                            prompt = "Write original song lyrics and a 4-chord progression (e.g. Am - F - C - G) for: $clean. Return sections [Verse], [Chorus], and first line `CHORDS: ...`.",
                            persona = "AI_ASSISTANT",
                            mode = ProviderPoolMode.FAST,
                            systemPromptOverride = "You are a songwriter and music producer. Include `CHORDS: Am - F - C - G` on the first line followed by complete lyrics."
                        )
                        val res = runCatching { pool.executeSync(req).text }.getOrDefault("")
                        val chordLine = Regex("CHORDS:\\s*(.+)").find(res)?.groupValues?.get(1)?.trim() ?: "Am - F - C - G"
                        val lyricsBody = res.ifBlank {
                            "[Verse 1]\nPrompt: $clean\n(AI lyrics service offline — instrumental chord synthesizer active)"
                        }
                        JSONObject().apply {
                            put("title", clean.take(40).replaceFirstChar { it.uppercase() })
                            put("genre", "Synth Studio Track")
                            put("tempo", "$bpm BPM")
                            put("key", "A Minor")
                            put("chords", chordLine)
                            put("lyrics", lyricsBody)
                        }.toString()
                    }
                    songJson = composed
                    isBusy = false
                }
            },
            enabled = prompt.isNotBlank() && !isBusy,
            modifier = Modifier.fillMaxWidth().testTag("studio_compose_song_button")
        ) {
            Text(if (isBusy) "Composing..." else "Compose Lyrics & Synth Preview")
        }

        songJson?.let { payload ->
            SongPlayerCard(jsonPayload = payload)
        }
    }
}

@Composable
private fun ThreeDStudioPanel(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    val pool = remember { ProviderPool(context) }
    var prompt by remember { mutableStateOf("") }
    var customPayload by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var statusNote by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                text = "AI 3D Geometry Architect & WebGL2 PBR",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NeonCyan
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Describe any 3D object (e.g. hexagonal drone, sci-fi tower)") },
                modifier = Modifier.fillMaxWidth().testTag("studio_3d_prompt_input"),
                singleLine = true
            )
            Button(
                onClick = {
                    val clean = prompt.trim()
                    if (clean.isBlank() || isBusy) return@Button
                    isBusy = true
                    statusNote = "Synthesizing 3D Wavefront .OBJ geometry..."
                    scope.launch {
                        val resultPayload = withContext(Dispatchers.IO) {
                            val title = "3D ${clean.take(40).replaceFirstChar { it.uppercase() }}"
                            val rawObj = runCatching {
                                pool.executeSync(
                                    ChatRequest(
                                        prompt = """
                                            Generate a clean, low-poly 3D geometric mesh in Wavefront .OBJ format representing: "$clean".
                                            Output ONLY valid `v x y z` vertex lines (12-36 vertices in -1.5..1.5) followed by `f i1 i2 i3` (or quad `f i1 i2 i3 i4`) face lines (at least 6 faces). No markdown or explanations.
                                        """.trimIndent(),
                                        mode = ProviderPoolMode.FAST,
                                        systemPromptOverride = "You are a 3D CAD mesh generator outputting strictly raw Wavefront .OBJ lines."
                                    )
                                ).text
                            }.getOrDefault("")
                            val parsed = Model3dMeshCatalog.parseWavefrontObj(rawObj, "CUSTOM_AI")
                            if (parsed != null) {
                                statusNote = "AI synthesized custom mesh (${parsed.vertexCount} vertices, ${parsed.faceCount} faces)"
                                JSONObject().apply {
                                    put("title", title)
                                    put("kind", "CUSTOM_AI")
                                    put("vertices", parsed.vertexCount)
                                    put("edges", parsed.edgeCount)
                                    put("faces", parsed.faceCount)
                                    put("objContent", Model3dMeshCatalog.buildWavefrontObj(parsed, title))
                                }.toString()
                            } else {
                                statusNote = "Showing interactive WebGL2 PBR studio presets below."
                                null
                            }
                        }
                        if (resultPayload != null) {
                            customPayload = resultPayload
                        }
                        isBusy = false
                    }
                },
                enabled = prompt.isNotBlank() && !isBusy,
                modifier = Modifier.fillMaxWidth().testTag("studio_generate_3d_button")
            ) {
                Text(if (isBusy) "Synthesizing Mesh..." else "Generate Custom AI 3D Mesh")
            }
            statusNote?.let {
                Text(text = it, color = TextSecondary, fontSize = 11.sp)
            }
        }

        ThreeDModelCard(
            craftPayload = customPayload,
            title = "Ayra WebGL2 PBR 3D Studio"
        )
    }
}

@Composable
private fun CodeBuilderPanel(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    val pool = remember { ProviderPool(context) }

    var projectName by remember { mutableStateOf("ayra_app_module") }
    var prompt by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var generatedFiles by remember { mutableStateOf<List<GeneratedProjectFile>>(emptyList()) }
    var selectedFileIndex by remember { mutableIntStateOf(0) }
    var editedContent by remember { mutableStateOf("") }
    var activeProjectDir by remember { mutableStateOf<File?>(null) }
    var savedProjects by remember { mutableStateOf<List<SavedCodeProject>>(emptyList()) }
    var zipFile by remember { mutableStateOf<File?>(null) }
    var errorNote by remember { mutableStateOf<String?>(null) }

    fun refreshSavedProjects() {
        savedProjects = ProjectWorkspaceManager.listSavedProjects(context)
    }

    LaunchedEffect(Unit) {
        refreshSavedProjects()
    }

    LaunchedEffect(selectedFileIndex, generatedFiles) {
        editedContent = generatedFiles.getOrNull(selectedFileIndex)?.content.orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurfaceDark)
            .border(1.dp, NeonGreen.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Multi-File Code & ZIP Project Builder",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NeonGreen,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { refreshSavedProjects() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh saved projects", modifier = Modifier.size(16.dp))
            }
        }

        if (savedProjects.isNotEmpty()) {
            Text("Saved Workspace Projects (${savedProjects.size}):", color = TextSecondary, fontSize = 11.sp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                savedProjects.forEach { proj ->
                    FilterChip(
                        selected = activeProjectDir?.absolutePath == proj.rootDir.absolutePath,
                        onClick = {
                            val loaded = ProjectWorkspaceManager.loadProjectFiles(proj.rootDir)
                            activeProjectDir = proj.rootDir
                            projectName = proj.name
                            generatedFiles = loaded
                            selectedFileIndex = 0
                            editedContent = loaded.firstOrNull()?.content.orEmpty()
                            zipFile = ProjectWorkspaceManager.createProjectZip(context, proj.name, proj.rootDir)
                        },
                        label = { Text("${proj.name} (${proj.fileCount})", fontSize = 11.sp) },
                        modifier = Modifier.testTag("saved_project_chip_${proj.name}")
                    )
                }
            }
        }

        OutlinedTextField(
            value = projectName,
            onValueChange = { projectName = it },
            label = { Text("Project Folder Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Describe the multi-file app, API or module to generate") },
            modifier = Modifier.fillMaxWidth().testTag("studio_code_prompt_input"),
            maxLines = 4
        )

        Button(
            onClick = {
                val clean = prompt.trim()
                if (clean.isBlank() || isBusy) return@Button
                isBusy = true
                errorNote = null
                scope.launch {
                    val resText = withContext(Dispatchers.IO) {
                        val sys = "You are a senior software engineer. Output a multi-file project using exact markers `=== FILE: relative/path.ext ===` before each file's code. Write complete, runnable code with zero placeholders."
                        runCatching {
                            pool.executeSync(
                                ChatRequest(
                                    prompt = clean,
                                    persona = "PROFESSIONAL",
                                    mode = ProviderPoolMode.BEST,
                                    systemPromptOverride = sys
                                )
                            ).text
                        }.getOrElse { "" }
                    }

                    if (resText.isBlank()) {
                        errorNote = "No configured AI provider returned code. Check Tools & Keys or internet connection."
                        isBusy = false
                        return@launch
                    }

                    val parsed = ProjectWorkspaceManager.parseProjectFilesFromAiResponse(
                        aiOutput = resText,
                        fallbackProjectName = projectName.ifBlank { "ayra_project" }
                    )
                    val (dir, archive) = withContext(Dispatchers.IO) {
                        ProjectWorkspaceManager.writeProjectToDiskAndZip(
                            context = context,
                            projectName = projectName.ifBlank { "ayra_project" },
                            files = parsed
                        )
                    }
                    activeProjectDir = dir
                    generatedFiles = parsed
                    selectedFileIndex = 0
                    editedContent = parsed.firstOrNull()?.content.orEmpty()
                    zipFile = archive
                    refreshSavedProjects()
                    isBusy = false
                }
            },
            enabled = prompt.isNotBlank() && !isBusy,
            modifier = Modifier.fillMaxWidth().testTag("studio_build_code_button")
        ) {
            Text(if (isBusy) "Generating Project..." else "Generate Multi-File Project & ZIP")
        }

        errorNote?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        if (generatedFiles.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                generatedFiles.forEachIndexed { idx, file ->
                    FilterChip(
                        selected = idx == selectedFileIndex,
                        onClick = { selectedFileIndex = idx },
                        label = { Text(file.relativePath, fontSize = 11.sp) }
                    )
                }
            }

            val currentFile = generatedFiles.getOrNull(selectedFileIndex)
            if (currentFile != null) {
                OutlinedTextField(
                    value = editedContent,
                    onValueChange = { editedContent = it },
                    label = { Text("Edit ${currentFile.relativePath}") },
                    modifier = Modifier.fillMaxWidth().testTag("studio_code_editor_input"),
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp
                    )
                )

                val projDir = activeProjectDir
                if (projDir != null) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val updatedArchive = withContext(Dispatchers.IO) {
                                    val savedOk = ProjectWorkspaceManager.saveEditedProjectFile(
                                        rootDir = projDir,
                                        relativePath = currentFile.relativePath,
                                        content = editedContent
                                    )
                                    if (savedOk) {
                                        ProjectWorkspaceManager.createProjectZip(context, projectName.ifBlank { projDir.name }, projDir)
                                    } else null
                                }
                                if (updatedArchive != null) {
                                    generatedFiles = generatedFiles.mapIndexed { i, f ->
                                        if (i == selectedFileIndex) f.copy(content = editedContent) else f
                                    }
                                    zipFile = updatedArchive
                                    Toast.makeText(context, "Saved & updated ZIP!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("studio_save_rezip_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save Edit & Re-ZIP Project")
                    }
                }
            }

            zipFile?.let { zip ->
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val uri = FileProvider.getUriForFile(context, "com.ayra.ai.app.fileprovider", zip)
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Share Project ZIP"))
                        }.onFailure {
                            Toast.makeText(context, "Saved ZIP: ${zip.absolutePath}", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("studio_share_zip_button")
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export & Share ZIP (${generatedFiles.size} files)")
                }
            }
        }
    }
}
