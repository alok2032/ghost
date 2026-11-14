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
