# Google Play Data Safety & Privacy Disclosure Matrix — Ayra AI (`com.ayra.ai.app`)

This document maps **every actual data flow in the Ayra AI codebase** to Google Play's Data Safety declarations so Play Console disclosures match code behavior 1:1.

---

## 1. Encryption & Deletion Commitments (Play Data Safety Form)
| Play Console Question | Ayra AI Answer | Technical Evidence in Codebase |
|---|---|---|
| **Is all user data encrypted in transit?** | **Yes** (HTTPS / TLS 1.2–1.3 enforced) | `AndroidManifest.xml` (`usesCleartextTraffic="false"`), `network_security_config.xml`, `NetworkSecurityHelper.kt` (`TlsVersion.TLS_1_3`, `TLS_1_2`). Exception: optional user-configured Local LAN Ollama/ComfyUI HTTP (`isAllowLanEndpointsEnabled` + `validateLocalAiEndpointUrl`, user's own local network). |
| **Is user data encrypted at rest on device?** | **Yes** | `VaultDatabaseKeyManager.kt` + `ChatDatabase.kt` (SQLCipher AES-256), `EncryptedPreferences.kt` (AES-256-GCM backed by Android Keystore), `EncryptedVaultStorage.kt` (`AYRAVLT1` AEAD). |
| **Can users request that their data be deleted?** | **Yes (In-App + Cloud Account Purge)** | `DataErasureManager.performCompleteCryptographicErasure` wipes local SQLCipher DB, vault files, Keystore keys, and Firestore user subcollections; Security Center also provides Cloud & Firebase Account deletion. |

---

## 2. Data Collection, Destination & Purpose Matrix

| Data Category | Destination | Purpose | Required vs Opt-In |
|---|---|---|---|
| **User Profile (Name, Email, Photo URL, Auth UID)** | **Firebase Auth & Cloud Firestore** (`users/{uid}`) | Account sign-in & multi-device session sync | **Optional** (app launches into local chat without forced sign-in; Google Sign-In is opt-in; suppressed when Local-Only AI Mode is ON) |
| **Chat Messages & Prompts** | **1) On-device SQLCipher DB** (`chat_messages`)<br>**2) Cloud Firestore** (`users/{uid}/messages`, E2EE ciphertext when enabled)<br>**3) Selected AI Provider** (`Firebase.ai` / user-configured Groq, OpenAI, Anthropic, DeepSeek, OpenRouter, Ollama, or `/backend` gateway) | App functionality (AI inference, history, cross-device E2EE sync) | Required for AI inference when user sends a prompt; cloud sync is opt-in (`isCloudE2eeSyncEnabled`); external cloud AI requires `hasExternalAiConsent` and is blocked by `isLocalOnlyAiModeEnabled`. API keys/tokens are redacted when Privacy Redaction Mode is ON. |
| **Uploaded Documents, Images & Audio** | **On-device RAG/Vault**; sent to chosen Multimodal/STT provider only when attached by user and External AI Consent is enabled | Document Q&A, image understanding, speech transcription | **Opt-in per attachment**; files are never uploaded silently in the background. |
| **Long-Term Memory Facts (Phase 2)** | **On-device SQLCipher DB only** (optional E2EE backup on explicit user export) | Personalized assistant context | **Opt-in (Default OFF)**; excludes passwords/payment cards/sensitive health notes; user can view, edit, or wipe all memories. |
| **Contacts (Phone/Name Lookup)** | **On-device only** (`ContactsContract`) during explicit dial/SMS intent resolution | Voice/text "call X" or "text X" pre-fill (`ACTION_DIAL` / `ACTION_SENDTO`) | **Opt-in (`READ_CONTACTS` runtime permission)**; contact lists are **never** uploaded to Firebase or third-party AI servers. |
| **Approximate Location** | **On-device only** (when user asks location-aware automation/weather with explicit consent) | Local context tools | **Opt-in (`ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` runtime permission)** |
| **Microphone Audio (`RECORD_AUDIO`)** | **On-device OS SpeechRecognizer** or **User-selected STT endpoint** (Groq/SambaNova/OpenAI Whisper) | Push-to-talk voice input & optional wake word | **Opt-in (`RECORD_AUDIO` runtime permission)**; mic stops immediately on release/barge-in/stop. |
| **Camera (`CAMERA`)** | **On-device Photo/Camera capture** | Multimodal vision prompts & flashlight toggle | **Opt-in (`CAMERA` runtime permission)**; Play Photo Picker (`PickVisualMedia`) is used without broad storage permissions. |
| **Device Identifiers & E2EE Public Keys** | **Cloud Firestore** (`users/{uid}/devices`, `users/{uid}/sessions`) | Trusted device verification, session revocation & E2EE key exchange | Generated only when signed in & Cloud Sync enabled. |
| **Crash/Diagnostics & App Info** | **On-device HMAC Audit Log** (`SecurityAuditLogger`); optional Firebase Crashlytics/Performance (no prompt/API secrets logged) | Security forensics & app stability | Secret-redacted telemetry (`[REDACTED]`). |

---

## 3. Permissions Justification (Play Console Permissions Declaration)
- `RECORD_AUDIO`: Voice assistant dictation & push-to-talk interaction (runtime prompt with clear rationale).
- `CAMERA`: Capturing a photo to ask the multimodal AI about, plus flashlight hardware toggle (runtime prompt). Using Android Photo Picker (`PickVisualMedia`) for gallery photos — **zero** `READ_EXTERNAL_STORAGE` / `READ_MEDIA_*` declarations.
- `READ_CONTACTS`: Resolving contact display name to phone number exclusively on-device when user requests dialing or drafting an SMS (`ACTION_DIAL` / `ACTION_SENDTO`, always gated by `ProposedAction` confirmation card).
- `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION`: User-triggered local context/map navigation actions (`ACTION_VIEW` geo/maps intent).
- `POST_NOTIFICATIONS` / `SCHEDULE_EXACT_ALARM` / `RECEIVE_BOOT_COMPLETED`: Firing user-created scheduled reminders & re-arming pending reminders after device reboot (`AyraTaskScheduler` checks `canScheduleExactAlarms()` and falls back to inexact `AlarmManager` delivery with an explicit UI notice & Settings CTA when exact alarm permission is not granted).
- `USE_BIOMETRIC`: Hardware `BiometricPrompt` (`CryptoObject`) vault unlock and destructive export/erasure authorization.

**Strictly Excluded Permissions:** `CALL_PHONE`, `SEND_SMS`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `REQUEST_INSTALL_PACKAGES`, `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`.
