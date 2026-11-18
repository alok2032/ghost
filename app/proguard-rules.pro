# Full R8 Optimization, Shrinking & Obfuscation Rules for Ayra AI

# ---------------------------------------------------------------------------
# R8 Full Mode Obfuscation & Optimization Hardening
# ---------------------------------------------------------------------------
-allowaccessmodification
-repackageclasses 'o'
-mergeinterfacesaggressively
-overloadaggressively
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Honor explicit @Keep annotations only where reflection/serialization is required
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# ---------------------------------------------------------------------------
# Room Database & KSP Generated Implementations
# Only keep the RoomDatabase implementation names required by Room's reflection
# lookup while allowing full shrinking & obfuscation of all other data classes.
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keepclassmembers class * {
    @androidx.room.* *;
}
-dontwarn androidx.room.**

# ---------------------------------------------------------------------------
# SQLCipher Native JNI Bindings
# Keep only JNI signatures & native entrypoints rather than broad wildcard packages.
# ---------------------------------------------------------------------------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class net.zetetic.database.sqlcipher.SQLiteDatabase {
    public *;
    native <methods>;
}
-keep class net.zetetic.database.sqlcipher.SQLiteConnection {
    native <methods>;
    <init>(...);
}
-keep class net.zetetic.database.sqlcipher.SQLiteCustomFunction {
    *;
}
-dontwarn net.zetetic.**

# ---------------------------------------------------------------------------
# Kotlin Coroutines ServiceLoader Factories
# ---------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ---------------------------------------------------------------------------
# Networking & Security Libraries (consumer rules bundled in AARs)
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.bouncycastle.**
-dontwarn com.google.firebase.ai.**
-dontwarn com.google.firebase.appcheck.**
-dontwarn com.google.android.play.core.integrity.**

# ---------------------------------------------------------------------------
# Strip verbose, debug, and info logging calls in release artifacts
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
