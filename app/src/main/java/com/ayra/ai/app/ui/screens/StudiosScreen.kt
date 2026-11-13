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
import androidx.compose.runtime.getValue
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
            .background(CosmicBackground)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Ayra Creative & Code Studios",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = "Image synthesis/editing, motion storyboards, real WAV composition, WebGL2 PBR 3D, and multi-file ZIP code generation.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
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
            StudioMode.THREE_D -> ThreeDStudioPanel()
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
                            when (val fbRes = firebaseAi.generateOrEditImage(clean, refBase64)) {
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

                        // 2. Try self-hosted ComfyUI if user configured URL
                        val comfyUrl = withContext(Dispatchers.IO) { AyraPreferences.getComfyUiUrl(context) }
                        if (comfyUrl.isNotBlank() && refBase64 == null) {
                            val outDir = File(context.filesDir, "generated_images")
                            when (val comfyRes = comfyClient.generateImage(comfyUrl, clean, outDir)) {
                                is ComfyUiResult.ImageSuccess -> {
                                    generatedFile = comfyRes.file
                                    statusLabel = "Generated via ComfyUI (${comfyRes.elapsedMs}ms)"
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
    var prompt by remember { mutableStateOf("") }
    var aspectRatio by remember { mutableStateOf("16:9") }
    var videoPayloadJson by remember { mutableStateOf<String?>(null) }
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
            text = "Cinematic Motion Storyboard Studio",
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("16:9", "9:16").forEach { ratio ->
                FilterChip(
                    selected = aspectRatio == ratio,
                    onClick = { aspectRatio = ratio },
                    label = { Text(if (ratio == "16:9") "16:9 Cinema" else "9:16 Portrait") }
                )
            }
        }

        Button(
            onClick = {
                val clean = prompt.trim()
                if (clean.isBlank() || isBusy) return@Button
                isBusy = true
                scope.launch {
                    val payload = withContext(Dispatchers.IO) {
                        OpenHiggsfieldEngine(context).synthesizeVideoCraft(clean, aspectRatio).jsonPayload
                    }
                    videoPayloadJson = payload
                    isBusy = false
                }
            },
            enabled = prompt.isNotBlank() && !isBusy,
            modifier = Modifier.fillMaxWidth().testTag("studio_generate_video_button")
        ) {
            Text(if (isBusy) "Building Storyboard..." else "Generate Motion Storyboard")
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
private fun ThreeDStudioPanel() {
    ThreeDModelCard(
        title = "Ayra WebGL2 PBR 3D Studio"
    )
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
    var zipFile by remember { mutableStateOf<File?>(null) }
    var errorNote by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurfaceDark)
            .border(1.dp, NeonGreen.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Multi-File Code & ZIP Project Builder",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NeonGreen
        )

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
                    val (_, archive) = withContext(Dispatchers.IO) {
                        ProjectWorkspaceManager.writeProjectToDiskAndZip(
                            context = context,
                            projectName = projectName.ifBlank { "ayra_project" },
                            files = parsed
                        )
                    }
                    generatedFiles = parsed
                    selectedFileIndex = 0
                    zipFile = archive
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF050811))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = currentFile.content,
                        color = Color(0xFFE2E8F0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
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
