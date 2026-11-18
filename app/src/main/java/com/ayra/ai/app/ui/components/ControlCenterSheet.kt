package com.ayra.ai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ayra.ai.app.automation.DeviceAutomationController
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.ScheduledTask
import com.ayra.ai.app.ui.components.control.ControlCenterCloudAccountCard
import com.ayra.ai.app.ui.components.control.ControlCenterHeader
import com.ayra.ai.app.ui.components.control.ControlCenterInstantControlsSection
import com.ayra.ai.app.ui.components.control.ControlCenterModelsAndProvidersSection
import com.ayra.ai.app.ui.components.control.ControlCenterPermissionsCard
import com.ayra.ai.app.ui.components.control.ControlCenterPersonaSection
import com.ayra.ai.app.ui.components.control.ControlCenterPrivacySummaryCard
import com.ayra.ai.app.ui.components.control.ControlCenterPromptHistorySection
import com.ayra.ai.app.ui.components.control.ControlCenterScheduledRoutinesSection
import com.ayra.ai.app.ui.components.control.ControlCenterStorageSection
import com.ayra.ai.app.ui.components.control.ControlCenterVoiceSentimentAndLanguageSection
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.voice.UserSentiment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlCenterSheet(
    sheetState: SheetState,
    automationController: DeviceAutomationController,
    messageCount: Int,
    currentPersona: String = "AI_ASSISTANT",
    onPersonaChange: (String) -> Unit = {},
    selectedModelTier: String = "auto",
    onModelTierChange: (String) -> Unit = {},
    wakeWordEnabled: Boolean = true,
    onWakeWordToggle: (Boolean) -> Unit = {},
    onRequestSinglePermission: (String) -> Unit = {},
    canScheduleExactAlarms: Boolean = true,
    onOpenExactAlarmSettings: () -> Unit = {},
    currentSentiment: UserSentiment = UserSentiment.NEUTRAL_CARING,
    dynamicVoiceModulationEnabled: Boolean = true,
    onToggleDynamicVoiceModulation: (Boolean) -> Unit = {},
    selectedSentimentLock: UserSentiment? = null,
    onSentimentLockChange: (UserSentiment?) -> Unit = {},
    onTestSentimentVoice: (UserSentiment) -> Unit = {},
    scheduledTasks: List<ScheduledTask> = emptyList(),
    onDeleteScheduledTask: (Long) -> Unit = {},
    userMessages: List<ChatMessage> = emptyList(),
    onSelectPrompt: (String) -> Unit = {},
    onCopyPrompt: (String) -> Unit = {},
    userDisplayName: String? = null,
    userEmail: String? = null,
    onSignOut: (() -> Unit)? = null,
    onOpenSecurityCenter: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onClearHistory: () -> Unit,
    onExportHistory: () -> Unit = {},
    onQuickFeedback: (String) -> Unit
) {
    val context = LocalContext.current
    var isFlashlightOn by remember { mutableStateOf(automationController.isFlashlightActive()) }
    var lockScreenWakeEnabled by remember { mutableStateOf(AyraPreferences.isLockScreenWakeEnabled(context)) }
    var selectedLanguage by remember { mutableStateOf(AyraPreferences.getPreferredLanguage(context)) }
    var isKeySectionExpanded by remember { mutableStateOf(false) }
    var showCompanionAgeGateDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0C101B),
        scrimColor = Color.Black.copy(alpha = 0.75f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(NeonCyan.copy(alpha = 0.6f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ControlCenterHeader(onDismiss = onDismiss)

            HorizontalDivider(color = Color(0x3300FFFF), thickness = 0.8.dp)

            ControlCenterPermissionsCard(
                onRequestSinglePermission = onRequestSinglePermission,
                canScheduleExactAlarms = canScheduleExactAlarms,
                onOpenExactAlarmSettings = onOpenExactAlarmSettings
            )

            ControlCenterCloudAccountCard(
                userDisplayName = userDisplayName,
                userEmail = userEmail,
                onSignOut = onSignOut,
                onDismiss = onDismiss
            )

            HorizontalDivider(color = Color(0x2200FFFF), thickness = 0.8.dp)

            ControlCenterModelsAndProvidersSection(
                context = context,
                selectedModelTier = selectedModelTier,
                isKeySectionExpanded = isKeySectionExpanded,
                onToggleKeySection = { isKeySectionExpanded = !isKeySectionExpanded },
                onModelTierChange = onModelTierChange,
                onSelectPrompt = onSelectPrompt,
                onDismiss = onDismiss,
                onQuickFeedback = onQuickFeedback
            )

            HorizontalDivider(color = Color(0x2200FFFF), thickness = 0.8.dp)

            ControlCenterPrivacySummaryCard(
                onOpenSecurityCenter = onOpenSecurityCenter,
                onDismiss = onDismiss
            )

            HorizontalDivider(color = Color(0x2200FFFF), thickness = 0.8.dp)

            ControlCenterPersonaSection(
                context = context,
                currentPersona = currentPersona,
                showCompanionAgeGateDialog = showCompanionAgeGateDialog,
                onShowAgeGateChange = { showCompanionAgeGateDialog = it },
                onPersonaChange = onPersonaChange,
                onQuickFeedback = onQuickFeedback
            )

            HorizontalDivider(color = Color(0x2200FFFF), thickness = 0.8.dp)

            ControlCenterVoiceSentimentAndLanguageSection(
                context = context,
                currentSentiment = currentSentiment,
                dynamicVoiceModulationEnabled = dynamicVoiceModulationEnabled,
                onToggleDynamicVoiceModulation = onToggleDynamicVoiceModulation,
                selectedSentimentLock = selectedSentimentLock,
                onSentimentLockChange = onSentimentLockChange,
                onTestSentimentVoice = onTestSentimentVoice,
                lockScreenWakeEnabled = lockScreenWakeEnabled,
                onLockScreenWakeChange = { lockScreenWakeEnabled = it },
                wakeWordEnabled = wakeWordEnabled,
                onWakeWordToggle = onWakeWordToggle,
                selectedLanguage = selectedLanguage,
                onLanguageChange = { selectedLanguage = it },
                onQuickFeedback = onQuickFeedback
            )

            HorizontalDivider(color = Color(0x2200FFFF), thickness = 0.8.dp)

            ControlCenterInstantControlsSection(
                automationController = automationController,
                isFlashlightOn = isFlashlightOn,
                onFlashlightChange = { isFlashlightOn = it },
                onQuickFeedback = onQuickFeedback
            )

            HorizontalDivider(color = Color(0x2200FFFF), thickness = 0.8.dp)

            ControlCenterScheduledRoutinesSection(
                scheduledTasks = scheduledTasks,
                canScheduleExactAlarms = canScheduleExactAlarms,
                onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                onDeleteScheduledTask = onDeleteScheduledTask
            )

            HorizontalDivider(color = Color(0x2200FFFF), thickness = 0.8.dp)

            ControlCenterPromptHistorySection(
                userMessages = userMessages,
                onSelectPrompt = onSelectPrompt,
                onCopyPrompt = onCopyPrompt
            )

            HorizontalDivider(color = Color(0x2200FFFF), thickness = 0.8.dp)

            ControlCenterStorageSection(
                messageCount = messageCount,
                onExportHistory = onExportHistory,
                onClearHistory = onClearHistory
            )

            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}
