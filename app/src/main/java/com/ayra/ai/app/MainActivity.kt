package com.ayra.ai.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ayra.ai.app.cloud.GoogleAuthManager
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.ui.screens.AuthScreen
import com.ayra.ai.app.ui.screens.AyraWorkspaceHost
import com.ayra.ai.app.ui.theme.AyraTheme
import com.ayra.ai.app.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * Production MainActivity for Ayra AI.
 * Configures Firebase App Check before ViewModels/Compose, enforces Google Sign-In gating
 * for Firestore cloud data, and adheres to just-in-time runtime permissions.
 */
class MainActivity : ComponentActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must execute BEFORE any ViewModel, Firestore, Auth, or AI Logic access
        configureAppCheck(this, intent)
        enableEdgeToEdge()

        val chatDao = ChatDatabase.getDatabase(applicationContext).chatDao()

        setContent {
            AyraTheme {
                val currentUser by authManager.authStateFlow.collectAsStateWithLifecycle(
                    initialValue = authManager.currentUser
                )
                val user = currentUser
                if (user == null) {
                    AuthScreen(
                        authManager = authManager,
                        onSignedIn = { /* authStateFlow updates UI automatically */ }
                    )
                } else {
                    key(user.uid) {
                        AyraWorkspaceHost(
                            chatDao = chatDao,
                            viewModel = chatViewModel,
                            currentUser = user,
                            onSignOut = {
                                lifecycleScope.launch {
                                    authManager.signOut()
                                }
                            }
                        )
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
}
