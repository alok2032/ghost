package com.ayra.ai.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ayra.ai.app.cloud.AyraCloudRepository
import com.ayra.ai.app.cloud.GoogleAuthManager
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
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
 * configures Firebase App Check before ViewModels/Compose, enforces Google Sign-In gating,
 * applies FLAG_SECURE / tapjacking hardening, and manages lifecycle vault auto-lock & temp wiping.
 */
class MainActivity : FragmentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission evaluated
    }

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
        lifecycle.addObserver(vaultLifecycleObserver)

        val chatDao = ChatDatabase.getDatabase(applicationContext).chatDao()

        setContent {
            val themeMode by AyraPreferences.themeModeFlow.collectAsStateWithLifecycle(
                initialValue = AyraPreferences.getThemeMode(applicationContext)
            )
            val blockScreenCapture by AyraPreferences.screenCaptureBlockedFlow.collectAsStateWithLifecycle(
                initialValue = AyraPreferences.isScreenCaptureBlocked(applicationContext)
            )
            LaunchedEffect(blockScreenCapture) {
                AndroidHardeningHelper.applyScreenCaptureProtection(this@MainActivity, blockScreenCapture)
            }

            val useDark = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            AyraTheme(darkTheme = useDark) {
                val currentUser by authManager.authStateFlow.collectAsStateWithLifecycle(
                    initialValue = authManager.currentUser
                )
                val isVaultLocked by VaultLockManager.isVaultLocked.collectAsStateWithLifecycle()
                val vaultLockReason by VaultLockManager.lockReason.collectAsStateWithLifecycle()

                val user = currentUser
                if (user == null) {
                    AuthScreen(
                        authManager = authManager,
                        onSignedIn = { /* authStateFlow updates UI automatically */ }
                    )
                } else {
                    LaunchedEffect(user.uid) {
                        launch(Dispatchers.IO) {
                            runCatching {
                                AyraCloudRepository.create(applicationContext)
                                    .registerCurrentDeviceAndSession(user.uid)
                            }
                        }
                    }

                    key(user.uid) {
                        if (isVaultLocked) {
                            VaultLockScreen(
                                lockReason = vaultLockReason,
                                onUnlocked = { /* VaultLockManager flow transitions automatically */ }
                            )
                        } else {
                            AyraWorkspaceHost(
                                chatDao = chatDao,
                                viewModel = chatViewModel,
                                currentUser = user,
                                onSignOut = {
                                    VaultLockManager.lockNow(applicationContext, "Signed out of session")
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

        // Defer runtime notification permission dialog until after Frame 1 SurfaceSyncGroup settles
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }, 1500L)
        }
    }

    override fun onDestroy() {
        lifecycle.removeObserver(vaultLifecycleObserver)
        EncryptedVaultStorage.wipeTempShareFiles(applicationContext)
        super.onDestroy()
    }
}
