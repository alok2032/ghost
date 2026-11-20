# AYRA AI (`com.ayra.ai.app`)

Enterprise-hardened Multi-Model AI Companion, Multimodal Studio, and Local/Cloud Assistant for Android (Kotlin, Jetpack Compose, Material 3, Room + SQLCipher, Firebase AI Logic & Firestore) paired with a Zero-Trust Node.js/TypeScript backend gateway (`backend/`).

## Architecture Overview

- **Android App (`app/`):**
  - **UI & Navigation:** Single-activity Jetpack Compose M3 (`MainActivity`, `AyraWorkspaceHost`, `AuthScreen`, `VaultLockScreen`, modular `ChatScreen` + `ui/components/chat/`, `ControlCenterSheet` + `ui/components/control/`, `StudiosScreen` + `ui/screens/studios/`, `ModelsArenaScreen`, `ResearchDocsScreen` + `ui/screens/research/`, `DiagnosticsToolsScreen`, `SecurityCenterScreen`).
  - **AI Routing, AgentCore & Local Privacy:** `UnifiedAiEngine`, `DefaultAgentCore` (bounded multi-step tools, 25s step timeout, bounded max-3 parallel read-only execution, 8-state `AgentActionLifecycleState`, pre-dispatch `ToolArgumentValidator`, prompt-injection boundary framing on both attached docs and tool outputs, confirmation-required tools & tainted `reminder_schedule` held `PENDING`), `AyraAgentOrchestrator` (`VerificationReport` with evidence coverage, reliability, validity & uncertainty notes), `ProviderPool` (consent-enforced circuit breaker + bounded LRU cache), `FirebaseAiEngine`, `ModelCatalog` (active `2026-11-20` baseline models + HTTP `410` retirement tracking + visible failover notices), `AutonomousKnowledgeEngine` (synchronized bounded 250-entry LRU cache), and `SecureAiGatewayClient` enforcing fail-closed **Local-Only Privacy Mode** and explicit opt-in **External AI / Free Pool / Cloud Sync Consent** (`false` out of the box).
  - **Zero-Trust Security & Storage:** `AyraKeyManager` (purpose-separated Android Keystore AES-256-GCM / ECDSA P-256 / HMAC-SHA256), `VaultDatabaseKeyManager` + `SQLCipher` (Room v10, migrations `4->5->6->7->8->9->10`, fail-closed on corrupted wrapped key envelopes, embedding-model fingerprint reindexing via `EmbeddingProvider` / `SemanticEmbeddingAdapter`), `EncryptedVaultStorage` (`AYRAVLT1` containers with canonical path confinement), `AyraE2eeEngine` (P-256 ECDH + HKDF-SHA256 + persisted encrypted anti-replay watermarks + strict IV/mode validation + multi-epoch revocation), `BiometricAuthManager` + `VaultLockManager`, `NetworkSecurityHelper` (fail-closed DNS, IPv4-mapped IPv6/NAT64/6to4/Teredo/ULA blocking, validated-IP binding `PinnedResolvedDns`, redirect chain SSRF validation, pooled TLS 1.3/1.2), `FirebaseConfigConsistencyValidator`, `AyraDiagnosticsEngine` (secret-redacted latency/p95 telemetry), and `AiToolSecurityGuard`.
  - **Variant-Isolated App Check:** `DebugAppCheckProviderFactory` is restricted exclusively to the `debug` build (`src/debug/.../AppCheckDebugBridge.kt` + `debugImplementation`); release builds ship zero debug App Check code and install `RecaptchaAppCheckProviderFactory` via `recaptcha_site_key` in `app/src/main/res/values/firebase_applet_config.xml` (tracked explicitly by `AppCheckStatusRegistry` as `RELEASE_RECAPTCHA_ACTIVE`, `DEBUG_ACTIVE`, or `MISSING_RELEASE_CONFIG`).
- **Zero-Trust Backend (`backend/`):** Express + TypeScript gateway enforcing Firebase ID token verification (`checkRevoked = true`), Firebase App Check (`consume: true` replay protection), `.strict()` Zod validation, Helmet HSTS/CSP, explicit CORS allowlists, DNS-rebinding SSRF defense (`agent.connect` IP binding + redirect validation), pre-auth per-IP + distributed Redis (`Redis >= 7` required for distributed window scripts) / in-memory rate limiting, daily token quotas, secret redaction, `"staging"` config profile, and fail-closed `HTTP 503` on `/api/v1/code/run` with HMAC attestation receipt verification (`node:vm` removed). *(Status in mobile app: **Route B wired** in `ExternalAiProviders.generateChatResponse` via `SecureAiGatewayClient.shouldUseBackendGatewayProxy` when authenticated with Firebase Auth, external consent is enabled, gateway URL is configured, and BYOK preference is off.)*
- **Cloud Rules (`firestore.rules`):** Owner-scoped, schema-validated Firestore rules across user profiles, encrypted messages, plugins, creations, E2EE devices, sessions, backups, and append-only security events (with strict `allow read, write: if false` on unmodeled collections). See `docs/FACTS.md` for live generated file, SDK, and test counts.

## Build & Verification

### 1. Preflight & Single-Source-of-Truth Guard
```bash
bash scripts/preflight.sh
bash scripts/gen-facts.sh --check
```

### 2. Android Unit, Robolectric & Firebase Emulator Tests
```bash
firebase emulators:exec --project demo-no-project --only firestore,auth "gradle :app:testDebugUnitTest --max-workers=2 --console=plain"
```

### 3. Android Debug & R8-Minified Release APKs/AAB + Signing Gate
```bash
gradle :app:assembleDebug :app:assembleRelease :app:bundleRelease :app:verifyReleaseSigningCertificate --max-workers=2 --console=plain
# Fail-closed production upload keystore check (blocks debug.keystore in CI/release pipelines):
gradle :app:validateProductionReleaseSigning -PayraRequireProductionSigning=true
```
> **Note for Play Store Distribution & Google Sign-In:**
> 1. In-container `release` builds in this workspace intentionally retain `signingConfigs.release` (`debug.keystore`) per AI Studio container policy so local builds remain reproducible without modifying `signingConfigs`.
> 2. **Fresh clones / Play Store uploads CANNOT ship `debug.keystore`:** `debug.keystore` is gitignored and uses debug credentials. Before distributing publicly or uploading `.aab`/`.apk` to Google Play Console, configure a private production upload keystore via `keystore.properties` (see `keystore.properties.example`) or encrypted CI secrets, pass `-PayraRequireProductionSigning=true`, and enroll in Play App Signing.
> 3. **Production Google Sign-In Requirement:** See `app/google-services.json` for currently registered signing fingerprints. You **must** register your production upload key **and** Google Play App Signing key SHA-1 / SHA-256 fingerprints in **Firebase Console -> Project settings -> Your apps (`com.ayra.ai.app`) -> Add fingerprint**, then download the updated `app/google-services.json` before releasing; otherwise `CredentialManager` Google Sign-In will fail in production.

### 4. Firebase Configuration (`app/google-services.json` Single Source of Truth)
- Active Firebase project ID, project number, OAuth clients, and API key come exclusively from `app/google-services.json`, paired with `app/src/main/res/values/firebase_applet_config.xml` and verified at runtime by `FirebaseConfigConsistencyValidator`.
- Never copy project IDs or SHA hashes into markdown docs or code; check `docs/FACTS.md` for live project metadata generated from code.
- **API Key Restriction:** In Google Cloud Console -> APIs & Services -> Credentials, restrict the Android API key to package `com.ayra.ai.app` + your SHA-1/SHA-256 signing fingerprints, and enforce **Firebase App Check**.

### 5. Firestore Rules Verification
```bash
firebase emulators:exec --project demo-no-project --only firestore "node --test firestore.test.js"
```

### 6. Backend Build & Security Suite
```bash
npm --prefix backend ci
npm --prefix backend run build
npm --prefix backend test
```

## Further Documentation
- `docs/FACTS.md` — Auto-generated metrics, SDKs, Room version, and dependency versions
- `docs/INCIDENTS.md` — Incident log and automated regression guards
- `ARCHITECTURE_MAP.md` — Complete codebase & module responsibility map
- `SECURITY_ARCHITECTURE.md` — Key hierarchy, SQLCipher, E2EE, and zero-trust planes
- `SECURITY_AUDIT_REPORT.md` — Audit findings & remediation matrix
- `AUDIT.md` — Chronological remediation log
- `DEPLOYMENT_AND_RISK_REPORT.md` — Verified test/APK artifacts & production prerequisites
