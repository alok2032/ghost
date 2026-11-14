package com.ayra.ai.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

private const val TAG = "AppCheckDebugBridge"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

internal fun installDebugAppCheckProviderIfSupported(
    appCheck: FirebaseAppCheck,
    appContext: Context,
    app: FirebaseApp,
    intent: Intent?
): Boolean {
    val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
        ?: intent?.getStringExtra(DEBUG_SECRET_KEY)
    if (!debugToken.isNullOrBlank()) {
        val prefs = appContext.getSharedPreferences(
            "$DEBUG_STORE_PREFIX${app.persistenceKey}",
            Context.MODE_PRIVATE
        )
        prefs.edit()
            .putString(DEBUG_SECRET_KEY, debugToken)
            .commit()
        Log.d(TAG, "App Check debug secret configured cleanly.")
    } else {
        Log.w(TAG, "No debug token found in Intent extra.")
    }
    appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    return true
}
