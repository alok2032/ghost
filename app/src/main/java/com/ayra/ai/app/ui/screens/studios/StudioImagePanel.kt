package com.ayra.ai.app.ui.screens.studios

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.SubcomposeAsyncImage
import com.ayra.ai.app.ai.AiMediaPromptSynthesizer
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.FirebaseImageResult
import com.ayra.ai.app.ai.studios.ComfyUiClient
import com.ayra.ai.app.ai.studios.ComfyUiResult
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ImageStudioPanel(context: Context) {
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

                        if (FirebaseAiEngine.isAvailable(context)) {
                            val fbPrompt = if (negativePrompt.isNotBlank()) {
                                "$clean (Avoid: ${negativePrompt.trim()})"
                            } else clean
                            when (val fbRes = firebaseAi.generateOrEditImage(fbPrompt, refBase64, imageAspect)) {
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

                        val allowCommunityImagePool = withContext(Dispatchers.IO) {
                            !AyraPreferences.isLocalOnlyAiModeEnabled(context) &&
                                AyraPreferences.hasExternalAiConsent(context) &&
                                AyraPreferences.isFreeAiPoolEnabled(context)
                        }
                        if (!allowCommunityImagePool) {
                            statusLabel = "Image generation unavailable: Nano Banana/ComfyUI failed and Global Free AI Pool or external consent is disabled (or Local-Only AI Mode is active)."
                            isBusy = false
                            return@launch
                        }
                        val url = withContext(Dispatchers.IO) {
                            AiMediaPromptSynthesizer.buildImageUrl(clean, context = context, aspectRatio = imageAspect)
                        }
                        generatedUrl = url
                        statusLabel = "Loading preview via Pollinations Flux..."
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

        var imageRetryNonce by remember(generatedFile, generatedUrl) { mutableIntStateOf(0) }
        val displayModel: Any? = generatedFile ?: generatedUrl?.let { raw ->
            if (imageRetryNonce == 0) raw else {
                val sep = if (raw.contains("?")) "&" else "?"
                "${raw}${sep}retry=$imageRetryNonce"
            }
        }
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
                onSuccess = {
                    if (generatedFile == null && generatedUrl != null) {
                        statusLabel = "Generated via Pollinations Flux (Community Tier)"
                    }
                },
                onError = { state ->
                    if (generatedFile == null) {
                        val reason = state.result.throwable.message ?: "Image request failed"
                        statusLabel = "Preview failed ($reason)"
                    }
                },
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = NeonCyan)
                    }
                },
                error = {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF111827))
                            .clickable {
                                statusLabel = "Retrying image preview..."
                                imageRetryNonce++
                            }
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry image preview",
                            tint = NeonCyan,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Could not load image preview • Tap to retry",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
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
