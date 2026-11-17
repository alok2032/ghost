package com.ayra.ai.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayra.ai.app.R
import com.ayra.ai.app.cloud.AyraCloudRepository
import com.ayra.ai.app.cloud.CloudBackupRecord
import com.ayra.ai.app.cloud.CloudSessionInfo
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.security.AyraE2eeEngine
import com.ayra.ai.app.security.AyraKeyManager
import com.ayra.ai.app.security.BiometricAuthManager
import com.ayra.ai.app.security.DataErasureManager
import com.ayra.ai.app.security.DeviceSecurityInspector
import com.ayra.ai.app.security.EncryptedBackupManager
import com.ayra.ai.app.security.EncryptedVaultStorage
import com.ayra.ai.app.security.SecurityAuditCategory
import com.ayra.ai.app.security.SecurityAuditLogger
import com.ayra.ai.app.security.SecurityAuditSeverity
import com.ayra.ai.app.security.TrustedE2eeDevice
import com.ayra.ai.app.security.VaultDatabaseKeyManager
import com.ayra.ai.app.security.VaultLockManager
import com.ayra.ai.app.security.rejectObscuredTouches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SecurityCenterScreen(
    onBack: (() -> Unit)? = null,
    onVaultWiped: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val db = remember(context) { ChatDatabase.getDatabase(context) }
    val cloudRepo = remember(context) { AyraCloudRepository.create(context) }

    var deviceReport by remember { mutableStateOf<com.ayra.ai.app.security.DeviceSecurityReport?>(null) }
    var dbVerifiedAtRest by remember { mutableStateOf(false) }
    var keystoreReports by remember { mutableStateOf(emptyList<com.ayra.ai.app.security.KeystoreKeyReport>()) }
    var vaultArtifacts by remember { mutableStateOf(emptyList<com.ayra.ai.app.security.EncryptedVaultEntry>()) }
    var devices by remember { mutableStateOf(emptyList<TrustedE2eeDevice>()) }
    var sessions by remember { mutableStateOf(emptyList<CloudSessionInfo>()) }
    var cloudBackups by remember { mutableStateOf(emptyList<CloudBackupRecord>()) }
    var chainValid by remember { mutableStateOf(true) }
    var localFingerprint by remember { mutableStateOf("Generating...") }
    var currentEpoch by remember { mutableStateOf(1) }

    var biometricLock by remember { mutableStateOf(AyraPreferences.isBiometricLockEnabled(context)) }
    var autoLockSec by remember { mutableStateOf(AyraPreferences.getAutoLockTimeoutSeconds(context)) }
    var biometricForExport by remember { mutableStateOf(AyraPreferences.isBiometricRequiredForExport(context)) }
    var screenCaptureBlocked by remember { mutableStateOf(AyraPreferences.isScreenCaptureBlocked(context)) }
    var clipboardAutoClear by remember { mutableStateOf(AyraPreferences.isClipboardAutoClearEnabled(context)) }
    var localOnlyAi by remember { mutableStateOf(AyraPreferences.isLocalOnlyAiModeEnabled(context)) }
    var privacyRedaction by remember { mutableStateOf(AyraPreferences.isPrivacyModeEnabled(context)) }
    var externalAiConsent by remember { mutableStateOf(AyraPreferences.hasExternalAiConsent(context)) }
    var freePoolConsent by remember { mutableStateOf(AyraPreferences.isFreeAiPoolEnabled(context)) }
    var cloudE2eeSync by remember { mutableStateOf(AyraPreferences.isCloudE2eeSyncEnabled(context)) }
    var promptInjectionGuard by remember { mutableStateOf(AyraPreferences.isPromptInjectionGuardEnabled(context)) }
    var allowLanEndpoints by remember { mutableStateOf(AyraPreferences.isAllowLanEndpointsEnabled(context)) }

    var pendingExportPassphrase by remember { mutableStateOf<String?>(null) }
    var pendingImportPassphrase by remember { mutableStateOf<String?>(null) }
    var showErasureDialog by remember { mutableStateOf(false) }
    var erasureConfirmText by remember { mutableStateOf("") }
    var erasureReportNote by remember { mutableStateOf<String?>(null) }

    val auditEvents by SecurityAuditLogger.events.collectAsStateWithLifecycle()

    fun refreshAllState() {
        scope.launch(Dispatchers.IO) {
            SecurityAuditLogger.loadIfNeeded(context)
            val rep = DeviceSecurityInspector.inspectDeviceSecurity(context)
            val dbOk = VaultDatabaseKeyManager.verifyDatabaseEncryptedAtRest(context)
            val keys = AyraKeyManager.inspectAllKeys(context)
            val artifacts = EncryptedVaultStorage.listVaultArtifacts(context)
            val e2eeBundle = runCatching { AyraE2eeEngine.getOrCreateLocalDeviceBundle(context) }.getOrNull()
            val epoch = AyraE2eeEngine.getCurrentEpoch(context)
            val validChain = SecurityAuditLogger.verifyLogChain(context)
            val cloudDevs = cloudRepo.fetchCloudDevices().ifEmpty { AyraE2eeEngine.getTrustedDevices(context) }
            val cloudSess = cloudRepo.fetchCloudSessions()
            val cloudBaks = cloudRepo.listCloudBackups()

            withContext(Dispatchers.Main) {
                deviceReport = rep
                dbVerifiedAtRest = dbOk
                keystoreReports = keys
                vaultArtifacts = artifacts
                localFingerprint = e2eeBundle?.fingerprintSha256 ?: "Unavailable"
                currentEpoch = epoch
                chainValid = validChain
                devices = cloudDevs
                sessions = cloudSess
                cloudBackups = cloudBaks
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshAllState()
    }

    fun runWithOptionalBiometricGate(title: String, action: () -> Unit) {
        if (!biometricForExport || activity == null ||
            !BiometricAuthManager.checkCapability(context).deviceCredentialAvailable
        ) {
            action()
            return
        }
        BiometricAuthManager.authenticate(
            activity = activity,
            title = title,
            subtitle = "Sensitive Vault Export / Destructive Verification",
            onSuccess = { _ -> action() },
            onError = { reason, _ ->
                Toast.makeText(context, "Verification cancelled: $reason", Toast.LENGTH_LONG).show()
            }
        )
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val passphrase = pendingExportPassphrase
        pendingExportPassphrase = null
        if (uri != null && !passphrase.isNullOrBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val sessionsList = db.chatSessionDao().getAllSessionsOnce()
                    val messagesList = db.chatDao().getAllMessages().first()
                    val pluginsList = db.gitHubPluginDao().getAllPluginsSync()
                    val (json, meta) = EncryptedBackupManager.createEncryptedBackupJson(
                        context, passphrase, sessionsList, messagesList, pluginsList
                    )
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    meta
                }.onSuccess { meta ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Encrypted backup exported (${meta.backupId})", Toast.LENGTH_LONG).show()
                    }
                }.onFailure { err ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Export failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val passphrase = pendingImportPassphrase
        pendingImportPassphrase = null
        if (uri != null && !passphrase.isNullOrBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                    val payload = EncryptedBackupManager.restoreFromEncryptedBackupJson(context, raw, passphrase)
                    val idMap = mutableMapOf<Long, Long>()
                    payload.sessions.forEach { s -> idMap[s.id] = db.chatSessionDao().insertSession(s.copy(id = 0L)) }
                    payload.messages.forEach { m ->
                        db.chatDao().insertMessage(m.copy(id = 0L, sessionId = idMap[m.sessionId] ?: m.sessionId))
                    }
                    payload.plugins.forEach { db.gitHubPluginDao().upsertPlugin(it) }
                    payload
                }.onSuccess { payload ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Verified & restored ${payload.sessions.size} sessions, ${payload.messages.size} messages.",
                            Toast.LENGTH_LONG
                        ).show()
                        refreshAllState()
                    }
                }.onFailure { err ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Decrypt failed: Wrong passphrase or corrupted backup.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .let { if (onBack != null) it.padding(top = 36.dp) else it }
            .verticalScroll(rememberScrollState())
            .padding(if (onBack != null) 16.dp else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp).testTag("security_center_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.security_center_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.security_center_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = { VaultLockManager.lockNow(context, "Locked manually from Security Center") },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("security_lock_now_button")
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("Lock Now")
                }
            }

            if (!erasureReportNote.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = erasureReportNote!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            SecurityPostureCard(
                deviceReport = deviceReport,
                dbVerifiedAtRest = dbVerifiedAtRest,
                e2eeEnabled = cloudE2eeSync,
                vaultArtifacts = vaultArtifacts,
                onRefresh = { refreshAllState() }
            )

            // Vault Lock & Platform Hardening Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Biometric Vault Lock & Platform Hardening", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    SecurityToggleItem(
                        title = "Require Biometric / PIN Lock on Launch & Idle",
                        subtitle = "Verified via BiometricPrompt CryptoObject nonce cipher",
                        checked = biometricLock,
                        tag = "toggle_biometric_lock",
                        onCheckedChange = { wantEnable ->
                            if (!wantEnable || activity == null) {
                                biometricLock = wantEnable
                                AyraPreferences.setBiometricLockEnabled(context, wantEnable)
                            } else {
                                BiometricAuthManager.authenticate(
                                    activity = activity,
                                    title = "Enable Vault Biometric Lock",
                                    subtitle = "Confirm device biometric/PIN to activate",
                                    onSuccess = { _ ->
                                        biometricLock = true
                                        AyraPreferences.setBiometricLockEnabled(context, true)
                                    },
                                    onError = { err, _ ->
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    )

                    Text("Auto-Lock Timeout", style = MaterialTheme.typography.labelMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0L to "Immediate", 30L to "30s", 60L to "60s", 300L to "5m", -1L to "Manual").forEach { (sec, label) ->
                            FilterChip(
                                selected = autoLockSec == sec,
                                onClick = {
                                    autoLockSec = sec
                                    AyraPreferences.setAutoLockTimeoutSeconds(context, sec)
                                },
                                label = { Text(label) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("autolock_chip_$sec")
                            )
                        }
                    }

                    SecurityToggleItem(
                        title = "Re-Authenticate Before Exports & Erasure",
                        subtitle = "Enforces fresh biometric confirmation on sensitive actions",
                        checked = biometricForExport,
                        tag = "toggle_biometric_export",
                        onCheckedChange = {
                            biometricForExport = it
                            AyraPreferences.setBiometricRequiredForExport(context, it)
                        }
                    )
                    SecurityToggleItem(
                        title = "Block Screenshots & Recents Thumbnail (FLAG_SECURE)",
                        subtitle = "Prevents screenshots, screen recording, and external display/emulator capture",
                        checked = screenCaptureBlocked,
                        tag = "toggle_flag_secure",
                        onCheckedChange = {
                            screenCaptureBlocked = it
                            AyraPreferences.setScreenCaptureBlocked(context, it)
                        }
                    )
                    SecurityToggleItem(
                        title = "Sensitive Clipboard & 60s Auto-Clear",
                        subtitle = "Marks copied text EXTRA_IS_SENSITIVE & clears after 60 seconds",
                        checked = clipboardAutoClear,
                        tag = "toggle_clipboard_autoclear",
                        onCheckedChange = {
                            clipboardAutoClear = it
                            AyraPreferences.setClipboardAutoClearEnabled(context, it)
                        }
                    )
                }
            }

            // Hybrid AI Privacy & E2EE Sync Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Hybrid AI & Zero-Trust Cloud Controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    SecurityToggleItem(
                        title = "Local-Only AI Mode (Block Cloud & Telemetry)",
                        subtitle = "Routes strictly to Local Ollama/LAN; disables external providers & cloud sync",
                        checked = localOnlyAi,
                        tag = "toggle_local_only_ai",
                        onCheckedChange = {
                            localOnlyAi = it
                            AyraPreferences.setLocalOnlyAiModeEnabled(context, it)
                            SecurityAuditLogger.log(context, SecurityAuditCategory.AI_POLICY, SecurityAuditSeverity.INFO, "Local-Only AI Mode set to $it")
                        }
                    )
                    SecurityToggleItem(
                        title = "Privacy Redaction Mode",
                        subtitle = "Strips detected API keys & Bearer tokens before outbound AI prompts",
                        checked = privacyRedaction,
                        tag = "toggle_privacy_redaction",
                        onCheckedChange = {
                            privacyRedaction = it
                            AyraPreferences.setPrivacyModeEnabled(context, it)
                        }
                    )
                    SecurityToggleItem(
                        title = "External Cloud AI Provider Consent",
                        subtitle = "Allows Gemini, Groq, Anthropic, OpenAI & OpenRouter when Local-Only is off",
                        checked = externalAiConsent,
                        tag = "toggle_external_ai_consent",
                        onCheckedChange = {
                            externalAiConsent = it
                            AyraPreferences.setExternalAiConsent(context, it)
                        }
                    )
                    SecurityToggleItem(
                        title = "Anonymous Public AI Pool Opt-In (Pollinations)",
                        subtitle = "Disabled by default so unauthenticated pools never receive prompts unexpectedly",
                        checked = freePoolConsent,
                        tag = "toggle_free_pool_consent",
                        onCheckedChange = {
                            freePoolConsent = it
                            AyraPreferences.setFreeAiPoolEnabled(context, it)
                        }
                    )
                    SecurityToggleItem(
                        title = "End-to-End Encrypted Cloud Sync (E2EEv1)",
                        subtitle = "Encrypts messages & creations on-device prior to Firestore upload",
                        checked = cloudE2eeSync,
                        tag = "toggle_e2ee_sync",
                        onCheckedChange = {
                            cloudE2eeSync = it
                            AyraPreferences.setCloudE2eeSyncEnabled(context, it)
                        }
                    )
                    SecurityToggleItem(
                        title = "Prompt-Injection & RAG Tag Isolation Guard",
                        subtitle = "Detects overrides & isolates untrusted documents inside XML boundaries",
                        checked = promptInjectionGuard,
                        tag = "toggle_prompt_injection_guard",
                        onCheckedChange = {
                            promptInjectionGuard = it
                            AyraPreferences.setPromptInjectionGuardEnabled(context, it)
                        }
                    )
                    SecurityToggleItem(
                        title = "Allow LAN HTTP Endpoints (Ollama / ComfyUI)",
                        subtitle = "Permits RFC1918 LAN HTTP for self-hosted inference while blocking 169.254.169.254",
                        checked = allowLanEndpoints,
                        tag = "toggle_allow_lan",
                        onCheckedChange = {
                            allowLanEndpoints = it
                            AyraPreferences.setAllowLanEndpointsEnabled(context, it)
                        }
                    )
                }
            }

            KeystoreKeysCard(
                keys = keystoreReports,
                onRotateKey = { baseAlias ->
                    scope.launch(Dispatchers.IO) {
                        val gen = AyraKeyManager.rotatePurposeKey(context, baseAlias)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Rotated $baseAlias -> Gen $gen", Toast.LENGTH_SHORT).show()
                            refreshAllState()
                        }
                    }
                }
            )

            DevicesAndSessionsCard(
                localFingerprint = localFingerprint,
                currentEpoch = currentEpoch,
                devices = devices,
                sessions = sessions,
                onRevokeDevice = { devId ->
                    runWithOptionalBiometricGate("Authorize Device Revocation") {
                        scope.launch(Dispatchers.IO) {
                            cloudRepo.revokeDeviceAndSessionInCloud(devId)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Revoked $devId & rotated E2EE epoch!", Toast.LENGTH_SHORT).show()
                                refreshAllState()
                            }
                        }
                    }
                }
            )

            EncryptedBackupCard(
                cloudBackups = cloudBackups,
                onExportEncryptedFile = { passphrase ->
                    runWithOptionalBiometricGate("Authorize Encrypted Backup Export") {
                        pendingExportPassphrase = passphrase
                        exportBackupLauncher.launch("ayra_vault_backup_${System.currentTimeMillis()}.ayrabak")
                    }
                },
                onImportEncryptedFile = { passphrase ->
                    pendingImportPassphrase = passphrase
                    importBackupLauncher.launch(arrayOf("*/*"))
                },
                onUploadEncryptedCloudBackup = { passphrase ->
                    runWithOptionalBiometricGate("Upload Encrypted Cloud Backup") {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                val s = db.chatSessionDao().getAllSessionsOnce()
                                val m = db.chatDao().getAllMessages().first()
                                val p = db.gitHubPluginDao().getAllPluginsSync()
                                val (json, meta) = EncryptedBackupManager.createEncryptedBackupJson(context, passphrase, s, m, p)
                                cloudRepo.uploadEncryptedBackup(meta.backupId, EncryptedBackupManager.FORMAT_V2, json, meta.payloadSha256, meta.createdAtMs)
                                meta.backupId
                            }.onSuccess { id ->
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Uploaded E2EE backup $id", Toast.LENGTH_SHORT).show()
                                    refreshAllState()
                                }
                            }.onFailure { err ->
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Cloud upload failed: ${err.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                },
                onRestoreCloudBackup = { record, passphrase ->
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val payload = EncryptedBackupManager.restoreFromEncryptedBackupJson(context, record.encryptedPayloadJson, passphrase)
                            val idMap = mutableMapOf<Long, Long>()
                            payload.sessions.forEach { s -> idMap[s.id] = db.chatSessionDao().insertSession(s.copy(id = 0L)) }
                            payload.messages.forEach { m -> db.chatDao().insertMessage(m.copy(id = 0L, sessionId = idMap[m.sessionId] ?: m.sessionId)) }
                            payload.plugins.forEach { db.gitHubPluginDao().upsertPlugin(it) }
                            payload
                        }.onSuccess { payload ->
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Restored ${payload.sessions.size} sessions from cloud backup!", Toast.LENGTH_LONG).show()
                                refreshAllState()
                            }
                        }.onFailure { err ->
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Decrypt failed: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onDeleteCloudBackup = { backupId ->
                    scope.launch(Dispatchers.IO) {
                        cloudRepo.deleteCloudBackup(backupId)
                        withContext(Dispatchers.Main) { refreshAllState() }
                    }
                }
            )

            SecurityAuditLogCard(
                events = auditEvents,
                chainValid = chainValid,
                onVerifyChain = {
                    chainValid = SecurityAuditLogger.verifyLogChain(context)
                    Toast.makeText(
                        context,
                        if (chainValid) "HMAC chain integrity verified!" else "Audit chain tampering detected!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )

            CryptographicErasureCard(
                onRequestCompleteErasure = {
                    runWithOptionalBiometricGate("Authorize Complete Vault Erasure") {
                        erasureConfirmText = ""
                        showErasureDialog = true
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showErasureDialog) {
        AlertDialog(
            onDismissRequest = { showErasureDialog = false },
            title = { Text(stringResource(R.string.security_erasure_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.security_erasure_confirm_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = erasureConfirmText,
                        onValueChange = { erasureConfirmText = it },
                        label = { Text("Type ERASE") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("erasure_confirm_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = erasureConfirmText.trim().equals("ERASE", ignoreCase = true),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showErasureDialog = false
                        scope.launch {
                            val report = DataErasureManager.performCompleteCryptographicErasure(context, includeCloudPurge = true)
                            val authDeleteRes = com.ayra.ai.app.cloud.GoogleAuthManager(context).deleteCurrentAccount()
                            val accountNote = if (authDeleteRes.isSuccess) {
                                "Firebase account & credential session cleared."
                            } else {
                                "Cloud documents erased; re-sign-in may be needed to permanently delete Firebase Auth user (${authDeleteRes.exceptionOrNull()?.localizedMessage ?: "auth check"})."
                            }
                            erasureReportNote = "Vault wiped (${report.wipedVaultFiles} files, ${report.deletedCloudDocuments} cloud docs). $accountNote ${report.honestHardwareDisclosure}"
                            refreshAllState()
                            onVaultWiped()
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("erasure_confirm_execute_button").rejectObscuredTouches()
                ) {
                    Text("Delete Account & Erase")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showErasureDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SecurityToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}
