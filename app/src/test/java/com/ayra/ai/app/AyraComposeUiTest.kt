package com.ayra.ai.app

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.mcp.McpClientManager
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.ChatRepository
import com.ayra.ai.app.security.SecureKeyStore
import com.ayra.ai.app.security.VaultDatabaseKeyManager
import com.ayra.ai.app.ui.components.McpServersSection
import com.ayra.ai.app.ui.components.OnboardingOverlay
import com.ayra.ai.app.ui.components.ProviderKeysSection
import com.ayra.ai.app.ui.components.chat.GlassInputRow
import com.ayra.ai.app.ui.screens.AyraWorkspaceHost
import com.ayra.ai.app.ui.screens.SecurityCenterScreen
import com.ayra.ai.app.ui.screens.VaultLockScreen
import com.ayra.ai.app.ui.theme.AyraTheme
import com.ayra.ai.app.ui.viewmodel.ChatViewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AyraComposeUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:123456789012:android:composetest001")
                    .setApiKey("AIzaSyComposeUiTestPlaceholderKey000000")
                    .setProjectId("demo-no-project")
                    .build()
            )
        }
        ChatDatabase.closeDatabaseForMaintenance()
        VaultDatabaseKeyManager.destroyDatabaseKeyMetadata(context)
        context.deleteDatabase("ayra_chat_database")
        SecureKeyStore.clearAllSecrets(context)
        AyraPreferences.clearAllPreferences(context)
    }

    @After
    fun tearDown() {
        ChatDatabase.closeDatabaseForMaintenance()
        VaultDatabaseKeyManager.destroyDatabaseKeyMetadata(context)
        context.deleteDatabase("ayra_chat_database")
        SecureKeyStore.clearAllSecrets(context)
        AyraPreferences.clearAllPreferences(context)
    }

    @Test
    fun tabletWidthRendersNavigationRailAndNavigatesToResearchWiki() {
        AyraPreferences.setOnboardingCompleted(context, true)
        val db = ChatDatabase.getDatabase(context)
        val repo = ChatRepository(context, db.chatDao(), db.chatSessionDao())
        val vm = ChatViewModel(repo)

        composeRule.setContent {
            AyraTheme {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(DpSize(960.dp, 800.dp))
                ) {
                    AyraWorkspaceHost(
                        chatDao = db.chatDao(),
                        viewModel = vm,
                        currentUser = null,
                        onSignOut = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("workspace_navigation_rail").assertIsDisplayed()
        composeRule.onNodeWithTag("rail_nav_chat").assertIsDisplayed()
        composeRule.onNodeWithTag("rail_nav_research_wiki").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("rail_nav_chat").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun onboardingOverlayNextFlowAndFinishPersistCompletionAndPrivacyToggle() {
        AyraPreferences.setOnboardingCompleted(context, false)
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        var dismissed = false

        composeRule.setContent {
            AyraTheme {
                OnboardingOverlay(onDismissed = { dismissed = true })
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("onboarding_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("onboarding_local_only_switch").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(AyraPreferences.isLocalOnlyAiModeEnabled(context))

        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("onboarding_finish_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertTrue(dismissed)
        assertTrue(AyraPreferences.isOnboardingCompleted(context))
    }

    @Test
    fun onboardingOverlaySkipPersistsCompletion() {
        AyraPreferences.setOnboardingCompleted(context, false)
        var dismissed = false

        composeRule.setContent {
            AyraTheme {
                OnboardingOverlay(onDismissed = { dismissed = true })
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("onboarding_skip_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertTrue(dismissed)
        assertTrue(AyraPreferences.isOnboardingCompleted(context))
    }

    @Test
    fun mcpServersSectionBlocksCleartextAddsValidHttpsAndRevokes() {
        composeRule.setContent {
            AyraTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    McpServersSection(context = context)
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("mcp_name_input").performScrollTo().performTextInput("Cleartext Test")
        composeRule.onNodeWithTag("mcp_url_input").performScrollTo().performTextInput("http://10.0.0.5/mcp")
        composeRule.onNodeWithTag("mcp_add_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(0, McpClientManager.listServers(context).size)
        composeRule.onNodeWithText("Blocked:", substring = true).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("mcp_name_input").performScrollTo().performTextReplacement("Public Test MCP")
        composeRule.onNodeWithTag("mcp_url_input").performScrollTo().performTextReplacement("https://1.1.1.1/mcp")
        composeRule.onNodeWithTag("mcp_add_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        val saved = McpClientManager.listServers(context)
        assertEquals(1, saved.size)
        val serverId = saved.first().id

        composeRule.onNodeWithTag("mcp_revoke_$serverId").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(0, McpClientManager.listServers(context).size)
    }

    @Test
    fun vaultLockScreenRendersExpectedSemantics() {
        composeRule.setContent {
            AyraTheme {
                VaultLockScreen(
                    lockReason = "Session locked for test",
                    onUnlocked = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("vault_locked_title").assertIsDisplayed()
        composeRule.onNodeWithTag("vault_unlock_button").assertIsDisplayed()
    }

    @Test
    fun securityCenterScreenRendersExpectedSemantics() {
        composeRule.setContent {
            AyraTheme {
                SecurityCenterScreen(onBack = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("security_center_title").assertIsDisplayed()
        composeRule.onNodeWithTag("security_lock_now_button").assertIsDisplayed()
        assertEquals(1, composeRule.onAllNodesWithTag("security_center_title").fetchSemanticsNodes().size)
    }

    @Test
    fun providerKeysSectionConsentSwitchUpdatesGroqStatusAndShowsVerifiedNimLlama() {
        AyraPreferences.setLocalOnlyAiModeEnabled(context, false)
        AyraPreferences.setExternalAiConsent(context, false)
        AyraPreferences.setGroqKey(context, "gsk-compose-ui-test-key")

        composeRule.setContent {
            AyraTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ProviderKeysSection(context = context)
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("provider_activation_consent_card").assertIsDisplayed()
        composeRule.onNodeWithTag("provider_status_badge_groq").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Enable Consent", substring = true).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("external_cloud_consent_switch").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertTrue(AyraPreferences.hasExternalAiConsent(context))
        composeRule.onNodeWithText("Active ✓", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("NVIDIA • Llama 3.3 70B Instruct (Verified Default)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun glassInputRowAllowsSendWithAttachmentEvenWhenTextBlank() {
        var sendTriggered = false
        composeRule.setContent {
            AyraTheme {
                GlassInputRow(
                    text = "",
                    onTextChange = {},
                    isListening = false,
                    isRecordingAudio = false,
                    isTranscribingAudio = true,
                    canSendWithAttachment = true,
                    isGenerating = false,
                    onSend = { sendTriggered = true },
                    onStop = {},
                    onAudioTranscribeClick = {},
                    onMicClick = {},
                    onAttachClick = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("gemini_audio_transcribe_button").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_send_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertTrue(sendTriggered)
    }
}
