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
