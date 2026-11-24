package com.ayra.ai.app.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.InetSocketAddress
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
abstract class FirestoreEmulatorTestBase {

    protected lateinit var firestore: FirebaseFirestore
    protected lateinit var auth: FirebaseAuth
    protected lateinit var databaseId: String

    @Before
    open fun setUpFirebase() {
        Assume.assumeTrue(
            "Skipping Firestore emulator test because local Firebase emulators are not running on $EMULATOR_HOST:$FIRESTORE_PORT / $AUTH_PORT",
            isPortReachable(EMULATOR_HOST, FIRESTORE_PORT) && isPortReachable(EMULATOR_HOST, AUTH_PORT)
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resId = context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
        check(resId != 0) { "Missing R.string.firestore_database_id in app/src/main/res/values/firebase_applet_config.xml" }
        databaseId = context.getString(resId)
        check(databaseId.isNotBlank()) { "R.string.firestore_database_id must not be blank" }

        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == TEST_APP_NAME }
        val app = existing ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId("com.ayra.ai.app")
                .setProjectId(PROJECT_ID)
                .setApiKey("fake-api-key-for-emulator")
                .build(),
            TEST_APP_NAME
        ).also { newApp ->
            val newFirestore = FirebaseFirestore.getInstance(newApp, databaseId)
            newFirestore.useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
            newFirestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()

            val newAuth = FirebaseAuth.getInstance(newApp)
            newAuth.useEmulator(EMULATOR_HOST, AUTH_PORT)
        }

        firestore = FirebaseFirestore.getInstance(app, databaseId)
        auth = FirebaseAuth.getInstance(app)
    }

    @After
    open fun tearDownFirebase() {
        if (::auth.isInitialized) {
            auth.signOut()
        }
    }

    protected suspend fun signInTestUser(email: String): String = withContext(Dispatchers.IO) {
        withTimeout(AUTH_TIMEOUT_MS) {
            val result = try {
                auth.signInWithEmailAndPassword(email, DEFAULT_PASSWORD).awaitResult()
            } catch (_: FirebaseAuthInvalidUserException) {
                auth.createUserWithEmailAndPassword(email, DEFAULT_PASSWORD).awaitResult()
            }
            checkNotNull(result.user?.uid) { "Test emulator auth failed for $email" }
        }
    }

    protected fun Throwable.unwrapFirestoreException(): FirebaseFirestoreException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is FirebaseFirestoreException) return current
            current = current.cause
        }
        return null
    }

    private companion object {
        const val TEST_APP_NAME = "ayra-emulator-test"
        const val EMULATOR_HOST = "127.0.0.1"
        const val FIRESTORE_PORT = 8085
        const val AUTH_PORT = 9099
        const val PROJECT_ID = "demo-no-project"
        const val DEFAULT_PASSWORD = "password123"
        const val AUTH_TIMEOUT_MS = 10000L

        fun isPortReachable(host: String, port: Int): Boolean {
            return try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 200)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
