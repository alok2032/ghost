package com.ayra.ai.app

import android.app.Application
import android.content.Context
import android.os.StrictMode
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.ayra.ai.app.security.EncryptedPreferences
import com.ayra.ai.app.security.FirebaseConfigConsistencyValidator
import com.ayra.ai.app.security.NetworkConnectivityManager
import com.ayra.ai.app.security.NetworkSecurityHelper
import com.ayra.ai.app.security.SecureKeyStore
import com.ayra.ai.app.security.SecurityAuditCategory
import com.ayra.ai.app.security.SecurityAuditLogger
import com.ayra.ai.app.security.SecurityAuditSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AyraApplication : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val coilTransportClient by lazy {
        NetworkSecurityHelper.hardenedPooledClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val coilHttpClient by lazy {
        coilTransportClient.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                if (url.startsWith("https://", ignoreCase = true)) {
                    NetworkSecurityHelper.executePublicHttpsWithValidatedRedirects(
                        client = coilTransportClient,
                        initialRequest = req,
                        urlValidator = { target -> NetworkSecurityHelper.validatePublicHttpsSyntaxOnly(target) }
                    )
                } else {
                    chain.proceed(req)
                }
            }
            .build()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(coilHttpClient)
        .crossfade(true)
        .build()

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

        // Initialize OS connectivity monitor & bounded validated-network recovery
        NetworkConnectivityManager.initialize(this@AyraApplication)
        NetworkConnectivityManager.registerRecoveryListener {
            com.ayra.ai.app.ai.providers.ExternalAiProviders.clearDynamicModelCache(null)
            com.ayra.ai.app.ai.UnifiedAiEngine.clearCache()
            com.ayra.ai.app.ai.providers.AiModelRegistry.refreshConfigurationStates(this@AyraApplication)
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
