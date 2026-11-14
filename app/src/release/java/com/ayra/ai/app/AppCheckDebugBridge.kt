package com.ayra.ai.app

import android.content.Context
import android.content.Intent
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck

internal fun installDebugAppCheckProviderIfSupported(
    appCheck: FirebaseAppCheck,
    appContext: Context,
    app: FirebaseApp,
    intent: Intent?
): Boolean = false
