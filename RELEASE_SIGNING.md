# Ayra AI — Release Signing & Distribution Architecture

This document defines the production signing architecture for **Ayra AI (`com.ayra.ai.app`)** across local developer releases, automated CI/CD builds, and Google Play App Signing.

> **Workspace Container Constraint Note (H1):** Inside the interactive AI Studio workspace container, `app/build.gradle.kts` (`signingConfigs` and `buildTypes.*.signingConfig`) and `debug.keystore*` are kept unmodified per platform policy so preview builds stay reproducible (`UNVERIFIED` for production upload signing inside the container). Standalone CI and Play release pipelines MUST supply externalized upload-key credentials and enforce `-PayraRequireProductionSigning=true` as documented below.

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

**Current Repository Audit Status (2026-11-24):**
- `app/google-services.json` currently registers **only ONE** Android OAuth client (`client_type: 1`) with certificate hash `587f08f3ddaffc7c32e527871ec2e5003f47044e` (`SHA-1: 58:7F:08:F3:DD:AF:FC:7C:32:E5:27:87:1E:C2:E5:00:3F:47:04:4E`, debug keystore only).
- **Missing for production (blocking owner action):** Neither your **Upload Key** nor your **Google Play App Signing** SHA-1 / SHA-256 fingerprints are registered yet. Until both are registered, **Google Sign-In (`CredentialManager`)**, **Firebase App Check (reCAPTCHA Enterprise)**, and **Play Integrity** verification will fail on release builds distributed outside debug workstations.

Before publishing to Play Console / internal testing:

1. Generate your private Upload Keystore (Section 2) and inspect both SHA-1 & SHA-256:
   ```bash
   keytool -list -v -keystore upload-keystore.jks -alias ayra_ai_upload | grep -E 'SHA1:|SHA256:'
   ```
2. Retrieve your **Play App Signing key certificate** SHA-1 and SHA-256 from **Google Play Console -> Release -> Setup -> App signing**.
3. Open **Firebase Console -> Project Settings -> Your Apps (`com.ayra.ai.app`) -> Add fingerprint** and register ALL FOUR fingerprints:
   - Upload Key **SHA-1**
   - Upload Key **SHA-256**
   - Play App Signing Key **SHA-1**
   - Play App Signing Key **SHA-256**
4. Download the updated `app/google-services.json` from Firebase Console and replace the tracked client file (verify the new `oauth_client` entries include your release SHA-1 hashes while keeping `package_name = "com.ayra.ai.app"`).
5. Verify `recaptcha_site_key` in `app/src/main/res/values/firebase_applet_config.xml` matches your Firebase Console reCAPTCHA Enterprise site key for the active project in `app/google-services.json`, so `AppCheckInit.kt` installs `RecaptchaAppCheckProviderFactory` and `AppCheckStatusRegistry.currentState` reports `RELEASE_RECAPTCHA_ACTIVE`.

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
3. Enforce fail-closed verification via Gradle before producing or distributing release artifacts (must pass `-PversionCode=<N>` with `N > 1` or `CI_RUN_NUMBER > 1`):
   ```bash
   gradle :app:validateProductionReleaseSigning -PayraRequireProductionSigning=true -PversionCode=2
   gradle :app:assembleRelease :app:verifyReleaseSigningCertificate -PayraRequireProductionSigning=true -PversionCode=2
   ```
4. Note for Play Store pipelines: inside an external standalone clone where container signing constraints do not apply, configure `signingConfigs.release` in `app/build.gradle.kts` to read `keystore.properties` / `AYRA_UPLOAD_*` env vars so `:app:bundleRelease` signs the AAB directly with your upload key.
5. `ValidateProductionReleaseSigningTask` in `app/build.gradle.kts` automatically:
   - Reads `AYRA_UPLOAD_KEYSTORE_PATH` / `AYRA_UPLOAD_KEY_ALIAS` / `AYRA_UPLOAD_KEYSTORE_PASSWORD` / `AYRA_UPLOAD_KEY_PASSWORD` (or `keystore.properties`) without logging any password.
   - Fails closed if `versionCode <= 1` when `-PayraRequireProductionSigning=true`, or if the path points to `debug.keystore`, alias is `androiddebugkey`, password is `"android"`, the keystore file does not exist, or `keytool -list -v` fails to confirm the alias and SHA-256 certificate fingerprint.
   - Prevents release packaging in CI whenever `-PayraRequireProductionSigning=true` or `AYRA_REQUIRE_PRODUCTION_SIGNING=true` is enabled with missing/debug signing credentials or unbumped `versionCode`.

### GitHub Actions Encrypted Secrets

Store the following repository secrets under **Settings -> Secrets and variables -> Actions**:
- `AYRA_UPLOAD_KEYSTORE_BASE64` (`base64 -w0 upload-keystore.jks`)
- `AYRA_UPLOAD_KEYSTORE_PASSWORD`
- `AYRA_UPLOAD_KEY_ALIAS`
- `AYRA_UPLOAD_KEY_PASSWORD`
- `AYRA_RECAPTCHA_SITE_KEY` (optional, for release App Check)

Decode into a runner temp path (`$RUNNER_TEMP/upload-keystore.jks`) with `chmod 600`, export `AYRA_REQUIRE_PRODUCTION_SIGNING=true` and `AYRA_UPLOAD_KEYSTORE_PATH=$RUNNER_TEMP/upload-keystore.jks`, execute `:app:bundleRelease`, and remove the temporary keystore in an `always()` cleanup step.

---

## 5. Verifying APK / AAB Signatures

Inspect APK signing certificates without exposing passwords:

```bash
$ANDROID_HOME/build-tools/<version>/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab
```

Confirm that the printed SHA-256 matches your **upload key** (and NOT `CN=Android Debug`).
