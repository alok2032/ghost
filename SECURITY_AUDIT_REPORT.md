# AYRA AI — Phase 1 Complete Enterprise Security Audit Report (Deliverable A)

**Target Application**: `AYRA AI` (`com.ayra.ai.app`)  
**Target SDKs**: `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`  
**Audit Scope**: Full inspection of Android Kotlin/Compose codebase, Gradle build & manifest, Firebase configuration & Firestore rules, cryptographic key management, SQLite/Room storage, file storage, AI routing/gateways, plugins/RAG, network clients, permissions, and UI controls.

---

## 1. Executive Architecture Inventory

| Layer | Technology Identified | Current State Summary |
|---|---|---|
| **Mobile Framework** | Kotlin 2.2.10 + Jetpack Compose (Material 3) | Single-activity Compose UI (`MainActivity`, `AyraWorkspaceHost`) |
| **Backend Infrastructure** | Firebase (`auth`, `firestore`, `appcheck`, `ai`) | Client-direct Firestore + Firebase AI Logic (`AppCheckInit`); **no zero-trust custom backend gateway** |
| **Database** | Room 2.7.0 (`ChatDatabase` v6, SQLite `ayra_chat_database`) | **Unencrypted plaintext SQLite** (`chats`, `sessions`, `plugins`, `scheduled_tasks`, `knowledge_documents`, `document_chunks`) |
| **AI Integrations** | 3 Tiers: (1) Local Ollama/ComfyUI, (2) Firebase AI Logic (`Firebase.ai`), (3) 14 External REST Providers (`ExternalAiProviders`) + `POLLINATIONS_FREE` | Direct BYOK REST + Firebase AI Logic + unconsented default-on `POLLINATIONS_FREE` (`isFreeAiPoolEnabled = true`) |
| **Authentication** | Google Sign-In via Jetpack `CredentialManager` -> Firebase Auth | Enforces Firebase Auth sign-in; **no BiometricPrompt / local vault lock / session or device registry** |
| **Local Keystore** | `SecureKeyStore.kt` & `EncryptedPreferences.kt` (AES-256-GCM) | Stores API keys in SharedPreferences ciphertext; **lacks StrongBox, `KeyInfo` verification, AAD binding, rotation, biometric keys, and fails open in exception fallback** |
| **Sensitive Preferences** | `AyraPreferences.kt` (`ayra_ai_user_prefs`), `AiUsageTracker.kt` | **Plaintext SharedPreferences** storing custom instructions, remembered personal memory facts, local host URLs, active session ID, and provider usage |
| **File Storage** | `StorageHelper.kt`, `FirebaseAiEngine.kt`, `ProjectWorkspaceManager.kt` (`context.filesDir`) | **Plaintext** files across 9 folders (`saved_attachments`, `generated_pdfs`, `generated_docs`, `generated_images`, `generated_audio`, `generated_3d`, `chat_exports`, `code_projects`, `project_zips`) |
| **Cloud Sync** | `AyraCloudRepository.kt` -> Cloud Firestore (`/users/{uid}/...`) | Owner-scoped rules (`firestore.rules`), **stores chat content, plugins, and creations in plaintext (no client-side E2EE)** |
| **Network Security** | `OkHttpClient` (`ExternalAiProviders`, `ComfyUiClient`, `CyberTerminalEngine`, `ElevenLabsVoiceEngine`), `network_security_config.xml` | Cleartext permitted for `localhost`/`127.0.0.1`/`10.0.2.2`; **missing TLS 1.3 spec, pinning, and SSRF validation in `CyberTerminalEngine`/`ComfyUiClient`/`Qwen38UncensoredEngine`/`ProviderKeysSection`** |

---

## 2. Critical & High-Severity Audit Findings

### FINDING-01 [CRITICAL] — Plaintext Room SQLite Database (`ChatDatabase.kt`)
- **Evidence**: `ChatDatabase.getDatabase()` opens `ayra_chat_database` via standard unencrypted SQLite.
- **Risk**: Rooted devices, ADB backup extraction on misconfigured builds, or physical forensic dumps expose all chat history, personal RAG documents/chunks, GitHub plugin specs, and scheduled tasks in readable plaintext (`"SQLite format 3"`).
- **Remediation**: Enable SQLCipher (`net.zetetic:sqlcipher-android`) with a 256-bit cryptographically random passphrase wrapped by hardware-backed Android Keystore KEK (`AyraKeyManager`) and perform automatic encrypted migration of legacy databases.

### FINDING-02 [CRITICAL] — Unencrypted Workspace Files & Temp PDFs (`StorageHelper.kt`, `ProjectWorkspaceManager.kt`, `FirebaseAiEngine.kt`)
- **Evidence**: Attachments (`saved_attachments`), generated PDFs/Docs/Images, exported chat history, and backup JSONs are written unencrypted directly to disk; PDF rendering/extraction leaves temporary `.pdf` copies in `cacheDir` using plain `delete()` without byte overwriting.
- **Risk**: Sensitive user documents and images sit unencrypted in internal storage.
- **Remediation**: Build Envelope Encryption (`EncryptedVaultStorage`) with unique per-file 256-bit AES-GCM DEKs + AAD wrapped by Android Keystore file KEK, plus secure overwriting temp-file erasure.

### FINDING-03 [CRITICAL] — Missing True End-to-End Encryption & Cloud Metadata Plaintext (`AyraCloudRepository.kt`)
- **Evidence**: `/users/{uid}/messages/{messageId}` writes plaintext `content` and `craftPayload` to Firestore. No device identity keypairs, ECDH key agreement, forward secrecy epochs, key fingerprints, or device revocation exist.
- **Risk**: Cloud backend/database compromise or multi-device token compromise exposes user conversation history without client-side confidentiality.
- **Remediation**: Implement real P-256 ECDH + HKDF-SHA256 + AES-256-GCM + ECDSA P-256 device identity signing (`AyraE2eeEngine`), epoch-based forward secrecy, replay sequence validation, device trust/revocation registry, and ciphertext-only Firestore E2EE envelopes.

### FINDING-04 [HIGH] — Weaknesses in Android Keystore Wrapper (`SecureKeyStore.kt`, `EncryptedPreferences.kt`)
- **Evidence**:
  1. Fallback `catch (e: Exception)` silently falls back to an in-memory AES key `jvmTestKey` even on real Android devices if `AndroidKeyStore` throws — breaking fail-closed guarantees.
  2. Does not use `setIsStrongBoxBacked(true)` (API 28+), does not inspect `KeyInfo` hardware security level, does not bind AAD, lacks key rotation, and defines two redundant master keys (`ayra_ai_master_aes_key`, `ayra_encrypted_prefs_master_key`).
- **Remediation**: Introduce unified `AyraKeyManager` with purpose-separated keys, genuine StrongBox + `KeyInfo` inspection (`HARDWARE_STRONGBOX` / `HARDWARE_TEE` / `SOFTWARE` / `UNKNOWN`), fail-closed production enforcement, key rotation, and invalidation recovery.

### FINDING-05 [HIGH] — Sensitive User Preferences Stored in Plaintext (`AyraPreferences.kt`, `AiUsageTracker.kt`)
- **Evidence**: `user_custom_instructions`, `user_remembered_facts` (personal memories), `local_ollama_host`, and `AiUsageTracker` metrics sit in unencrypted SharedPreferences.
- **Remediation**: Transparently migrate sensitive preferences & usage metrics into Keystore-encrypted storage and purge legacy plaintext keys.

### FINDING-06 [HIGH] — Zero Biometric Protection, Auto-Lock, or Re-Authentication (`MainActivity.kt`, `DiagnosticsToolsScreen.kt`)
- **Evidence**: App relies solely on Firebase Auth login state. No `androidx.biometric:biometric`, no `BiometricPrompt` + `CryptoObject`, no inactivity/background vault lock, and no step-up authentication before exporting chats/backups or clearing all data.
- **Remediation**: Implement `BiometricAuthManager` with `CryptoObject` cipher verification + `DEVICE_CREDENTIAL` fallback, `VaultLockManager` with configurable auto-lock, and mandatory step-up re-auth for destructive/export flows.

### FINDING-07 [HIGH] — Missing Zero-Trust Backend Gateway & Unconsented External AI Routing
- **Evidence**: No server-side gateway exists for authenticated API proxying, RBAC, rate limiting, or SSRF mediation. Meanwhile `isFreeAiPoolEnabled` defaults to `true`, silently permitting fallback to `text.pollinations.ai` without explicit consent.
- **Remediation**: Build standalone Zero-Trust Node/TypeScript backend (`backend/`) with Firebase ID token verification, App Check support, Zod schema validation, rate limiting, SSRF guards, and audit logging; require explicit opt-in before external AI routing; implement `Local-Only AI Mode` & `Privacy Mode` that enforce hard network blocks in code.

### FINDING-08 [HIGH] — SSRF & Cleartext Exposure in Network Tools (`CyberTerminalEngine.kt`, `ComfyUiClient.kt`, `Qwen38UncensoredEngine.kt`, `ProviderKeysSection.kt`)
- **Evidence**: `CyberTerminalEngine.curl` and `headers` execute HTTP requests against arbitrary URLs without private/link-local/loopback IP checks (`169.254.169.254`, `10.x`, `127.0.0.1`, `http://`). `ComfyUiClient` and `ProviderKeysSection` allow untrusted `http://` endpoints without LAN/SSRF policy verification. Shared `OkHttpClient` instances lack explicit TLS 1.3 `ConnectionSpec` and SPKI pinning hooks.
- **Remediation**: Enforce `NetworkSecurityHelper` / `UrlSecurityValidator` across all HTTP entrypoints and enforce TLS 1.3 `ConnectionSpec` + configurable certificate pinning.

### FINDING-09 [HIGH] — Indirect Prompt Injection & Unconfirmed OpenAPI Tool Execution (`GitHubPluginEngine.kt`, `DocumentRagEngine.kt`)
- **Evidence**: Untrusted repo `SKILL.md` / `README.md` / RAG document chunks are concatenated directly into system/user prompts without boundary framing or injection scanning; matching OpenAPI GET calls execute automatically without user approval.
- **Remediation**: Add `AiToolSecurityGuard` separating untrusted data delimiters, scanning for prompt-injection/exfiltration vectors, and gating plugin/tool invocations behind user confirmation.

### FINDING-10 [MEDIUM] — Android Backup Rules, Clipboard Sensitivity, Screenshot Protection & Legacy Launcher Assets
- **Evidence**:
  - `backup_rules.xml` & `data_extraction_rules.xml` are template stubs without explicit `<exclude>` paths for DB, encrypted prefs, or vault directories.
  - Clipboard copies in `ChatScreen.kt` & `CyberTerminalCard.kt` lack `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+) and timed clearing.
  - No `FLAG_SECURE` window protection or tapjacking (`filterTouchesWhenObscured`) on sensitive security controls.
  - Default template `.webp` launcher icons remain in `res/mipmap-*/`.
- **Remediation**: Configure strict XML exclusion rules, implement `SensitiveClipboardHelper` & `FLAG_SECURE`, harden manifest/components, and replace template `.webp` launcher assets with AYRA custom adaptive/non-adaptive PNGs.

---

## 3. Post-Remediation Audit Status (Updated 18 Nov 2026 — Phase 2 Sync)

All 10 findings (`FINDING-01` through `FINDING-10`) remain remediated and verified in the codebase, with Phase 2 audit hardening applied:

| Finding | Implemented Controls | Verification Status |
|---|---|---|
| **FINDING-01** | `VaultDatabaseKeyManager` + `sqlcipher-android` (`SupportOpenHelperFactory`), `PRAGMA secure_delete=ON`, automatic legacy SQLite export migration, exported Room schemas (`MIGRATION_4_5`..`MIGRATION_9_10`, see `docs/FACTS.md`), recoverable `VaultState` (`KEY_LOST` / `CORRUPT`) fail-closed without opening plaintext SQLite | Verified (`VaultDatabaseKeyManager`, `ChatDatabaseSessionTest`, `AyraSecurityRegressionTest`, `Phase2HardeningAndAgentTest`) |
| **FINDING-02** | `EncryptedVaultStorage` (`AYRAVLT1` per-file 256-bit AES-GCM DEKs + AAD), `AndroidHardeningHelper.secureWipeFile`, canonical path confinement in `materializeTempDecryptedFile` & `ComfyUiClient.resolveConfinedOutputFile`, key-free `exportSafeWorkspaceBackupJson` | Verified (`AyraSecurityRegressionTest`, `MasterIntegrityUpgradeTest`, `Phase2HardeningAndAgentTest`) |
| **FINDING-03** | `AyraE2eeEngine` (`E2EEv1:` P-256 ECDH + HKDF-SHA256 + ECDSA P-256 identity signatures, anti-replay watermark, persisted `revokedAtEpoch` multi-epoch revocation enforcement) | Verified (`AyraSecurityRegressionTest`) |
| **FINDING-04** | `AyraKeyManager` purpose-separated aliases, StrongBox preference + TEE fallback, `KeyInfo` inspection, versioned key rotation, fail-closed when `AndroidKeyStore` is present | Verified (`AyraSecurityRegressionTest`) |
| **FINDING-05** | `EncryptedPreferences` (`.commit()` atomic persistence) + `ALIAS_PREFS_KEK` protecting custom instructions, memory facts, usage telemetry, and host URLs | Verified (`AyraSecurityRegressionTest`) |
| **FINDING-06** | `BiometricAuthManager` (`CryptoObject` cipher + `DEVICE_CREDENTIAL`), `VaultLockManager` background/timeout auto-lock, `VaultLockScreen` | Verified (`AyraSecurityRegressionTest`) |
| **FINDING-07** | Zero-trust `backend/` (Firebase Auth `checkRevoked=true`, App Check, Zod `.strict()`, Helmet, CORS, rate limit, SSRF DNS-rebinding protection, `"staging"` profile, `/api/v1/code/run` fail-closed `503` with `node:vm` removed) + Android `SecureAiGatewayClient`, default-off `POLLINATIONS_FREE`, Local-Only blocking in `EnterpriseCognitiveEngine`/`GeminiCliEngine`/`ExternalAiProviders.discoverDynamicModels`, dead `GodMode`/`Qwen38` engines removed | Verified (`backend` 12/12 tests, `MasterIntegrityUpgradeTest`, `NoFakeOutputTest`) |
| **FINDING-08** | Consolidated `NetworkSecurityHelper` pooled TLS 1.3/1.2 AEAD `OkHttpClient`s (`hardenedPooledClient`, `localLanClient`) & SSRF validator across terminal, ComfyUI, and provider test flows + `network_security_config.xml` | Verified (`AyraSecurityRegressionTest`) |
| **FINDING-09** | `AiToolSecurityGuard` role-tag stripping, `<untrusted_external_data>` framing, exfiltration scoring, Local-Only & SSRF tool gating, plus `DefaultAgentCore` bounded read-only concurrency & mandatory `ToolCallProposed` (`PENDING`) confirmation gate | Verified (`AyraSecurityRegressionTest`, `Phase2HardeningAndAgentTest`) |
| **FINDING-10** | Full `<exclude>` in `backup_rules.xml` & `data_extraction_rules.xml`, `allowBackup="false"`, opt-in `FLAG_SECURE` (`MainActivity`), `filterTouchesWhenObscured`, `EXTRA_IS_SENSITIVE` clipboard helper, Play-compliant removal of `CALL_PHONE`/`SEND_SMS`, R8 release minification (`isMinifyEnabled=true`, `isShrinkResources=true`), variant-isolated `DebugAppCheckProviderFactory` (absent from release APK), explicit `AppCheckStatusRegistry` state tracking, and verified APK packaging inspection | Verified (`app-release.apk` dex & manifest scan, `AyraSecurityRegressionTest`, `Phase2HardeningAndAgentTest`) |

---

## 4. Phase 3 — Deep Forensic Security Audit & Permanent Repairs (18 Nov 2026)

Direct source inspection identified and permanently repaired the following residual architectural and security gaps (`P3-01` .. `P3-09`):

| Finding ID | Severity | File(s) & Root Cause | Exploit / Failure Scenario | Permanent Fix | Regression Test & Verification |
|---|---|---|---|---|---|
| **P3-01** | **CRITICAL** | `app/build.gradle.kts`, `.github/workflows/security-ci.yml`: No automated gate prevented `debug.keystore` release builds when CI/production flags were requested, and an initial inline closure violated Gradle 9 configuration cache. | Accidental Play/staging release signed with `debug.keystore` or CI failure due to `Task.project` execution-time reference. | Added configuration-cache-safe `ValidateProductionReleaseSigningTask` (`validateProductionReleaseSigning` / `verifyReleaseSigningCertificate`) enforcing non-debug upload keystore + `keytool` verification under `-PayraRequireProductionSigning=true` / `AYRA_REQUIRE_PRODUCTION_SIGNING=true`; preserved `signingConfigs` per container policy. | `Phase3HardeningAndRegressionTest.releaseSigningValidationTasksAndTemplatesExistAndBlockDebugKeystore` + CLI verified fail-closed non-zero exit with `-PayraRequireProductionSigning=true`. |
| **P3-02** | **CRITICAL** | `NetworkSecurityHelper.kt`, `ProviderOpenAiTransport.kt`, `GitHubPluginEngine.kt`, `backend/src/security/ssrf.ts`: `validatePublicHttpsUrl()` failed open or risked DNS-rebinding TOCTOU; IPv4-mapped IPv6/NAT64/6to4/Teredo/ULA and unsafe redirects bypassed naive checks; `discoverModels` skipped LAN guard. | Attacker supplies `evil.example.com` resolving to `::ffff:169.254.169.254`, `127.0.0.1` via DNS rebinding between check & connect, HTTP 302 to `10.0.0.1`, or triggers LAN Ollama probe when disabled. | Fail-closed DNS resolution; literal byte inspection blocking IPv4-mapped IPv6, NAT64, 6to4, Teredo, ULA, loopback, link-local, multicast, credentials, non-443 ports; `PinnedResolvedDns` & Node `agent.connect` IP binding; manual redirect-hop validation; `ProviderOpenAiTransport.discoverModels` LAN enforcement. | `Phase3HardeningAndRegressionTest` (DNS fail, IPv6 encodings, LAN guard), `backend/test/security.test.mjs` Subtest 6 (**15/15 passing**). |
| **P3-03** | **HIGH** | `AyraE2eeEngine.kt`, `AyraCloudRepository.kt`, `firestore.rules`: Replay watermarks lived only in process memory (lost across restarts); `E2EE_ENCRYPTED` lacked null-Context fail-closed guard and chunk SHA-256 verification; Firestore rules lacked chunk/unmodeled guards. | Cold restart resets replay protection allowing stale ciphertext replay; null context drops to plaintext; unauthenticated/tampered chunks corrupt backup recovery. | Persisted encrypted anti-replay watermarks + strict IV/mode validation; fail-closed `E2EE_ENCRYPTED` null-context check & authenticated SHA-256 chunk manifests; strict `firestore.rules` anonymous-auth block & chunk verification. | `Phase3HardeningAndRegressionTest` (replay across simulated restart, chunk tamper, null context), `firestore.test.js` (**9/9 passing**) + live `DeployRules` RPC. |
| **P3-04** | **HIGH** | `README.md`, `RELEASE_SIGNING.md`, `DEPLOYMENT_AND_RISK_REPORT.md`, `AyraApplication.kt`: Hardcoded project IDs drifted across docs/validators whenever `google-services.json` rotated. | Misconfigured OAuth / App Check / Console instructions causing production sign-in or App Check failures. | Switched to Single Source of Truth allowlist (`app/google-services.json` + generated `docs/FACTS.md` + consistency-only `FirebaseConfigConsistencyValidator` + `scripts/preflight.sh` CI gate); eliminated retired-ID blocklists. | `Phase3HardeningAndRegressionTest.firebaseConfigValidatorEnforcesSingleSourceConsistency` + `bash scripts/preflight.sh`. |
| **P3-05** | **HIGH** | `backend/src/services/codeSandbox.ts`, `middleware/rateLimit.ts`, `services/aiGateway.ts`: Sandbox responses lacked cryptographic execution attestation; rate limiting lacked distributed store support; model catalog lacked provenance/failure states. | Compromised/spoofed sandbox upstream falsely marks `isolated=true`; horizontal instances bypass single-node limits; rate-limited/retired models appear healthy. | Added HMAC-SHA256 sandbox receipt verification (`503`/`502` fail-closed), `RedisHttpRateLimitStore`, daily token quota enforcement (`429`), and explicit model catalog provenance. | `backend/test/security.test.mjs` Subtests 9–15 (**15/15 passing**). |
| **P3-06** | **MEDIUM** | `ModelCatalog.kt`, `AiModelRegistry.kt`, `AyraAgentOrchestrator.kt`: Limited verification statuses and single scalar confidence score masked stale/failed models or unverified citations. | Users see `429`/`410` models as ready or over-trust sparse citations. | Added `RATE_LIMITED`, `RETIRED`, `UNAVAILABLE` + verification provenance fields (`verifiedAtMs`, `pricingVerified`, `failureReason`) and structured `VerificationReport`. | `Phase3HardeningAndRegressionTest` model catalog & verification report tests. |
| **P3-07** | **MEDIUM** | `ChatDatabase.kt`, `VectorKnowledgeRepository.kt`, `LocalVectorEmbedder.kt`, `AutonomousKnowledgeEngine.kt`: Vector chunks had no embedding model/fingerprint metadata for upgrades; knowledge cache wiped 100% on overflow. | Embedding model upgrade silently mixes incompatible vector spaces; overflow triggers cache stampede. | Room `v10` (`MIGRATION_9_10`) adds `embeddingModelId` & `embeddingFingerprint`, pluggable `EmbeddingProvider` + auto-reindex in `ensureSeeded()`, and 250-entry synchronized LRU cache. | `Phase3HardeningAndRegressionTest.roomMigration9To10PreservesDataAndReindexesOnModelUpgrade` & LRU eviction test. |
| **P3-08** | **MEDIUM** | `MainActivity.kt`, `ChatScreen.kt`, `AgentCore.kt`, `AgentToolRegistry.kt`: Cold-start `POST_NOTIFICATIONS` prompt; tool arguments lacked strict schema/range validation; `AgentCore` tool outputs lacked injection wrapping. | Premature permission denial on cold start; out-of-range tool args or indirect prompt injection via tool responses. | Moved `POST_NOTIFICATIONS` request just-in-time into scheduling intent; added pre-dispatch `ToolArgumentValidator`, 8-state `AgentActionLifecycleState`, and `<untrusted_external_data>` framing on both `documentText` & tool outputs. | `Phase3HardeningAndRegressionTest.toolArgumentValidatorAndAgentCoreLifecycleAndInjectionIsolationWork`. |
| **P3-09** | **LOW** | `AyraDiagnosticsEngine.kt`, `AyraDiagnosticsSection.kt`, `DiagnosticsToolsScreen.kt`: No unified in-app latency/p95/error telemetry with secret-safe export. | Latency regressions and provider failures hard to inspect without exposing keys in logs/exports. | Added bounded ring-buffer telemetry with p95/mean calculation and regex redaction surfaced in Workspace Diagnostics. | `Phase3HardeningAndRegressionTest.knowledgeLruCacheBoundsMemoryAndDiagnosticsEngineRedactsSecretsAndComputesP95`. |

**Verified Post-Phase-3 Totals (18 Nov 2026):**
- Android + Auth/Firestore Emulators: **`212 / 212` passed across `34` suites** (`0` failed, `0` errors, `0` skipped)
- Zero-Trust Node/TS Backend: **`15 / 15` passed** (`npm --prefix backend run build && npm --prefix backend test`)
- Firestore Rules Emulator Tests: **`9 / 9` passed** + live rules deployed (`DeployRules` RPC)
- APKs: Debug (`52,608,939` bytes, `19` DEX) & R8 Release (`26,711,215` bytes, `1` DEX, zero debug App Check classes, zero `CALL_PHONE`/`SEND_SMS`)

