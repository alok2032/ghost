# AYRA AI — Master Engineering Audit, Model Renewal & Hardening Report (`FINAL_REPORT.md`)

**Project:** Ayra AI (`com.ayra.ai.app`)
**Audit & Verification Date:** 2026-11-21
**Verification Status:** All automated preflight checks, Android JVM/Robolectric unit tests (**40 suites, 252 / 252 passed**), Debug APK, R8 Release APK, Release AAB, Android `lintDebug` & `lintVitalRelease`, fail-closed production signing gate, Node.js 22 backend tests (**17 / 17**), and Firebase Firestore Rules emulator tests (**9 / 9**) **PASSED** against the final source tree. Physical hardware, streaming emulator interactive QA, Play Store upload keystore provisioning, and live third-party LLM endpoint round-trips remain honestly marked **UNVERIFIED** (no physical device / ADB / production keystore / live provider keys in this cloud container).

---

## 1. Executive Summary Matrix (`ID | Status | Files Changed | Guard Added | Verification Evidence`)

| ID | Status | Files Changed | Automated Guard Added | Verification Evidence |
|---|---|---|---|---|
| **H1 / A1** Production Signing Gate & Upload Keystore CI Check | **FIXED (CI/Gate); Dev-Container WARN** (`UNVERIFIED` on real Play upload `.jks`) | `.github/workflows/security-ci.yml`, `RELEASE_SIGNING.md`, `app/build.gradle.kts` (`signingConfigs` retained per container rule; `ValidateProductionReleaseSigningTask` preserved) | `validateProductionReleaseSigning -PayraRequireProductionSigning=true` fails closed on missing/debug keystore; CI workflow tests ephemeral PKCS12 keystore validation via `keytool` | `gradle :app:validateProductionReleaseSigning -PayraRequireProductionSigning=true` exited non-zero (`PRODUCTION SIGNING MISCONFIGURED`); `:app:verifyReleaseSigningCertificate` exited 0 |
| **H2** Backend Per-User-First & Pre-Auth IP Rate Limiting | **FIXED & VERIFIED** | `backend/src/middleware/rateLimit.ts`, `backend/src/app.ts` | Per-user check evaluated before incrementing global counter + pre-auth per-IP rate limiter | `backend/test/security.test.mjs` Subtest 16 (**17 / 17 passed**) |
| **H3** `reminder_schedule` REMINDER-Only & Taint Confirmation Gate | **FIXED & VERIFIED** | `AyraTaskScheduler.kt`, `AgentToolRegistry.kt`, `AgentCore.kt`, `scripts/preflight.sh` | `scheduleReminderOnly` restricts `reminder_schedule` strictly to `REMINDER`; `AgentCore` holds tainted `reminder_schedule` behind `ProposedAction.ExecuteExternalTool`; `preflight.sh` Section 14 fails if `scheduleTaskFromPrompt` appears in `AgentToolRegistry.kt` | `SecurityAndModelUpgradeRegressionTest` & `preflight.sh` Section 14 `ok` |
| **H4** Active 2026-11-20 Model Catalog, `410` Retirement & Failover Notices | **FIXED & VERIFIED** | `config/dead-models.txt`, `ModelCatalog.kt`, `ExternalAiProviders.kt`, `AiModelRegistry.kt`, `FirebaseAiEngine.kt`, `ModelTierSpec.kt`, `Provider*Transport.kt`, `backend/src/services/modelCatalog.ts`, `.github/workflows/model-health.yml` | Updated all default/fallback IDs (`gemini-3.8-flash`, `gemini-4-argon`, `gpt-6.2-sol`/`astra`/`gpt-6-luna`, `claude-*-5-5`, `deepseek-v4-pro`/`flash`, `grok-4.7`, `qwen/qwen3.8-27b`, `openrouter/free`), retired `GITHUB_MODELS` (`2026-07-30` Azure shutdown), tracked HTTP `410` as `RETIRED`, added visible `[Model Notice: ...]` failover banners, marked unprobed Blaze models `UNKNOWN`; enforced by `preflight.sh` Section 4 | `SecurityAndModelUpgradeRegressionTest` + `preflight.sh` Section 4 `ok` |
| **M1 / M2 / M7** Alarm Reboot/Time-Shift Recovery & WhatsApp Recipient Fix | **FIXED & VERIFIED** | `AyraTaskScheduler.kt`, `ScheduledTaskReceiver.kt`, `AndroidManifest.xml` | Registered `ACTION_TIME_CHANGED` & `ACTION_TIMEZONE_CHANGED`, preserved sanitized phone digits in `buildWhatsAppUri`, split `rearmAllPendingTasks` catch-up window (`15m`: `FIRED` vs `MISSED` + missed notification) | `SecurityAndModelUpgradeRegressionTest` PASSED |
| **M3 / M4** Redis `>= 7` Atomic Rate-Limit Scripts & Proxy Config | **FIXED & VERIFIED** | `backend/src/middleware/rateLimit.ts`, `backend/src/config.ts`, `README.md` | Atomic Lua/pipeline rate-limit & concurrency scripts + strict `TRUST_PROXY_HOPS` parsing | `backend/test/security.test.mjs` Subtest 17 PASSED |
| **M5** Route B Gateway Proxy Wiring & ProviderPool Consent Filter | **FIXED & VERIFIED** | `ExternalAiProviders.kt`, `ProviderPool.kt`, `ProviderAnthropicAndFreeTransport.kt` | Wired `SecureAiGatewayClient.shouldUseBackendGatewayProxy` in `ExternalAiProviders.generateChatResponse`, enforced `evaluateProviderAccess` in `ProviderPool.streamChat`, changed Pollinations null-context fallbacks to `?: false` | `SecurityAndModelUpgradeRegressionTest` (MockWebServer Route B test) PASSED |
| **Phase 12** Fail-Closed Privacy & Explicit Consent Defaults | **FIXED & VERIFIED** | `AyraPreferences.kt`, `OnboardingOverlay.kt`, `SecurityCenterScreen.kt`, `ChatScreen.kt`, `ProviderKeysSection.kt`, `ChatScreenMediaHelper.kt` | `hasExternalAiConsent`, `isFreeAiPoolEnabled`, `hasFreePoolConsent`, `isCloudSyncEnabled`, `isOnboardingCompleted` all default `false`; saving API keys never silently flips consent | `AutoConnectBestAnswerTest`, `Phase2HardeningAndAgentTest` (`ChatScreen.kt < 800L`), `SecurityAndModelUpgradeRegressionTest` PASSED |
| **A2 / A3** Agent Core Taint Tracking & NFKC/Multilingual Boundary | **FIXED & VERIFIED** | `AgentCore.kt`, `AiToolSecurityGuard.kt` | Per-run `taintedByUntrustedData`, always-on `<untrusted_external_data>` wrapping, NFKC + `ZERO_WIDTH_OR_BIDI_REGEX` stripping | `Phase3HardeningAndRegressionTest` + `AyraEvalSuiteTest` PASSED |
| **A4 / A5 / B1** NSC Parity, Redirect SSRF & Bounded HTTP Readers | **FIXED & VERIFIED** | `NetworkSecurityHelper.kt`, `res/xml/network_security_config.xml`, 15 caller files | Loopback-only cleartext HTTP, redirect-hop SSRF validation + cross-origin auth strip, bounded `readBounded`/`readBoundedBytes` everywhere (`preflight.sh` Sections 10–11) | `Phase3HardeningAndRegressionTest` PASSED; `preflight.sh` Sections 10–11 `ok` |
| **B2–B9 / C1–C4 / §3** FileProvider SSOT, Keystore Consolidation, WebView CSP, Backend Lockfile | **FIXED & VERIFIED** | `StorageHelper.kt`, `AyraPreferences.kt`, `ThreeDModelCard.kt`, `CyberTerminalEngine.kt`, `SecurityResearchEngine.kt`, `backend/*`, `firestore.rules` | Unified `ExportPaths`, Keystore-only secret writes, hardened WebView CSP + float sanitization, `GHOST AI` -> `AYRA AI` branding cleanup, Room `v10` schema JSON | `Phase3HardeningAndRegressionTest`, `AyraSecurityRegressionTest`, `backend` (`17/17`), `firestore.test.js` (`9/9`) PASSED |

---

## 2. Real Verified Command Outputs (`2026-11-21`)

### 2.1 Facts Generation & Preflight Guard
```text
$ bash scripts/gen-facts.sh && bash scripts/gen-facts.sh --check && bash scripts/preflight.sh
Updated docs/FACTS.md
ok: docs/FACTS.md is up to date
...
== 4. Dead Models & Legacy Unsafe Aliases ==
  ok    no dead/retiring model IDs active in app/src/main (verified against config/dead-models.txt)
  ok    no legacy qwen_uncensored/godmode aliases outside preference migration
...
== 14. Automation & Reminder Tool Confirmation Guard (H3) ==
  ok    reminder_schedule restricted to scheduleReminderOnly and gated by taint guard in AgentCore

PREFLIGHT: clean. (2 WARN(s))
```
*(The 2 `WARN`s are the intentional AI Studio container `debug.keystore` warnings guarded by `ValidateProductionReleaseSigningTask`.)*

### 2.2 Android Unit & Robolectric Tests (with Firebase Firestore & Auth Emulators)
```text
$ chmod +x gradlew scripts/*.sh && firebase emulators:exec --project demo-no-project --only firestore,auth "gradle :app:testDebugUnitTest --max-workers=2 --console=plain"
SUITES=40 TESTS=252 FAIL=0 ERR=0 SKIP=0
```

### 2.3 Debug APK, Release R8 APK, Release AAB, Signing Gate & Android Lint
- `gradle :app:assembleDebug :app:assembleRelease :app:bundleRelease :app:verifyReleaseSigningCertificate --max-workers=1 --console=plain` → `BUILD SUCCESSFUL`
  - `app/build/outputs/apk/debug/app-debug.apk`: `52,399,992` bytes (`SHA-256: d07e6361f66ab87a8e08ac1b1243813d172034ea0b19dd8881fc9a9aa741ba0b`)
  - `app/build/outputs/apk/release/app-release.apk`: `26,662,661` bytes (`SHA-256: c906433414f11a2ba7040eae487c7ff6afeaa9c922a0e122c8c9e3d0f656fe43`)
  - `app/build/outputs/bundle/release/app-release.aab`: `20,544,690` bytes (`SHA-256: b9991ac6633f7309d02069d8f56054fa70b85baae4695c7ac6947961464e2440`)
- `gradle :app:lintDebug --max-workers=2 --console=plain` → `BUILD SUCCESSFUL in 3m 18s` (`app/build/reports/lint-results-debug.html`, zero fatal/error issues)
- `gradle :app:validateProductionReleaseSigning -PayraRequireProductionSigning=true --console=plain` → Fail-closed as designed (`PRODUCTION SIGNING MISCONFIGURED: Release build requires a private upload keystore`)

### 2.4 Backend Node 22 Service & Firestore Security Rules Emulator Tests
- `npm --prefix backend ci --no-audit --no-fund && npm --prefix backend run build && npm --prefix backend test` → `# tests 17, # pass 17, # fail 0`
- `firebase emulators:exec --project demo-no-project --only firestore "node --test firestore.test.js"` → `# tests 9, # pass 9, # fail 0` (`Script exited successfully (code 0)`)

---

## 3. Explicitly UNVERIFIED Items (Hardware / Container Constraints)

1. **Physical Device / Streaming Emulator Runtime & Instrumented UI Tests (`androidTest` / Macrobenchmark):** **UNVERIFIED** — No Android Emulator or ADB is available in the server-side build container (`androidTest` execution and device hardware camera/biometric/torch/microphone/WebGL rendering tests cannot be executed here).
2. **Production Play Store Upload Signing & Real Firebase App Check Play Integrity Attestation:** **UNVERIFIED** — Container policy retains `signingConfigs.release` pointing to `debug.keystore` for local container builds; production distribution MUST supply external upload-keystore credentials (`AYRA_UPLOAD_KEYSTORE_PATH` / `keystore.properties`) with `-PayraRequireProductionSigning=true` and register Upload + Play App Signing SHA-1/SHA-256 fingerprints in `app/google-services.json`.
3. **Live External LLM / Cloud API Round-Trips:** **UNVERIFIED** — Unit tests verify transports cleanly against `OkHttp MockWebServer` and local Firebase Auth/Firestore emulators without live third-party provider API keys; Route B proxy activation and `410`/failover handling are verified under MockWebServer.
