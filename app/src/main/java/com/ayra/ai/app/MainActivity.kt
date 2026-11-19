package com.ayra.ai.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
import com.ayra.ai.app.security.FirebaseConfigConsistencyValidator
import com.ayra.ai.app.security.VaultLockManager
import com.ayra.ai.app.ui.screens.AuthScreen
import com.ayra.ai.app.ui.screens.AyraWorkspaceHost
import com.ayra.ai.app.ui.screens.VaultLockScreen
import com.ayra.ai.app.ui.screens.VaultRecoveryScreen
import com.ayra.ai.app.ui.theme.AyraTheme
import com.ayra.ai.app.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Production MainActivity for Ayra AI.
 * Extends FragmentActivity to host hardware-backed BiometricPrompt CryptoObject verification,
 * configures Firebase App Check before ViewModels/Compose, keeps optional Google Sign-In/Cloud Sync
 * gated behind an authenticated FirebaseUser, applies FLAG_SECURE / tapjacking hardening,
 * fails closed into [VaultRecoveryScreen] if SQLCipher key wrapping is lost or corrupted,
 * and manages lifecycle vault auto-lock & temp wiping.
 */
class MainActivity : FragmentActivity() {

    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModel.factory(null)
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
                var vaultOpenResult by remember { mutableStateOf<ChatDatabase.VaultOpenResult?>(null) }
                LaunchedEffect(Unit) {
                    vaultOpenResult = withContext(Dispatchers.IO) {
                        ChatDatabase.tryGetDatabase(applicationContext)
                    }
                }

                val fbAudit = FirebaseConfigConsistencyValidator.lastAuditResult
                val invalidDebugConfig = if (BuildConfig.DEBUG) fbAudit?.takeIf { !it.valid } else null

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

                Column(modifier = Modifier.fillMaxSize()) {
                    if (invalidDebugConfig != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .testTag("debug_firebase_config_banner")
                        ) {
                            Text(
                                text = "Firebase config warning: ${invalidDebugConfig.issues.joinToString("; ")}",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (val openState = vaultOpenResult) {
                            null -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                            is ChatDatabase.VaultOpenResult.NeedsRecovery -> {
                                VaultRecoveryScreen(
                                    vaultState = openState.vaultState,
                                    reason = openState.reason,
                                    onRecovered = { recoveredDb ->
                                        vaultOpenResult = ChatDatabase.VaultOpenResult.Ready(recoveredDb)
                                    }
                                )
                            }
                            is ChatDatabase.VaultOpenResult.Ready -> {
                                val activeDb = openState.database
                                if (showOptionalAuthScreen && user == null) {
                                    AuthScreen(
                                        authManager = authManager,
                                        onSignedIn = { showOptionalAuthScreen = false }
                                    )
                                } else {
                                    key(user?.uid ?: "guest") {
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
