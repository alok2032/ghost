package com.ayra.ai.app.ui.components.chat

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import com.ayra.ai.app.ai.UnifiedAiEngine
import com.ayra.ai.app.ai.providers.ModelTier
import com.ayra.ai.app.automation.AyraTaskScheduler
import com.ayra.ai.app.automation.DeviceAutomationController
import com.ayra.ai.app.cloud.AyraCloudRepository
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.ChatSession
import com.ayra.ai.app.data.ScheduledTask
import com.ayra.ai.app.data.StorageHelper
import com.ayra.ai.app.security.AndroidHardeningHelper
import com.ayra.ai.app.ui.components.ChatSessionsSheet
import com.ayra.ai.app.ui.components.ControlCenterSheet
import com.ayra.ai.app.ui.viewmodel.ChatViewModel
import com.ayra.ai.app.voice.UserSentiment
import com.ayra.ai.app.voice.VoiceAssistantManager
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatOverlaysHost(
    context: Context,
    scope: CoroutineScope,
    viewModel: ChatViewModel,
    db: ChatDatabase,
    aiEngine: UnifiedAiEngine,
    voiceManager: VoiceAssistantManager,
    automationController: DeviceAutomationController,
    taskScheduler: AyraTaskScheduler,
    cloudRepo: AyraCloudRepository?,
    currentUser: FirebaseUser?,
    sheetState: SheetState,
    messages: List<ChatMessage>,
    sessions: List<ChatSession>,
    activeSessionId: Long,
    scheduledTasks: List<ScheduledTask>,
    currentPersona: String,
    selectedModelTier: String,
    currentSentiment: UserSentiment,
    expandedImageUri: String?,
    onDismissExpandedImage: () -> Unit,
    showAttachmentPicker: Boolean,
    onDismissAttachmentPicker: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit,
    onPickDocument: () -> Unit,
    onOpenControlCenter: () -> Unit,
    onShareGpsLocation: () -> Unit,
    onDispatchPromptImmediately: (String) -> Unit,
    rationaleTitle: String?,
    rationaleBody: String,
    rationaleActionLabel: String,
    onConfirmRationale: () -> Unit,
    onDismissRationale: () -> Unit,
    showControlCenterSheet: Boolean,
    onDismissControlCenter: () -> Unit,
    onRequestSinglePermission: (String) -> Unit,
    onSignOut: () -> Unit,
    onOpenSecurityCenter: () -> Unit,
    onShowClearChatDialog: () -> Unit,
    showSessionsSheet: Boolean,
    onDismissSessionsSheet: () -> Unit,
    showClearChatDialog: Boolean,
    onDismissClearChatDialog: () -> Unit,
    showKeyOnboarding: Boolean
) {
    expandedImageUri?.let { uri ->
        ChatExpandedImageDialog(
            context = context,
            expandedImageUri = uri,
            onDismiss = onDismissExpandedImage
        )
    }

    if (showAttachmentPicker) {
        ChatAttachmentHubDialog(
            onDismiss = onDismissAttachmentPicker,
            onTakePhoto = onTakePhoto,
            onPickGallery = onPickGallery,
            onPickDocument = onPickDocument,
            onSelectModelTierAndPrompt = { tierId, prompt ->
                viewModel.setModelTier(tierId)
                aiEngine.selectedModelTier = tierId
                viewModel.updateInputText(prompt)
            },
            onFillPromptOnly = { viewModel.updateInputText(it) },
            onDispatchPromptImmediately = onDispatchPromptImmediately,
            onOpenControlCenter = onOpenControlCenter,
            onShareGpsLocation = onShareGpsLocation
        )
    }

    rationaleTitle?.let { dialogTitle ->
        ChatPermissionRationaleDialog(
            title = dialogTitle,
            body = rationaleBody,
            actionLabel = rationaleActionLabel,
            onConfirm = onConfirmRationale,
            onDismiss = onDismissRationale
        )
    }

    if (showControlCenterSheet) {
        ControlCenterSheet(
            sheetState = sheetState,
            automationController = automationController,
            messageCount = messages.size,
            currentPersona = currentPersona,
            onPersonaChange = { newPersona ->
                val safePersona = AyraPreferences.resolveSafePersona(context, newPersona)
                viewModel.setPersona(safePersona)
                AyraPreferences.savePersona(context, safePersona)
                voiceManager.personaMode = safePersona
                aiEngine.currentPersona = safePersona
            },
            selectedModelTier = selectedModelTier,
            onModelTierChange = { newTier ->
                val canonical = ModelTier.canonicalizeId(newTier)
                viewModel.setModelTier(canonical)
                AyraPreferences.saveModelTier(context, canonical)
                aiEngine.selectedModelTier = canonical
            },
            wakeWordEnabled = voiceManager.isAlwaysOnWakeWordEnabled,
            onWakeWordToggle = { enabled ->
                voiceManager.isAlwaysOnWakeWordEnabled = enabled
                if (enabled) voiceManager.startListening() else voiceManager.stopListening()
            },
            onRequestSinglePermission = onRequestSinglePermission,
            canScheduleExactAlarms = taskScheduler.canScheduleExactAlarms(),
            onOpenExactAlarmSettings = { runCatching { context.startActivity(taskScheduler.buildExactAlarmSettingsIntent()) } },
            currentSentiment = currentSentiment,
            dynamicVoiceModulationEnabled = voiceManager.isDynamicModulationEnabled,
            onToggleDynamicVoiceModulation = { voiceManager.isDynamicModulationEnabled = it },
            selectedSentimentLock = voiceManager.manualSentimentLock,
            onSentimentLockChange = { voiceManager.manualSentimentLock = it },
            onTestSentimentVoice = { voiceManager.testSentimentVoice(it) },
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
                onDismissControlCenter()
                viewModel.updateInputText(selectedPrompt)
            },
            onCopyPrompt = { promptToCopy ->
                AndroidHardeningHelper.copyWithSensitivityProtection(
                    context = context,
                    label = "Ayra AI Prompt",
                    text = promptToCopy,
                    isSensitive = true,
                    autoClearMs = if (AyraPreferences.isClipboardAutoClearEnabled(context)) 60_000L else 0L
                )
                Toast.makeText(context, "Prompt copied securely / কপি করা হয়েছে", Toast.LENGTH_SHORT).show()
            },
            userDisplayName = currentUser?.displayName,
            userEmail = currentUser?.email,
            onSignOut = if (currentUser != null) onSignOut else null,
            onOpenSecurityCenter = onOpenSecurityCenter,
            onDismiss = onDismissControlCenter,
            onClearHistory = onShowClearChatDialog,
            onExportHistory = {
                if (messages.isEmpty()) {
                    Toast.makeText(context, "এক্সপোর্ট করার মতো কোনো হিস্ট্রি নেই", Toast.LENGTH_SHORT).show()
                } else {
                    val snapshot = messages.toList()
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { StorageHelper.exportChatHistory(context, snapshot) }
                        Toast.makeText(context, "চ্যাট হিস্ট্রি সফলভাবে সেভ করা হয়েছে:\n${file.name}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onQuickFeedback = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        )
    }

    if (showSessionsSheet) {
        ChatSessionsSheet(
            sessions = sessions,
            activeSessionId = activeSessionId,
            onSelectSession = { viewModel.selectSession(it) },
            onCreateNewSession = { viewModel.createNewSession() },
            onRenameSession = { sid, newTitle -> viewModel.renameSession(sid, newTitle) },
            onTogglePinSession = { viewModel.togglePinSession(it) },
            onDeleteSession = { viewModel.deleteSession(it) },
            onDismiss = onDismissSessionsSheet
        )
    }

    if (showClearChatDialog) {
        ChatClearConversationDialog(
            onConfirm = {
                scope.launch {
                    viewModel.clearCurrentSession()
                    cloudRepo?.let { repo -> withContext(Dispatchers.IO) { runCatching { repo.clearCloudMessages() } } }
                    onDismissClearChatDialog()
                    onDismissControlCenter()
                    Toast.makeText(context, "Conversation cleared / চ্যাট হিস্ট্রি মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = onDismissClearChatDialog
        )
    }

    if (showKeyOnboarding) {
        ChatKeyOnboardingModal(context = context, onDismiss = { viewModel.setShowKeyOnboarding(false) })
    }
}
