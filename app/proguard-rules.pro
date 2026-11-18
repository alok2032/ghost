# Proguard rules for Ayra AI
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* *;
}

# SQLCipher native JNI bindings
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# Biometric & Credential Manager
-keep class androidx.biometric.** { *; }
-keep class androidx.credentials.** { *; }

# Keep @Immutable data/UI models and ModelTier routing specs
-keep @androidx.compose.runtime.Immutable class com.ayra.ai.app.** { *; }
-keep enum com.ayra.ai.app.ai.providers.ModelTier { *; }
-keep class com.ayra.ai.app.data.** { *; }

# Kotlinx Serialization (if used by transitive/plugin payloads)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Remove debug logging in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# OkHttp & Serialization Reflection Metadata
-keepattributes Signature,Exceptions,InnerClasses
-dontwarn okhttp3.**
-dontwarn okio.**

# Firebase AI Logic, App Check & Play Integrity
-keep class com.google.firebase.ai.** { *; }
-dontwarn com.google.firebase.ai.**
-keep class com.google.firebase.appcheck.** { *; }
-dontwarn com.google.firebase.appcheck.**
-keep class com.google.android.play.core.integrity.** { *; }
-dontwarn com.google.android.play.core.integrity.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

