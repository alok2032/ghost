# AYRA AI — Architecture & File Map (`com.ayra.ai.app`)

Last verified: 16 Nov 2026 | Total Kotlin files in `app/src/main`: 121 (~46,600 lines) | Backend TS files: 9 | Unit/Emulator tests: 24 suites

---

## 1. Entry Points, App Check & Root Navigation
| File | Lines | Responsibility |
|---|---|---|
| `app/src/main/java/com/ayra/ai/app/AyraApplication.kt` | 54 | App startup, `StrictMode` in `DEBUG`, key vault readiness off main thread |
| `app/src/main/java/com/ayra/ai/app/MainActivity.kt` | 232 | `FragmentActivity` host, calls `configureAppCheck(this, intent)` pre-Compose, edge-to-edge, tapjacking/`FLAG_SECURE`, `VaultLockManager` lifecycle, auth/workspace routing |
| `app/src/main/java/com/ayra/ai/app/AppCheckInit.kt` | — | Initializes Firebase App Check (`DebugAppCheckProviderFactory` in debug via variant bridge; `RecaptchaAppCheckProviderFactory` in release when configured) |
| `app/src/debug/.../AppCheckDebugBridge.kt` & `app/src/release/.../AppCheckDebugBridge.kt` | — | Build-variant isolation ensuring `firebase-appcheck-debug` never ships in release APK |
| `app/src/main/java/com/ayra/ai/app/ui/screens/AyraWorkspaceHost.kt` | 368 | Primary app shell hosting Chat tab, Navigation Drawer/Rail, Settings, Studios, Research Docs, Security Center, Models Arena |
| `app/src/main/java/com/ayra/ai/app/ui/viewmodel/ChatViewModel.kt` | 303 | ViewModel managing Room chat sessions, scoped messages, atomic send mutex, streaming state, active job cancel, pending action confirmations |

---

## 2. AI Orchestration, Models & Providers (Target for Phase 1–2 Consolidation)
| File | Lines | Responsibility |
|---|---|---|
| `ai/UnifiedAiEngine.kt` | 1648 | Primary legacy god-engine: model tiers, persona/system prompts, intent routing, tool execution, RAG & provider dispatch |
| `ai/AyraOrchestrator.kt` | 538 | Newer streaming Flow (`AyraEvent`) pipeline: CrisisDetector -> Automation `ProposedAction` -> `AyraIntentClassifier` -> `ProviderPool` / `UnifiedAiEngine` |
| `ai/agents/AyraAgentOrchestrator.kt` | 345 | 12-domain intent classifier + Banglish detector + execution plan structs |
| `ai/AiComparisonEngine.kt` | 237 | Parallel multi-model Arena runner with latency measurement & synthesis |
| `ai/FirebaseAiEngine.kt` | 421 | Firebase AI Logic (`Firebase.ai`) wrapper using `gemini-3.8-flash`, `gemini-4-argon`, `gemini-3.5-flash-lite`, `gemini-nano-banana-2.1` |
| `ai/providers/ExternalAiProviders.kt` | 2135 | HTTP clients for OpenRouter, Groq, Cerebras, SambaNova, NVIDIA NIM, HuggingFace, DeepSeek, Ollama/local endpoints (SSE + sync) |
| `ai/providers/ProviderPool.kt` | 849 | Bounded cache, priority selection, failover, Local-Only Privacy gate |
| `ai/providers/AiModelRegistry.kt` | 641 | Model catalog/presets metadata for UI & routing |
| `ai/providers/ProviderCircuitBreaker.kt` | — | Failure cooldown & circuit breaker per provider |
| `ai/providers/AiUsageTracker.kt` | — | Session/request usage accounting |
| `ai/AyraIntentClassifier.kt` | — | Regex/slash-command classifier (`AyraToolIntent`) |
| `ai/AutonomousKnowledgeEngine.kt` | 1086 | Multi-step research / Wikipedia / CVE / knowledge aggregation |
| `ai/BuildYourOwnXKnowledgeEngine.kt` | 848 | Curated engineering/architecture tutorial corpus |
| `ai/GlobalWebKnowledgeGateway.kt` | 287 | External web/wiki gateway guarded by explicit consent & SSRF checks |
| `ai/PromptQualityAnalyzer.kt` | — | Heuristic prompt clarity/quality scorer |
| `ai/qwen/Qwen38UncensoredEngine.kt` | 110 | **Phase 1 Task 1.3 rename target -> `OpenModelEngine` (remove no-refusal framing)** |
| `ai/godmode/GodModePliniusEngine.kt` | 103 | **Phase 1 Task 1.3 rename target -> `SecurityResearchEngine` (safe defensive framing)** |
| `ai/deepseek/DeepSeekHarnessEngine.kt` | — | CoT/harness prompt wrapper |
| `ai/ecc/EnterpriseCognitiveEngine.kt` | — | Structured enterprise reasoning wrapper |
| `ai/gemini/GeminiCliEngine.kt` | — | CLI-style structured coding/shell helper |
| `ai/omniroute/OmniRouteEngine.kt` | — | Multi-provider ensemble synthesis |
| `ai/superpowers/AyraSuperpowersEngine.kt` | — | Workflow skill/superpower definitions & templates |
| `ai/higgsfield/OpenHiggsfieldEngine.kt` | — | Video/camera cinematic prompt synthesis |
| `ai/AiMediaPromptSynthesizer.kt` & `ai/Model3dMeshCatalog.kt` | 324 / 298 | Media prompts & 3D mesh metadata |
| `ai/studios/ComfyUiClient.kt` | 590 | Image/workflow ComfyUI HTTP client with path traversal confinement |
| `ai/AiAppAndCodeStudio.kt`, `ai/DataAnalysisEngine.kt`, `ai/WorkspaceContextBuilder.kt` | — | App generator, CSV/JSON analysis, workspace context builder |
| `ai/code/ProjectWorkspaceManager.kt` | — | Multi-file generated project workspace manager |
| `ai/plugins/GitHubPluginEngine.kt` & `GitHubPluginRegistry.kt` | 941 / — | GitHub repository skill/OpenAPI/MCP importer & registry |

---

## 3. Search, RAG & Voice/Audio
| File | Lines | Responsibility |
|---|---|---|
| `search/TavilySearchEngine.kt` | — | Web search client with SSRF/URL validation |
| `ai/rag/DocumentRagEngine.kt` | — | Document chunking, keyword + vector retrieval for uploaded files |
| `ai/rag/VectorKnowledgeRepository.kt` & `LocalVectorEmbedder.kt` | 621 / — | Room-backed vector index & local hash/TF-IDF embeddings |
| `ai/rag/CuratedDarkDeepKnowledgeCorpus.kt` | — | Curated offline cybersecurity defensive knowledge corpus |
| `voice/VoiceAssistantManager.kt` | 774 | Speech recognition & TTS coordinator (mic/TTS lifecycle hardened) |
| `voice/ElevenLabsVoiceEngine.kt` | — | ElevenLabs neural voice synthesis client |
| `voice/AyraVoiceAnnouncer.kt` | — | System TTS wrapper with barge-in support |
| `voice/CrisisDetector.kt` | — | Multi-lingual (EN/BN/HI) self-harm & crisis signal detector + helpline guidance |
| `voice/SentimentDetector.kt` & `UserSentiment.kt` | — | Conversational tone/sentiment detection |
| `audio/AudioCaptureManager.kt` & `AyraAudioSynthesizer.kt` | — / 291 | PCM audio capture & procedural audio/song synthesis |

---

## 4. Data Persistence, Cloud & Device Automation
| File | Lines | Responsibility |
|---|---|---|
| `data/ChatDatabase.kt` | 325 | SQLCipher-encrypted Room database (`schema v6`, migrations `4->5->6`) |
| `data/ChatDao.kt`, `ChatSessionDao.kt`, `GitHubPluginDao.kt`, `ScheduledTaskDao.kt`, `VectorKnowledgeDao.kt`, `AyraWorkspaceDao.kt` | — | Room DAOs |
| `data/ChatMessage.kt`, `ChatSession.kt`, `InstalledGitHubPlugin.kt`, `ScheduledTask.kt`, `KnowledgeDocument.kt`, `VectorKnowledgeEntities.kt`, `AyraWorkspaceEntities.kt` | — | Room entities |
| `data/ChatRepository.kt` | — | Session & message repository (Room + optional encrypted Firestore sync) |
| `data/AyraPreferences.kt` | 940 | User preferences, UI StateFlows, AI routing/privacy toggles (uses `EncryptedPreferences`) |
| `data/StorageHelper.kt` | 1062 | PDF/document export (`FileProvider`), workspace persistence, attachment IO |
| `cloud/GoogleAuthManager.kt` | — | Credential Manager Google Sign-In + Firebase Auth session |
| `cloud/AyraCloudRepository.kt` | 724 | Owner-scoped Firestore sync for messages, plugins, creations, E2EE devices, backups, audit events |
| `cloud/FirebaseTasks.kt` | — | Coroutine `Task<T>` await helpers |
| `automation/DeviceAutomationController.kt` | 852 | Proposes & executes Play-compliant `ProposedAction` intents (`ACTION_DIAL`, `ACTION_SENDTO`, alarm, app launch) |
| `automation/ProposedAction.kt`, `ContactResolver.kt`, `AyraTaskScheduler.kt`, `ScheduledTaskReceiver.kt` | — | Confirmation cards, contact lookup, AlarmManager task scheduling & boot receiver |

---

## 5. Security, Cryptography, Privacy & Terminal
| File | Lines | Responsibility |
|---|---|---|
| `security/AyraKeyManager.kt` | 648 | Hardware/AndroidKeyStore keys (AES-256-GCM master/vault/backup, ECDSA P-256 identity, HMAC-SHA256 log key) |
| `security/VaultDatabaseKeyManager.kt` | 250 | Keystore-wrapped 256-bit SQLCipher passphrase manager |
| `security/EncryptedPreferences.kt` | — | AES-GCM encrypted SharedPreferences with synchronous `.commit()` |
| `security/SecureKeyStore.kt` | — | Provider API key storage backed by Keystore |
| `security/EncryptedVaultStorage.kt` | 293 | `AYRAVLT1` AEAD file vault with canonical path confinement & temp wipe |
| `security/AyraE2eeEngine.kt` | 532 | P-256 ECDH + HKDF-SHA256 device-to-device E2EE + multi-epoch revocation |
| `security/BiometricAuthManager.kt` & `VaultLockManager.kt` | — | `BiometricPrompt` `CryptoObject` binding & auto-lock state machine |
| `security/NetworkSecurityHelper.kt` | 238 | TLS 1.3/1.2 OkHttpClient, DNS/IPv4/IPv6 SSRF guard, LAN endpoint allowlist, SPKI pinning builder |
| `security/SecureAiGatewayClient.kt` | — | Authenticated client for `/backend` using Firebase ID Token + App Check header |
| `security/AiToolSecurityGuard.kt` | — | Prompt-injection, dangerous-tool rate limit & schema verification guard |
| `security/CyberTerminalEngine.kt` | 824 | Sandbox FS + safe diagnostic probes (`ping`, `traceroute`, `dig`, `curl`, `cve`, `owasp`, `nmap`) |
| `security/DeviceSecurityInspector.kt` & `AndroidHardeningHelper.kt` | — | Root/debug/emulator checks, Play Integrity bridge, `FLAG_SECURE` / tapjacking |
| `security/EncryptedBackupManager.kt`, `DataErasureManager.kt`, `SecurityAuditLogger.kt` | — | PBKDF2 vault backups, cryptographic erasure, HMAC-chained tamper-evident logs |
| `vpn/AyraVpnPrivacyController.kt` | — | Privacy shield & local DNS/telemetry status inspector |

---

## 6. UI Screens & Components (Jetpack Compose M3)
| Screen / Component | Lines | Target Action |
|---|---|---|
| `ui/screens/ChatScreen.kt` | 2846 | **Phase 4 modularization (<400 lines per module)** + Phase 2 streaming controls (Stop/Regenerate/Edit/Badges) |
| `ui/components/ControlCenterSheet.kt` | 1602 | **Phase 4 modularization** + Phase 1 safety/label cleanup |
| `ui/screens/StudiosScreen.kt` | 1058 | **Phase 4 modularization** |
| `ui/screens/ResearchDocsScreen.kt` | 1042 | **Phase 4 modularization** + Phase 3 file assistant |
| `ui/components/ProviderKeysSection.kt` | 956 | **Phase 4 modularization** + model catalog discovery UI |
| `ui/components/ThreeDModelCard.kt` & `SolarSystem3DCanvas.kt` | 745 / 638 | Interactive 3D mesh & Canvas views |
| `ui/screens/SecurityCenterScreen.kt` & `SecurityCenterComponents.kt` | 660 / 490 | Vault posture, E2EE devices, HMAC audit log, erasure UI |
| `ui/screens/PromptEngineeringScreen.kt` & `DiagnosticsToolsScreen.kt` | 579 / 545 | Prompt analyzer & terminal/diagnostics UI |
| `ui/screens/ProjectsAndAssistantsScreen.kt` & `ModelsArenaScreen.kt` | 506 / 503 | Workspaces, custom personas & Model Comparison Arena |
| `ui/screens/AuthScreen.kt`, `VaultLockScreen.kt`, `SettingsAppearanceScreen.kt`, `DataAnalysisWorkspaceScreen.kt` | 266–284 | Sign-in, biometric lock, theming, CSV/JSON workspace |
| `ui/components/*` | — | `ActionConfirmationCard`, `RichMarkdownView`, `SongPlayerCard`, `VideoPlayerCard`, `PdfDocumentDownloadCard`, `CyberTerminalCard`, `GitHubPluginCard`, `VoiceCommandOverlay`, `ChatSessionsSheet` |

---

## 7. Backend (`backend/`), Firebase & CI
| File | Responsibility |
|---|---|
| `backend/src/app.ts` & `server.ts` | Express app with Helmet, CORS allowlist, Firebase Auth (`checkRevoked=true`) + App Check, rate limiter, AI gateway routes |
| `backend/src/security/ssrf.ts` & `middleware/*` | DNS-rebinding SSRF defense, Firebase ID/App Check verification, Zod `.strict()` schemas |
| `backend/test/security.test.mjs` | Node test suite covering auth, RBAC, SSRF, rate limits, secret redaction |
| `firestore.rules`, `firestore.test.js`, `firebase-blueprint.json` | Owner-isolated Firestore rules & emulator tests |
| `.github/workflows/security-ci.yml` | Secret scan + Android unit/regression + APK build + Backend CI |
