package com.ayra.ai.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ayra.ai.app.cloud.AyraCloudRepository
import com.ayra.ai.app.cloud.GoogleAuthManager
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.ChatRepository
import com.ayra.ai.app.security.AndroidHardeningHelper
import com.ayra.ai.app.security.EncryptedVaultStorage
import com.ayra.ai.app.security.VaultLockManager
import com.ayra.ai.app.ui.screens.AuthScreen
import com.ayra.ai.app.ui.screens.AyraWorkspaceHost
import com.ayra.ai.app.ui.screens.VaultLockScreen
import com.ayra.ai.app.ui.theme.AyraTheme
import com.ayra.ai.app.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Production MainActivity for Ayra AI.
 * Extends FragmentActivity to host hardware-backed BiometricPrompt CryptoObject verification,
 * configures Firebase App Check before ViewModels/Compose, keeps optional Google Sign-In/Cloud Sync
 * gated behind an authenticated FirebaseUser, applies FLAG_SECURE / tapjacking hardening,
 * and manages lifecycle vault auto-lock & temp wiping.
 */
class MainActivity : FragmentActivity() {

    private val chatRepository by lazy {
        val db = ChatDatabase.getDatabase(applicationContext)
        com.ayra.ai.app.data.ChatRepository(
            context = applicationContext,
            chatDao = db.chatDao(),
            sessionDao = db.chatSessionDao()
        )
    }

    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModel.factory(chatRepository)
    }
    private val authManager: GoogleAuthManager by lazy { GoogleAuthManager(applicationContext) }

    private val vaultLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            VaultLockManager.onAppForegrounded(applicationContext)
        }

        override fun onStop(owner: LifecycleOwner) {
            VaultLockManager.onAppBackgrounded(applicationContext)
            EncryptedVaultStorage.wipeTempShareFiles(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must execute BEFORE any ViewModel, Firestore, Auth, or AI Logic access
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        AndroidHardeningHelper.configureViewTapjackingProtection(window.decorView)
        AndroidHardeningHelper.applyScreenCaptureProtection(
            this,
            AyraPreferences.isScreenCaptureBlocked(applicationContext)
        )
        VaultLockManager.initialize(applicationContext)
        AyraPreferences.refreshUiFlows(applicationContext)
        lifecycle.addObserver(vaultLifecycleObserver)

        setContent {
            val themeMode by AyraPreferences.themeModeFlow.collectAsStateWithLifecycle(
                initialValue = AyraPreferences.getThemeMode(applicationContext)
            )
            val accentPreset by AyraPreferences.accentPresetFlow.collectAsStateWithLifecycle(
                initialValue = AyraPreferences.getAccentPreset(applicationContext)
            )
            val customAccentHex by AyraPreferences.customAccentHexFlow.collectAsStateWithLifecycle(
                initialValue = AyraPreferences.getCustomAccentHex(applicationContext)
            )
            val fontScale by AyraPreferences.fontScaleFlow.collectAsStateWithLifecycle(
                initialValue = AyraPreferences.getFontScale(applicationContext)
            )
            val bubbleCornerDp by AyraPreferences.bubbleCornerDpFlow.collectAsStateWithLifecycle(
                initialValue = AyraPreferences.getBubbleCornerDp(applicationContext)
            )
            val uiDensity by AyraPreferences.uiDensityFlow.collectAsStateWithLifecycle(
                initialValue = AyraPreferences.getUiDensity(applicationContext)
            )
            val reducedMotion by AyraPreferences.reducedMotionFlow.collectAsStateWithLifecycle(
                initialValue = AyraPreferences.isReducedMotionEnabled(applicationContext)
            )
            val blockScreenCapture by AyraPreferences.screenCaptureBlockedFlow.collectAsStateWithLifecycle(
                initialValue = AyraPreferences.isScreenCaptureBlocked(applicationContext)
            )
            LaunchedEffect(blockScreenCapture) {
                AndroidHardeningHelper.applyScreenCaptureProtection(this@MainActivity, blockScreenCapture)
            }

            val useDark = when (themeMode) {
                "light" -> false
                "dark", "high_contrast", "custom" -> true
                "auto" -> {
                    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    hour !in 6..18
                }
                else -> isSystemInDarkTheme()
            }
            AyraTheme(
                darkTheme = useDark,
                themeMode = themeMode,
                accentPreset = accentPreset,
                customAccentHex = customAccentHex,
                fontScale = fontScale,
                bubbleCornerDp = bubbleCornerDp,
                uiDensity = uiDensity,
                reducedMotion = reducedMotion
            ) {
                val currentUser by authManager.authStateFlow.collectAsStateWithLifecycle(
                    initialValue = authManager.currentUser
                )
                val isVaultLocked by VaultLockManager.isVaultLocked.collectAsStateWithLifecycle()
                val vaultLockReason by VaultLockManager.lockReason.collectAsStateWithLifecycle()
                var showOptionalAuthScreen by rememberSaveable { mutableStateOf(false) }

                val user = currentUser
                LaunchedEffect(user?.uid) {
                    val uid = user?.uid ?: return@LaunchedEffect
                    showOptionalAuthScreen = false
                    launch(Dispatchers.IO) {
                        runCatching {
                            AyraCloudRepository.create(applicationContext)
                                .registerCurrentDeviceAndSession(uid)
                        }
                    }
                }

                BackHandler(enabled = showOptionalAuthScreen && user == null) {
                    showOptionalAuthScreen = false
                }

                if (showOptionalAuthScreen && user == null) {
                    AuthScreen(
                        authManager = authManager,
                        onSignedIn = { showOptionalAuthScreen = false }
                    )
                } else {
                    key(user?.uid ?: "guest") {
                        val activeDb = remember { ChatDatabase.getDatabase(applicationContext) }
                        val activeRepo = remember(activeDb) {
                            ChatRepository(
                                context = applicationContext,
                                chatDao = activeDb.chatDao(),
                                sessionDao = activeDb.chatSessionDao()
                            )
                        }
                        LaunchedEffect(activeRepo) {
                            chatViewModel.attachRepository(activeRepo)
                        }
                        if (isVaultLocked) {
                            VaultLockScreen(
                                lockReason = vaultLockReason,
                                onUnlocked = { /* VaultLockManager flow transitions automatically */ }
                            )
                        } else {
                            AyraWorkspaceHost(
                                chatDao = activeDb.chatDao(),
                                viewModel = chatViewModel,
                                currentUser = user,
                                onSignOut = {
                                    if (AyraPreferences.isBiometricLockEnabled(applicationContext)) {
                                        VaultLockManager.lockNow(applicationContext, "Signed out of session")
                                    } else {
                                        VaultLockManager.unlockIfDisabled(applicationContext)
                                    }
                                    lifecycleScope.launch {
                                        authManager.signOut()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        lifecycle.removeObserver(vaultLifecycleObserver)
        EncryptedVaultStorage.wipeTempShareFiles(applicationContext)
        super.onDestroy()
    }
}
