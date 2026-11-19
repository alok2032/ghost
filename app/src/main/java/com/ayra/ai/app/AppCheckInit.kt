package com.ayra.ai.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AppCheckInit"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

enum class AppCheckState(val description: String) {
    UNINITIALIZED("App Check has not been initialized yet"),
    DEBUG_ACTIVE("Debug App Check provider active"),
    RELEASE_RECAPTCHA_ACTIVE("Release reCAPTCHA Enterprise App Check provider active"),
    MISSING_RELEASE_CONFIG("Release recaptcha_site_key resource is missing or blank; App Check provider not installed"),
    ERROR("App Check initialization failed")
}

object AppCheckStatusRegistry {
    private val _state = MutableStateFlow(AppCheckState.UNINITIALIZED)
    val stateFlow: StateFlow<AppCheckState> = _state.asStateFlow()

    @Volatile
    var lastErrorMessage: String? = null
        private set

    val currentState: AppCheckState
        get() = _state.value

    fun isReadyForVerifiedCloudCalls(): Boolean {
        return _state.value == AppCheckState.DEBUG_ACTIVE ||
            _state.value == AppCheckState.RELEASE_RECAPTCHA_ACTIVE
    }

    internal fun update(state: AppCheckState, errorMessage: String? = null) {
        lastErrorMessage = errorMessage
        _state.value = state
    }

    fun resetForTests() {
        lastErrorMessage = null
        _state.value = AppCheckState.UNINITIALIZED
    }
}

/**
 * Configures Firebase App Check before any Firebase service (Firestore, Auth, AI Logic) is used.
 * Uses DebugAppCheckProviderFactory strictly in debug variant (via AppCheckDebugBridge)
 * and RecaptchaAppCheckProviderFactory in release builds, recording an explicit fail-safe state
 * when release credentials are absent instead of failing silently.
 */
fun configureAppCheck(
    context: Context,
    intent: Intent? = null,
    isDebugBuild: Boolean = BuildConfig.DEBUG,
    recaptchaSiteKeyOverride: String? = null
) {
    try {
        val appContext = context.applicationContext
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            FirebaseApp.initializeApp(appContext)
        }
        val app = FirebaseApp.getInstance()
        val appCheck = Firebase.appCheck

        if (isDebugBuild && installDebugAppCheckProviderIfSupported(appCheck, appContext, app, intent)) {
            AppCheckStatusRegistry.update(AppCheckState.DEBUG_ACTIVE)
            return
        }

        val siteKey = recaptchaSiteKeyOverride?.trim() ?: runCatching {
            appContext.getString(R.string.recaptcha_site_key).trim()
        }.getOrDefault("")
        if (siteKey.isNotEmpty()) {
            appCheck.installAppCheckProviderFactory(
                RecaptchaAppCheckProviderFactory.getInstance(siteKey)
            )
            AppCheckStatusRegistry.update(AppCheckState.RELEASE_RECAPTCHA_ACTIVE)
        } else {
            AppCheckStatusRegistry.update(
                AppCheckState.MISSING_RELEASE_CONFIG,
                "Missing non-blank string resource '$RECAPTCHA_SITE_KEY_RES' for release App Check."
            )
            Log.w(TAG, "Release App Check NOT configured: string resource '$RECAPTCHA_SITE_KEY_RES' is absent.")
        }
    } catch (e: Exception) {
        AppCheckStatusRegistry.update(AppCheckState.ERROR, e.message ?: e.javaClass.simpleName)
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
