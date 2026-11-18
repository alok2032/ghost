# Ayra AI — Release Signing & Distribution Architecture

This document defines the production signing architecture for **Ayra AI (`com.ayra.ai.app`)** across local developer releases, automated CI/CD builds, and Google Play App Signing.

> **Workspace Container Constraint Note**: Inside the interactive AI Studio workspace container, `app/build.gradle.kts` (`signingConfigs` and `buildTypes.*.signingConfig`) and `debug.keystore*` are kept unmodified per platform guidelines so automated preview builds stay compatible. Before building a production release artifact on a standalone build workstation or CI runner, apply the standard externalized release signing configuration documented below.

---

## 1. Local & CI Signing Principles

1. **Standard Android Debug Signing**: Debug builds use standard Android debug signing for local development only. Never ship debug-signed artifacts to production channels.
2. **Externalized Upload Keystore**: Production release artifacts (`assembleRelease`, `bundleRelease`) are signed with an upload keystore generated outside version control (for example, `upload-keystore.jks`).
3. **Never Commit Credentials**: `keystore.properties`, `*.jks`, `*.keystore`, `.env`, and `debug.keystore` are gitignored in `.gitignore`. Never print passwords or base64 keystores in CI logs.
4. **Google Play App Signing**: Enroll `com.ayra.ai.app` in Play App Signing so Google holds the final Play distribution signing key and the local/CI keystore acts strictly as the rotatable **upload key**.
5. **Fail-Closed Verification**: Always verify keystore existence, alias validity, and SHA-1 / SHA-256 certificate fingerprints before uploading artifacts or describing a build as production-signed.

---

## 2. Generating a Production Upload Keystore

Run locally in a secure directory outside the git repository:

```bash
keytool -genkeypair -v \
  -keystore upload-keystore.jks \
  -alias ayra_ai_upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Extract SHA-1 and SHA-256 fingerprints:

```bash
keytool -list -v -keystore upload-keystore.jks -alias ayra_ai_upload
```

---

## 3. Registering Production Fingerprints in Firebase (`google-services.json`)

If `google-services.json` only contains the debug SHA-1 fingerprint, **Google Sign-In (Credential Manager)**, **Firebase App Check (reCAPTCHA Enterprise)**, and **Play Integrity** token exchange will fail on release builds.

Before publishing to Play Console / internal testing:

1. Open **Firebase Console -> Project Settings -> Your Apps (`com.ayra.ai.app`)**.
2. Add BOTH:
   - Your **Upload Key** SHA-1 and SHA-256.
   - Your **Play App Signing** key SHA-1 and SHA-256 (from Play Console -> Release -> Setup -> App signing).
3. Download the updated `app/google-services.json` and commit the public client config (never private keystores).
4. Verify `recaptcha_site_key` in `app/src/main/res/values/firebase_applet_config.xml` matches your Firebase Console reCAPTCHA Enterprise site key (already populated for project `gen-lang-client-0595455617`) so `AppCheckInit.kt` installs `RecaptchaAppCheckProviderFactory` and `AppCheckStatusRegistry.currentState` reports `RELEASE_RECAPTCHA_ACTIVE` (or `MISSING_RELEASE_CONFIG` if blank/removed).

---

## 4. Configuring `keystore.properties` (Local) and CI Encrypted Secrets

### Local Developer Release Builds

1. Copy the template:
   ```bash
   cp keystore.properties.example keystore.properties
   ```
2. Populate private values in `keystore.properties` (already listed in `.gitignore`):
   ```properties
   storeFile=/secure/path/to/upload-keystore.jks
   storePassword=<strong_store_password>
   keyAlias=ayra_ai_upload
   keyPassword=<strong_key_password>
   ```
3. Wire externalized `signingConfigs.create("release")` in your standalone CI/local release branch `app/build.gradle.kts`:
   ```kotlin
   import java.util.Properties

   val keystorePropsFile = rootProject.file("keystore.properties")
   val keystoreProps = Properties().apply {
       if (keystorePropsFile.exists()) {
           keystorePropsFile.inputStream().use { load(it) }
       }
   }

   val releaseStorePath = System.getenv("AYRA_RELEASE_STORE_FILE")
       ?: keystoreProps.getProperty("storeFile")
   val releaseStorePassword = System.getenv("AYRA_RELEASE_STORE_PASSWORD")
       ?: keystoreProps.getProperty("storePassword")
   val releaseKeyAlias = System.getenv("AYRA_RELEASE_KEY_ALIAS")
       ?: keystoreProps.getProperty("keyAlias")
   val releaseKeyPassword = System.getenv("AYRA_RELEASE_KEY_PASSWORD")
       ?: keystoreProps.getProperty("keyPassword")
   ```
4. Validate inputs fail-closed when assembling a signed production release:
   - Ensure `storeFile` exists on disk.
   - Ensure `storePassword`, `keyAlias`, and `keyPassword` are non-blank.
   - Only permit an explicit local unsigned release artifact (`-Payra.allowUnsignedRelease=true`) for dry-run R8/ProGuard verification—and **never** label an unsigned or debug-key-signed APK as production-signed.

### GitHub Actions Encrypted Secrets

Store the following repository secrets under **Settings -> Secrets and variables -> Actions**:
- `AYRA_UPLOAD_KEYSTORE_BASE64` (`base64 -w0 upload-keystore.jks`)
- `AYRA_RELEASE_STORE_PASSWORD`
- `AYRA_RELEASE_KEY_ALIAS`
- `AYRA_RELEASE_KEY_PASSWORD`
- `AYRA_RECAPTCHA_SITE_KEY` (optional, for release App Check)

Decode into a runner temp path (`$RUNNER_TEMP/upload-keystore.jks`) with `chmod 600`, execute `:app:bundleRelease`, and remove the temporary keystore in an `always()` cleanup step.

---

## 5. Verifying APK / AAB Signatures

Inspect APK signing certificates without exposing passwords:

```bash
$ANDROID_HOME/build-tools/<version>/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab
```

Confirm that the printed SHA-256 matches your **upload key** (and NOT `CN=Android Debug`).
