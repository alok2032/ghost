package com.ayra.ai.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ayra.ai.app.R
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.security.EncryptedBackupManager
import com.ayra.ai.app.security.VaultDatabaseKeyManager
import com.ayra.ai.app.security.VaultState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VaultRecoveryScreen(
    vaultState: VaultState,
    reason: String,
    onRecovered: (ChatDatabase) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isWorking by remember { mutableStateOf(false) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var showBackupPassphraseDialog by remember { mutableStateOf(false) }
    var pendingBackupPassphrase by remember { mutableStateOf("") }
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var resetConfirmText by remember { mutableStateOf("") }

    val titleText = if (vaultState == VaultState.KEY_LOST) {
        stringResource(R.string.vault_recovery_title_key_lost)
    } else {
        stringResource(R.string.vault_recovery_title_corrupt)
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val passphrase = pendingBackupPassphrase
        pendingBackupPassphrase = ""
        if (uri == null || passphrase.isBlank()) return@rememberLauncherForActivityResult

        isWorking = true
        statusError = null
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val rawJson = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (rawJson.isBlank()) {
                    throw IllegalArgumentException("Selected backup file is empty or unreadable.")
                }
                val restoredPayload = EncryptedBackupManager.restoreFromEncryptedBackupJson(
                    context = context,
                    encryptedBackupJson = rawJson,
                    recoveryPassphrase = passphrase
                )
                VaultDatabaseKeyManager.resetCorruptedVaultAndDatabase(context)
                when (val openRes = ChatDatabase.tryGetDatabase(context)) {
                    is ChatDatabase.VaultOpenResult.Ready -> {
                        val db = openRes.database
                        val idMap = mutableMapOf<Long, Long>()
                        restoredPayload.sessions.forEach { s ->
                            idMap[s.id] = db.chatSessionDao().insertSession(s.copy(id = 0L))
                        }
                        restoredPayload.messages.forEach { m ->
                            db.chatDao().insertMessage(m.copy(id = 0L, sessionId = idMap[m.sessionId] ?: m.sessionId))
                        }
                        restoredPayload.plugins.forEach { db.gitHubPluginDao().upsertPlugin(it) }
                        db
                    }
                    is ChatDatabase.VaultOpenResult.NeedsRecovery -> {
                        throw IllegalStateException(openRes.reason)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                isWorking = false
                result.onSuccess { db -> onRecovered(db) }
                    .onFailure { err ->
                        statusError = err.message ?: "Backup decryption failed. Your existing vault files were left untouched."
                    }
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("vault_recovery_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (vaultState == VaultState.KEY_LOST) {
                                Icons.Default.KeyOff
                            } else {
                                Icons.Default.Warning
                            },
                            contentDescription = titleText,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = if (vaultState == VaultState.KEY_LOST) {
                            "Your Android Keystore encryption key is missing or invalidated (for example after an OS lock reset). Ayra will never open an unencrypted database or silently delete your encrypted history."
                        } else {
                            "Ayra could not verify the encrypted vault envelope. Your database has been kept intact so you can restore from an encrypted backup or confirm a fresh reset."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    if (reason.isNotBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    if (!statusError.isNullOrBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = statusError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    if (isWorking) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = { showBackupPassphraseDialog = true },
                        enabled = !isWorking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("vault_recovery_restore_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = stringResource(R.string.vault_recovery_restore_backup))
                    }

                    OutlinedButton(
                        onClick = {
                            resetConfirmText = ""
                            showResetConfirmDialog = true
                        },
                        enabled = !isWorking,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("vault_recovery_reset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = stringResource(R.string.vault_recovery_reset_vault))
                    }
                }
            }
        }
    }

    if (showBackupPassphraseDialog) {
        var passphraseInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBackupPassphraseDialog = false },
            title = { Text("Enter Backup Passphrase") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the recovery passphrase used when you exported your .ayrabak archive.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = passphraseInput,
                        onValueChange = { passphraseInput = it },
                        label = { Text("Recovery Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingBackupPassphrase = passphraseInput
                        showBackupPassphraseDialog = false
                        openBackupLauncher.launch(arrayOf("*/*"))
                    },
                    enabled = passphraseInput.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Choose .ayrabak File")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBackupPassphraseDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Confirm Vault Reset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "This will permanently wipe the unreachable local encrypted database and initialize a brand-new SQLCipher AES-256 vault. Type RESET to confirm.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = resetConfirmText,
                        onValueChange = { resetConfirmText = it },
                        label = { Text("Type RESET") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vault_recovery_confirm_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirmDialog = false
                        isWorking = true
                        statusError = null
                        scope.launch(Dispatchers.IO) {
                            val openResult = runCatching {
                                VaultDatabaseKeyManager.resetCorruptedVaultAndDatabase(context)
                                ChatDatabase.tryGetDatabase(context)
                            }
                            withContext(Dispatchers.Main) {
                                isWorking = false
                                openResult.onSuccess { res ->
                                    when (res) {
                                        is ChatDatabase.VaultOpenResult.Ready -> onRecovered(res.database)
                                        is ChatDatabase.VaultOpenResult.NeedsRecovery -> {
                                            statusError = res.reason
                                        }
                                    }
                                }.onFailure { err ->
                                    statusError = err.message ?: "Unable to initialize fresh vault."
                                }
                            }
                        }
                    },
                    enabled = resetConfirmText.trim().equals("RESET", ignoreCase = false),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("vault_recovery_confirm_reset_button")
                ) {
                    Text("Reset Vault")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetConfirmDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
