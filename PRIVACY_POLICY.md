# Privacy Policy — Ayra AI (`com.ayra.ai.app`)

**Effective Date:** November 16, 2026

Ayra AI is designed around user-controlled privacy, on-device encryption, and explicit consent before sending any prompt or attachment to external AI services.

## 1. What Data Ayra AI Processes
- **Conversations & Attachments:** Your chat messages, uploaded documents, images, and custom assistants/projects are stored locally inside a SQLCipher-encrypted database protected by an Android Keystore key.
- **Voice & Camera Inputs:** Microphone audio and camera images are processed only when you initiate voice input, live vision, or photo capture. Photo library selection uses the zero-permission Android Photo Picker (`PickVisualMedia`).
- **Contacts & Location (Optional):** Used solely on-device when you ask Ayra AI to look up a contact to draft a call/message (opening your dialer/SMS app for your confirmation) or answer a location request. Contacts are not uploaded to remote servers.
- **Optional Google Sign-In & Cloud Sync:** If you sign in with Google, your Firebase UID, display name, email, registered E2EE device keys, and (when Cloud Sync is enabled) end-to-end encrypted (`E2EEv1`) messages/backups are stored in Google Cloud Firestore scoped strictly to your `uid`.

## 2. AI Model Providers & Third-Party Processing
- **Local-Only Privacy Mode:** When enabled in Security Center, all outbound cloud AI requests, cloud sync, web search, and external plugin calls are blocked.
- **External AI Consent & Redaction:** Outbound requests to Firebase AI Logic (Gemini) or third-party OpenAI-compatible providers configured with your own API keys happen only with External AI Consent enabled. Privacy Redaction strips detected API keys and bearer tokens before sending prompts.
- **No Unsolicited Uploads:** Files you export or open are never silently uploaded. You choose when to share or analyze a file.

## 3. Long-Term Memory (Opt-In)
- Personal memory is disabled by default (`OFF`). When turned on, facts extracted with your approval are stored encrypted on-device and can be viewed, edited, or deleted at any time in Settings / Memory Manager. Passwords, payment card numbers, and sensitive medical records are excluded from auto-extraction.

## 4. Security Measures
- **In Transit:** Strict TLS 1.2/1.3 (`usesCleartextTraffic="false"`), SSRF/private-IP protections on outbound fetches, and Firebase ID Token + App Check verification on the gateway backend.
- **At Rest:** Android Keystore purpose-separated AES-256-GCM / ECDSA P-256 / HMAC-SHA256 keys, SQLCipher database encryption, and `AYRAVLT1` file vault containers.

## 5. Data Retention, Export & Account Deletion
- **In-App Complete Deletion:** Open **Security Center -> Erase All Vault & Cloud Data** (or **Delete Account & Cloud Data**) to permanently destroy local Keystore keys (cryptographic erasure), delete the SQLCipher database and vault files, purge all Firestore user collections, and optionally delete your Firebase Auth account.
- **Web Deletion Request Instructions:** Signed-in users who no longer have the app installed can request full cloud data & Firebase Auth deletion through your published support URL before Play Store release (host this policy publicly and include an email/form endpoint tied to Firebase Admin deletion for your `uid`).
