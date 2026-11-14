package com.ayra.ai.app.security

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

enum class DatabaseEncryptionState(val label: String) {
    SQLCIPHER_AES256("SQLCipher AES-256 Encrypted"),
    ROBOLECTRIC_JVM_FALLBACK("JVM Test Host (Native SQLCipher SO Unavailable)"),
    UNINITIALIZED("Uninitialized"),
    ERROR("Database Encryption Error")
}

/**
 * Manages the cryptographically random 256-bit SQLCipher database passphrase wrapped under
 * [AyraKeyManager.ALIAS_DB_KEK] and handles automatic plaintext SQLite -> SQLCipher migration.
 */
object VaultDatabaseKeyManager {

    private const val META_PREFS = "ayra_vault_db_key_meta"
    private const val KEY_WRAPPED_DB_PASSPHRASE = "wrapped_db_passphrase_v1"
    private const val AAD_CONTEXT = "ayra_sqlcipher_passphrase_v1"
    private const val SQLITE_MAGIC = "SQLite format 3\u0000"

    @Volatile
    private var nativeLoaded: Boolean? = null

    @Volatile
    private var cachedPassphrase: ByteArray? = null

    @Volatile
    var currentEncryptionState: DatabaseEncryptionState = DatabaseEncryptionState.UNINITIALIZED
        private set

    fun isRobolectricEnvironment(): Boolean {
        return Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
    }

    @Synchronized
    fun ensureSqlCipherNativeLoaded(): Boolean {
        nativeLoaded?.let { return it }
        val loaded = try {
            System.loadLibrary("sqlcipher")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: Throwable) {
            false
        }
        nativeLoaded = loaded
        return loaded
    }

    /**
     * Retrieves or generates a cryptographically random 256-bit database key (hex-encoded 64 ASCII bytes
     * for maximum compatibility across SQLCipher PRAGMA/API paths), wrapped at rest under [AyraKeyManager.ALIAS_DB_KEK].
     */
    @Synchronized
    fun getOrCreateDatabasePassphrase(context: Context): ByteArray {
        cachedPassphrase?.let { if (it.isNotEmpty()) return it.copyOf() }

        val prefs = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(KEY_WRAPPED_DB_PASSPHRASE, null)
        if (!wrapped.isNullOrBlank()) {
            return try {
                val decodedHex = AyraKeyManager.decryptStringWithAad(
                    context = context,
                    baseAlias = AyraKeyManager.ALIAS_DB_KEK,
                    serializedEnvelope = wrapped,
                    aadContext = AAD_CONTEXT
                )
                val passBytes = decodedHex.toByteArray(StandardCharsets.UTF_8)
                // Re-wrap if DB KEK generation rotated
                val activeGen = AyraKeyManager.getKeyGeneration(context, AyraKeyManager.ALIAS_DB_KEK)
                val envGen = runCatching { JSONObject(wrapped).optInt("gen", 1) }.getOrDefault(1)
                if (envGen < activeGen) {
                    val rewrapped = AyraKeyManager.encryptStringWithAad(
                        context = context,
                        baseAlias = AyraKeyManager.ALIAS_DB_KEK,
                        plaintext = decodedHex,
                        aadContext = AAD_CONTEXT
                    )
                    prefs.edit().putString(KEY_WRAPPED_DB_PASSPHRASE, rewrapped).commit()
                }
                cachedPassphrase = passBytes.copyOf()
                passBytes
            } catch (e: Exception) {
                currentEncryptionState = DatabaseEncryptionState.ERROR
                throw IllegalStateException("Failed to unwrap SQLCipher database key", e)
            }
        }

        // Generate fresh 256-bit random passphrase
        val randomBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hexKey = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
            ?: java.util.Base64.getEncoder().encodeToString(randomBytes)
        randomBytes.fill(0)

        val wrappedEnvelope = AyraKeyManager.encryptStringWithAad(
            context = context,
            baseAlias = AyraKeyManager.ALIAS_DB_KEK,
            plaintext = hexKey,
            aadContext = AAD_CONTEXT
        )
        prefs.edit().putString(KEY_WRAPPED_DB_PASSPHRASE, wrappedEnvelope).commit()

        val result = hexKey.toByteArray(StandardCharsets.UTF_8)
        cachedPassphrase = result.copyOf()
        SecurityAuditLogger.log(
            context = context,
            category = SecurityAuditCategory.DATABASE,
            severity = SecurityAuditSeverity.INFO,
            message = "Generated and Keystore-wrapped fresh 256-bit SQLCipher database key."
        )
        return result
    }

    /**
     * Checks if [dbName] exists on disk as an unencrypted SQLite file ("SQLite format 3").
     */
    fun isPlaintextSqliteDatabase(dbFile: File): Boolean {
        if (!dbFile.exists() || dbFile.length() < 16L) return false
        return runCatching {
            dbFile.inputStream().use { input ->
                val header = ByteArray(16)
                val read = input.read(header)
                read == 16 && String(header, StandardCharsets.US_ASCII) == SQLITE_MAGIC
            }
        }.getOrDefault(false)
    }

    /**
     * Verifies at byte level that the Room database file does NOT have a plaintext SQLite header.
     */
    fun verifyDatabaseEncryptedAtRest(context: Context, dbName: String = "ayra_chat_database"): Boolean {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) {
            return currentEncryptionState == DatabaseEncryptionState.SQLCIPHER_AES256
        }
        return !isPlaintextSqliteDatabase(dbFile)
    }

    /**
     * Migrates a legacy unencrypted SQLite database to SQLCipher AES-256 via `sqlcipher_export`
     * and securely wipes the old plaintext database, WAL, and SHM files.
     */
    @Synchronized
    fun migrateLegacyPlaintextDatabaseIfNeeded(context: Context, dbName: String = "ayra_chat_database") {
        val dbFile = context.getDatabasePath(dbName)
        if (!isPlaintextSqliteDatabase(dbFile)) return
        if (!ensureSqlCipherNativeLoaded()) return

        val passphraseBytes = getOrCreateDatabasePassphrase(context)
        val passphraseString = String(passphraseBytes, StandardCharsets.UTF_8)
        val tempEncryptedFile = File(dbFile.parentFile, "${dbName}_sqlcipher_tmp.db")
        val legacyWal = File("${dbFile.absolutePath}-wal")
        val legacyShm = File("${dbFile.absolutePath}-shm")

        try {
            if (tempEncryptedFile.exists()) AndroidHardeningHelper.secureWipeFile(tempEncryptedFile)

            // Open legacy unencrypted DB using empty passphrase with SQLCipher library
            val legacyDb = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                "",
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null
            )
            legacyDb.use { db ->
                val escapedPath = tempEncryptedFile.absolutePath.replace("'", "''")
                val escapedKey = passphraseString.replace("'", "''")
                db.rawExecSQL("ATTACH DATABASE '$escapedPath' AS encrypted KEY '$escapedKey';")
                db.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                db.rawExecSQL("DETACH DATABASE encrypted;")
            }

            // Replace plaintext file with SQLCipher encrypted DB
            AndroidHardeningHelper.secureWipeFile(legacyWal)
            AndroidHardeningHelper.secureWipeFile(legacyShm)
            AndroidHardeningHelper.secureWipeFile(dbFile)
            if (!tempEncryptedFile.renameTo(dbFile)) {
                tempEncryptedFile.copyTo(dbFile, overwrite = true)
                AndroidHardeningHelper.secureWipeFile(tempEncryptedFile)
            }

            SecurityAuditLogger.log(
                context = context,
                category = SecurityAuditCategory.DATABASE,
                severity = SecurityAuditSeverity.INFO,
                message = "Migrated legacy plaintext SQLite '$dbName' to SQLCipher AES-256 and wiped legacy artifacts."
            )
        } catch (e: Exception) {
            AndroidHardeningHelper.secureWipeFile(tempEncryptedFile)
            SecurityAuditLogger.log(
                context = context,
                category = SecurityAuditCategory.DATABASE,
                severity = SecurityAuditSeverity.CRITICAL,
                message = "Plaintext->SQLCipher migration error: ${e.javaClass.simpleName}"
            )
            throw IllegalStateException("Plaintext database migration failed", e)
        } finally {
            passphraseBytes.fill(0)
        }
    }

    /**
     * Creates a [SupportOpenHelperFactory] backed by SQLCipher when native SQLCipher loads.
     * Fails CLOSED on genuine Android devices if SQLCipher fails to initialize; allows fallback ONLY
     * in Robolectric JVM host unit tests where Android JNI .so libraries cannot be executed.
     */
    fun createOpenHelperFactory(context: Context, dbName: String = "ayra_chat_database"): SupportSQLiteOpenHelper.Factory? {
        val loaded = ensureSqlCipherNativeLoaded()
        if (!loaded) {
            if (isRobolectricEnvironment()) {
                // Still initialize/verify Keystore KEK DB key wrapping on JVM tests
                getOrCreateDatabasePassphrase(context).fill(0)
                currentEncryptionState = DatabaseEncryptionState.ROBOLECTRIC_JVM_FALLBACK
                return null
            }
            currentEncryptionState = DatabaseEncryptionState.ERROR
            throw IllegalStateException("SQLCipher native library failed to load; refusing unencrypted database open.")
        }

        migrateLegacyPlaintextDatabaseIfNeeded(context, dbName)
        val passphrase = getOrCreateDatabasePassphrase(context)
        currentEncryptionState = DatabaseEncryptionState.SQLCIPHER_AES256
        return SupportOpenHelperFactory(passphrase)
    }

    /**
     * Irreversibly erases the wrapped DB passphrase and clears the in-memory passphrase cache.
     */
    @Synchronized
    fun destroyDatabaseKeyMetadata(context: Context) {
        cachedPassphrase?.fill(0)
        cachedPassphrase = null
        currentEncryptionState = DatabaseEncryptionState.UNINITIALIZED
        runCatching {
            context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
