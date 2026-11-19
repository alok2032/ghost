import java.io.File
import java.time.Duration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.ayra.ai.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ayra.ai.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    // Unused navigation-compose trimmed cleanly for smaller APK/faster builds (app uses custom AyraWorkspaceHost state routing)
    // implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Firebase BoM & Integrations (AI Logic, App Check, Firestore, Auth)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    implementation(libs.firebase.appcheck.recaptcha)
    debugImplementation(libs.firebase.appcheck.debug)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)

    // Jetpack Credential Manager for Google Sign-In
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    // Enterprise Security: SQLCipher, BiometricPrompt, Play Integrity
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.play.integrity)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation("org.json:json:20240303")

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<Test> {
    failFast = true
    timeout.set(Duration.ofMinutes(6))
    testLogging {
        events("failed", "skipped")
        showExceptions = true
        showCauses = true
    }
}

abstract class ValidateProductionReleaseSigningTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val requireProdProp: Property<String>

    @get:Input
    abstract val rootDirPath: Property<String>

    @TaskAction
    fun validate() {
        val requireProdSigning =
            requireProdProp.orNull?.equals("true", ignoreCase = true) == true ||
                System.getenv("AYRA_REQUIRE_PRODUCTION_SIGNING")?.equals("true", ignoreCase = true) == true

        val rootDir = File(rootDirPath.get())
        val ksPropsFile = File(rootDir, "keystore.properties")
        val props = Properties().apply {
            if (ksPropsFile.exists()) {
                ksPropsFile.inputStream().use { load(it) }
            }
        }
        val configuredStorePath =
            (System.getenv("AYRA_UPLOAD_KEYSTORE_PATH")
                ?: props.getProperty("storeFile")
                ?: "").trim()
        val configuredAlias =
            (System.getenv("AYRA_UPLOAD_KEY_ALIAS")
                ?: props.getProperty("keyAlias")
                ?: "").trim()
        val configuredStorePass =
            (System.getenv("AYRA_UPLOAD_KEYSTORE_PASSWORD")
                ?: props.getProperty("storePassword")
                ?: "")
        val configuredKeyPass =
            (System.getenv("AYRA_UPLOAD_KEY_PASSWORD")
                ?: props.getProperty("keyPassword")
                ?: "")

        if (requireProdSigning) {
            if (configuredStorePath.isEmpty()) {
                throw GradleException(
                    "PRODUCTION SIGNING MISCONFIGURED: Release build requires a private upload keystore " +
                        "(set AYRA_UPLOAD_KEYSTORE_PATH or keystore.properties; never ship debug.keystore). See RELEASE_SIGNING.md."
                )
            }
            if (configuredStorePath.endsWith("debug.keystore", ignoreCase = true) ||
                configuredAlias.equals("androiddebugkey", ignoreCase = true) ||
                configuredStorePass == "android"
            ) {
                throw GradleException(
                    "PRODUCTION SIGNING VIOLATION: Debug keystore/alias/password is forbidden for production distribution."
                )
            }
            val keystoreFile = File(configuredStorePath).let {
                if (it.isAbsolute) it else File(rootDir, configuredStorePath)
            }
            if (!keystoreFile.exists() || !keystoreFile.isFile) {
                throw GradleException("Upload keystore file not found at: ${keystoreFile.absolutePath}")
            }
            if (configuredAlias.isEmpty() || configuredStorePass.isEmpty() || configuredKeyPass.isEmpty()) {
                throw GradleException("Upload keystore alias and credentials must all be non-empty.")
            }

            val proc = ProcessBuilder(
                "keytool", "-list", "-v",
                "-keystore", keystoreFile.absolutePath,
                "-alias", configuredAlias,
                "-storepass", configuredStorePass
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                throw GradleException("Upload keystore verification via keytool failed (exit=$exitCode). Check alias and credentials.")
            }
            val sha256Line = out.lineSequence().firstOrNull { it.trim().startsWith("SHA256:") }?.trim()
                ?: throw GradleException("Could not read SHA256 certificate fingerprint from upload keystore.")
            logger.lifecycle("Verified private upload certificate ($sha256Line)")
        } else {
            logger.lifecycle(
                "Release signing dev-container mode (set -PayraRequireProductionSigning=true for strict upload keystore enforcement)."
            )
        }
    }
}

val validateProductionReleaseSigning by tasks.registering(ValidateProductionReleaseSigningTask::class) {
    group = "verification"
    description = "Validates release signing posture and verifies private upload keystore when AYRA_REQUIRE_PRODUCTION_SIGNING=true."
    requireProdProp.set(providers.gradleProperty("ayraRequireProductionSigning"))
    rootDirPath.set(rootProject.layout.projectDirectory.asFile.absolutePath)
    outputs.upToDateWhen { false }
}

val verifyReleaseSigningCertificate by tasks.registering {
    group = "verification"
    description = "Alias task verifying production release signing rules."
    dependsOn(validateProductionReleaseSigning)
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(validateProductionReleaseSigning)
}


