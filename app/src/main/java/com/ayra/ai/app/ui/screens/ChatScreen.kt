package com.ayra.ai.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayra.ai.app.AyraApplication
import com.ayra.ai.app.ai.AyraOrchestrator
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.UnifiedAiEngine
import com.ayra.ai.app.ai.plugins.GitHubPluginRegistry
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.audio.AudioCaptureManager
import com.ayra.ai.app.automation.AyraTaskScheduler
import com.ayra.ai.app.automation.DeviceAutomationController
import com.ayra.ai.app.automation.ProposedAction
import com.ayra.ai.app.cloud.AyraCloudRepository
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDao
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.ChatRepository
import com.ayra.ai.app.data.StorageHelper
import com.ayra.ai.app.security.AndroidHardeningHelper
import com.ayra.ai.app.ui.components.ActionConfirmationCard
import com.ayra.ai.app.ui.components.AmbientCornerGlow
import com.ayra.ai.app.ui.components.SolarSystem3DCanvas
import com.ayra.ai.app.ui.components.VoiceCommandOverlay
import com.ayra.ai.app.ui.components.chat.AssistantGeneratingIndicator
import com.ayra.ai.app.ui.components.chat.ChatAttachmentPreviewBanners
import com.ayra.ai.app.ui.components.chat.ChatOverlaysHost
import com.ayra.ai.app.ui.components.chat.ChatPendingActionHost
import com.ayra.ai.app.ui.components.chat.ChatScreenMediaHelper
import com.ayra.ai.app.ui.components.chat.EmptyCosmicState
import com.ayra.ai.app.ui.components.chat.GlassHeaderBar
import com.ayra.ai.app.ui.components.chat.GlassInputRow
import com.ayra.ai.app.ui.components.chat.GlassMessageBubble
import com.ayra.ai.app.ui.theme.CosmicBackground
import com.ayra.ai.app.ui.viewmodel.ChatViewModel
import com.ayra.ai.app.voice.CrisisDetector
import com.ayra.ai.app.voice.SentimentDetector
import com.ayra.ai.app.voice.VoiceAssistantManager
import com.ayra.ai.app.voice.VoiceUiState
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatDao: ChatDao,
    viewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    currentUser: FirebaseUser? = null,
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
        ChatRepository(context = context.applicationContext, chatDao = chatDao, sessionDao = db.chatSessionDao())
    }
    LaunchedEffect(chatRepository) { viewModel.attachRepository(chatRepository) }

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSessionId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()

    val automationController = remember { DeviceAutomationController(context) }
    val orchestrator = remember { AyraOrchestrator(context) }
    val aiEngine = remember { UnifiedAiEngine(context) }
    val firebaseAiEngine = remember { FirebaseAiEngine(context) }
    val externalAiProviders = remember { ExternalAiProviders(context) }
    val audioCaptureManager = remember { AudioCaptureManager(context) }
    val cloudRepo = remember(currentUser?.uid) {
        if (currentUser != null && FirebaseAiEngine.isAvailable(context)) {
            runCatching { AyraCloudRepository.create(context) }.getOrNull()
        } else null
    }
    val taskScheduler = remember { AyraTaskScheduler(context) }
    val scheduledTasks by db.scheduledTaskDao().getAllTasks().collectAsStateWithLifecycle(initialValue = emptyList())

    val currentPersona by viewModel.currentPersona.collectAsStateWithLifecycle()
    val selectedModelTier by viewModel.selectedModelTier.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val thinkingStatus by viewModel.thinkingStatus.collectAsStateWithLifecycle()
    val streamingAnswer by viewModel.streamingAnswer.collectAsStateWithLifecycle()
    val agentPlanSteps by viewModel.agentPlanSteps.collectAsStateWithLifecycle()
    val activeCitations by viewModel.activeCitations.collectAsStateWithLifecycle()
    val pendingMemoryCandidate by viewModel.pendingMemoryCandidate.collectAsStateWithLifecycle()
    val showKeyOnboarding by viewModel.showKeyOnboarding.collectAsStateWithLifecycle()
    val pendingAction by viewModel.pendingAction.collectAsStateWithLifecycle()

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDocumentUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDocumentName by remember { mutableStateOf<String?>(null) }
    var selectedDocumentText by remember { mutableStateOf<String?>(null) }
    var selectedDocumentBase64Image by remember { mutableStateOf<String?>(null) }

    var showClearChatDialog by remember { mutableStateOf(false) }
    var showAttachmentPicker by remember { mutableStateOf(false) }
    var showControlCenterSheet by remember { mutableStateOf(false) }
    var showSessionsSheet by remember { mutableStateOf(false) }
    var isFallbackVoiceMode by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var expandedImageUri by remember { mutableStateOf<String?>(null) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    var isTranscribingAudio by remember { mutableStateOf(false) }

    var showExactAlarmWarningBanner by remember { mutableStateOf(false) }
    var exactAlarmBannerDismissed by remember { mutableStateOf(false) }
    var rationaleTitle by remember { mutableStateOf<String?>(null) }
    var rationaleBody by remember { mutableStateOf("") }
    var rationaleActionLabel by remember { mutableStateOf("Continue") }
    var rationaleOnConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }

    val isRecordingAudio by audioCaptureManager.isRecording.collectAsStateWithLifecycle()
    val audioAmplitude by audioCaptureManager.amplitudeLevel.collectAsStateWithLifecycle()
    val audioSeconds by audioCaptureManager.elapsedSeconds.collectAsStateWithLifecycle()
    var dispatchPromptRef by remember { mutableStateOf<((String) -> Unit)?>(null) }

    LaunchedEffect(Unit) {
        val (savedPersona, savedTier) = withContext(Dispatchers.IO) {
            AyraApplication.ensureActiveAiKeysSeeded(context)
            AyraPreferences.getSavedPersona(context) to AyraPreferences.getSavedModelTier(context)
        }
        viewModel.setPersona(savedPersona)
        viewModel.setModelTier(savedTier)
        aiEngine.currentPersona = savedPersona
        aiEngine.selectedModelTier = savedTier
    }
    LaunchedEffect(currentPersona) { aiEngine.currentPersona = currentPersona }
    LaunchedEffect(selectedModelTier) { aiEngine.selectedModelTier = selectedModelTier }

    LaunchedEffect(currentUser?.uid) {
        val repo = cloudRepo ?: return@LaunchedEffect
        val user = currentUser ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                repo.upsertUserProfile(
                    displayName = user.displayName ?: "",
                    email = user.email ?: "",
                    photoUrl = user.photoUrl?.toString() ?: "",
                    preferredPersona = currentPersona,
                    preferredModelTier = selectedModelTier
                )
            }
            GitHubPluginRegistry.reconcileWithCloud(context, repo)
            val currentSessionId = viewModel.ensureSessionReady()
            if (chatRepository.getRecentMessages(currentSessionId, 1).isEmpty()) {
                repo.fetchCloudMessagesOnce(100).forEach { chatRepository.appendMessage(currentSessionId, it) }
            }
        }
    }

    val voiceManager = remember {
        VoiceAssistantManager(context) { recognizedCommand ->
            if (recognizedCommand.isNotBlank()) {
                viewModel.updateInputText(recognizedCommand)
                dispatchPromptRef?.invoke(recognizedCommand)
            }
        }.apply {
            personaMode = currentPersona
            onSpeechTranscribed = { transcribedText, _ -> viewModel.updateInputText(transcribedText) }
            onWakeWordTriggered = { greeting ->
                scope.launch { viewModel.persistMessage(ChatMessage(role = "assistant", content = greeting)) }
            }
        }
    }

    val isListening by voiceManager.isListening.collectAsStateWithLifecycle()
    val isSpeaking by voiceManager.isSpeaking.collectAsStateWithLifecycle()
    val currentSentiment by voiceManager.currentSentiment.collectAsStateWithLifecycle()
    val voiceState by voiceManager.voiceState.collectAsStateWithLifecycle()
    val liveSpokenText by voiceManager.liveSpokenText.collectAsStateWithLifecycle()
    val rmsLevel by voiceManager.rmsLevel.collectAsStateWithLifecycle()
    val selectedVoiceLang by voiceManager.selectedLanguageTag.collectAsStateWithLifecycle()

    val startVoiceCommandSession: () -> Unit = {
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
            isFallbackVoiceMode = false
            voiceManager.onSpeechTranscribed = { transcribedText, _ -> viewModel.updateInputText(transcribedText) }
            voiceManager.startManualVoiceCommand()
        } else {
            isFallbackVoiceMode = true
            audioCaptureManager.startRecording().onFailure { err ->
                isFallbackVoiceMode = false
                Toast.makeText(context, "Voice microphone error: ${err.localizedMessage ?: "Unavailable"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(voiceManager, audioCaptureManager) {
        onDispose {
            voiceManager.destroy()
            audioCaptureManager.destroy()
        }
    }

    val voiceCommandPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceCommandSession()
        else Toast.makeText(context, "Microphone permission is required for real-time voice commands.", Toast.LENGTH_LONG).show()
    }
    val audioRecordPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            audioCaptureManager.startRecording().onFailure { err ->
                Toast.makeText(context, "Audio capture error: ${err.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Microphone permission is required for AI audio transcription.", Toast.LENGTH_SHORT).show()
        }
    }
    val singlePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(context, if (granted) "Permission granted." else "Permission denied. Enable it in Android Settings when needed.", Toast.LENGTH_SHORT).show()
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            dispatchPromptRef?.invoke("Where am I right now? GPS Coordinates")
        } else {
            Toast.makeText(context, "Location permission is required to read current coordinates.", Toast.LENGTH_LONG).show()
        }
    }
    val contactsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val (updatedAction, feedback) = ChatScreenMediaHelper.resolvePendingContact(context, viewModel.pendingAction.value)
            if (updatedAction != null) viewModel.setPendingAction(updatedAction)
            Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Contacts permission was not granted. You can still confirm to open the dialer/SMS app.", Toast.LENGTH_LONG).show()
        }
    }

    fun requestPermissionWithRationale(permission: String, onLaunch: () -> Unit) {
        val (title, body, buttonText) = ChatScreenMediaHelper.permissionRationaleFor(permission)
        rationaleTitle = title
        rationaleBody = body
        rationaleActionLabel = buttonText
        rationaleOnConfirm = onLaunch
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    StorageHelper.saveMediaToInternalStorage(context, uri, "gallery_img")?.let { Uri.fromFile(it) } ?: uri
                }
                selectedImageUri = resolved
            }
        }
    }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedDocumentUri = uri
            scope.launch {
                val parsed = withContext(Dispatchers.IO) { ChatScreenMediaHelper.processSelectedDocument(context, uri, db) }
                selectedDocumentName = parsed.name
                selectedDocumentText = parsed.extractedText
                selectedDocumentBase64Image = parsed.firstPageBase64
            }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val captureUri = tempCameraUri
        if (success && captureUri != null) {
            scope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    StorageHelper.saveMediaToInternalStorage(context, captureUri, "cam_img")?.let { Uri.fromFile(it) } ?: captureUri
                }
                selectedImageUri = resolved
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            ChatScreenMediaHelper.createCameraCaptureUri(context)
                .onSuccess { uri ->
                    tempCameraUri = uri
                    cameraLauncher.launch(uri)
                }
                .onFailure { e ->
                    Toast.makeText(context, "Camera launch failed: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(context, "Camera permission is required to capture photos", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun transcribeAudioAndHandle(recordedFile: File?, submitImmediately: Boolean) {
        if (recordedFile == null) return
        isTranscribingAudio = true
        scope.launch {
            val res = ChatScreenMediaHelper.transcribeRecordedAudio(firebaseAiEngine, externalAiProviders, recordedFile)
            isTranscribingAudio = false
            res.onSuccess { transcript ->
                if (transcript.isNotBlank()) {
                    if (submitImmediately) {
                        viewModel.updateInputText(transcript)
                        dispatchPromptRef?.invoke(transcript)
                    } else {
                        viewModel.updateInputText(if (inputText.isBlank()) transcript else "$inputText $transcript")
                    }
                }
            }.onFailure { err ->
                Toast.makeText(context, err.localizedMessage ?: "Audio transcription failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun dispatchPrompt(query: String) {
        val rawTrimmed = query.trim()
        val trimmed = when {
            rawTrimmed.isNotBlank() -> rawTrimmed
            selectedDocumentUri != null -> "Please analyze this document thoroughly, extract all key details, summarize its contents, and provide actionable insights."
            selectedImageUri != null -> "Please inspect this photo in detail, identify all subjects and objects, read visible text, and explain the context."
            else -> ""
        }
        if (trimmed.isBlank() && selectedImageUri == null && selectedDocumentUri == null) return

        val hasCloudSensitiveAttachment = selectedImageUri != null || selectedDocumentUri != null
        val canAnalyzeAttachmentBuiltInOrConsented = FirebaseAiEngine.isAvailable(context) ||
            AyraPreferences.isLocalOnlyAiModeEnabled(context) ||
            AyraPreferences.hasExternalAiConsent(context) ||
            externalAiProviders.hasAnyConfiguredProvider()
        if (hasCloudSensitiveAttachment && !canAnalyzeAttachmentBuiltInOrConsented) {
            rationaleTitle = "Cloud AI Consent Required for Attachments"
            rationaleBody = "You attached a photo or document while built-in Firebase AI is offline and External Cloud AI consent is disabled. Allow External Cloud AI to analyze it, or enable Local-Only mode in Security Center."
            rationaleActionLabel = "Allow Cloud AI"
            rationaleOnConfirm = {
                AyraPreferences.setExternalAiConsent(context, true)
                dispatchPrompt(query)
            }
            return
        }

        val attachedUriStr = selectedImageUri?.toString()
        val docText = selectedDocumentText
        val docName = selectedDocumentName
        val docBase64Image = selectedDocumentBase64Image

        scope.launch {
            if (!viewModel.tryStartSending()) return@launch
            selectedImageUri = null; selectedDocumentUri = null; selectedDocumentText = null
            selectedDocumentName = null; selectedDocumentBase64Image = null
            viewModel.clearInput()

            val detectedSentiment = voiceManager.detectAndApplySentiment(trimmed)
            val userContent = if (!docName.isNullOrBlank()) {
                if (rawTrimmed.isNotBlank()) "📄 [$docName]\n$rawTrimmed" else "📄 [$docName]\nPlease analyze this document."
            } else trimmed

            val crisisRegion = CrisisDetector.CrisisRegion.fromCode(AyraPreferences.getCrisisRegion(context))
            val crisis = CrisisDetector.evaluate(trimmed, crisisRegion)
            if (crisis.isCrisis && crisis.supportiveMessage != null) {
                viewModel.persistMessage(ChatMessage(role = "user", content = userContent, attachedImagePath = attachedUriStr))
                viewModel.persistMessage(ChatMessage(role = "assistant", content = crisis.supportiveMessage))
                voiceManager.speak("I hear you and I care about your safety. Free confidential help is available right now. Please reach out to someone you trust.")
                viewModel.finishSending()
                return@launch
            }

            val proposed = automationController.proposeAction(trimmed)
            if (proposed != null) {
                viewModel.persistMessage(ChatMessage(role = "user", content = userContent, attachedImagePath = attachedUriStr))
                viewModel.setPendingAction(proposed)
                viewModel.finishSending()
                return@launch
            }

            val userMsg = ChatMessage(role = "user", content = userContent, attachedImagePath = attachedUriStr)
            val insertedUserId = viewModel.persistMessage(userMsg)
            cloudRepo?.let { repo ->
                scope.launch(Dispatchers.IO) { runCatching { repo.syncMessageToCloud(userMsg.copy(id = insertedUserId)) } }
            }

            if (taskScheduler.isSchedulingIntent(trimmed)) {
                val scheduleResult = taskScheduler.scheduleTaskFromPrompt(trimmed)
                if (scheduleResult.executed) {
                    viewModel.finishSending()
                    if (runCatching { JSONObject(scheduleResult.extraData.orEmpty()).optBoolean("needsExactPermissionPrompt", false) }.getOrDefault(false)) {
                        showExactAlarmWarningBanner = true
                        exactAlarmBannerDismissed = false
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissionWithRationale(Manifest.permission.POST_NOTIFICATIONS) {
                            singlePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    viewModel.persistMessage(
                        ChatMessage(role = "assistant", content = scheduleResult.feedbackMessage, craftType = scheduleResult.actionType, craftPayload = scheduleResult.extraData)
                    )
                    voiceManager.speak(scheduleResult.feedbackMessage, overrideSentiment = detectedSentiment)
                    return@launch
                }
            }

            val autoResult = automationController.tryExecuteAutomation(trimmed)
            if (autoResult != null && autoResult.executed) {
                viewModel.finishSending()
                viewModel.persistMessage(
                    ChatMessage(role = "assistant", content = autoResult.feedbackMessage, craftType = autoResult.actionType, craftPayload = autoResult.extraData)
                )
                voiceManager.speak(autoResult.feedbackMessage, overrideSentiment = detectedSentiment)
                return@launch
            }

            try {
                val (base64Image, history) = withContext(Dispatchers.IO) {
                    ChatScreenMediaHelper.loadAttachmentBase64(context, attachedUriStr, docBase64Image) to viewModel.getRecentHistory(15)
                }

                viewModel.inspectCandidateMemory(context, trimmed)
                viewModel.launchOrchestratedInference(
                    orchestrator = orchestrator,
                    prompt = trimmed,
                    persona = currentPersona,
                    tier = selectedModelTier,
                    history = history,
                    imageBase64 = base64Image,
                    documentText = docText,
                    documentName = docName,
                    cloudRepo = cloudRepo,
                    onSpeakResponse = { reply -> voiceManager.speak(reply, overrideSentiment = detectedSentiment) }
                )
            } catch (e: Exception) {
                val err = "Error processing request: ${e.message}"
                viewModel.persistMessage(ChatMessage(role = "assistant", content = err))
                viewModel.finishSending(err)
            }
        }
    }

    LaunchedEffect(Unit) { dispatchPromptRef = { prompt: String -> dispatchPrompt(prompt) } }

    Box(modifier = modifier.fillMaxSize().background(CosmicBackground)) {
        SolarSystem3DCanvas(modifier = Modifier.fillMaxSize().graphicsLayer { })
        AmbientCornerGlow(modifier = Modifier.fillMaxSize().graphicsLayer { })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .then(if (embeddedInWorkspace) Modifier else Modifier.navigationBarsPadding())
        ) {
            GlassHeaderBar(
                isListening = isListening,
                isSpeaking = isSpeaking,
                currentPersona = currentPersona,
                currentSentiment = currentSentiment,
                activeSessionTitle = activeSession?.title,
                onOpenDrawer = onOpenDrawer,
                onLogoClick = { showControlCenterSheet = true },
                onSessionsClick = { showSessionsSheet = true },
                onNewSessionClick = { viewModel.createNewSession() },
                onClearChat = { showClearChatDialog = true },
                onStopVoice = { voiceManager.stopSpeaking() }
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    EmptyCosmicState(
                        currentPersona = currentPersona,
                        readinessRefreshKey = Pair(showControlCenterSheet, showKeyOnboarding),
                        onOpenSetup = { showControlCenterSheet = true }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            GlassMessageBubble(
                                message = msg,
                                onImageClick = { expandedImageUri = it },
                                onCopy = {
                                    AndroidHardeningHelper.copyWithSensitivityProtection(
                                        context = context,
                                        label = "Ayra AI Message",
                                        text = msg.content,
                                        isSensitive = true,
                                        autoClearMs = if (AyraPreferences.isClipboardAutoClearEnabled(context)) 60_000L else 0L
                                    )
                                    Toast.makeText(context, "Copied (auto-clears securely)!", Toast.LENGTH_SHORT).show()
                                },
                                onSpeak = { voiceManager.speak(msg.content, overrideSentiment = SentimentDetector.detectSentiment(msg.content)) },
                                onEdit = { contentToEdit ->
                                    viewModel.updateInputText(contentToEdit)
                                    Toast.makeText(context, "Loaded into editor to edit / এডিট করতে লোড হয়েছে", Toast.LENGTH_SHORT).show()
                                },
                                onRunPluginPrompt = { dispatchPrompt(it) }
                            )
                        }
                        if (isGenerating) {
                            item {
                                AssistantGeneratingIndicator(
                                    statusText = thinkingStatus,
                                    streamingText = streamingAnswer,
                                    planSteps = agentPlanSteps,
                                    citations = activeCitations
                                )
                            }
                        }
                    }
                }
            }

            val hasPendingScheduledTasks = remember(scheduledTasks) { scheduledTasks.any { it.status == "PENDING" } }
            val exactAlarmBannerVisible = !exactAlarmBannerDismissed &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !taskScheduler.canScheduleExactAlarms() &&
                (showExactAlarmWarningBanner || hasPendingScheduledTasks)

            val showContactsInlineBanner = remember(pendingAction, context) {
                ChatScreenMediaHelper.shouldShowContactsBanner(context, pendingAction)
            }

            ChatAttachmentPreviewBanners(
                selectedImageUri = selectedImageUri,
                onClearImage = { selectedImageUri = null },
                selectedDocumentName = selectedDocumentName,
                onClearDocument = {
                    selectedDocumentUri = null; selectedDocumentName = null
                    selectedDocumentText = null; selectedDocumentBase64Image = null
                },
                pendingMemoryCandidate = pendingMemoryCandidate,
                onDismissMemoryCandidate = { viewModel.dismissPendingMemorySave() },
                onConfirmMemoryCandidate = {
                    viewModel.confirmPendingMemorySave(context) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                },
                exactAlarmBannerVisible = exactAlarmBannerVisible,
                onDismissExactAlarmBanner = { exactAlarmBannerDismissed = true },
                onOpenExactAlarmSettings = { runCatching { context.startActivity(taskScheduler.buildExactAlarmSettingsIntent()) } },
                showContactsInlineBanner = showContactsInlineBanner,
                onAllowContactsFromBanner = {
                    requestPermissionWithRationale(Manifest.permission.READ_CONTACTS) {
                        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                },
                isRecordingAudio = isRecordingAudio,
                isFallbackVoiceMode = isFallbackVoiceMode,
                isTranscribingAudio = isTranscribingAudio,
                audioSeconds = audioSeconds,
                audioAmplitude = audioAmplitude,
                onCancelAudioRecording = { audioCaptureManager.cancelRecording() }
            )

            ChatPendingActionHost(
                context = context,
                scope = scope,
                pendingAction = pendingAction,
                voiceManager = voiceManager,
                isFallbackVoiceMode = isFallbackVoiceMode,
                automationController = automationController,
                viewModel = viewModel
            )

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
                        transcribeAudioAndHandle(recordedFile, submitImmediately = true)
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
                    if (transcript.isNotBlank()) viewModel.updateInputText(transcript)
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

            GlassInputRow(
                text = inputText,
                onTextChange = { viewModel.updateInputText(it) },
                isListening = isListening,
                isRecordingAudio = isRecordingAudio,
                isTranscribingAudio = isTranscribingAudio,
                canSendWithAttachment = selectedImageUri != null || selectedDocumentUri != null,
                isGenerating = isGenerating,
                onSend = { dispatchPrompt(inputText) },
                onStop = { viewModel.stopGeneration() },
                onAudioTranscribeClick = {
                    val hasRecord = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (!hasRecord) {
                        requestPermissionWithRationale(Manifest.permission.RECORD_AUDIO) {
                            audioRecordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else if (!isRecordingAudio) {
                        audioCaptureManager.startRecording().onFailure { err ->
                            Toast.makeText(context, "Could not start recording: ${err.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        transcribeAudioAndHandle(audioCaptureManager.stopRecording(), submitImmediately = false)
                    }
                },
                onMicClick = {
                    val hasRecord = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    when {
                        !hasRecord -> requestPermissionWithRationale(Manifest.permission.RECORD_AUDIO) {
                            voiceCommandPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        isFallbackVoiceMode && isRecordingAudio -> {
                            audioCaptureManager.cancelRecording()
                            isFallbackVoiceMode = false
                        }
                        isListening -> voiceManager.finishAndSubmitManualListening()
                        else -> startVoiceCommandSession()
                    }
                },
                onAttachClick = { showAttachmentPicker = true }
            )
        }

        ChatOverlaysHost(
            context = context,
            scope = scope,
            viewModel = viewModel,
            db = db,
            aiEngine = aiEngine,
            voiceManager = voiceManager,
            automationController = automationController,
            taskScheduler = taskScheduler,
            cloudRepo = cloudRepo,
            currentUser = currentUser,
            sheetState = sheetState,
            messages = messages,
            sessions = sessions,
            activeSessionId = activeSessionId,
            scheduledTasks = scheduledTasks,
            currentPersona = currentPersona,
            selectedModelTier = selectedModelTier,
            currentSentiment = currentSentiment,
            expandedImageUri = expandedImageUri,
            onDismissExpandedImage = { expandedImageUri = null },
            showAttachmentPicker = showAttachmentPicker,
            onDismissAttachmentPicker = { showAttachmentPicker = false },
            onTakePhoto = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    ChatScreenMediaHelper.createCameraCaptureUri(context)
                        .onSuccess { uri ->
                            tempCameraUri = uri
                            cameraLauncher.launch(uri)
                        }
                        .onFailure { e ->
                            Toast.makeText(context, "Camera launch failed: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    requestPermissionWithRationale(Manifest.permission.CAMERA) {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            },
            onPickGallery = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onPickDocument = { documentLauncher.launch(ChatScreenMediaHelper.SUPPORTED_DOCUMENT_MIME_TYPES) },
            onOpenControlCenter = { showControlCenterSheet = true },
            onShareGpsLocation = {
                val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (hasFine || hasCoarse) {
                    dispatchPrompt("Where am I right now? GPS Coordinates")
                } else {
                    requestPermissionWithRationale(Manifest.permission.ACCESS_FINE_LOCATION) {
                        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
            },
            onDispatchPromptImmediately = { prompt ->
                viewModel.updateInputText(prompt)
                dispatchPromptRef?.invoke(prompt)
            },
            rationaleTitle = rationaleTitle,
            rationaleBody = rationaleBody,
            rationaleActionLabel = rationaleActionLabel,
            onConfirmRationale = {
                val action = rationaleOnConfirm
                rationaleTitle = null; rationaleOnConfirm = null
                action?.invoke()
            },
            onDismissRationale = { rationaleTitle = null; rationaleOnConfirm = null },
            showControlCenterSheet = showControlCenterSheet,
            onDismissControlCenter = { showControlCenterSheet = false },
            onRequestSinglePermission = { permission ->
                requestPermissionWithRationale(permission) {
                    if (permission == Manifest.permission.ACCESS_FINE_LOCATION) {
                        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } else {
                        singlePermissionLauncher.launch(permission)
                    }
                }
            },
            onSignOut = { if (currentUser != null) onSignOut() },
            onOpenSecurityCenter = onOpenSecurityCenter,
            onShowClearChatDialog = { showClearChatDialog = true },
            showSessionsSheet = showSessionsSheet,
            onDismissSessionsSheet = { showSessionsSheet = false },
            showClearChatDialog = showClearChatDialog,
            onDismissClearChatDialog = { showClearChatDialog = false },
            showKeyOnboarding = showKeyOnboarding
        )
    }
}
