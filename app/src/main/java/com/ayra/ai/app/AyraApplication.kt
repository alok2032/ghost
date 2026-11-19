package com.ayra.ai.app

import android.app.Application
import android.content.Context
import android.os.StrictMode
import com.ayra.ai.app.security.EncryptedPreferences
import com.ayra.ai.app.security.FirebaseConfigConsistencyValidator
import com.ayra.ai.app.security.SecureKeyStore
import com.ayra.ai.app.security.SecurityAuditCategory
import com.ayra.ai.app.security.SecurityAuditLogger
import com.ayra.ai.app.security.SecurityAuditSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AyraApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }

        // Seed encrypted AI provider vault, apply cached catalog overrides & verify Firebase config off the main thread
        appScope.launch {
            ensureActiveAiKeysSeeded(this@AyraApplication)
            com.ayra.ai.app.ai.providers.ModelCatalog.loadCachedRemoteOverrides(this@AyraApplication)
            com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(this@AyraApplication)
            val fbAudit = FirebaseConfigConsistencyValidator.validate(this@AyraApplication)
            if (!fbAudit.valid) {
                SecurityAuditLogger.log(
                    context = this@AyraApplication,
                    category = SecurityAuditCategory.NETWORK,
                    severity = SecurityAuditSeverity.CRITICAL,
                    message = "Firebase config inconsistency detected: ${fbAudit.issues.joinToString("; ")}"
                )
            }
        }
    }

    companion object {
        private val seededOnce = AtomicBoolean(false)

        fun ensureActiveAiKeysSeeded(context: Context) {
            // Security Guarantee: Never seed hardcoded API keys in source code.
            // Keys must exclusively come from user input via Android Keystore (SecureKeyStore / EncryptedPreferences)
            // or keyless Firebase AI Logic / App Check.
            seededOnce.compareAndSet(false, true)
        }
    }
}
