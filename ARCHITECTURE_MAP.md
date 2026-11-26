# AYRA AI — Architecture & File Map (`com.ayra.ai.app`)

Last verified: 20 Nov 2026 | See `docs/FACTS.md` for live generated source, test, and dependency counts.

---

## 1. Entry Points, App Check & Root Navigation
| File | Lines | Responsibility |
|---|---|---|
| `app/src/main/java/com/ayra/ai/app/AyraApplication.kt` | 53 | App startup, `StrictMode` in `DEBUG`, key vault readiness off main thread |
| `app/src/main/java/com/ayra/ai/app/MainActivity.kt` | 231 | `FragmentActivity` host, calls `configureAppCheck(this, intent)` pre-Compose, edge-to-edge, tapjacking/`FLAG_SECURE`, `VaultLockManager` lifecycle, auth/workspace routing |
| `app/src/main/java/com/ayra/ai/app/AppCheckInit.kt` | 99 | Configures Firebase App Check (`DEBUG_ACTIVE`, `RELEASE_RECAPTCHA_ACTIVE` via `recaptcha_site_key` in `firebase_applet_config.xml`, or explicit `MISSING_RELEASE_CONFIG` / `ERROR` via `AppCheckStatusRegistry`) |
| `app/src/debug/.../AppCheckDebugBridge.kt` & `app/src/release/.../AppCheckDebugBridge.kt` | — | Build-variant isolation ensuring `firebase-appcheck-debug` never ships in release APK |
| `app/src/main/java/com/ayra/ai/app/ui/screens/AyraWorkspaceHost.kt` | 377 | Primary app shell hosting Chat tab, Navigation Drawer/Rail, Settings, Studios, Research Docs, Security Center, Models Arena |
| `app/src/main/java/com/ayra/ai/app/ui/viewmodel/ChatViewModel.kt` | 494 | ViewModel managing Room chat sessions, scoped messages, atomic send mutex, streaming state, active job cancel, pending action confirmations, `agentPlanSteps`, `activeCitations` |

---

## 2. AI Orchestration, Agents, Models & Providers
| File | Lines | Responsibility |
|---|---|---|
| `ai/UnifiedAiEngine.kt` | 596 | Modularized AI facade delegating craft generation (`AiCraftGenerators.kt`), streaming provider dispatch, persona prompts, and consent gating |
| `ai/AyraOrchestrator.kt` | 515 | Streaming Flow (`AyraEvent`) pipeline: CrisisDetector -> Automation `ProposedAction` -> `AyraIntentClassifier` -> `DefaultAgentCore` / `ProviderPool` / `UnifiedAiEngine` |
| `ai/agent/AgentCore.kt` & `AgentToolRegistry.kt` | — | Bounded multi-step tool agent (21 tools, `HARD_MAX_STEPS = 12`, `STEP_TIMEOUT_MS = 25_000L`, `MAX_PARALLEL_READONLY_TOOLS = 3`, 8-state `AgentActionLifecycleState`, pre-dispatch `ToolArgumentValidator`, prompt-injection wrapping on both `documentText` and tool outputs, confirmation tools stay `StepStatus.PENDING` via `ToolCallProposed`, `CancellationException` propagated) |
| `ai/agents/AyraAgentOrchestrator.kt` | — | 12-domain intent classifier + Banglish detector + `VerificationReport` (`evidenceCoverage`, `sourceReliability`, `citationValidity`, `uncertaintyNotes`, nullable `confidenceScore`) |
| `ai/AiComparisonEngine.kt` | 237 | Parallel multi-model Arena runner with latency measurement & synthesis |
| `ai/FirebaseAiEngine.kt` | — | Firebase AI Logic (`Firebase.ai`) wrapper delegating IDs to `ModelCatalog` (`gemini-3.6-flash`, `gemini-3.1-pro-preview`, `gemini-3.5-flash-lite`, `gemini-3.5-flash`, `gemini-3.1-flash-image`) plus `runStartupSelfTest` |
| `ai/providers/ExternalAiProviders.kt` + (`ExternalAiCatalog.kt`, `ExternalAiKeyAndDiscovery.kt`, `ExternalAiStreamingSupport.kt`) + transports (`ProviderOpenAiTransport.kt`, `ProviderGeminiTransport.kt`, `ProviderAnthropicAndFreeTransport.kt`, `AiProviderModels.kt`) | — | Modular HTTP clients (SSE streaming + sync) using pooled `NetworkSecurityHelper` clients + validated-redirect HTTPS calls across Gemini, OpenRouter, Groq, Cerebras, SambaNova, NVIDIA NIM, HuggingFace, DeepSeek, Ollama, and Pollinations (`gen.pollinations.ai`) |
| `ai/providers/ProviderPool.kt` + (`ProviderPoolModels.kt`, `ProviderRoutingSupport.kt`, `ProviderEnsembleExecutor.kt`) | 596 + 762 | Modularized quorum ensemble, priority selection, failover, bounded cache, Local-Only Privacy gate, OS-validated `DnsError` vs `NoNetwork`, and `SecurityPolicyBlocked` classification |
| `ai/providers/ModelCatalog.kt` & `AiModelRegistry.kt` | — | Single-source-of-truth model constants + metadata (`config/model-allowlist.txt`), `ModelVerificationStatus`, provenance, live `/models` discovery, and fallback chains |
| `ai/providers/ProviderCircuitBreaker.kt` & `AiUsageTracker.kt` | 156 / 285 | Failure cooldown circuit breaker and persistent provider usage metrics |
| `ai/AyraIntentClassifier.kt` | — | Regex/slash-command classifier (`AyraToolIntent`) |
| `ai/AutonomousKnowledgeEngine.kt` | — | Multi-step research / Wikipedia / CVE / knowledge aggregation with synchronized bounded 250-entry LRU cache (evicts oldest entries cleanly without wipe-to-zero cliffs) |
| `ai/BuildYourOwnXKnowledgeEngine.kt` | 848 | Curated engineering/architecture tutorial corpus |
| `ai/GlobalWebKnowledgeGateway.kt` | 287 | External web/wiki gateway guarded by explicit consent & SSRF checks |
| `ai/PromptQualityAnalyzer.kt` | — | Heuristic prompt clarity/quality scorer |
| `ai/providers/ModelTierSpec.kt` | 283 | Single source of truth `@Immutable enum class ModelTier` for tiers, aliases, pool modes & specialist directives |
| `ai/openmodel/OpenModelEngine.kt` | 110 | Open-weights objective local/cloud reasoning engine |
| `ai/securityresearch/SecurityResearchEngine.kt` | 103 | Defensive cybersecurity, OWASP & CVE mitigation grounding engine |
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
| `ai/rag/VectorKnowledgeRepository.kt` & `LocalVectorEmbedder.kt` | — | Room-backed vector index with pluggable `EmbeddingProvider` / `SemanticEmbeddingAdapter` and automatic `embeddingModelId` + `embeddingFingerprint` mismatch reindexing |
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
| `data/ChatDatabase.kt` | — | SQLCipher-encrypted Room database (`schema v10`, minimum supported source `v4`, explicit migrations `4->5->6->7->8->9->10`; zero destructive fallback — unsupported `< v4` DBs fail closed into `VaultOpenResult.NeedsRecovery`) |
| `data/ChatDao.kt`, `ChatSessionDao.kt`, `GitHubPluginDao.kt`, `ScheduledTaskDao.kt`, `VectorKnowledgeDao.kt`, `AyraWorkspaceDao.kt`, `LongTermMemoryDao.kt` | — | Room DAOs |
| `data/ChatMessage.kt`, `ChatSession.kt`, `InstalledGitHubPlugin.kt`, `ScheduledTask.kt`, `KnowledgeDocument.kt`, `VectorKnowledgeEntities.kt`, `AyraWorkspaceEntities.kt`, `LongTermMemoryEntity.kt` | — | Room entities |
| `data/ChatRepository.kt` | — | Session & message repository (Room + optional encrypted Firestore sync) |
| `data/AyraPreferences.kt` | 979 | User preferences, UI StateFlows, AI routing/privacy toggles (uses `EncryptedPreferences`) |
| `data/StorageHelper.kt`, `DocumentParsers.kt`, `DocumentExporters.kt` | 229 / 369 / 516 | Modular encrypted vault attachment facade, OOXML/PDF/CSV extraction parsers, and key-free JSON workspace backup/export (`exportSafeWorkspaceBackupJson`) |
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
| `security/AyraE2eeEngine.kt` | — | P-256 ECDH + HKDF-SHA256 device-to-device E2EE + persisted encrypted replay watermarks, strict IV/mode check & multi-epoch revocation |
| `security/BiometricAuthManager.kt` & `VaultLockManager.kt` | — | `BiometricPrompt` `CryptoObject` binding & auto-lock state machine |
| `security/NetworkSecurityHelper.kt` | — | TLS 1.3/1.2 OkHttpClient, fail-closed DNS, IPv4/IPv4-mapped IPv6/NAT64/6to4/Teredo/ULA SSRF guard, validated-IP binding `PinnedResolvedDns`, redirect validation, LAN endpoint allowlist, SPKI pinning builder |
| `security/FirebaseConfigConsistencyValidator.kt` | — | Validates `google-services.json` vs `firebase_applet_config.xml` alignment and blocks retired Firebase project IDs |
| `security/AyraDiagnosticsEngine.kt` | — | Bounded ring-buffer latency/p95/error telemetry with automatic secret redaction (`AIza`, `sk-proj-`, `ghp_`, Bearer tokens) and JSON export |
| `security/SecureAiGatewayClient.kt` | — | Authenticated client for `/backend` using Firebase ID Token + App Check header |
| `security/AiToolSecurityGuard.kt` | — | Prompt-injection, dangerous-tool rate limit & schema verification guard |
| `security/CyberTerminalEngine.kt` | 824 | Sandbox FS + safe diagnostic probes (`ping`, `traceroute`, `dig`, `curl`, `cve`, `owasp`, `nmap`) |
| `security/DeviceSecurityInspector.kt` & `AndroidHardeningHelper.kt` | — | Root/debug/emulator checks, Play Integrity bridge, `FLAG_SECURE` / tapjacking |
| `security/EncryptedBackupManager.kt`, `DataErasureManager.kt`, `SecurityAuditLogger.kt` | — | PBKDF2 vault backups, cryptographic erasure, HMAC-chained tamper-evident logs |
| `vpn/AyraVpnPrivacyController.kt` | — | Privacy shield & local DNS/telemetry status inspector |

---

## 6. UI Screens & Components (Jetpack Compose M3)
| Screen / Component | Lines | Notes |
|---|---|---|
| `ui/screens/ChatScreen.kt` + `ui/components/chat/` (`ChatHeaderAndInput.kt`, `ChatMessageComponents.kt`, `ChatBannersAndModals.kt`, `ChatAttachmentHubDialog.kt`, `ChatOverlaysHost.kt`, `ChatScreenMediaHelper.kt`) | — | Modular chat workspace with streaming indicators, plan steps, citations, media helpers, and overlay dialogs |
| `ui/components/ControlCenterSheet.kt` + `ui/components/control/` (`ControlCenterDeviceAndHistorySection.kt`, `ControlCenterModelsAndPersonaSection.kt`) | — | Modular settings & device control sheet with 18+ companion gate, exact-alarm banner, and model selector |
| `ui/screens/ResearchDocsScreen.kt` + `ui/screens/research/` (`ResearchWikiExplorerPanel.kt`, `ResearchDeepAnalysisPanel.kt`, `ResearchKnowledgeRagPanel.kt`) | — | Modular research, vector RAG, and wiki explorer tabs |
| `ui/components/ProviderKeysSection.kt` + (`ProviderActivationAndRoutingCards.kt`, `ProviderKeyEntryCards.kt`, `ProviderEndpointCards.kt`, `ProviderPoolAndNimCards.kt`) | 194 + 1,707 | Modular Keystore-backed API key management, auto-routing, Free AI Pool & NVIDIA NIM controls |
| `ui/components/ThreeDModelCard.kt` & `SolarSystem3DCanvas.kt` | — | Interactive 3D mesh (hardened WebView CSP) & Canvas views |
| `ui/screens/SecurityCenterScreen.kt` & `SecurityCenterComponents.kt` | — | Vault posture, E2EE devices, HMAC audit log, erasure UI |
| `ui/screens/DiagnosticsToolsScreen.kt` & `ui/components/AyraDiagnosticsSection.kt` | — | Terminal probes, built-in diagnostic tools, and live secret-redacted p95/error telemetry panel |
| `ui/screens/ProjectsAndAssistantsScreen.kt`, `MemoryScreen.kt`, `ModelsArenaScreen.kt`, `AiUnifiedSettingsHubScreen.kt` | — | Workspaces, long-term memory vault, `ModelVerificationStatus` Arena, and unified AI Control hub |
| `ui/screens/AuthScreen.kt`, `VaultLockScreen.kt`, `VaultRecoveryScreen.kt`, `SettingsAppearanceScreen.kt` | — | Google Sign-In + guest fallback, biometric lock, transient/corrupt vault recovery, bundled typography (`Plus Jakarta Sans` + `Hind Siliguri`), `values-night` splash parity, and EN/BN resources |
| `ui/components/*` & `res/` | — | `ActionConfirmationCard`, `RichMarkdownView`, `SongPlayerCard`, `VideoPlayerCard` (confined local/HTTPS playback), `PdfDocumentDownloadCard`, `CyberTerminalCard`, `GitHubPluginCard`, `VoiceCommandOverlay`, `ChatSessionsSheet`, `ic_launcher_monochrome.xml`, `ic_notification_ayra.xml` |

---

## 7. Backend (`backend/`), Firebase & CI
| File | Responsibility |
|---|---|
| `backend/src/app.ts` & `server.ts` | Express app with Helmet, CORS allowlist, Firebase Auth (`checkRevoked=true`) + App Check, rate limiter, AI gateway, and code sandbox routes |
| `backend/src/config.ts` | Supports `"development"`, `"staging"`, `"test"`, `"production"` profiles; fail-closed production defaults |
| `backend/src/services/codeSandbox.ts` | Isolated remote sandbox client failing closed with `HTTP 503` (`SANDBOX_DISABLED`) when `SANDBOX_EXECUTOR_URL`/`TOKEN` are absent (`node:vm` permanently removed) |
| `backend/src/security/ssrf.ts` & `middleware/*` | DNS-rebinding SSRF defense, Firebase ID/App Check verification, Zod `.strict()` schemas |
| `backend/test/security.test.mjs` | Node test suite covering auth, RBAC, SSRF, rate limits, secret redaction, `"staging"` config, and `/api/v1/code/run` fail-closed `503` |
| `firestore.rules`, `firestore.test.js`, `firebase-blueprint.json` | Owner-isolated Firestore rules & emulator tests |
| `.github/workflows/security-ci.yml` | Secret scan, `firestore-rules-tests` emulator suite, Android unit/regression + APK build, and Backend CI |
