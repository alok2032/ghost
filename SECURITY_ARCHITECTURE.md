# AYRA AI — Enterprise Zero-Trust Security Architecture

## 1. Security Architecture Overview (Hybrid Tri-Environment Model)

AYRA AI enforces a unified Zero-Trust security architecture across all three AI execution planes:

| Execution Plane | Components | Security Controls Enforced |
|---|---|---|
| **Plane 1: On-Device Local AI** | `Qwen38UncensoredEngine`, `ComfyUiClient`, `ExternalAiProviders (LOCAL_OLLAMA)` | Zero external egress when **Local-Only Privacy Mode** is active; SSRF guard blocking metadata IPs and non-LAN cleartext (`validateLocalAiEndpointUrl`). |
| **Plane 2: AYRA Node.js / TypeScript Backend (`/backend`)** | `app.ts`, `auth.ts`, `ssrf.ts`, `schemas.ts`, `aiGateway.ts` | Firebase ID token (`checkRevoked=true`) + Firebase App Check enforcement; strict `.strict()` Zod schemas; Helmet HSTS/CSP; exact-origin CORS; DNS-rebinding & IPv6-aware SSRF defense; per-UID rate limiting; server-side credential isolation. |
| **Plane 3: External AI Providers & Firebase Cloud** | `SecureAiGatewayClient`, `FirebaseAiEngine`, `ProviderPool`, `AyraCloudRepository`, `firestore.rules` | Strict consent & Local-Only policy gating; outbound PII/secret redaction when Privacy Mode is active; App Check on Firebase AI Logic; E2EE ciphertext envelopes in Firestore; owner-only Firestore rules. |

---

## 2. Cryptographic Key Hierarchy (`AyraKeyManager`)

Every cryptographic purpose uses an **isolated Android Keystore key alias** with randomized 96-bit IVs and AEAD (AES-256-GCM) Additional Authenticated Data (`AAD`) binding:

1. `ayra_master_secrets_v2` — Hardware-backed (StrongBox preferred -> TEE fallback) AES-256-GCM key protecting API credentials in `SecureKeyStore` and `EncryptedPreferences`.
2. `ayra_vault_db_kek_v1` — StrongBox/TEE AES-256-GCM Key Encryption Key wrapping the random 256-bit SQLCipher passphrase in `VaultDatabaseKeyManager`.
3. `ayra_vault_file_kek_v1` — StrongBox/TEE AES-256-GCM KEK wrapping per-file 256-bit DEKs for `EncryptedVaultStorage` (`AYRAVLT1` containers).
4. `ayra_prefs_kek_v1` — StrongBox/TEE AES-256-GCM KEK protecting sensitive preferences & encrypted audit logs.
5. `ayra_biometric_vault_key_v1` — User-authenticated AES-256-GCM key (`AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL`, `setInvalidatedByBiometricEnrollment(true)`, timeout `0s`) bound to `BiometricPrompt.CryptoObject`.
6. `ayra_e2ee_identity_ecdsa_v1` — Hardware-backed ECDSA P-256 (`secp256r1`) key signing E2EE envelopes and peer key-distribution packets.
7. `ayra_audit_hmac_v1` — Android Keystore `HmacSHA256` key chaining `SecurityAuditLogger` entries.

Key rotation (`AyraKeyManager.rotatePurposeKey`) increments generation counters (`_gen_N`) cleanly while preserving historical decryption keys until DEKs are re-wrapped.

---

## 3. Private Data Vault & SQLCipher (`ChatDatabase` + `EncryptedVaultStorage`)

- **Room Database Encryption**: `VaultDatabaseKeyManager` generates a 256-bit random passphrase, wraps it under `ALIAS_DB_KEK`, migrates any legacy plaintext SQLite DB via `sqlcipher_export(...)`, and opens Room with `net.zetetic.database.sqlcipher.SupportOpenHelperFactory`. On JVM Robolectric test hosts where native `libsqlcipher.so` is absent, `VaultDatabaseKeyManager` records `ROBOLECTRIC_JVM_FALLBACK`; on Android devices missing the native library, initialization fails closed.
- **File Vault (`filesDir/encrypted_vault`)**: Each stored document/image/audio artifact receives a unique 256-bit DEK and is written in `AYRAVLT1` format with AAD `$folder|$fileId|$originalName|$createdMs|v1`. Temporary plaintext copies exported for `FileProvider` sharing live in `cacheDir/exports` and are cryptographically wiped (`RandomAccessFile` random + zero overwrite) when backgrounded, locked, or destroyed.

---

## 4. End-to-End Encryption (`AyraE2eeEngine` & `AyraCloudRepository`)

- **Ciphertext Format (`E2EEv1:...`)**: ECDH P-256 + HKDF-SHA256 epoch key distribution + per-message forward-ratcheted AES-256-GCM + ECDSA P-256 sender signature, with AAD `$senderDeviceId|$epoch|$seq|$uid|$docId|$field`.
- **Forward Secrecy on Revocation**: Calling `revokeDeviceAndRotateEpoch` marks the target device revoked in Firestore `/devices` & `/sessions` and rotates the E2EE epoch key forward so the revoked device cannot decrypt future messages.
- **Anti-Replay**: `decryptEnvelope(..., enforceReplayProtection = true)` rejects out-of-order/replayed `(senderDeviceId, epoch, seq)` sequences.

---

## 5. Passphrase Backups & Cryptographic Erasure

- **Backups (`EncryptedBackupManager`)**: Uses `PBKDF2WithHmacSHA256` (210,000 iterations, 128-bit random salt) + AES-256-GCM + SHA-256 verification. Backups exclude API keys, OAuth tokens, and Android Keystore keys.
- **Cryptographic Erasure (`DataErasureManager`)**: Destroys all Keystore purpose aliases, wrapped DB KEKs, SQLCipher DB (`-wal`/`-shm`), encrypted vault files, sensitive preferences, audit logs, and (when confirmed) all Firestore user subcollections.

---

## 6. Android & AI Prompt/Network Hardening

- **Activity & Manifest**: `MainActivity` extends `FragmentActivity`, enforces `FLAG_SECURE`, sets `filterTouchesWhenObscured = true` + Compose `rejectObscuredTouches`, enforces `android:allowBackup="false"`, `usesCleartextTraffic="false"`, non-exported components, and `network_security_config.xml` HTTPS enforcement (system CAs only in release).
- **Anti-SSRF (`NetworkSecurityHelper`)**: Enforces TLS 1.3 / 1.2 AEAD cipher suites and blocks `http://`, `file://`, `javascript:`, cloud metadata (`169.254.169.254`, `metadata.google.internal`), RFC1918, loopback, and IPv6 ULA/link-local addresses across AI tools, GitHub plugins, and Cyber Terminal probes.
- **Indirect Prompt Injection Defense (`AiToolSecurityGuard`)**: Inspects retrieved RAG/PDF/plugin chunks for override/exfiltration markers, neutralizes spoofed role XML tags, wraps content inside `<untrusted_external_data>` boundaries, and enforces Local-Only / SSRF checks on tool execution.
