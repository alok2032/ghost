# AYRA AI — Verification, Deployment & Honest Residual Risk Report

## 1. Verified Automated Security & Build Controls (18 Nov 2026 — Phase 3)

| Verification Suite | Command / Method | Outcome | Notes |
|---|---|---|---|
| **Android Compilation (`compile_applet` / Debug APK)** | `gradle :app:assembleDebug --console=plain` | **Passed** | `app/build/outputs/apk/debug/app-debug.apk` (`52,608,939` bytes, `19` DEX files, SHA-256 `6950352ce9f85a87083b2ff0ffc6a2b92f6218f5762f58eea36bcddd773f70df`). |
| **Android R8 Release Packaging & Signing Gate** | `gradle :app:assembleRelease :app:verifyReleaseSigningCertificate --console=plain` & `gradle :app:validateProductionReleaseSigning -PayraRequireProductionSigning=true` | **Passed (Build) / Fail-Closed Gate Verified** | `isMinifyEnabled = true`, `isShrinkResources = true`. `app/build/outputs/apk/release/app-release.apk` (`26,711,215` bytes, `1` DEX file, SHA-256 `c96bc3c2a4850942ce4c92680a433deba5d70b7334ed6562683e7fc5dd184e13`, ~49.2% smaller than Debug). Bytecode/manifest inspection confirmed `DebugAppCheckProviderFactory`, `com.google.firebase.appcheck.debug`, `CALL_PHONE`, and `SEND_SMS` are absent, `resources.arsc` matches active project `gen-lang-client-0153630349`, and `validateProductionReleaseSigning -PayraRequireProductionSigning=true` exits non-zero when `debug.keystore` is active. |
| **Android Unit, Robolectric & Emulator Tests** | `firebase emulators:exec --project demo-no-project --only firestore,auth "gradle :app:testDebugUnitTest --console=plain"` | **212 / 212 Passed (34 suites)** | 0 failed, 0 errors, 0 skipped. Includes Keystore AES-GCM/AAD/rotation, SQLCipher wrapper & Room `4->10` migrations + embedding fingerprint reindexing, vault AEAD & path confinement, E2EE persisted replay watermark & multi-epoch revocation, Biometric `CryptoObject` proof, PBKDF2 backups, prompt-injection framing for docs & tool outputs, `ToolArgumentValidator` & 8-state `AgentActionLifecycleState`, fail-closed DNS & IPv4-mapped IPv6/NAT64/6to4/Teredo/ULA/redirect SSRF blocks, HMAC log chain, secret-redacted `AyraDiagnosticsEngine` telemetry, LRU cache bounds, and Firestore rules against local Auth/Firestore emulators. |
| **Node.js / TypeScript Backend Build & Tests** | `npm --prefix backend run build && npm --prefix backend test` | **15 / 15 Passed** | Strict `.strict()` Zod validation, Helmet HSTS/CSP, Firebase Auth `checkRevoked=true` + App Check enforcement, RBAC 403, IPv4/IPv6/DNS-rebinding SSRF (`agent.connect` IP binding + redirect validation), CORS allowlist, secret redaction, token revocation, distributed Redis/in-memory rate limiters, daily token quota 429, model provenance catalog, and `/api/v1/code/run` fail-closed `503` + HMAC sandbox attestation verification. |
| **Firestore Security Rules Emulator Tests & Deployment** | `firebase emulators:exec --project demo-no-project --only firestore "node --test firestore.test.js"` & `DeployRules` RPC | **9 / 9 Passed & Deployed** | Verified owner isolation, unauthenticated/anonymous deny, immutable fields, message/backup chunk SHA-256 verification, unmodeled collection deny-by-default, and schema enums; live `firestore.rules` deployed cleanly. |
| **Physical Device / Instrumented UI Tests** | Physical device / ADB / Android Emulator | **NOT RUN** | Execution environment has no ADB or streaming emulator attachment; live hardware StrongBox, physical BiometricPrompt UI, runtime device sensors, and live network TLS pinning behavior remain unverified on physical hardware. |

---

## 2. Honest Residual Gaps & Production Deployment Prerequisites

No security posture is falsely overstated as complete where external infrastructure or physical hardware is required:

1. **Production Release Signing Keystore (`debug.keystore` Environment Constraint)**:
   - `app/build.gradle.kts` preserves `signingConfigs.release` pointing to `debug.keystore` (`CN=Android Debug, O=Android, C=US`) per build-environment signing-config constraints.
   - `ValidateProductionReleaseSigningTask` + `.github/workflows/security-ci.yml` enforce `-PayraRequireProductionSigning=true` / `AYRA_REQUIRE_PRODUCTION_SIGNING=true` so CI/release builds fail closed whenever `debug.keystore` or missing upload credentials are detected.
   - **Before Google Play Store submission**: Supply a private upload keystore via `keystore.properties` / CI secrets and enroll in Play App Signing (never publish Play Store artifacts signed with `debug.keystore`).
2. **Google Play Integrity Server Verdict Verification**:
   - The Play Integrity SDK (`com.google.android.play:integrity:1.4.0`) is integrated and `DeviceSecurityInspector` inspects debuggable/emulator/test-keys/root indicators accurately.
   - Play Integrity tokens must be verified server-side via a Cloud Project Service Account before treating device attestation as authoritative (`INTEGRITY_STATE` reports `UNKNOWN` until verified server-side).
3. **Backend TLS Termination, App Check & Secret Manager Injection**:
   - `/backend` (`projectId` default `gen-lang-client-0153630349`) is verified locally over HTTP loopback during unit testing. In production deployment (e.g., Cloud Run / Kubernetes / Nginx Ingress), deploy behind TLS 1.3 termination with valid CA certificates, set `NODE_ENV=production`, `REQUIRE_APP_CHECK=true`, `CORS_ALLOWED_ORIGINS=https://your-domain`, and inject provider keys via Cloud Secret Manager.
   - Configure `NetworkSecurityHelper.buildPinnedClient(...)` with production primary + backup SPKI SHA-256 pins for your backend domain.
   - Verify `recaptcha_site_key` in release resources (`firebase_applet_config.xml`) and enable Firebase App Check enforcement in Firebase Console for `gen-lang-client-0153630349` so `RecaptchaAppCheckProviderFactory` activates in release builds.
4. **Physical Flash Wear-Leveling Limitation**:
   - On unrooted consumer Android devices, `SecureWipeFile` overwrites temporary files with random bytes + zeros before deletion and `DataErasureManager` destroys all Android Keystore keys (**cryptographic erasure**). Underlying NAND flash wear-leveling blocks remain governed by the OS/UFS controller.

