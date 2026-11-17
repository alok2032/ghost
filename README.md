# AYRA AI (`com.ayra.ai.app`)

Enterprise-hardened Multi-Model AI Companion, Multimodal Studio, and Local/Cloud Assistant for Android (Kotlin, Jetpack Compose, Material 3, Room + SQLCipher, Firebase AI Logic & Firestore) paired with a Zero-Trust Node.js/TypeScript backend gateway (`backend/`).

## Architecture Overview

- **Android App (`app/`):**
  - **UI & Navigation:** Single-activity Jetpack Compose M3 (`MainActivity`, `AyraWorkspaceHost`, `AuthScreen`, `VaultLockScreen`, `ChatScreen`, `StudiosScreen`, `ModelsArenaScreen`, `ResearchDocsScreen`, `DiagnosticsToolsScreen`, `SecurityCenterScreen`).
  - **AI Routing & Local Privacy:** `UnifiedAiEngine`, `ProviderPool` (circuit breaker + bounded LRU cache), `FirebaseAiEngine`, `ExternalAiProviders`, and `SecureAiGatewayClient` enforcing **Local-Only Privacy Mode** and explicit **External AI / Free Pool Consent** across cloud, wiki/CVE, and model discovery paths.
  - **Zero-Trust Security & Storage:** `AyraKeyManager` (purpose-separated Android Keystore AES-256-GCM / ECDSA P-256 / HMAC-SHA256), `VaultDatabaseKeyManager` + `SQLCipher`, `EncryptedVaultStorage` (`AYRAVLT1` containers with canonical path confinement), `AyraE2eeEngine` (P-256 ECDH + HKDF-SHA256 + multi-epoch revocation), `BiometricAuthManager` + `VaultLockManager`, `NetworkSecurityHelper` (TLS 1.3/1.2 + SSRF guards), and `AiToolSecurityGuard`.
  - **Variant-Isolated App Check:** `DebugAppCheckProviderFactory` is restricted exclusively to the `debug` build (`src/debug/.../AppCheckDebugBridge.kt` + `debugImplementation`); release builds ship zero debug App Check code and use `RecaptchaAppCheckProviderFactory` when `recaptcha_site_key` is configured.
- **Zero-Trust Backend (`backend/`):** Express + TypeScript gateway enforcing Firebase ID token verification (`checkRevoked = true`), Firebase App Check, `.strict()` Zod validation, Helmet HSTS/CSP, explicit CORS allowlists, DNS-rebinding SSRF defense, rate limiting, and secret redaction.
- **Cloud Rules (`firestore.rules`):** Owner-scoped, schema-validated Firestore rules across user profiles, encrypted messages, plugins, creations, E2EE devices, sessions, backups, and append-only security events.

## Build & Verification

### 1. Android Unit, Robolectric & Firebase Emulator Tests
```bash
firebase emulators:exec --project demo-no-project --only firestore,auth "./gradlew :app:testDebugUnitTest --console=plain"
```

### 2. Android Debug & R8-Minified Release APKs
```bash
./gradlew :app:assembleDebug :app:assembleRelease
```
> **Note for Play Store Distribution:** Release builds in this repository preserve the container's `debug.keystore` signing config (`signingConfigs.release`) so local/CI APKs build reproducibly. For Play Store distribution, generate a dedicated upload keystore (see `keystore.properties.example`), configure Play App Signing in your CI release pipeline, and back up your keystore & passwords securely.

### 3. Firebase Configuration (`google-services.json`) & API Key Security
- `app/google-services.json` is already aligned to `package_name = "com.ayra.ai.app"` (Firebase project `gen-lang-client-0643978985`).
- If `app/google-services.json` is missing in a fresh clone, copy `app/google-services.json.example` to `app/google-services.json` only for placeholder builds, or download your real `google-services.json` from **Firebase Console -> Project settings -> Your apps -> Add app -> Android (`com.ayra.ai.app`)** with your debug and release SHA-1/SHA-256 certificates (`gradle :app:signingReport`).
- **API Key Restriction:** In Google Cloud Console -> APIs & Services -> Credentials, restrict the Android API key to package `com.ayra.ai.app` + your SHA-1/SHA-256 signing fingerprints, and enforce **Firebase App Check**.

### 4. Firestore Rules Verification
```bash
firebase emulators:exec --project demo-no-project --only firestore "node --test firestore.test.js"
```

### 5. Backend Build & Security Suite
```bash
npm --prefix backend ci
npm --prefix backend run build
npm --prefix backend test
```

## Further Documentation
- `ARCHITECTURE_MAP.md` — Complete codebase & module responsibility map
- `SECURITY_ARCHITECTURE.md` — Key hierarchy, SQLCipher, E2EE, and zero-trust planes
- `SECURITY_AUDIT_REPORT.md` — Complete findings & remediation matrix (`FINDING-01`..`FINDING-10`)
- `AUDIT.md` — Chronological remediation log (`F01`..`F21`, Phases 0–21)
- `DEPLOYMENT_AND_RISK_REPORT.md` — Verified test/APK artifacts & production prerequisites
