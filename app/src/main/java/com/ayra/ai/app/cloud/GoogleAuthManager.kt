package com.ayra.ai.app.cloud

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.ayra.ai.app.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed class GoogleSignInResult {
    data class Success(val user: FirebaseUser) : GoogleSignInResult()
    object Cancelled : GoogleSignInResult()
    data class Error(val message: String) : GoogleSignInResult()
}

/**
 * Manages Firebase Authentication strictly via Jetpack Credential Manager + Google Sign-In.
 * Never hardcodes OAuth client IDs or falls back to unprovisioned providers.
 */
class GoogleAuthManager(
    private val context: Context,
    private val auth: FirebaseAuth = Firebase.auth
) {
    private val appContext = context.applicationContext
    private val credentialManager by lazy { CredentialManager.create(appContext) }

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val authStateFlow: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser)
        awaitClose {
            auth.removeAuthStateListener(listener)
        }
    }

    /**
     * Explicit interactive Google Sign-In triggered by user button tap.
     * Uses GetSignInWithGoogleOption in its own GetCredentialRequest.
     */
    suspend fun signInWithGoogle(activityContext: Context): GoogleSignInResult {
        val webClientId = runCatching {
            appContext.getString(R.string.default_web_client_id)
        }.getOrNull().orEmpty()

        if (webClientId.isBlank()) {
            return GoogleSignInResult.Error(
                "Google Sign-In configuration is incomplete (missing default_web_client_id)."
            )
        }

        return try {
            val googleSignInOption = GetSignInWithGoogleOption.Builder(webClientId)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleSignInOption)
                .build()

            val credentialContext = if (activityContext is Activity) activityContext else activityContext
            val result = credentialManager.getCredential(credentialContext, request)
            val credential = result.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                val authResult = auth.signInWithCredential(firebaseCredential).awaitResult()
                val user = authResult.user
                if (user != null) {
                    GoogleSignInResult.Success(user)
                } else {
                    GoogleSignInResult.Error("Firebase sign-in returned no user session.")
                }
            } else {
                GoogleSignInResult.Error("Unsupported credential type returned.")
            }
        } catch (_: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (_: NoCredentialException) {
            GoogleSignInResult.Error(
                "No Google account is configured on this device. Please add a Google account in device settings and retry."
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Google sign-in failed", e)
            GoogleSignInResult.Error(e.localizedMessage ?: "Google Sign-In failed. Please try again.")
        }
    }

    /**
     * Optional one-tap silent returning-user sign-in using GetGoogleIdOption in a separate request.
     */
    suspend fun trySilentReturnSignIn(activityContext: Context): FirebaseUser? {
        if (auth.currentUser != null) return auth.currentUser
        val webClientId = runCatching {
            appContext.getString(R.string.default_web_client_id)
        }.getOrNull().orEmpty()
        if (webClientId.isBlank()) return null

        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(activityContext, request)
            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                auth.signInWithCredential(firebaseCredential).awaitResult().user
            } else null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun signOut() {
        runCatching {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        }
        auth.signOut()
    }

    companion object {
        private const val TAG = "GoogleAuthManager"
    }
}
