package com.ayra.ai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ayra.ai.app.cloud.CloudBackupRecord
import com.ayra.ai.app.cloud.CloudSessionInfo
import com.ayra.ai.app.security.DeviceSecurityReport
import com.ayra.ai.app.security.EncryptedVaultEntry
import com.ayra.ai.app.security.KeySecurityLevel
import com.ayra.ai.app.security.KeystoreKeyReport
import com.ayra.ai.app.security.SecurityAuditEvent
import com.ayra.ai.app.security.TrustedE2eeDevice
import com.ayra.ai.app.security.rejectObscuredTouches
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SecurityPostureCard(
    deviceReport: DeviceSecurityReport?,
    dbVerifiedAtRest: Boolean,
    e2eeEnabled: Boolean,
    vaultArtifacts: List<EncryptedVaultEntry>,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Vault Security Posture & Integrity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("security_refresh_posture_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh inspection", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Verify")
                }
            }

            if (deviceReport != null) {
                PostureLine("Android Keystore", deviceReport.keystoreLevel.label)
                PostureLine("SQLCipher Room DB", "${deviceReport.databaseState.label} • Header Clean: ${if (dbVerifiedAtRest) "YES" else "NO"}")
                PostureLine("Encrypted File Vault", "${vaultArtifacts.size} AES-256-GCM .ayravlt artifacts")
                PostureLine("Biometric Hardware", deviceReport.biometricCapability.status.label)
                PostureLine("E2EE Cloud Envelope", if (e2eeEnabled) "Active (E2EEv1 ECDSA+ECDH+AES-GCM)" else "Disabled")
                PostureLine("Play Integrity Verdict", deviceReport.playIntegrityStatus.label)
                PostureLine(
                    "Device Signals",
                    "Debuggable=${deviceReport.isDebuggableBuild} • Debugger=${deviceReport.isDebuggerAttached} • TestKeys=${deviceReport.isTestKeysBuild} • RootHits=${deviceReport.rootSignalsDetected.size}"
                )
                Text(
                    text = "APK Signer SHA-256: ${deviceReport.apkSigningSha256}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = deviceReport.playIntegrityNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PostureLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun KeystoreKeysCard(
    keys: List<KeystoreKeyReport>,
    onRotateKey: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Keystore Purpose-Separated Hierarchy",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            keys.forEach { report ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${report.purpose} (Gen ${report.generation})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            val hwNote = when (report.securityLevel) {
                                KeySecurityLevel.HARDWARE_STRONGBOX -> "StrongBox Hardware"
                                KeySecurityLevel.HARDWARE_TEE -> "Hardware TEE"
                                else -> report.securityLevel.label
                            }
                            Text(
                                text = "${report.activeAlias} • $hwNote" +
                                    if (report.isUserAuthRequired) " • UserAuth" else "" +
                                        if (report.isInvalidated) " • INVALIDATED" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (report.isInvalidated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { onRotateKey(report.baseAlias) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("rotate_key_${report.baseAlias}")
                        ) {
                            Text("Rotate")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
internal fun DevicesAndSessionsCard(
    localFingerprint: String,
    currentEpoch: Int,
    devices: List<TrustedE2eeDevice>,
    sessions: List<CloudSessionInfo>,
    onRevokeDevice: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Trusted E2EE Devices & Sessions (Epoch $currentEpoch)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Local Identity Fingerprint:\n$localFingerprint",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary
            )

            if (devices.isEmpty()) {
                Text(
                    text = "Sign in to inspect cloud-registered trusted devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                devices.forEach { dev ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${dev.deviceName} (${dev.deviceId.take(12)})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Epoch ${dev.epochAdded} • ${if (dev.isRevoked) "REVOKED" else "ACTIVE"} • ${dev.fingerprintSha256.take(23)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (dev.isRevoked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!dev.isRevoked) {
                            OutlinedButton(
                                onClick = { onRevokeDevice(dev.deviceId) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("revoke_device_${dev.deviceId}")
                            ) {
                                Text("Revoke")
                            }
                        }
                    }
                }
            }

            if (sessions.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Cloud Login Sessions (${sessions.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                sessions.take(5).forEach { s ->
                    Text(
                        text = "• ${s.deviceName}: ${s.status} (last ${DateFormat.getDateTimeInstance().format(Date(s.lastActiveMs.coerceAtLeast(1L)))})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun EncryptedBackupCard(
    cloudBackups: List<CloudBackupRecord>,
    onExportEncryptedFile: (String) -> Unit,
    onImportEncryptedFile: (String) -> Unit,
    onUploadEncryptedCloudBackup: (String) -> Unit,
    onRestoreCloudBackup: (CloudBackupRecord, String) -> Unit,
    onDeleteCloudBackup: (String) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var passphraseError by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Encrypted Recovery Backups (.ayrabak)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Uses PBKDF2-SHA256 (210,000 iterations) + AES-256-GCM. API keys and device private keys are NEVER exported. Passphrases are never stored — forgotten passphrases are permanently unrecoverable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    passphraseError = null
                },
                label = { Text("Backup Recovery Passphrase (10+ chars)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup_passphrase_input")
            )
            if (!passphraseError.isNullOrBlank()) {
                Text(passphraseError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val err = com.ayra.ai.app.security.EncryptedBackupManager.validatePassphraseStrength(passphrase)
                        if (err != null) passphraseError = err else onExportEncryptedFile(passphrase)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("export_encrypted_backup_button")
                        .rejectObscuredTouches()
                ) {
                    Text("Export .ayrabak")
                }
                OutlinedButton(
                    onClick = {
                        if (passphrase.isBlank()) passphraseError = "Enter recovery passphrase first to decrypt."
                        else onImportEncryptedFile(passphrase)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("import_encrypted_backup_button")
                        .rejectObscuredTouches()
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Restore File")
                }
            }

            OutlinedButton(
                onClick = {
                    val err = com.ayra.ai.app.security.EncryptedBackupManager.validatePassphraseStrength(passphrase)
                    if (err != null) passphraseError = err else onUploadEncryptedCloudBackup(passphrase)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("upload_cloud_encrypted_backup_button")
                    .rejectObscuredTouches()
            ) {
                Text("Upload Zero-Knowledge Encrypted Backup to Cloud")
            }

            cloudBackups.forEach { rec ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rec.backupId, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text("SHA256: ${rec.payloadSha256.take(16)}...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = {
                            if (passphrase.isBlank()) passphraseError = "Enter recovery passphrase to restore."
                            else onRestoreCloudBackup(rec, passphrase)
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Restore") }
                    TextButton(
                        onClick = { onDeleteCloudBackup(rec.backupId) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
internal fun SecurityAuditLogCard(
    events: List<SecurityAuditEvent>,
    chainValid: Boolean,
    onVerifyChain: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tamper-Evident Security Audit Log (${events.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (chainValid) "HMAC-SHA256 Chain Status: INTACT" else "HMAC-SHA256 Chain Status: TAMPER DETECTED",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chainValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                OutlinedButton(
                    onClick = onVerifyChain,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("verify_audit_chain_button")
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Check HMAC")
                }
            }

            events.take(8).forEach { evt ->
                Text(
                    text = "[${evt.severity.name}/${evt.category.name}] ${evt.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (evt.severity.name == "CRITICAL") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun CryptographicErasureCard(
    onRequestCompleteErasure: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Irreversible Cryptographic Erasure",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = "Permanently destroys Android Keystore purpose KEKs, SQLCipher database & wrapped passphrases, encrypted .ayravlt vault files, API credentials, audit logs, and cloud Firestore user records.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(
                onClick = onRequestCompleteErasure,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("complete_cryptographic_erasure_button")
                    .rejectObscuredTouches()
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Perform Complete Cryptographic Nuke")
            }
        }
    }
}
