# AYRA AI — Verification, Deployment & Honest Residual Risk Report

## 1. Verified Automated Security & Build Controls (21 Nov 2026 — Phase 4)

| Verification Suite | Command / Method | Outcome | Notes |
|---|---|---|---|
| **Android Debug APK Build** | `gradle :app:assembleDebug --max-workers=1 --console=plain` | **Passed** | `app/build/outputs/apk/debug/app-debug.apk` (`52,399,992` bytes, SHA-256 `d07e6361f66ab87a8e08ac1b1243813d172034ea0b19dd8881fc9a9aa741ba0b`). |
| **Android R8 Release APK, AAB & Signing Gate** | `gradle :app:assembleRelease :app:bundleRelease :app:verifyReleaseSigningCertificate --console=plain` & `gradle :app:validateProductionReleaseSigning -PayraRequireProductionSigning=true` | **Passed (Build) / Fail-Closed Gate Verified** | `app-release.apk` (`26,662,661` bytes, SHA-256 `c906433414f11a2ba7040eae487c7ff6afeaa9c922a0e122c8c9e3d0f656fe43`) & `app-release.aab` (`20,544,690` bytes, SHA-256 `b9991ac6633f7309d02069d8f56054fa70b85baae4695c7ac6947961464e2440`); `isMinifyEnabled = true`, `isShrinkResources = true`, `lintVitalRelease` + `lintDebug` passed, and `validateProductionReleaseSigning -PayraRequireProductionSigning=true` exits non-zero when `debug.keystore` is active. |
| **Android Unit, Robolectric & Emulator Tests** | `firebase emulators:exec --project demo-no-project --only firestore,auth "gradle :app:testDebugUnitTest --max-workers=2 --console=plain"` | **252 / 252 Passed (40 suites)** | `0` failed, `0` errors, `0` skipped. Includes Keystore AES-GCM/AAD/rotation, SQLCipher wrapper & Room `4->10` migrations + embedding fingerprint reindexing, vault AEAD & path confinement, E2EE persisted replay watermark & multi-epoch revocation, Biometric `CryptoObject` proof, PBKDF2 backups, H3 `reminder_schedule` REMINDER-only & taint gating, Phase 12 fail-closed privacy defaults, M1/M2/M7 reboot/time-shift/WhatsApp scheduling, H4 `2026-11-20` model catalog & HTTP `410` retirement notices, M5 Route B gateway proxy (`MockWebServer`), SSRF redirect & IPv6 blocking, and Firestore rules against local Auth/Firestore emulators. |
| **Node.js / TypeScript Backend Build & Tests** | `npm --prefix backend ci && npm --prefix backend run build && npm --prefix backend test` | **17 / 17 Passed** | Strict `.strict()` Zod validation, Helmet HSTS/CSP, Firebase Auth `checkRevoked=true` + App Check enforcement, RBAC 403, IPv4/IPv6/DNS-rebinding SSRF (`agent.connect` IP binding + redirect validation), CORS allowlist, secret redaction, token revocation, H2 per-user-first + pre-auth per-IP rate limiting, M3/M4 atomic Redis `>= 7` concurrency Lua scripts & token quota settlement/refund, model provenance catalog (`2026-11-20`), and `/api/v1/code/run` fail-closed `503` + HMAC sandbox attestation verification. |
| **Firestore Security Rules Emulator Tests** | `firebase emulators:exec --project demo-no-project --only firestore "node --test firestore.test.js"` | **9 / 9 Passed** | Verified owner isolation, unauthenticated/anonymous deny, immutable fields, message/backup chunk SHA-256 verification, unmodeled collection deny-by-default, and schema enums. |
| **Physical Device / Live External Cloud APIs** | Physical device / ADB / Live LLM endpoints | **UNVERIFIED** | Container has no ADB, physical hardware, Android emulator, private Play upload `.jks`, or live external LLM provider API keys; biometric hardware, camera/mic sensors, production upload signing, and live upstream provider round-trips remain unverified here. |

---

## 2. Honest Residual Gaps & Production Deployment Prerequisites

No security posture is falsely overstated as complete where external infrastructure or physical hardware is required:

1. **Production Release Signing Keystore (`debug.keystore` Environment Constraint)**:
   - `app/build.gradle.kts` preserves `signingConfigs.release` pointing to `debug.keystore` (`CN=Android Debug, O=Android, C=US`) per build-environment signing-config constraints.
   - `ValidateProductionReleaseSigningTask` + `.github/workflows/security-ci.yml` enforce `-PayraRequireProductionSigning=true` / `AYRA_REQUIRE_PRODUCTION_SIGNING=true` so CI/release builds fail closed whenever `debug.keystore` or missing upload credentials are detected.
   - **Before Google Play Store submission**: Supply a private upload keystore via `keystore.properties` / CI secrets, enroll in Play App Signing, and register both upload + Play App Signing SHA-1/SHA-256 fingerprints in Firebase Console.
2. **Google Play Integrity Server Verdict Verification**:
   - The Play Integrity SDK (`com.google.android.play:integrity:1.4.0`) is integrated and `DeviceSecurityInspector` inspects debuggable/emulator/test-keys/root indicators accurately.
   - Play Integrity tokens must be verified server-side via a Cloud Project Service Account before treating device attestation as authoritative (`INTEGRITY_STATE` reports `UNKNOWN` until verified server-side).
3. **Backend TLS Termination, App Check, Secret Manager Injection & Route B Connection Status**:
   - `/backend` requires `FIREBASE_PROJECT_ID` and Redis `>= 7` (for multi-instance distributed rate/concurrency Lua scripts) via environment variables with zero hardcoded fallbacks.
   - **Connection status:** Mobile **Route B proxy integration is now wired** in `ExternalAiProviders.generateChatResponse` via `SecureAiGatewayClient.shouldUseBackendGatewayProxy` (activating automatically when Firebase Auth is signed in, external consent is enabled, a validated backend gateway URL is configured, and BYOK preference is off; verified against `MockWebServer`). In production deployment (Cloud Run / Kubernetes / Dockerfile), deploy behind TLS 1.3 termination with valid CA certificates, set `NODE_ENV=production`, `REQUIRE_APP_CHECK=true`, `CORS_ALLOWED_ORIGINS=https://your-domain`, configure `TRUST_PROXY_HOPS`, and inject provider keys via Cloud Secret Manager.
   - Configure `NetworkSecurityHelper.buildPinnedClient(...)` with production primary + backup SPKI SHA-256 pins for your backend domain.
   - Verify `recaptcha_site_key` in release resources (`firebase_applet_config.xml`) and enable Firebase App Check enforcement in Firebase Console for the active project in `app/google-services.json`.
4. **Physical Flash Wear-Leveling Limitation**:
   - On unrooted consumer Android devices, `SecureWipeFile` overwrites temporary files with random bytes + zeros before deletion and `DataErasureManager` destroys all Android Keystore keys (**cryptographic erasure**). Underlying NAND flash wear-leveling blocks remain governed by the OS/UFS controller.

