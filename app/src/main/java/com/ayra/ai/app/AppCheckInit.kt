package com.ayra.ai.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

/**
 * Configures Firebase App Check before any Firebase service (Firestore, Auth, AI Logic) is used.
 * Uses DebugAppCheckProviderFactory strictly in debug variant (via AppCheckDebugBridge)
 * and RecaptchaAppCheckProviderFactory in release builds.
 */
fun configureAppCheck(context: Context, intent: Intent? = null) {
    try {
        val appContext = context.applicationContext
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            FirebaseApp.initializeApp(appContext)
        }
        val app = FirebaseApp.getInstance()
        val appCheck = Firebase.appCheck

        if (BuildConfig.DEBUG && installDebugAppCheckProviderIfSupported(appCheck, appContext, app, intent)) {
            return
        } else {
            val resId = appContext.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                appContext.packageName
            )
            if (resId != 0) {
                val siteKey = appContext.getString(resId)
                if (siteKey.isNotBlank()) {
                    appCheck.installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
