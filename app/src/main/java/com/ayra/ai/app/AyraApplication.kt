package com.ayra.ai.app

import android.app.Application
import android.content.Context
import android.os.StrictMode
import com.ayra.ai.app.security.EncryptedPreferences
import com.ayra.ai.app.security.SecureKeyStore
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

        // Seed encrypted AI provider vault off the main thread so startup stays jank-free
        appScope.launch {
            ensureActiveAiKeysSeeded(this@AyraApplication)
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
