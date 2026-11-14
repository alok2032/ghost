# AYRA AI — Verification, Deployment & Honest Residual Risk Report

## 1. Verified Automated Security & Build Controls (14 Nov 2026)

| Verification Suite | Command / Method | Outcome | Notes |
|---|---|---|---|
| **Android Compilation (`compile_applet` / Debug APK)** | `gradle :app:assembleDebug --console=plain` | **Passed** | `app/build/outputs/apk/debug/app-debug.apk` (`52,877,120` bytes, 20 DEX files, SHA-256 `ed9ca8b0145e2f67b59f42ffbcf4d72db6a9638b3dcb161b0438fe97a7400dd6`). |
| **Android R8 Release Packaging** | `gradle :app:assembleRelease --console=plain` | **Passed** | `isMinifyEnabled = true`, `isShrinkResources = true`. `app/build/outputs/apk/release/app-release.apk` (`27,533,706` bytes, 1 DEX file, SHA-256 `6bd723f4f45f7dac6ffb2f5393662d9711775628a13d9f23e1c95deaee0eceb9`, ~47.9% smaller than Debug). Bytecode/manifest inspection confirmed `DebugAppCheckProviderFactory`, `com.google.firebase.appcheck.debug`, `CALL_PHONE`, and `SEND_SMS` are absent from the release APK. |
| **Android Unit, Robolectric & Emulator Tests** | `firebase emulators:exec --project demo-no-project --only firestore,auth "gradle :app:testDebugUnitTest --console=plain"` | **155 / 155 Passed (24 suites)** | 0 failed, 0 skipped. Includes Keystore AES-GCM/AAD/rotation, SQLCipher wrapper & Room `4->5->6` migrations, vault AEAD & path confinement, E2EE replay & multi-epoch revocation, Biometric `CryptoObject` proof, PBKDF2 backups, prompt-injection guard, SSRF blocks, HMAC log chain, erasure, ComfyUI traversal guard, Local-Only privacy blocking, coroutine cancellation, and Firestore rules against local Auth/Firestore emulators. |
| **Node.js / TypeScript Backend Build & Tests** | `npm --prefix backend ci && npm --prefix backend run build && npm --prefix backend test` | **8 / 8 Passed** | Strict `.strict()` Zod validation, Helmet HSTS/CSP, Firebase Auth `checkRevoked=true` + App Check enforcement, RBAC 403, IPv4/IPv6/DNS-rebinding SSRF blocks, CORS allowlist, secret redaction, token revocation, and 429 rate limiting. |
| **Firestore Security Rules Emulator Tests** | `firebase emulators:exec --project demo-no-project --only firestore "node --test firestore.test.js"` | **6 / 6 Passed** | Verified owner isolation, unauthenticated deny, immutable fields, and schema enums across `users`, `messages`, `plugins`, `creations`, `devices`, `sessions`, `backups`, and `securityEvents`. |
| **Physical Device / Instrumented UI Tests** | Physical device / ADB / Android Emulator | **NOT RUN** | Execution environment has no ADB or streaming emulator attachment; live hardware StrongBox, physical BiometricPrompt UI, runtime device sensors, and live network TLS pinning behavior remain unverified on physical hardware. |

---

## 2. Honest Residual Gaps & Production Deployment Prerequisites

No security posture is falsely overstated as complete where external infrastructure or physical hardware is required:

1. **Production Release Signing Keystore (`debug.keystore` Environment Constraint)**:
   - `app/build.gradle.kts` preserves `signingConfig = signingConfigs.getByName("release")` pointing to `debug.keystore` (`CN=Android Debug, O=Android, C=US`, SHA-256 `277b62de228285b3f450857b6123590e3ac348580f8a22c8731997ed209e1e0a`) per build-environment signing-config constraints.
   - **Before Google Play Store submission**: Replace the release signing configuration in your CI/Play App Signing pipeline with a dedicated hardware/KMS-backed upload keystore (never publish Play Store artifacts signed with `debug.keystore`).
2. **Google Play Integrity Server Verdict Verification**:
   - The Play Integrity SDK (`com.google.android.play:integrity:1.4.0`) is integrated and `DeviceSecurityInspector` inspects debuggable/emulator/test-keys/root indicators accurately.
   - Play Integrity tokens must be verified server-side via a Cloud Project Service Account before treating device attestation as authoritative (`INTEGRITY_STATE` reports `UNKNOWN` until verified server-side).
3. **Backend TLS Termination, App Check & Secret Manager Injection**:
   - `/backend` (`projectId` default `gen-lang-client-0643978985`) is verified locally over HTTP loopback during unit testing. In production deployment (e.g., Cloud Run / Kubernetes / Nginx Ingress), deploy behind TLS 1.3 termination with valid CA certificates, set `NODE_ENV=production`, `REQUIRE_APP_CHECK=true`, `CORS_ALLOWED_ORIGINS=https://your-domain`, and inject provider keys via Cloud Secret Manager.
   - Configure `NetworkSecurityHelper.buildPinnedClient(...)` with production primary + backup SPKI SHA-256 pins for your backend domain.
   - Populate `recaptcha_site_key` in release resources (and enable Firebase App Check enforcement in Firebase Console for `gen-lang-client-0643978985`) so `RecaptchaAppCheckProviderFactory` activates in release builds.
4. **Physical Flash Wear-Leveling Limitation**:
   - On unrooted consumer Android devices, `SecureWipeFile` overwrites temporary files with random bytes + zeros before deletion and `DataErasureManager` destroys all Android Keystore keys (**cryptographic erasure**). Underlying NAND flash wear-leveling blocks remain governed by the OS/UFS controller.

