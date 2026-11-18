# AYRA AI (`com.ayra.ai.app`)

Enterprise-hardened Multi-Model AI Companion, Multimodal Studio, and Local/Cloud Assistant for Android (Kotlin, Jetpack Compose, Material 3, Room + SQLCipher, Firebase AI Logic & Firestore) paired with a Zero-Trust Node.js/TypeScript backend gateway (`backend/`).

## Architecture Overview

- **Android App (`app/`):**
  - **UI & Navigation:** Single-activity Jetpack Compose M3 (`MainActivity`, `AyraWorkspaceHost`, `AuthScreen`, `VaultLockScreen`, modular `ChatScreen` + `ui/components/chat/`, `ControlCenterSheet` + `ui/components/control/`, `StudiosScreen` + `ui/screens/studios/`, `ModelsArenaScreen`, `ResearchDocsScreen` + `ui/screens/research/`, `DiagnosticsToolsScreen`, `SecurityCenterScreen`).
  - **AI Routing, AgentCore & Local Privacy:** `UnifiedAiEngine`, `DefaultAgentCore` (21 tools, 25s step timeout, bounded max-3 parallel read-only execution, confirmation-required tools held `PENDING`), `AyraAgentOrchestrator` (nullable evidence-backed confidence), `ProviderPool` (circuit breaker + bounded LRU cache), `FirebaseAiEngine`, `ModelCatalog` (`VERIFIED_PLATFORM`, `VERIFIED_LIVE`, `CONFIGURED`, `UNVERIFIED`), and `SecureAiGatewayClient` enforcing **Local-Only Privacy Mode** and explicit **External AI / Free Pool Consent**.
  - **Zero-Trust Security & Storage:** `AyraKeyManager` (purpose-separated Android Keystore AES-256-GCM / ECDSA P-256 / HMAC-SHA256), `VaultDatabaseKeyManager` + `SQLCipher` (Room v9, migrations `4->5->6->7->8->9`, fail-closed on corrupted wrapped key envelopes), `EncryptedVaultStorage` (`AYRAVLT1` containers with canonical path confinement), `AyraE2eeEngine` (P-256 ECDH + HKDF-SHA256 + multi-epoch revocation), `BiometricAuthManager` + `VaultLockManager`, `NetworkSecurityHelper` (pooled TLS 1.3/1.2 + SSRF guards), and `AiToolSecurityGuard`.
  - **Variant-Isolated App Check:** `DebugAppCheckProviderFactory` is restricted exclusively to the `debug` build (`src/debug/.../AppCheckDebugBridge.kt` + `debugImplementation`); release builds ship zero debug App Check code and install `RecaptchaAppCheckProviderFactory` via `recaptcha_site_key` in `app/src/main/res/values/firebase_applet_config.xml` (tracked explicitly by `AppCheckStatusRegistry` as `RELEASE_RECAPTCHA_ACTIVE`, `DEBUG_ACTIVE`, or `MISSING_RELEASE_CONFIG`).
- **Zero-Trust Backend (`backend/`):** Express + TypeScript gateway enforcing Firebase ID token verification (`checkRevoked = true`), Firebase App Check, `.strict()` Zod validation, Helmet HSTS/CSP, explicit CORS allowlists, DNS-rebinding SSRF defense, rate limiting, secret redaction, `"staging"` config profile, and fail-closed `HTTP 503` on `/api/v1/code/run` unless `SANDBOX_EXECUTOR_URL` + `SANDBOX_EXECUTOR_TOKEN` are configured (`node:vm` removed).
- **Cloud Rules (`firestore.rules`):** Owner-scoped, schema-validated Firestore rules across user profiles, encrypted messages, plugins, creations, E2EE devices, sessions, backups, and append-only security events.

## Build & Verification

### 1. Android Unit, Robolectric & Firebase Emulator Tests
```bash
firebase emulators:exec --project demo-no-project --only firestore,auth "gradle :app:testDebugUnitTest --console=plain"
```

### 2. Android Debug & R8-Minified Release APKs
```bash
gradle :app:assembleDebug :app:assembleRelease
```
> **Note for Play Store Distribution & Google Sign-In:**
> 1. In-container `release` builds in this workspace intentionally retain `signingConfigs.release` (`debug.keystore`) per AI Studio container policy so local builds remain reproducible without modifying `signingConfigs`.
> 2. **Fresh clones / Play Store uploads CANNOT ship `debug.keystore`:** `debug.keystore` is gitignored and uses debug credentials. Before distributing publicly or uploading `.aab`/`.apk` to Google Play Console, configure a private production upload keystore via `keystore.properties` (see `keystore.properties.example`) or encrypted CI secrets, and enroll in Play App Signing.
> 3. **Production Google Sign-In Requirement:** `app/google-services.json` currently lists only the debug signing key SHA-1 (`app/google-services.json`). You **must** register your production upload key **and** Google Play App Signing key SHA-1 / SHA-256 fingerprints in **Firebase Console -> Project settings -> Your apps (`com.ayra.ai.app`) -> Add fingerprint**, then download the updated `app/google-services.json` before releasing; otherwise `CredentialManager` Google Sign-In will fail in production.

### 3. Firebase Configuration (`google-services.json`) & API Key Security
- `app/google-services.json` is aligned to `package_name = "com.ayra.ai.app"` (Firebase project `gen-lang-client-0595455617`).
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
