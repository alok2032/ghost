package com.ayra.ai.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.filled.Code
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.platform.testTag
import com.ayra.ai.app.ui.components.ChatSessionsSheet
import com.ayra.ai.app.ui.components.ControlCenterSheet
import com.ayra.ai.app.ui.components.ProviderKeysSection
import com.ayra.ai.app.ui.components.VoiceCommandOverlay
import com.ayra.ai.app.vpn.AyraVpnPrivacyController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.ayra.ai.app.ai.UnifiedAiEngine
import com.ayra.ai.app.automation.AyraTaskScheduler
import com.ayra.ai.app.automation.DeviceAutomationController
import com.ayra.ai.app.automation.ProposedAction
import com.ayra.ai.app.ui.components.ActionConfirmationCard
import com.ayra.ai.app.voice.CrisisDetector
import com.ayra.ai.app.data.ChatDao
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.ChatRepository
import org.json.JSONObject
import com.ayra.ai.app.ui.components.AmbientCornerGlow
import com.ayra.ai.app.ui.components.SolarSystem3DCanvas
import com.ayra.ai.app.ui.components.SongPlayerCard
import com.ayra.ai.app.ui.components.ThreeDModelCard
import com.ayra.ai.app.ui.components.VideoPlayerCard
import com.ayra.ai.app.ui.theme.CosmicBackground
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGreen
import com.ayra.ai.app.ui.theme.NeonGold
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import com.ayra.ai.app.ui.theme.UserBubble
import com.ayra.ai.app.voice.SentimentDetector
import com.ayra.ai.app.voice.UserSentiment
import com.ayra.ai.app.voice.VoiceAssistantManager
import com.ayra.ai.app.voice.VoiceUiState
import com.ayra.ai.app.ai.AyraOrchestrator
import com.ayra.ai.app.ui.viewmodel.ChatViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatDao: ChatDao,
    viewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    currentUser: com.google.firebase.auth.FirebaseUser? = null,
    onSignOut: () -> Unit = {},
    onOpenSecurityCenter: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null,
    embeddedInWorkspace: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val db = remember { ChatDatabase.getDatabase(context) }
    val chatRepository = remember(db, chatDao) {
        ChatRepository(
            context = context.applicationContext,
            chatDao = chatDao,
            sessionDao = db.chatSessionDao()
        )
    }
    LaunchedEffect(chatRepository) {
        viewModel.attachRepository(chatRepository)
    }

    val messages by viewModel.messages.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val activeSessionId by viewModel.activeSessionId.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()

    val automationController = remember { DeviceAutomationController(context) }
    val orchestrator = remember { AyraOrchestrator(context) }
    val aiEngine = remember { UnifiedAiEngine(context) }
    val firebaseAiEngine = remember { com.ayra.ai.app.ai.FirebaseAiEngine(context) }
    val externalAiProviders = remember { com.ayra.ai.app.ai.providers.ExternalAiProviders(context) }
    val audioCaptureManager = remember { com.ayra.ai.app.audio.AudioCaptureManager(context) }
    val cloudRepo = remember(currentUser?.uid) {
        if (currentUser != null && com.ayra.ai.app.ai.FirebaseAiEngine.isAvailable(context)) {
            runCatching { com.ayra.ai.app.cloud.AyraCloudRepository.create(context) }.getOrNull()
        } else null
    }
    val taskScheduler = remember { AyraTaskScheduler(context) }
    val scheduledTasks by db.scheduledTaskDao().getAllTasks().collectAsState(initial = emptyList())

    val currentPersona by viewModel.currentPersona.collectAsState()
    val selectedModelTier by viewModel.selectedModelTier.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val thinkingStatus by viewModel.thinkingStatus.collectAsState()
    val streamingAnswer by viewModel.streamingAnswer.collectAsState()
    val showKeyOnboarding by viewModel.showKeyOnboarding.collectAsState()
    val pendingAction by viewModel.pendingAction.collectAsState()
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDocumentUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDocumentName by remember { mutableStateOf<String?>(null) }
    var selectedDocumentText by remember { mutableStateOf<String?>(null) }

    var showClearChatDialog by remember { mutableStateOf(false) }
    var showAttachmentPicker by remember { mutableStateOf(false) }
    var showControlCenterSheet by remember { mutableStateOf(false) }
    var showSessionsSheet by remember { mutableStateOf(false) }
    var isFallbackVoiceMode by remember { mutableStateOf(false) }
    var pendingPermissionRationale by remember { mutableStateOf<Pair<String, String>?>(null) }
    var flaggingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var flagReasonInput by remember { mutableStateOf("Inaccurate or unsafe response") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var expandedImageUri by remember { mutableStateOf<String?>(null) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDocumentBase64Image by remember { mutableStateOf<String?>(null) }
    var isTranscribingAudio by remember { mutableStateOf(false) }

    val isRecordingAudio by audioCaptureManager.isRecording.collectAsState()
    val audioAmplitude by audioCaptureManager.amplitudeLevel.collectAsState()
    val audioSeconds by audioCaptureManager.elapsedSeconds.collectAsState()

    var dispatchPromptRef by remember { mutableStateOf<((String) -> Unit)?>(null) }

    LaunchedEffect(Unit) {
        val (savedPersona, savedTier, hasProvider) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.ayra.ai.app.AyraApplication.ensureActiveAiKeysSeeded(context)
            val persona = com.ayra.ai.app.data.AyraPreferences.getSavedPersona(context)
            val tier = com.ayra.ai.app.data.AyraPreferences.getSavedModelTier(context)
            val configured = com.ayra.ai.app.ai.providers.ExternalAiProviders(context).hasAnyConfiguredProvider() ||
                com.ayra.ai.app.ai.FirebaseAiEngine.isAvailable(context)
            Triple(persona, tier, configured)
        }
        viewModel.setPersona(savedPersona)
        viewModel.setModelTier(savedTier)
        if (!hasProvider) {
            viewModel.setShowKeyOnboarding(true)
        }
        aiEngine.currentPersona = savedPersona
        aiEngine.selectedModelTier = savedTier
    }

    LaunchedEffect(currentPersona) {
        aiEngine.currentPersona = currentPersona
    }

    LaunchedEffect(selectedModelTier) {
        aiEngine.selectedModelTier = selectedModelTier
    }

    LaunchedEffect(currentUser?.uid) {
        val repo = cloudRepo ?: return@LaunchedEffect
        val user = currentUser ?: return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                repo.upsertUserProfile(
                    displayName = user.displayName ?: "",
                    email = user.email ?: "",
                    photoUrl = user.photoUrl?.toString() ?: "",
                    preferredPersona = currentPersona,
                    preferredModelTier = selectedModelTier
                )
            }
            com.ayra.ai.app.ai.plugins.GitHubPluginRegistry.reconcileWithCloud(context, repo)
            val currentSessionId = viewModel.ensureSessionReady()
            if (chatRepository.getRecentMessages(currentSessionId, 1).isEmpty()) {
                val restored = repo.fetchCloudMessagesOnce(100)
                restored.forEach { msg ->
                    chatRepository.appendMessage(currentSessionId, msg)
                }
            }
        }
    }

    // Voice Assistant Manager setup with instant execution & real-time voice-to-text
    val voiceManager = remember {
        VoiceAssistantManager(context) { recognizedCommand ->
            if (recognizedCommand.isNotBlank()) {
                viewModel.updateInputText(recognizedCommand)
                dispatchPromptRef?.invoke(recognizedCommand)
            }
        }.apply {
            personaMode = currentPersona
            onSpeechTranscribed = { transcribedText, _ ->
                viewModel.updateInputText(transcribedText)
            }
            onWakeWordTriggered = { greeting ->
                scope.launch {
                    viewModel.persistMessage(
                        ChatMessage(
                            role = "assistant",
                            content = greeting
                        )
                    )
                }
            }
        }
    }

    val isListening by voiceManager.isListening.collectAsState()
    val isSpeaking by voiceManager.isSpeaking.collectAsState()
    val currentSentiment by voiceManager.currentSentiment.collectAsState()
    val voiceState by voiceManager.voiceState.collectAsState()
    val liveSpokenText by voiceManager.liveSpokenText.collectAsState()
    val rmsLevel by voiceManager.rmsLevel.collectAsState()
    val selectedVoiceLang by voiceManager.selectedLanguageTag.collectAsState()

    val startVoiceCommandSession: () -> Unit = {
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
            isFallbackVoiceMode = false
            voiceManager.onSpeechTranscribed = { transcribedText, _ ->
                viewModel.updateInputText(transcribedText)
            }
            voiceManager.startManualVoiceCommand()
        } else {
            isFallbackVoiceMode = true
            audioCaptureManager.startRecording().onFailure { err ->
                isFallbackVoiceMode = false
                Toast.makeText(
                    context,
                    "Voice microphone error: ${err.localizedMessage ?: "Unavailable"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(voiceManager, audioCaptureManager) {
        onDispose {
            voiceManager.destroy()
            audioCaptureManager.destroy()
        }
    }

    val voiceCommandPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceCommandSession()
        } else {
            Toast.makeText(
                context,
                "Microphone permission is required for real-time voice commands.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val audioRecordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            audioCaptureManager.startRecording().onFailure { err ->
                Toast.makeText(context, "Audio capture error: ${err.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Microphone permission is required for AI audio transcription.", Toast.LENGTH_SHORT).show()
        }
    }

    val allRequiredPermissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS
        )
    }

    // Permission Launchers
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val recordOk = grants[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordOk) {
            Toast.makeText(context, "Microphone enabled. Tap Mic button to speak.", Toast.LENGTH_SHORT).show()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val savedFile = com.ayra.ai.app.data.StorageHelper.saveMediaToInternalStorage(context, uri, "gallery_img")
            selectedImageUri = if (savedFile != null) Uri.fromFile(savedFile) else uri
        }
    }

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedDocumentUri = uri
            try {
                var name = "Document"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        name = cursor.getString(nameIndex) ?: "Document"
                    }
                }
                selectedDocumentName = name
                // Use intelligent document parser for PDF tokens, TXT, CSV, JSON
                val extracted = com.ayra.ai.app.data.StorageHelper.extractTextFromDocument(context, uri, name)
                selectedDocumentText = extracted

                // If document is PDF, also render first page to Base64 image for multimodal vision
                if (name.endsWith(".pdf", ignoreCase = true)) {
                    val pdfBase64 = com.ayra.ai.app.data.StorageHelper.renderPdfFirstPageToBase64(context, uri)
                    selectedDocumentBase64Image = pdfBase64
                } else {
                    selectedDocumentBase64Image = null
                }

                // Persist copy into internal app storage & index into Room Knowledge Base for persistent RAG
                com.ayra.ai.app.data.StorageHelper.saveMediaToInternalStorage(context, uri, name)
                if (extracted.isNotBlank()) {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val rag = com.ayra.ai.app.ai.rag.DocumentRagEngine(db.knowledgeDocumentDao())
                            rag.ingestDocument(
                                title = name,
                                mimeType = context.contentResolver.getType(uri) ?: "text/plain",
                                rawText = extracted,
                                sourceUri = uri.toString()
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                selectedDocumentName = "Uploaded File"
                selectedDocumentText = "Document attached for analysis: ${uri.lastPathSegment}"
                selectedDocumentBase64Image = null
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraUri != null) {
            val savedFile = com.ayra.ai.app.data.StorageHelper.saveMediaToInternalStorage(context, tempCameraUri!!, "cam_img")
            selectedImageUri = if (savedFile != null) Uri.fromFile(savedFile) else tempCameraUri
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                val exportsDir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
                val photoFile = File(exportsDir, "camera_capture_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "com.ayra.ai.app.fileprovider", photoFile)
                tempCameraUri = uri
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                android.util.Log.e("ChatScreen", "Camera launch error", e)
                Toast.makeText(context, "Camera launch failed: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Camera permission is required to capture photos", Toast.LENGTH_SHORT).show()
        }
    }

    // Scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Main Dispatcher function for prompt execution
    fun dispatchPrompt(query: String) {
        val rawTrimmed = query.trim()
        val trimmed = if (rawTrimmed.isNotBlank()) rawTrimmed else {
            if (selectedDocumentUri != null) "Please analyze this document thoroughly, extract all key details, summarize its contents, and provide actionable insights."
            else if (selectedImageUri != null) "Please inspect this photo in detail, identify all subjects and objects, read visible text, and explain the context."
            else ""
        }
        if (trimmed.isBlank() && selectedImageUri == null && selectedDocumentUri == null) return

        // Snapshot attachments before clearing
        val attachedUriStr = selectedImageUri?.toString()
        val docText = selectedDocumentText
        val docName = selectedDocumentName
        val docBase64Image = selectedDocumentBase64Image

        scope.launch {
            if (!viewModel.tryStartSending()) return@launch

            selectedImageUri = null
            selectedDocumentUri = null
            selectedDocumentText = null
            selectedDocumentName = null
            selectedDocumentBase64Image = null
            viewModel.clearInput()

            // Detect user sentiment immediately from prompt for dynamic voice modulation
            val detectedSentiment = voiceManager.detectAndApplySentiment(trimmed)

            // 1. Save user message to Room (displaying attached document badge if present)
            val userContent = if (!docName.isNullOrBlank()) {
                if (rawTrimmed.isNotBlank()) "📄 [$docName]\n$rawTrimmed" else "📄 [$docName]\nPlease analyze this document."
            } else trimmed

            // 1b. Safety Evaluation: Crisis & Self-Harm Detection
            val crisis = CrisisDetector.evaluate(trimmed)
            if (crisis.isCrisis && crisis.supportiveMessage != null) {
                viewModel.persistMessage(
                    ChatMessage(
                        role = "user",
                        content = userContent,
                        attachedImagePath = attachedUriStr
                    )
                )
                viewModel.persistMessage(
                    ChatMessage(
                        role = "assistant",
                        content = crisis.supportiveMessage
                    )
                )
                voiceManager.speak("I hear you and I care about your safety. Free confidential help is available right now. Please reach out to someone you trust.")
                viewModel.finishSending()
                return@launch
            }

            // 1c. ActionGate Check: Propose critical actions for user confirmation
            val proposed = automationController.proposeAction(trimmed)
            if (proposed != null) {
                viewModel.persistMessage(
                    ChatMessage(
                        role = "user",
                        content = userContent,
                        attachedImagePath = attachedUriStr
                    )
                )
                viewModel.setPendingAction(proposed)
                viewModel.finishSending()
                return@launch
            }

            val userMsg = ChatMessage(
                role = "user",
                content = userContent,
                attachedImagePath = attachedUriStr
            )
            val insertedUserId = viewModel.persistMessage(userMsg)
            cloudRepo?.let { repo ->
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { repo.syncMessageToCloud(userMsg.copy(id = insertedUserId)) }
                }
            }

            // 2. Check for Autonomous Scheduled Task
            if (taskScheduler.isSchedulingIntent(trimmed)) {
                val scheduleResult = taskScheduler.scheduleTaskFromPrompt(trimmed)
                if (scheduleResult.executed) {
                    viewModel.finishSending()
                    viewModel.persistMessage(
                        ChatMessage(
                            role = "assistant",
                            content = scheduleResult.feedbackMessage,
                            craftType = scheduleResult.actionType,
                            craftPayload = scheduleResult.extraData
                        )
                    )
                    voiceManager.speak(scheduleResult.feedbackMessage, overrideSentiment = detectedSentiment)
                    return@launch
                }
            }

            // 2b. Check for harmless quick automation commands
            val autoResult = automationController.tryExecuteAutomation(trimmed)
            if (autoResult != null && autoResult.executed) {
                viewModel.finishSending()
                viewModel.persistMessage(
                    ChatMessage(
                        role = "assistant",
                        content = autoResult.feedbackMessage,
                        craftType = autoResult.actionType,
                        craftPayload = autoResult.extraData
                    )
                )
                voiceManager.speak(autoResult.feedbackMessage, overrideSentiment = detectedSentiment)
                return@launch
            }

            // 3. Streaming Orchestrator Execution (Q&A, Coding, Vision, Research, etc.)
            try {
                var base64Image: String? = null
                if (!attachedUriStr.isNullOrBlank()) {
                    try {
                        val stream = com.ayra.ai.app.data.StorageHelper.openReadableAttachmentStream(context, attachedUriStr)
                        val bytes = stream?.readBytes()
                        stream?.close()
                        if (bytes != null) {
                            base64Image = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        }
                    } catch (_: Exception) {}
                } else if (!docBase64Image.isNullOrBlank()) {
                    base64Image = docBase64Image
                }

                val rawHistory = viewModel.getRecentHistory(15)
                val history = if (rawHistory.lastOrNull()?.role == "user" && rawHistory.lastOrNull()?.content == userContent) {
                    rawHistory.dropLast(1)
                } else {
                    rawHistory
                }
                var receivedTerminalEvent = false
                val orchestratorJob = scope.launch {
                    try {
                        orchestrator.handle(
                            prompt = trimmed,
                            persona = currentPersona,
                            tier = selectedModelTier,
                            history = history,
                            imageBase64 = base64Image,
                            documentText = docText,
                            documentName = docName
                        ).collect { event ->
                            when (event) {
                                is com.ayra.ai.app.ai.AyraEvent.Status -> {
                                    viewModel.setThinkingStatus(event.message)
                                }
                                is com.ayra.ai.app.ai.AyraEvent.Token -> {
                                    viewModel.appendStreamingToken(event.text)
                                }
                                is com.ayra.ai.app.ai.AyraEvent.ActionRequired -> {
                                    receivedTerminalEvent = true
                                    viewModel.setPendingAction(event.action)
                                }
                                is com.ayra.ai.app.ai.AyraEvent.Complete -> {
                                    receivedTerminalEvent = true
                                    val assistantMsg = ChatMessage(
                                        role = "assistant",
                                        content = event.response.text,
                                        craftType = event.response.craftType,
                                        craftPayload = event.response.craftPayload,
                                        actionCommand = event.response.modelUsed
                                    )
                                    val insertedAssistantId = viewModel.persistMessage(assistantMsg)
                                    cloudRepo?.let { repo ->
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            runCatching {
                                                repo.syncMessageToCloud(assistantMsg.copy(id = insertedAssistantId))
                                                val cType = event.response.craftType
                                                if (cType in listOf("IMAGE", "VIDEO", "SONG", "3D", "PDF_DOWNLOAD")) {
                                                    repo.logCreation(
                                                        creationType = if (cType == "PDF_DOWNLOAD") "PDF" else cType!!,
                                                        title = trimmed.take(120),
                                                        prompt = trimmed,
                                                        summary = event.response.text.take(800),
                                                        modelUsed = event.response.modelUsed ?: "Ayra AI"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    voiceManager.speak(event.response.text, overrideSentiment = detectedSentiment)
                                }
                                is com.ayra.ai.app.ai.AyraEvent.Error -> {
                                    receivedTerminalEvent = true
                                    val err = event.error.userFriendlyMessage
                                    viewModel.persistMessage(
                                        ChatMessage(
                                            role = "assistant",
                                            content = "⚠️ $err"
                                        )
                                    )
                                    if (event.error is com.ayra.ai.app.ai.providers.AyraError.NoProviderConfigured) {
                                        viewModel.setShowKeyOnboarding(true)
                                    }
                                }
                            }
                        }
                        if (!receivedTerminalEvent) {
                            viewModel.persistMessage(
                                ChatMessage(
                                    role = "assistant",
                                    content = "⚠️ কোনো উত্তর পাওয়া যায়নি। অনুগ্রহ করে ইন্টারনেট সংযোগ পরীক্ষা করে আবার চেষ্টা করুন।"
                                )
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val safeErr = e.localizedMessage ?: e::class.java.simpleName
                        viewModel.persistMessage(
                            ChatMessage(
                                role = "assistant",
                                content = "⚠️ Error processing request: $safeErr"
                            )
                        )
                    }
                }
                viewModel.setInFlightJob(orchestratorJob)
                orchestratorJob.join()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val err = "Error processing request: ${e.message}"
                viewModel.persistMessage(
                    ChatMessage(
                        role = "assistant",
                        content = err
                    )
                )
            } finally {
                viewModel.finishSending()
            }
        }
    }

    LaunchedEffect(Unit) {
        dispatchPromptRef = { prompt: String -> dispatchPrompt(prompt) }
    }

    val uiConfig = com.ayra.ai.app.ui.theme.LocalAyraUiConfig.current
    val activeThinkingMode by com.ayra.ai.app.data.AyraPreferences.thinkingModeFlow.collectAsState()
    val activeProjectId by com.ayra.ai.app.data.AyraPreferences.activeProjectIdFlow.collectAsState()
    val activeAssistantId by com.ayra.ai.app.data.AyraPreferences.activeAssistantIdFlow.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (uiConfig.isDark) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.background)
    ) {
        // 1. 3D Moving Solar System in the background (when Dark & Motion enabled)
        if (uiConfig.isDark && !uiConfig.reducedMotion && !uiConfig.isHighContrast) {
            SolarSystem3DCanvas(modifier = Modifier.fillMaxSize().graphicsLayer { })
            AmbientCornerGlow(modifier = Modifier.fillMaxSize().graphicsLayer { })
        }

        // 3. Foreground Chat Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .then(if (embeddedInWorkspace) Modifier else Modifier.navigationBarsPadding())
        ) {
            // Top App Bar (no bottom navigation tiles)
            GlassHeaderBar(
                isListening = isListening,
                isSpeaking = isSpeaking,
                currentPersona = currentPersona,
                currentSentiment = currentSentiment,
                activeSessionTitle = activeSession?.title,
                activeThinkingMode = activeThinkingMode,
                hasActiveWorkspaceContext = activeProjectId > 0L || activeAssistantId > 0L,
                onMenuClick = onOpenDrawer ?: { showSessionsSheet = true },
                onLogoClick = { showControlCenterSheet = true },
                onSessionsClick = { showSessionsSheet = true },
                onNewSessionClick = { viewModel.createNewSession() },
                onClearChat = {
                    showClearChatDialog = true
                },
                onStopVoice = { voiceManager.stopSpeaking() },
                onCycleThinkingMode = {
                    val modes = listOf("balanced", "fast", "deep_analysis", "reasoning", "research", "coding", "creative")
                    val next = modes[(modes.indexOf(activeThinkingMode) + 1) % modes.size]
                    com.ayra.ai.app.data.AyraPreferences.setThinkingMode(context, next)
                    Toast.makeText(context, "Thinking Mode: $next", Toast.LENGTH_SHORT).show()
                }
            )

            // Message List
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (messages.isEmpty()) {
                    EmptyCosmicState(
                        currentPersona = currentPersona,
                        onSuggestionClick = { suggestion ->
                            viewModel.updateInputText(suggestion)
                            dispatchPrompt(suggestion)
                        }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            val msgIndex = messages.indexOf(msg)
                            val precedingUserPrompt = remember(messages, msgIndex) {
                                if (msg.role == "assistant") {
                                    messages.take(msgIndex).lastOrNull { it.role == "user" }?.content
                                } else null
                            }
                            GlassMessageBubble(
                                message = msg,
                                onImageClick = { expandedImageUri = it },
                                onCopy = {
                                    com.ayra.ai.app.security.AndroidHardeningHelper.copyWithSensitivityProtection(
                                        context = context,
                                        label = "Ayra Message",
                                        text = msg.content,
                                        isSensitive = true,
                                        autoClearMs = if (com.ayra.ai.app.data.AyraPreferences.isClipboardAutoClearEnabled(context)) 60_000L else 0L
                                    )
                                    Toast.makeText(context, "Copied (auto-clears securely)!", Toast.LENGTH_SHORT).show()
                                },
                                onSpeak = {
                                    val msgSentiment = SentimentDetector.detectSentiment(msg.content)
                                    voiceManager.speak(msg.content, overrideSentiment = msgSentiment)
                                },
                                onEdit = { contentToEdit ->
                                    viewModel.updateInputText(contentToEdit)
                                    Toast.makeText(context, "Loaded into editor to edit / এডিট করতে লোড হয়েছে", Toast.LENGTH_SHORT).show()
                                },
                                onRegenerate = precedingUserPrompt?.let { prevPrompt ->
                                    {
                                        if (!isGenerating) dispatchPrompt(prevPrompt)
                                    }
                                },
                                onContinue = {
                                    if (!isGenerating) {
                                        dispatchPrompt("Continue from where you stopped and expand concisely:\n\n${msg.content.takeLast(500)}")
                                    }
                                },
                                onFlag = if (msg.role != "user") {
                                    {
                                        flagReasonInput = "Inaccurate or misleading"
                                        flaggingMessage = msg
                                    }
                                } else null,
                                onRunPluginPrompt = { pluginPrompt ->
                                    dispatchPrompt(pluginPrompt)
                                }
                            )
                        }

                        if (isGenerating) {
                            item {
                                AssistantGeneratingIndicator(
                                    statusText = thinkingStatus,
                                    streamingText = streamingAnswer
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Attachment Preview
            if (selectedImageUri != null) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassSurfaceDark)
                        .border(1.dp, NeonCyan, RoundedCornerShape(12.dp))
                        .padding(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AsyncImage(
                            model = selectedImageUri,
                            contentDescription = "Selected Image",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            text = "Visual attachment ready",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { selectedImageUri = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = NeonRose)
                        }
                    }
                }
            }

            // Document / PDF Attachment Preview
            if (selectedDocumentName != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassSurfaceDark)
                        .border(1.dp, NeonCyan.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedDocumentName ?: "Document",
                                color = TextPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Ready for Q&A, summary, editing & insights",
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                        IconButton(
                            onClick = {
                                selectedDocumentUri = null
                                selectedDocumentName = null
                                selectedDocumentText = null
                                selectedDocumentBase64Image = null
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = NeonRose, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // ActionGate Confirmation Card (Requires explicit user tap to execute)
            pendingAction?.let { action ->
                ActionConfirmationCard(
                    action = action,
                    onConfirm = {
                        scope.launch {
                            val result = automationController.executeConfirmedAction(action)
                            viewModel.persistMessage(
                                ChatMessage(
                                    role = "assistant",
                                    content = result.feedbackMessage,
                                    craftType = result.actionType,
                                    craftPayload = result.extraData
                                )
                            )
                            voiceManager.speak(result.feedbackMessage)
                            viewModel.setPendingAction(null)
                        }
                    },
                    onDismiss = {
                        scope.launch {
                            viewModel.persistMessage(
                                ChatMessage(
                                    role = "assistant",
                                    content = "Action cancelled by user / কাজটি বাতিল করা হয়েছে।"
                                )
                            )
                            viewModel.setPendingAction(null)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            // Real-Time Voice-to-Text Command HUD Overlay
            val showVoiceOverlay = isFallbackVoiceMode ||
                voiceState is VoiceUiState.Connecting ||
                (voiceState is VoiceUiState.Listening && (voiceState as VoiceUiState.Listening).isManual) ||
                voiceState is VoiceUiState.Processing ||
                voiceState is VoiceUiState.Error

            VoiceCommandOverlay(
                visible = showVoiceOverlay,
                voiceState = voiceState,
                liveSpokenText = liveSpokenText,
                rmsLevel = rmsLevel,
                selectedLanguageTag = selectedVoiceLang,
                isFallbackRecording = isFallbackVoiceMode && isRecordingAudio,
                fallbackSeconds = audioSeconds,
                fallbackAmplitude = audioAmplitude,
                onLanguageSelected = { tag ->
                    voiceManager.setLanguageTag(tag)
                    if (!isFallbackVoiceMode) {
                        voiceManager.cancelListening()
                        voiceManager.startManualVoiceCommand(tag)
                    }
                },
                onSendCommandNow = { spokenCommand ->
                    if (isFallbackVoiceMode && isRecordingAudio) {
                        val recordedFile = audioCaptureManager.stopRecording()
                        isFallbackVoiceMode = false
                        if (recordedFile != null) {
                            isTranscribingAudio = true
                            scope.launch {
                                var res = firebaseAiEngine.transcribeAudioFile(recordedFile)
                                if (res.isFailure) {
                                    val whisperText = externalAiProviders.transcribeAudioOpenAiCompatible(recordedFile)
                                    if (whisperText.isNotBlank()) {
                                        res = Result.success(whisperText)
                                    }
                                }
                                runCatching { recordedFile.delete() }
                                isTranscribingAudio = false
                                res.onSuccess { transcript ->
                                    if (transcript.isNotBlank()) {
                                        viewModel.updateInputText(transcript)
                                        dispatchPrompt(transcript)
                                    }
                                }.onFailure { err ->
                                    Toast.makeText(
                                        context,
                                        err.localizedMessage ?: "Voice transcription failed",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    } else if (spokenCommand.isNotBlank()) {
                        voiceManager.cancelListening()
                        viewModel.updateInputText(spokenCommand)
                        dispatchPrompt(spokenCommand)
                    } else {
                        voiceManager.finishAndSubmitManualListening()
                    }
                },
                onUseTranscriptInInput = { transcript ->
                    voiceManager.cancelListening()
                    if (transcript.isNotBlank()) {
                        viewModel.updateInputText(transcript)
                    }
                },
                onRetry = {
                    voiceManager.dismissVoiceError()
                    startVoiceCommandSession()
                },
                onCancel = {
                    if (isFallbackVoiceMode) {
                        audioCaptureManager.cancelRecording()
                        isFallbackVoiceMode = false
                    }
                    voiceManager.cancelListening()
                    voiceManager.dismissVoiceError()
                }
            )

            // Gemini Audio Capture / Transcription Live Status Banner
            if ((isRecordingAudio && !isFallbackVoiceMode) || isTranscribingAudio) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassSurfaceDark)
                        .border(1.dp, NeonRose.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isTranscribingAudio) {
                            "🎧 Gemini Audio AI প্রতিলিপি তৈরি করছে..."
                        } else {
                            "🎙️ Recording AI Audio (${audioSeconds}s • ${(audioAmplitude * 100).toInt()}%) • Tap Audio button to transcribe"
                        },
                        color = NeonRose,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (isRecordingAudio) {
                        Text(
                            text = "Cancel",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { audioCaptureManager.cancelRecording() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Clean Input Row (NO elevated typing bar as requested)
            GlassInputRow(
                text = inputText,
                onTextChange = { viewModel.updateInputText(it) },
                isListening = isListening,
                isRecordingAudio = isRecordingAudio,
                isGenerating = isGenerating,
                onSend = { dispatchPrompt(inputText) },
                onStop = { viewModel.stopGeneration() },
                onAudioTranscribeClick = {
                    val hasRecord = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasRecord) {
                        pendingPermissionRationale = "audio_transcribe" to "Ayra needs microphone access solely while recording your voice note for transcription."
                    } else if (!isRecordingAudio) {
                        audioCaptureManager.startRecording().onFailure { err ->
                            Toast.makeText(context, "Could not start recording: ${err.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val recordedFile = audioCaptureManager.stopRecording()
                        if (recordedFile != null) {
                            isTranscribingAudio = true
                            scope.launch {
                                var res = firebaseAiEngine.transcribeAudioFile(recordedFile)
                                if (res.isFailure) {
                                    val whisperText = externalAiProviders.transcribeAudioOpenAiCompatible(recordedFile)
                                    if (whisperText.isNotBlank()) {
                                        res = Result.success(whisperText)
                                    }
                                }
                                runCatching { recordedFile.delete() }
                                isTranscribingAudio = false
                                res.onSuccess { transcript ->
                                    viewModel.updateInputText(
                                        if (inputText.isBlank()) transcript else "$inputText $transcript"
                                    )
                                }.onFailure { err ->
                                    Toast.makeText(
                                        context,
                                        err.localizedMessage ?: "Audio transcription failed",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                },
                onMicClick = {
                    val hasRecord = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasRecord) {
                        pendingPermissionRationale = "voice_cmd" to "Ayra uses microphone access only when you tap the mic for hands-free voice commands."
                    } else if (isFallbackVoiceMode && isRecordingAudio) {
                        audioCaptureManager.cancelRecording()
                        isFallbackVoiceMode = false
                    } else if (isListening) {
                        voiceManager.finishAndSubmitManualListening()
                    } else {
                        startVoiceCommandSession()
                    }
                },
                onAttachClick = { showAttachmentPicker = true }
            )
        }

        // Full Screen Image Modal
        if (expandedImageUri != null) {
            Dialog(onDismissRequest = { expandedImageUri = null }) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.9f))
                        .clickable { expandedImageUri = null },
                    contentAlignment = Alignment.Center
                ) {
                    val resolvedExpandedModel = remember(expandedImageUri) {
                        com.ayra.ai.app.data.StorageHelper.resolveCoilImageModel(context, expandedImageUri)
                    }
                    AsyncImage(
                        model = resolvedExpandedModel,
                        contentDescription = "Expanded View",
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        // Attachment Source & Super-App Feature Hub Modal (Bottom '+' Hub)
        if (showAttachmentPicker) {
            Dialog(onDismissRequest = { showAttachmentPicker = false }) {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = Color(0xFF070B16),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        Brush.linearGradient(listOf(NeonCyan, NeonPurple, NeonRose))
                    ),
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
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
                                        text = "Ayra AI Studio & Super-Hub ⚡",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonCyan,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = "OmniRoute • DeepSeek • Higgsfield • Open-Weights • Security Lab",
                                        color = TextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showAttachmentPicker = false },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(20.dp))
                            }
                        }

                        // Section 1: Multimodal Inputs
                        Text(
                            text = "📸 মিডিয়া ও ডকুমেন্ট ইনপুট (Multimodal Capture):",
                            color = NeonCyan,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // 1. Camera
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0F172A))
                                .border(0.8.dp, NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                    if (hasPerm) {
                                        try {
                                            val exportsDir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
                                            val photoFile = File(exportsDir, "camera_capture_${System.currentTimeMillis()}.jpg")
                                            val uri = FileProvider.getUriForFile(context, "com.ayra.ai.app.fileprovider", photoFile)
                                            tempCameraUri = uri
                                            cameraLauncher.launch(uri)
                                        } catch (e: Exception) {
                                            android.util.Log.e("ChatScreen", "Camera launch error", e)
                                            Toast.makeText(context, "Camera launch failed: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        pendingPermissionRationale = "camera" to "Ayra uses your camera only when you choose to take a photo for multimodal visual analysis."
                                    }
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Take Photo with Camera", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("ছবি তুলে সরাসরি এআইকে অ্যানালাইসিস করতে বলুন", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 2. Gallery
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0F172A))
                                .border(0.8.dp, NeonPurple.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    galleryLauncher.launch(
                                        androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Upload from Photos / Gallery", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("মাল্টি-এআই ভিশন, অবজেক্ট ডিটেকশন ও স্টাইল এডিট", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 3. Document & PDF Upload
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0F172A))
                                .border(0.8.dp, NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    documentLauncher.launch(
                                        arrayOf(
                                            "application/pdf",
                                            "text/*",
                                            "application/msword",
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                            "application/vnd.ms-excel",
                                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                            "application/json"
                                        )
                                    )
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Upload Document / PDF File", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("PDF বা ডকুমেন্টের সম্পূর্ণ সারাংশ ও প্রশ্নোত্তর", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // Section 2: Frontier Multi-AI Architectures
                        Text(
                            text = "🧠 ফ্রন্টিয়ার এআই ইঞ্জিন (Multi-Model Frontier Suite):",
                            color = NeonGold,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // 4. OmniRoute Ensemble
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(1.dp, NeonGold.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    viewModel.setModelTier("omniroute")
                                    aiEngine.selectedModelTier = "omniroute"
                                    viewModel.updateInputText("OmniRoute Ensemble: সব এআই মডেলের সম্মিলিত জ্ঞান দিয়ে গভীর বিশ্লেষণ দাও")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NeonGold, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("OmniRoute Multi-Model Ensemble ⚡👑", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("সব এআই একসাথে সমন্বিত হয়ে বিশ্বের সেরা ফলাফল দেয়", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 5. DeepSeek-R1 Reasoning Harness
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(1.dp, NeonRose.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    viewModel.setModelTier("deepseek_harness")
                                    aiEngine.selectedModelTier = "deepseek_harness"
                                    viewModel.updateInputText("DeepSeek Reasoning Harness: ধাপে ধাপে গভীর গাণিতিক যুক্তি সহ প্রমাণ করো")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = NeonRose, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("DeepSeek-R1 Reasoning Harness 🔬", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("Chain-of-thought যুক্তি, গণিত ও স্বয়ংক্রিয় টেস্ট হারনেস", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 6. Open-Weights Studio
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(1.dp, Color(0xFFFF5722).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    viewModel.setModelTier("open_weights")
                                    aiEngine.selectedModelTier = "open_weights"
                                    viewModel.updateInputText("Open-Weights Studio: স্পষ্ট ও বিস্তারিত প্রযুক্তিগত বিশ্লেষণ দাও")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFFFF5722), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Open-Weights Studio 🌐", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("ওপেন-ওয়েটস লোকাল ও ক্লাউড মডেল — কাস্টম কোড ও গবেষণা", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 7. Defensive Security Research Mode
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    viewModel.setModelTier("security_research")
                                    aiEngine.selectedModelTier = "security_research"
                                    viewModel.updateInputText("Security Research: ডিফেন্সিভ সাইবার সিকিউরিটি, OWASP এবং ভালনারেবিলিটি অডিট")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Security Research Mode 🛡️", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("ডিফেন্সিভ সাইবার সিকিউরিটি, CVE বিশ্লেষণ এবং সিস্টেম অডিট", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 8. Open-Higgsfield-AI Cinematic Video
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(1.dp, NeonRose.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    viewModel.updateInputText("Generate a cinematic 4K video of a spaceship entering a colorful nebula with FPV drone motion")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NeonRose, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Open-Higgsfield-AI Cinematic 4K Video 🎬", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("ক্যামেরা মোশন ফিজিক্স ও অ্যানামরফিক অপটিক্যাল রেন্ডারিং", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 9. ECC Enterprise Cognitive Engine
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    viewModel.setModelTier("ecc")
                                    aiEngine.selectedModelTier = "ecc"
                                    viewModel.updateInputText("ECC Cognitive: কনটেক্সট মেমরি ও ইন্টেন্ট ডিসঅ্যাম্বিগিউয়েশন গ্রাউন্ড করো")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF7C4DFF), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("ECC Enterprise Cognitive 🌐", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("মাল্টি-টার্ন মেমরি গ্রাফ, ইন্টেন্ট রেজোলিউশন ও ফ্যাক্ট চেকিং", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 10. Superpowers Autonomous Agent
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(1.dp, NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    viewModel.updateInputText("Superpower: TDD এবং সিস্টেমেটিক ডিবাগিং দিয়ে কোড টেস্ট ও আর্কিটেকচার তৈরি করো")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Superpowers Autonomous Agent ⚙️", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("TDD টেস্ট ড্রাইভেন কোডিং, সিস্টেমেটিক ডিবাগিং ও কোড রিভিউ", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // Section 3: Advanced Practical Tools
                        Text(
                            text = "⚡ পাওয়ার টুলস ও সিকিউরিটি (Security & Utility Hub):",
                            color = NeonCyan,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // 11. Cyber Security Terminal
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(0.8.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    viewModel.updateInputText("Open Cyber Terminal")
                                    dispatchPromptRef?.invoke("Open Cyber Terminal")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Cyber Security Defensive Lab 💻", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("Ping, Traceroute, Whois, Hash, CVE ও OWASP অডিট", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 12. Create New PDF Document
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(0.8.dp, NeonGold.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    viewModel.updateInputText("Create a professional PDF document about Artificial Intelligence")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = NeonGold, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Create New PDF Document 📄", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("ফরম্যাটেড প্রফেশনাল PDF তৈরি ও ডিভাইসে সেভ", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 13. AI Image Studio
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(0.8.dp, NeonPurple.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    viewModel.updateInputText("একটি সুন্দর রঙিন ড্রাগনের 8K ছবি আঁকো")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("AI Image Studio (8K Masterpiece) 🎨", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("DALL-E 3 + Gemini + Flux.1 আল্ট্রা-রিয়েল ভিজ্যুয়াল", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 14. AI Music & Song Studio
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(0.8.dp, NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    viewModel.updateInputText("একটি রোমান্টিক মিষ্টি প্রেমের গান বানাও")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("AI Music & Song Creator 🎵", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("নতুন সুর, লিরিক্স ও অডিও প্লেয়ার সিন্থেসিস", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 15. Build-Your-Own-X Engineering
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(0.8.dp, NeonGold.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    viewModel.updateInputText("Build your own Redis server from scratch with complete architecture and code")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = NeonGold, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Build-Your-Own-X Master Engine ⚙️", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("Git, Docker, Redis, SQLite, Transformer স্ক্র্যাচ থেকে কোড", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 16. Install GitHub Repository Plugin (ChatGPT Plugins & Claude Skills style)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(0.8.dp, NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    showControlCenterSheet = true
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Install GitHub Plugin / Skill / MCP 🔌", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("যেকোনো GitHub repo URL দিয়ে ChatGPT/Claude-এর মতো প্লাগইন অ্যাড করুন", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }


                        // 17. Schedule Routine / Call
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(0.8.dp, NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    viewModel.updateInputText("কাল সকাল ১০টায় বাবাকে কল করে বলো ওষুধ খেয়ে নিতে")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Schedule Routine / Autonomous Call ⏰", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("নির্দিষ্ট সময়ে কল, মেসেজ বা উইশ করার শিডিউলার", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }

                        // 18. Share GPS Location
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131B2E))
                                .border(0.8.dp, NeonRose.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable {
                                    showAttachmentPicker = false
                                    dispatchPrompt("Where am I right now? GPS Coordinates")
                                }
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = NeonRose, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Share Current GPS Location 📍", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("রিয়েল-টাইম অক্ষাংশ, দ্রাঘিমাংশ ও মানচিত্র লিঙ্ক", color = TextSecondary, fontSize = 10.5.sp)
                            }
                        }
                    }
                }
            }
        }

        // Control Center & Settings Bottom Sheet (Opened by clicking Ayra Logo)
        if (showControlCenterSheet) {
            ControlCenterSheet(
                sheetState = sheetState,
                automationController = automationController,
                messageCount = messages.size,
                currentPersona = currentPersona,
                onPersonaChange = { newPersona ->
                    viewModel.setPersona(newPersona)
                    com.ayra.ai.app.data.AyraPreferences.savePersona(context, newPersona)
                    voiceManager.personaMode = newPersona
                    aiEngine.currentPersona = newPersona
                },
                selectedModelTier = selectedModelTier,
                onModelTierChange = { newTier ->
                    viewModel.setModelTier(newTier)
                    com.ayra.ai.app.data.AyraPreferences.saveModelTier(context, newTier)
                    aiEngine.selectedModelTier = newTier
                },
                wakeWordEnabled = voiceManager.isAlwaysOnWakeWordEnabled,
                onWakeWordToggle = { enabled ->
                    voiceManager.isAlwaysOnWakeWordEnabled = enabled
                    if (enabled) {
                        voiceManager.startListening()
                    } else {
                        voiceManager.stopListening()
                    }
                },
                onRequestAllPermissions = { permissionLauncher.launch(allRequiredPermissions) },
                currentSentiment = currentSentiment,
                dynamicVoiceModulationEnabled = voiceManager.isDynamicModulationEnabled,
                onToggleDynamicVoiceModulation = { voiceManager.isDynamicModulationEnabled = it },
                selectedSentimentLock = voiceManager.manualSentimentLock,
                onSentimentLockChange = { voiceManager.manualSentimentLock = it },
                onTestSentimentVoice = { sentiment ->
                    voiceManager.testSentimentVoice(sentiment)
                },
                scheduledTasks = scheduledTasks,
                onDeleteScheduledTask = { taskId ->
                    scope.launch {
                        taskScheduler.cancelAlarm(taskId)
                        db.scheduledTaskDao().deleteTask(taskId)
                        Toast.makeText(context, "Scheduled task removed / শিডিউল রুটিন মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                    }
                },
                userMessages = messages.filter { it.role == "user" },
                onSelectPrompt = { selectedPrompt ->
                    showControlCenterSheet = false
                    viewModel.updateInputText(selectedPrompt)
                },
                onCopyPrompt = { promptToCopy ->
                    com.ayra.ai.app.security.AndroidHardeningHelper.copyWithSensitivityProtection(
                        context = context,
                        label = "Ayra Prompt",
                        text = promptToCopy,
                        isSensitive = true,
                        autoClearMs = if (com.ayra.ai.app.data.AyraPreferences.isClipboardAutoClearEnabled(context)) 60_000L else 0L
                    )
                    Toast.makeText(context, "Prompt copied securely / কপি করা হয়েছে", Toast.LENGTH_SHORT).show()
                },
                userDisplayName = currentUser?.displayName,
                userEmail = currentUser?.email,
                onSignOut = if (currentUser != null) onSignOut else null,
                onOpenSecurityCenter = onOpenSecurityCenter,
                onDismiss = { showControlCenterSheet = false },
                onClearHistory = {
                    showClearChatDialog = true
                },
                onExportHistory = {
                    if (messages.isEmpty()) {
                        Toast.makeText(context, "এক্সপোর্ট করার মতো কোনো হিস্ট্রি নেই", Toast.LENGTH_SHORT).show()
                    } else {
                        val file = com.ayra.ai.app.data.StorageHelper.exportChatHistory(context, messages)
                        Toast.makeText(context, "চ্যাট হিস্ট্রি সফলভাবে সেভ করা হয়েছে:\n${file.name}", Toast.LENGTH_LONG).show()
                    }
                },
                onQuickFeedback = { feedback ->
                    Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (showSessionsSheet) {
            ChatSessionsSheet(
                sessions = sessions,
                activeSessionId = activeSessionId,
                onSelectSession = { sid -> viewModel.selectSession(sid) },
                onCreateNewSession = { viewModel.createNewSession() },
                onRenameSession = { sid, newTitle -> viewModel.renameSession(sid, newTitle) },
                onTogglePinSession = { sid -> viewModel.togglePinSession(sid) },
                onDeleteSession = { sid -> viewModel.deleteSession(sid) },
                onDismiss = { showSessionsSheet = false }
            )
        }

        if (showClearChatDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showClearChatDialog = false },
                title = { Text("Clear Current Conversation", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text("Are you sure you want to clear all messages in this conversation? Saved sessions can still be managed in Conversations.", color = TextSecondary) },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            scope.launch {
                                viewModel.clearCurrentSession()
                                cloudRepo?.let { repo ->
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching { repo.clearCloudMessages() }
                                    }
                                }
                                showClearChatDialog = false
                                showControlCenterSheet = false
                                Toast.makeText(context, "Conversation cleared / চ্যাট হিস্ট্রি মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Clear Chat", color = NeonRose, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showClearChatDialog = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                },
                containerColor = Color(0xFF1E293B)
            )
        }

        pendingPermissionRationale?.let { (permType, explanation) ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingPermissionRationale = null },
                title = { Text("Permission Rationale", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text(explanation, color = TextSecondary) },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            pendingPermissionRationale = null
                            when (permType) {
                                "audio_transcribe" -> audioRecordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                "voice_cmd" -> voiceCommandPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                "camera" -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.testTag("permission_rationale_continue_button")
                    ) {
                        Text("Continue", color = NeonCyan, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pendingPermissionRationale = null }) {
                        Text("Not Now", color = TextSecondary)
                    }
                },
                containerColor = Color(0xFF1E293B)
            )
        }

        flaggingMessage?.let { targetMsg ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { flaggingMessage = null },
                title = { Text("Report AI Response", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Help improve Ayra's safety and accuracy by logging this response for review (never uploaded without consent).",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = flagReasonInput,
                            onValueChange = { flagReasonInput = it },
                            label = { Text("Reason") },
                            singleLine = false,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth().testTag("flag_reason_input")
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            val snippet = targetMsg.content.take(80).replace("\n", " ")
                            com.ayra.ai.app.security.SecurityAuditLogger.log(
                                context = context,
                                category = com.ayra.ai.app.security.SecurityAuditCategory.AI_POLICY,
                                severity = com.ayra.ai.app.security.SecurityAuditSeverity.WARN,
                                message = "AI response flagged [msg=${targetMsg.id}, reason=${flagReasonInput.trim()}]: $snippet"
                            )
                            flaggingMessage = null
                            Toast.makeText(
                                context,
                                "Response flagged in Audit Log. Thank you!",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.testTag("flag_confirm_button")
                    ) {
                        Text("Submit Report", color = NeonRose, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { flaggingMessage = null }) {
                        Text("Cancel", color = TextSecondary)
                    }
                },
                containerColor = Color(0xFF1E293B)
            )
        }

        if (showKeyOnboarding) {
            val keySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { viewModel.setShowKeyOnboarding(false) },
                sheetState = keySheetState,
                containerColor = Color(0xFF0F172A),
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                    )
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔑 Connect Your AI Key",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        IconButton(onClick = { viewModel.setShowKeyOnboarding(false) }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }
                    Text(
                        text = "Add at least one free AI key (Google Gemini, OpenRouter, DeepSeek, OpenAI, or Anthropic) to activate real answers without limits.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    ProviderKeysSection(context = context)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun GlassHeaderBar(
    isListening: Boolean,
    isSpeaking: Boolean,
    currentPersona: String,
    currentSentiment: UserSentiment = UserSentiment.NEUTRAL_CARING,
    activeSessionTitle: String? = null,
    activeThinkingMode: String = "balanced",
    hasActiveWorkspaceContext: Boolean = false,
    onMenuClick: () -> Unit = {},
    onLogoClick: () -> Unit,
    onSessionsClick: () -> Unit = {},
    onNewSessionClick: () -> Unit = {},
    onClearChat: () -> Unit,
    onStopVoice: () -> Unit,
    onCycleThinkingMode: () -> Unit = {}
) {
    val uiConfig = com.ayra.ai.app.ui.theme.LocalAyraUiConfig.current
    val infiniteTransition = rememberInfiniteTransition(label = "header_text_anim")
    val animOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (uiConfig.reducedMotion) 0f else 700f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "anim_offset"
    )

    val animatedGradient = Brush.linearGradient(
        colors = listOf(uiConfig.accentColor, uiConfig.secondaryAccentColor, NeonRose, uiConfig.accentColor),
        start = Offset(animOffset, 0f),
        end = Offset(animOffset + 350f, 150f)
    )

    val headerBg = if (uiConfig.isDark) GlassSurfaceDark else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(headerBg)
            .border(
                1.dp,
                Brush.linearGradient(
                    if (currentPersona == "GF_COMPANION")
                        listOf(NeonRose.copy(alpha = 0.6f), uiConfig.secondaryAccentColor.copy(alpha = 0.4f), uiConfig.accentColor.copy(alpha = 0.3f))
                    else
                        listOf(uiConfig.accentColor.copy(alpha = 0.6f), uiConfig.secondaryAccentColor.copy(alpha = 0.4f))
                ),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onMenuClick,
            modifier = Modifier.size(48.dp).testTag("open_workspace_drawer_button")
        ) {
            Icon(Icons.Default.Menu, contentDescription = "Open Workspace Menu", tint = uiConfig.accentColor)
        }

        // Glowing Ayra Indicator / Control Center Button
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        if (currentPersona == "GF_COMPANION")
                            listOf(NeonRose, uiConfig.secondaryAccentColor, Color.Black)
                        else
                            listOf(uiConfig.accentColor, uiConfig.secondaryAccentColor, Color.Black)
                    )
                )
                .clickable { onLogoClick() }
                .testTag("ayra_logo_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Ayra Control Center & Settings",
                tint = Color.Black,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onLogoClick() }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "Ayra AI",
                    style = MaterialTheme.typography.titleMedium.copy(
                        brush = animatedGradient
                    ),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(uiConfig.accentColor.copy(alpha = 0.14f))
                        .border(0.8.dp, uiConfig.accentColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .clickable { onCycleThinkingMode() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("header_thinking_mode_chip")
                ) {
                    Text(
                        text = (if (hasActiveWorkspaceContext) "● " else "") + activeThinkingMode.replace("_", " "),
                        color = uiConfig.accentColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = when {
                    isListening -> "Listening... 🎙️ Speak command"
                    isSpeaking -> if (currentPersona == "GF_COMPANION") {
                        "Speaking • ${currentSentiment.emoji} ${currentSentiment.displayName} 🔊"
                    } else {
                        "Speaking (Clear Voice) 🔊"
                    }
                    !activeSessionTitle.isNullOrBlank() -> activeSessionTitle
                    currentPersona == "GF_COMPANION" -> "${currentSentiment.emoji} ${currentSentiment.bengaliName} Voice • Ready"
                    else -> "Voice Trigger Ready (Say 'Ayra')"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }

        if (isSpeaking) {
            IconButton(onClick = onStopVoice, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Mute voice", tint = NeonRose)
            }
        }

        IconButton(
            onClick = onNewSessionClick,
            modifier = Modifier.size(48.dp).testTag("new_chat_session_button")
        ) {
            Icon(Icons.Default.AddComment, contentDescription = "New conversation", tint = uiConfig.secondaryAccentColor)
        }

        IconButton(onClick = onClearChat, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Clear Chat", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GlassMessageBubble(
    message: ChatMessage,
    onImageClick: (String) -> Unit,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onEdit: (String) -> Unit,
    onRegenerate: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    onFlag: (() -> Unit)? = null,
    onRunPluginPrompt: (String) -> Unit = {}
) {
    val isUser = message.role == "user"
    val context = LocalContext.current
    val uiConfig = com.ayra.ai.app.ui.theme.LocalAyraUiConfig.current
    val bubbleShape = RoundedCornerShape(uiConfig.bubbleCornerDp.dp)
    val bubbleBg = when {
        uiConfig.isDark && isUser -> UserBubble
        uiConfig.isDark -> GlassSurfaceDark
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.84f else 1f)
                .clip(bubbleShape)
                .background(bubbleBg)
                .border(
                    1.1.dp,
                    if (isUser) {
                        Brush.linearGradient(listOf(uiConfig.accentColor.copy(alpha = 0.5f), uiConfig.accentColor.copy(alpha = 0.2f)))
                    } else {
                        Brush.linearGradient(listOf(uiConfig.accentColor.copy(alpha = 0.65f), uiConfig.secondaryAccentColor.copy(alpha = 0.45f), NeonRose.copy(alpha = 0.45f)))
                    },
                    bubbleShape
                )
                .padding(uiConfig.bubblePaddingDp)
        ) {
            Column {
                // Sender label & Auto-Selected AI Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isUser) NeonCyan else NeonRose)
                        )
                        Text(
                            text = if (isUser) "You" else "Ayra AI",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isUser) NeonCyan else NeonRose
                        )
                    }

                    // Auto-Selected Model Indicator
                    if (!isUser && !message.actionCommand.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF0F172A))
                                .border(0.8.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "🤖 ${message.actionCommand}",
                                color = NeonCyan,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Attached / Generated Image Preview
                if (!message.attachedImagePath.isNullOrBlank()) {
                    val resolvedImageModel = remember(message.attachedImagePath) {
                        com.ayra.ai.app.data.StorageHelper.resolveCoilImageModel(context, message.attachedImagePath)
                    }
                    AsyncImage(
                        model = resolvedImageModel,
                        contentDescription = "Image preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable { onImageClick(message.attachedImagePath) },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Generative Media: Photo (SubcomposeAsyncImage with local/vault path support & tap-to-retry)
                if (message.craftType == "IMAGE" && !message.craftPayload.isNullOrBlank()) {
                    var imgRetryTick by remember(message.craftPayload) { mutableStateOf(0) }
                    val rawCraftPayload = message.craftPayload
                    val isRemoteImageUrl = rawCraftPayload.startsWith("http://", ignoreCase = true) ||
                        rawCraftPayload.startsWith("https://", ignoreCase = true)
                    val activeImgModel = remember(rawCraftPayload, imgRetryTick) {
                        if (isRemoteImageUrl) {
                            if (imgRetryTick == 0) rawCraftPayload else {
                                val sep = if (rawCraftPayload.contains("?")) "&" else "?"
                                "${rawCraftPayload}${sep}retry=$imgRetryTick"
                            }
                        } else {
                            com.ayra.ai.app.data.StorageHelper.resolveCoilImageModel(context, rawCraftPayload)
                                ?: rawCraftPayload
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.2.dp, NeonCyan, RoundedCornerShape(14.dp))
                    ) {
                        SubcomposeAsyncImage(
                            model = activeImgModel,
                            contentDescription = "AI Generated Photo",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .clickable { onImageClick(message.craftPayload) },
                            contentScale = ContentScale.Crop,
                            loading = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(250.dp)
                                        .background(Color(0xFF0F172A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(32.dp),
                                            color = NeonCyan,
                                            strokeWidth = 2.5.dp
                                        )
                                        Text(
                                            text = "Crafting 8K Ultra-HD Masterpiece...",
                                            color = TextSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            },
                            error = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(250.dp)
                                        .background(Color(0xFF1E293B))
                                        .clickable { imgRetryTick++ },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Could not render image preview • Tap to retry",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.75f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "HD 8K Masterpiece • Tap to Expand 🔍",
                                color = NeonCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Generative Media: Video Player Card
                if (message.craftType == "VIDEO" && !message.craftPayload.isNullOrBlank()) {
                    VideoPlayerCard(
                        jsonPayload = message.craftPayload,
                        onExpand = onImageClick
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Generative Media: Song Player Card
                if (message.craftType == "SONG" && !message.craftPayload.isNullOrBlank()) {
                    SongPlayerCard(jsonPayload = message.craftPayload)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Generative Media: 3D CAD Hologram Model
                if (message.craftType == "3D") {
                    ThreeDModelCard(
                        craftPayload = message.craftPayload,
                        title = "3D Interactive Hologram"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Autonomous Scheduled Task / Call / Message / Reminder Banner
                if ((message.craftType == "SCHEDULED_ROUTINE" || message.craftType == "SCHEDULED_CALL" ||
                            message.craftType == "SCHEDULED_MESSAGE" || message.craftType == "SCHEDULED_REMINDER") && !message.craftPayload.isNullOrBlank()) {
                    ScheduledRoutineBannerCard(jsonPayload = message.craftPayload)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Generative PDF Download & Document Viewer Card
                if (message.craftType == "PDF_DOWNLOAD" && !message.craftPayload.isNullOrBlank()) {
                    com.ayra.ai.app.ui.components.PdfDocumentDownloadCard(filePath = message.craftPayload)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Cyber Security Terminal & Practice Console
                if (message.craftType == "CYBER_TERMINAL") {
                    com.ayra.ai.app.ui.components.CyberTerminalCard(
                        initialCommand = message.craftPayload
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // GitHub Repository Plugin / Skill / OpenAPI Card
                if (message.craftType == "GITHUB_PLUGIN" && !message.craftPayload.isNullOrBlank()) {
                    com.ayra.ai.app.ui.components.GitHubPluginCard(
                        jsonPayload = message.craftPayload,
                        onRunPluginPrompt = onRunPluginPrompt
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Text Content with Markdown Code Blocks & Syntax formatting
                MarkdownCodeContentView(
                    content = message.content,
                    onCopyCode = { codeToCopy ->
                        com.ayra.ai.app.security.AndroidHardeningHelper.copyWithSensitivityProtection(
                            context = context,
                            label = "Ayra AI Code",
                            text = codeToCopy,
                            isSensitive = true,
                            autoClearMs = if (com.ayra.ai.app.data.AyraPreferences.isClipboardAutoClearEnabled(context)) 60_000L else 0L
                        )
                        Toast.makeText(context, "Code copied securely! / কোড কপি করা হয়েছে 📋", Toast.LENGTH_SHORT).show()
                    }
                )

                // Moderate Futuristic Edit Question Button for user queries
                if (isUser) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (uiConfig.isDark) Color(0xFF0B132B).copy(alpha = 0.85f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    1.dp,
                                    Brush.linearGradient(listOf(uiConfig.accentColor.copy(alpha = 0.7f), uiConfig.secondaryAccentColor.copy(alpha = 0.5f))),
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onEdit(message.content) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .testTag("edit_question_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit Question / প্রশ্ন এডিট",
                                tint = uiConfig.accentColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Edit Question / প্রশ্ন এডিট",
                                color = uiConfig.accentColor,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Bottom Action Bar for assistant responses
                if (!isUser) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onSpeak, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Listen", tint = uiConfig.accentColor, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onEdit(message.content) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Note", tint = uiConfig.secondaryAccentColor, modifier = Modifier.size(18.dp))
                        }
                        if (onRegenerate != null) {
                            IconButton(onClick = onRegenerate, modifier = Modifier.size(48.dp).testTag("regenerate_message_button")) {
                                Icon(Icons.Default.Refresh, contentDescription = "Regenerate Response", tint = uiConfig.accentColor, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (onContinue != null) {
                            IconButton(onClick = onContinue, modifier = Modifier.size(48.dp).testTag("continue_message_button")) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Continue Response", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                            }
                        }
                        if (onFlag != null) {
                            IconButton(onClick = onFlag, modifier = Modifier.size(48.dp).testTag("flag_message_button")) {
                                Icon(Icons.Default.Flag, contentDescription = "Report AI Response", tint = NeonRose.copy(alpha = 0.85f), modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassInputRow(
    text: String,
    onTextChange: (String) -> Unit,
    isListening: Boolean,
    isRecordingAudio: Boolean = false,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAudioTranscribeClick: () -> Unit = {},
    onMicClick: () -> Unit,
    onAttachClick: () -> Unit
) {
    val uiConfig = com.ayra.ai.app.ui.theme.LocalAyraUiConfig.current
    val inputBg = if (uiConfig.isDark) GlassSurfaceDark else MaterialTheme.colorScheme.surface
    val attachBg = if (uiConfig.isDark) listOf(Color(0xFF1E1035), GlassSurfaceDark) else listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Upgraded Bottom '+' Hub Button (High Quality, Futuristic Glow, 48dp target)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(attachBg))
                .border(
                    1.4.dp,
                    Brush.linearGradient(listOf(uiConfig.accentColor, uiConfig.secondaryAccentColor, NeonRose)),
                    CircleShape
                )
                .clickable { onAttachClick() }
                .testTag("bottom_attachment_hub_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Attach & Super-App Tools Hub",
                tint = uiConfig.accentColor,
                modifier = Modifier.size(22.dp)
            )
        }

        // Text Field (High Contrast & Theme-Adaptive Input)
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = {
                Text(
                    "Ask Ayra, /wiki, generate photo, or command...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            },
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(inputBg)
                .testTag("chat_input_text_field"),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = uiConfig.accentColor,
                unfocusedBorderColor = uiConfig.accentColor.copy(alpha = 0.28f),
                cursorColor = uiConfig.accentColor,
                focusedContainerColor = inputBg,
                unfocusedContainerColor = inputBg
            ),
            shape = RoundedCornerShape(24.dp),
            maxLines = 4
        )

        // Gemini Audio Record & Transcribe Button (48dp touch target)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (isRecordingAudio) NeonRose else inputBg)
                .border(1.2.dp, if (isRecordingAudio) NeonRose else uiConfig.secondaryAccentColor, CircleShape)
                .clickable { onAudioTranscribeClick() }
                .testTag("gemini_audio_transcribe_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "AI Audio Transcribe",
                tint = if (isRecordingAudio) Color.Black else uiConfig.secondaryAccentColor,
                modifier = Modifier.size(20.dp)
            )
        }

        // Mic Button with glowing state (48dp touch target)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (isListening) NeonRose else inputBg)
                .border(1.2.dp, if (isListening) NeonRose else uiConfig.accentColor, CircleShape)
                .clickable { onMicClick() }
                .testTag("voice_mic_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = "Voice",
                tint = if (isListening) Color.Black else uiConfig.accentColor
            )
        }

        // Send / Stop Button (48dp touch target)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (isGenerating) Brush.linearGradient(listOf(Color(0xFFFF1744), Color(0xFFD50000)))
                    else Brush.linearGradient(listOf(uiConfig.accentColor, uiConfig.secondaryAccentColor))
                )
                .clickable {
                    if (isGenerating) onStop()
                    else if (text.isNotBlank()) onSend()
                }
                .testTag("chat_send_button"),
            contentAlignment = Alignment.Center
        ) {
            if (isGenerating) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Stop",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyCosmicState(
    currentPersona: String,
    onSuggestionClick: (String) -> Unit = {}
) {
    val uiConfig = com.ayra.ai.app.ui.theme.LocalAyraUiConfig.current
    val infiniteTransition = rememberInfiniteTransition(label = "empty_cosmic_anim")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (uiConfig.reducedMotion) 0f else 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    val animatedGradient = Brush.linearGradient(
        colors = listOf(
            uiConfig.accentColor,
            uiConfig.secondaryAccentColor,
            NeonRose,
            Color(0xFFFFD700),
            NeonGreen,
            uiConfig.accentColor
        ),
        start = Offset(shimmerOffset, 0f),
        end = Offset(shimmerOffset + 450f, 200f)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, start = 14.dp, end = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // Glowing animated title row with "by Alok" badge directly above galaxy
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Ayra AI",
                style = MaterialTheme.typography.headlineLarge.copy(
                    brush = animatedGradient
                ),
                fontWeight = FontWeight.Black,
                fontSize = 30.sp,
                letterSpacing = 1.2.sp
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (uiConfig.isDark) Color(0xFF0F172A).copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                    )
                    .border(
                        1.2.dp,
                        animatedGradient,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "by Alok",
                    style = MaterialTheme.typography.labelSmall.copy(
                        brush = animatedGradient
                    ),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = if (currentPersona == "GF_COMPANION") {
                "Sweet Companion & All-in-One Prompt AI"
            } else {
                "Prompt anything below — Q&A, /wiki, Code, Images, Research, PDF & Voice"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AssistantGeneratingIndicator(
    statusText: String? = null,
    streamingText: String? = null
) {
    val uiConfig = com.ayra.ai.app.ui.theme.LocalAyraUiConfig.current
    val bgColor = if (uiConfig.isDark) GlassSurfaceDark else MaterialTheme.colorScheme.surface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(uiConfig.bubbleCornerDp.dp))
            .background(bgColor)
            .border(1.dp, uiConfig.accentColor.copy(alpha = 0.5f), RoundedCornerShape(uiConfig.bubbleCornerDp.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = uiConfig.accentColor
            )
            Text(
                text = statusText ?: "Ayra is thinking & synthesizing...",
                style = MaterialTheme.typography.bodySmall,
                color = uiConfig.accentColor,
                fontWeight = FontWeight.Medium
            )
        }

        if (!streamingText.isNullOrBlank()) {
            Text(
                text = streamingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun ScheduledRoutineBannerCard(jsonPayload: String) {
    val data = remember(jsonPayload) {
        try {
            JSONObject(jsonPayload)
        } catch (_: Exception) {
            null
        }
    }
    val taskType = data?.optString("taskType", "ROUTINE") ?: "ROUTINE"
    val recipient = data?.optString("recipient", "Friend") ?: "Friend"
    val targetApp = data?.optString("targetApp", "WhatsApp") ?: "WhatsApp"
    val time = data?.optString("time", "12:00 AM") ?: "12:00 AM"
    val phone = data?.optString("phone", "") ?: ""
    val spokenMessage = data?.optString("spokenMessage", "") ?: ""
    val reminder = data?.optString("reminder", "") ?: ""
    val message = data?.optString("message", "") ?: ""

    val isCall = taskType == "CALL" || targetApp.equals("CALL", ignoreCase = true)
    val isReminder = taskType == "REMINDER" || targetApp.equals("REMINDER", ignoreCase = true)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    if (isCall) listOf(Color(0xFF0F172A), Color(0xFF1E1B4B))
                    else if (isReminder) listOf(Color(0xFF2E1065), Color(0xFF0F172A))
                    else listOf(Color(0xFF0D2520), Color(0xFF0A1526))
                )
            )
            .border(
                1.2.dp,
                Brush.linearGradient(
                    if (isCall) listOf(NeonCyan, NeonPurple)
                    else if (isReminder) listOf(Color(0xFFFFD700), NeonRose)
                    else listOf(NeonGreen, NeonCyan)
                ),
                RoundedCornerShape(14.dp)
            )
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isCall) NeonCyan else if (isReminder) Color(0xFFFFD700) else NeonGreen)
                    )
                    Text(
                        text = if (isCall) "VOICE CALL ARMED" else if (isReminder) "REMINDER ARMED" else "SCHEDULED MESSAGE ARMED",
                        color = if (isCall) NeonCyan else if (isReminder) Color(0xFFFFD700) else NeonGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(NeonCyan.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isCall) "PHONE CALL" else if (isReminder) "REMINDER" else targetApp.uppercase(),
                        color = NeonCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isCall) {
                Text(
                    text = "📞 কল প্রাপক: $recipient ${if (phone.isNotBlank()) "($phone)" else ""} • সময়: $time",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                if (spokenMessage.isNotBlank()) {
                    Text(
                        text = "🎙️ কলে যে বার্তাটি বলবে: \"$spokenMessage\"",
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "🛡️ মোবাইল স্লিপ বা লক থাকলেও AlarmManager অ্যালার্ম বাজিয়ে বার্তাটি মনে করিয়ে দেবে এবং এক-ট্যাপ ডায়ালার (ACTION_DIAL) নোটিফিকেশন দেখাবে।",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            } else if (isReminder) {
                Text(
                    text = "⏰ রিমাইন্ডার সময়: $time",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                if (reminder.isNotBlank()) {
                    Text(
                        text = "📌 কাজ: \"$reminder\"",
                        color = Color(0xFFFFD700),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "🔔 নির্ধারিত সময়ে রিংটোন বাজিয়ে এবং ভয়েস অ্যাসিস্ট্যান্টের মাধ্যমে স্পষ্ট কণ্ঠে আপনাকে কাজটি মনে করিয়ে দেওয়া হবে।",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            } else {
                Text(
                    text = "🎯 প্রাপক: $recipient ${if (phone.isNotBlank()) "($phone)" else ""} • সময়: $time",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                if (message.isNotBlank()) {
                    Text(
                        text = "✉️ বার্তা: \"$message\"",
                        color = NeonGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "🔔 নির্ধারিত সময়ে AlarmManager আপনাকে মনে করিয়ে দেবে এবং এক-ট্যাপে মেসেজ রিভিউ ও পাঠানোর নোটিফিকেশন দেখাবে।",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
fun MarkdownCodeContentView(
    content: String,
    onCopyCode: (String) -> Unit
) {
    com.ayra.ai.app.ui.components.RichMarkdownView(
        content = content,
        onCopyCode = onCopyCode
    )
}


