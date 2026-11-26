package com.ayra.ai.app

import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabaseLockedException
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.automation.ScheduledTaskReceiver
import com.ayra.ai.app.cloud.GoogleAuthManager
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.ChatRepository
import com.ayra.ai.app.security.SecureKeyStore
import com.ayra.ai.app.security.VaultDatabaseKeyManager
import com.ayra.ai.app.ui.components.isSyntacticallySafeVideoUrl
import com.ayra.ai.app.ui.components.sanitizeLocalVideoFile
import com.ayra.ai.app.ui.screens.AuthScreen
import com.ayra.ai.app.ui.screens.AyraWorkspaceHost
import com.ayra.ai.app.ui.theme.AyraTheme
import com.ayra.ai.app.ui.viewmodel.ChatViewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuditPolishRegressionTest {

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
                    .setApplicationId("1:123456789012:android:auditpolish001")
                    .setApiKey("AIzaSyAuditPolishTestPlaceholderKey0000")
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
    fun transientDatabaseLockClassifierSeparatesLocksFromCorruptionAndKeyLoss() {
        assertTrue(ChatDatabase.isTransientDatabaseOpenFailure(SQLiteDatabaseLockedException("database is locked")))
        assertTrue(ChatDatabase.isTransientDatabaseOpenFailure(RuntimeException("SQLITE_BUSY: database table is locked")))
        assertTrue(ChatDatabase.isTransientDatabaseOpenFailure(IllegalStateException("outer", RuntimeException("disk i/o error"))))

        assertFalse(ChatDatabase.isTransientDatabaseOpenFailure(RuntimeException("file is not a database")))
        assertFalse(ChatDatabase.isTransientDatabaseOpenFailure(RuntimeException("HMAC verification failed: corrupt")))
        assertFalse(ChatDatabase.isTransientDatabaseOpenFailure(IllegalStateException("Keystore key invalidated")))
        assertFalse(ChatDatabase.isTransientDatabaseOpenFailure(IllegalStateException("Migration didn't properly handle")))
    }

    @Test
    fun videoPlayerCardSanitizesPathsAndRejectsUnsafeUrls() {
        val validCacheVideo = File(context.cacheDir, "clip_${System.nanoTime()}.mp4").apply {
            writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        }
        val validFilesVideo = File(context.filesDir, "saved_${System.nanoTime()}.mp4").apply {
            writeBytes(byteArrayOf(0x04, 0x05))
        }
        val emptyFile = File(context.cacheDir, "empty_${System.nanoTime()}.mp4").apply {
            createNewFile()
        }
        try {
            assertNotNull(sanitizeLocalVideoFile(context, validCacheVideo.absolutePath))
            assertNotNull(sanitizeLocalVideoFile(context, validFilesVideo.absolutePath))
            assertNull(sanitizeLocalVideoFile(context, emptyFile.absolutePath))
            assertNull(sanitizeLocalVideoFile(context, "/etc/passwd"))
            assertNull(sanitizeLocalVideoFile(context, "${context.cacheDir.absolutePath}/../shared_prefs/secret.xml"))
            assertNull(sanitizeLocalVideoFile(context, "/sdcard/Download/clip.mp4"))

            assertTrue(isSyntacticallySafeVideoUrl("https://cdn.example.com/media/clip.mp4"))
            assertFalse(isSyntacticallySafeVideoUrl("http://cdn.example.com/media/clip.mp4"))
            assertFalse(isSyntacticallySafeVideoUrl("file:///etc/passwd"))
            assertFalse(isSyntacticallySafeVideoUrl("https://127.0.0.1/clip.mp4"))
            assertFalse(isSyntacticallySafeVideoUrl("https://192.168.1.10/clip.mp4"))
            assertFalse(isSyntacticallySafeVideoUrl("https://localhost/clip.mp4"))
        } finally {
            validCacheVideo.delete()
            validFilesVideo.delete()
            emptyFile.delete()
        }
    }

    @Test
    fun scheduledTaskReceiverReminderUsesAppIconAndCompletesSpeechCallback() {
        var speechCompleted = false
        ScheduledTaskReceiver.executeScheduledTaskNotification(
            context = context,
            taskId = 4242L,
            targetApp = "REMINDER",
            recipient = "You",
            messageText = "Review project checklist",
            phoneNumber = "",
            playSoundAndVoice = false,
            onSpeechComplete = { speechCompleted = true }
        )

        assertTrue("Reminder execution must signal completion", speechCompleted)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifications = shadowOf(nm).allNotifications
        assertTrue(notifications.isNotEmpty())
        val reminderNotif = notifications.last()
        assertEquals(R.drawable.ic_notification_ayra, reminderNotif.smallIcon.resId)
    }

    @Test
    fun authScreenExposesContinueAsGuestAndWorkspaceDrawerOffersSignIn() {
        var guestClicked = false
        val authManager = GoogleAuthManager(context, FirebaseAuth.getInstance())
        composeRule.setContent {
            AyraTheme {
                AuthScreen(
                    authManager = authManager,
                    onSignedIn = {},
                    onContinueAsGuest = { guestClicked = true }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("continue_as_guest_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(guestClicked)
    }

    @Test
    fun workspaceDrawerDisplaysSignInActionForGuestUser() {
        AyraPreferences.setOnboardingCompleted(context, true)
        val db = ChatDatabase.getDatabase(context)
        val repo = ChatRepository(context, db.chatDao(), db.chatSessionDao())
        val vm = ChatViewModel(repo)
        var signInRequested = false

        try {
            composeRule.setContent {
                AyraTheme {
                    DeviceConfigurationOverride(
                        DeviceConfigurationOverride.ForcedSize(DpSize(390.dp, 844.dp))
                    ) {
                        AyraWorkspaceHost(
                            chatDao = db.chatDao(),
                            viewModel = vm,
                            currentUser = null,
                            onSignOut = {},
                            onRequestSignIn = { signInRequested = true }
                        )
                    }
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chat_open_drawer_button").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("drawer_sign_in_button").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            assertTrue(signInRequested)
        } finally {
            vm.releaseForMaintenance()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun bundledFontsIconsAndEnglishBengaliResourcesResolveCleanly() {
        assertNotNull(ContextCompat.getDrawable(context, R.drawable.ic_launcher_monochrome))
        assertNotNull(ContextCompat.getDrawable(context, R.drawable.ic_notification_ayra))
        assertTrue(context.resources.openRawResource(R.font.plus_jakarta_sans).use { it.read() != -1 })
        assertTrue(context.resources.openRawResource(R.font.hind_siliguri).use { it.read() != -1 })

        val enCfg = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val bnCfg = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("bn")) }
        val enRes = context.createConfigurationContext(enCfg).resources
        val bnRes = context.createConfigurationContext(bnCfg).resources

        val localizedKeys = listOf(
            R.string.action_retry,
            R.string.action_cancel,
            R.string.action_new_chat,
            R.string.auth_continue_guest,
            R.string.auth_sign_in_google,
            R.string.auth_connecting,
            R.string.drawer_sign_in_title,
            R.string.drawer_sign_in_subtitle,
            R.string.drawer_signed_in_label,
            R.string.drawer_sign_out,
            R.string.drawer_search_placeholder,
            R.string.vault_retry_title,
            R.string.vault_retry_body,
            R.string.vault_retry_button,
            R.string.vault_recovery_reset_confirm_title,
            R.string.vault_recovery_reset_confirm_button
        )

        localizedKeys.forEach { resId ->
            val enVal = enRes.getString(resId)
            val bnVal = bnRes.getString(resId)
            assertTrue("Missing EN string for $resId", enVal.isNotBlank())
            assertTrue("Missing BN string for $resId", bnVal.isNotBlank())
            assertFalse("Expected distinct BN translation for $resId", enVal == bnVal)
        }
    }
}
