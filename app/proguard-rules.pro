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

# Moshi & Kotlinx Serialization
-keepclasseswithmembers class * {
    @com.squareup.moshi.JsonClass *;
}
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
}

# Remove debug logging in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# Retrofit & OkHttp
-dontwarn retrofit2.**
-keepattributes Signature,Exceptions,InnerClasses
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
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

