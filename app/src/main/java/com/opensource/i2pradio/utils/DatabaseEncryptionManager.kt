package com.opensource.i2pradio.utils

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * Manager for optional database-level encryption using SQLCipher.
 *
 * Provides functionality to:
 * - Enable/disable database encryption with password-derived keys
 * - Derive encryption keys from user password using PBKDF2
 * - Migrate between encrypted and unencrypted databases
 * - Re-key database when password changes
 * - Use hardware-backed keystore for salt storage
 *
 * SECURITY MODEL:
 * - Database encryption key is derived from user's app password
 * - Salt is stored in EncryptedSharedPreferences (hardware-backed)
 * - Database can only be decrypted with correct password
 * - Password changes require database re-keying
 */
object DatabaseEncryptionManager {
    private const val ENCRYPTED_PREFS_NAME = "db_encryption_prefs"
    private const val KEY_DB_ENCRYPTION_ENABLED = "db_encryption_enabled"
    private const val KEY_DB_SALT = "db_salt"  // Changed from KEY_DB_PASSPHRASE
    private const val PASSPHRASE_LENGTH = 32 // 256 bits

    @Volatile
    private var sqlCipherInitialized = false

    // Cache EncryptedSharedPreferences to avoid repeated slow initialization
    // MasterKey creation and EncryptedSharedPreferences.create() are expensive operations
    @Volatile
    private var cachedPrefs: android.content.SharedPreferences? = null
    private val prefsLock = Any()

    /**
     * Get or create the master key for encryption
     */
    private fun getMasterKey(context: Context): MasterKey {
        return MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Get EncryptedSharedPreferences for storing database encryption settings
     * Cached to avoid repeated slow initialization (MasterKey + crypto setup)
     */
    private fun getEncryptedPreferences(context: Context): android.content.SharedPreferences {
        // Fast path - return cached instance
        cachedPrefs?.let { return it }

        // Slow path - create and cache
        synchronized(prefsLock) {
            // Double-check after acquiring lock
            cachedPrefs?.let { return it }

            return try {
                EncryptedSharedPreferences.create(
                    context.applicationContext,  // Use app context to avoid leaks
                    ENCRYPTED_PREFS_NAME,
                    getMasterKey(context.applicationContext),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { cachedPrefs = it }
            } catch (e: GeneralSecurityException) {
                throw RuntimeException("Failed to create EncryptedSharedPreferences", e)
            }
        }
    }

    /**
     * Check if database encryption is enabled
     */
    fun isDatabaseEncryptionEnabled(context: Context): Boolean {
        return getEncryptedPreferences(context)
            .getBoolean(KEY_DB_ENCRYPTION_ENABLED, false)
    }

    /**
     * Enable database encryption with password-derived key
     * Generates a new random salt and derives encryption key from password
     *
     * @param password The user's app password
     * @return The derived passphrase for database encryption
     */
    fun enableDatabaseEncryption(context: Context, password: String): ByteArray {
        // Generate a cryptographically secure random salt
        val salt = PasswordHashUtil.generateSalt()

        // Store salt securely in EncryptedSharedPreferences
        val saltHex = salt.toHexString()
        getEncryptedPreferences(context)
            .edit()
            .putString(KEY_DB_SALT, saltHex)
            .putBoolean(KEY_DB_ENCRYPTION_ENABLED, true)
            .apply()

        // Derive encryption key from password + salt
        return PasswordHashUtil.deriveKey(password, salt)
    }

    /**
     * Disable database encryption
     * WARNING: This should trigger database migration to unencrypted format
     */
    fun disableDatabaseEncryption(context: Context) {
        getEncryptedPreferences(context)
            .edit()
            .remove(KEY_DB_SALT)
            .putBoolean(KEY_DB_ENCRYPTION_ENABLED, false)
            .apply()
    }

    /**
     * Get the database passphrase by deriving it from user password
     * This method requires the user's password to decrypt the database
     *
     * @param password The user's app password
     * @return The derived passphrase as ByteArray, or null if encryption is not enabled
     */
    fun getDatabasePassphrase(context: Context, password: String): ByteArray? {
        if (!isDatabaseEncryptionEnabled(context)) {
            return null
        }

        val saltHex = getEncryptedPreferences(context)
            .getString(KEY_DB_SALT, null) ?: return null

        val salt = saltHex.hexToByteArray()

        // Derive encryption key from password + salt
        return PasswordHashUtil.deriveKey(password, salt)
    }

    /**
     * Get SupportFactory for Room database with password-derived encryption
     * Requires user password to derive the encryption key
     *
     * @param password The user's app password
     * @return SupportFactory for encrypted database, or null for unencrypted
     */
    fun getSupportFactory(context: Context, password: String): SupportFactory? {
        val passphrase = getDatabasePassphrase(context, password) ?: return null

        // Use clearPassphrase = false to prevent SupportFactory from automatically
        // clearing the passphrase after first use. This is required for Room which
        // may close and reopen the database multiple times (e.g., for LiveData queries).
        //
        // IMPORTANT: We do NOT clear the passphrase here because SupportFactory holds
        // a reference to the ByteArray. Clearing it would zero out the passphrase that
        // SupportFactory is using, causing "file is not a database" errors when Room
        // reopens the database. The passphrase will remain in memory as long as the
        // SupportFactory instance exists, which is necessary for Room's operation.
        return SupportFactory(passphrase, null, false)
    }

    /**
     * Initialize SQLCipher library
     * This is idempotent - safe to call multiple times, but only loads once
     */
    fun initializeSQLCipher(context: Context) {
        if (!sqlCipherInitialized) {
            synchronized(this) {
                if (!sqlCipherInitialized) {
                    SQLiteDatabase.loadLibs(context)
                    sqlCipherInitialized = true
                }
            }
        }
    }

    /**
     * Convert ByteArray to hex string
     */
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    /**
     * Convert hex string to ByteArray
     */
    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length" }

        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    /**
     * Check if database file is encrypted
     * Reads the first 16 bytes of the database file to check for SQLite header
     *
     * @param dbPath Path to the database file
     * @return true if database appears to be encrypted (no SQLite header)
     */
    fun isDatabaseFileEncrypted(dbPath: String): Boolean {
        return try {
            val file = java.io.File(dbPath)
            if (!file.exists() || file.length() < 16) {
                return false
            }

            // Read first 16 bytes
            val header = ByteArray(16)
            file.inputStream().use { it.read(header) }

            // SQLite database files start with "SQLite format 3\u0000"
            val sqliteHeader = "SQLite format 3\u0000".toByteArray()

            // If header doesn't match, database is likely encrypted
            !header.contentEquals(sqliteHeader.copyOf(16))
        } catch (e: Exception) {
            android.util.Log.e("DatabaseEncryption", "Error checking database encryption", e)
            false
        }
    }

    /**
     * Migrate database from unencrypted to encrypted format
     * This is a destructive operation - backup recommended
     *
     * @param context Application context
     * @param dbName Database name
     * @param passphrase Passphrase for encryption
     */
    fun encryptDatabase(context: Context, dbName: String, passphrase: ByteArray) {
        val dbPath = context.getDatabasePath(dbName).absolutePath
        val tempDbPath = "$dbPath.encrypted"

        var unencryptedDb: SQLiteDatabase? = null
        try {
            // Clean up WAL and SHM files from Room's WAL mode
            deleteWalFiles(dbPath)

            // Open unencrypted database with empty key
            // For plaintext databases, SQLCipher needs to be told explicitly
            unencryptedDb = SQLiteDatabase.openOrCreateDatabase(dbPath, "", null, null)

            // Set empty key for plaintext database
            unencryptedDb.rawExecSQL("PRAGMA key = '';")

            // Verify we can read the database
            unencryptedDb.rawExecSQL("SELECT count(*) FROM sqlite_master;")

            // Create hex string for the passphrase
            val passphraseHex = passphrase.toHexString()

            // Export to encrypted database using proper SQLCipher syntax
            // The x'...' blob literal must NOT be quoted with double quotes
            // Double quotes denote identifiers in SQL, not literals
            // The blob literal x'hexstring' should be used directly as the KEY value
            unencryptedDb.rawExecSQL("ATTACH DATABASE '$tempDbPath' AS encrypted KEY x'$passphraseHex';")
            unencryptedDb.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            unencryptedDb.rawExecSQL("DETACH DATABASE encrypted;")
            unencryptedDb.close()
            unencryptedDb = null

            // Replace original with encrypted version
            val originalFile = java.io.File(dbPath)
            val encryptedFile = java.io.File(tempDbPath)

            if (!encryptedFile.exists()) {
                throw IllegalStateException("Encrypted database file was not created")
            }

            originalFile.delete()
            if (!encryptedFile.renameTo(originalFile)) {
                throw IllegalStateException("Failed to rename encrypted database")
            }

            // Clean up any remaining WAL files
            deleteWalFiles(dbPath)

            android.util.Log.i("DatabaseEncryption", "Database encrypted successfully")
        } catch (e: Exception) {
            android.util.Log.e("DatabaseEncryption", "Failed to encrypt database", e)
            // Clean up temporary files
            java.io.File(tempDbPath).delete()
            java.io.File("$tempDbPath-wal").delete()
            java.io.File("$tempDbPath-shm").delete()
            throw e
        } finally {
            unencryptedDb?.close()
            passphrase.fill(0)
        }
    }

    /**
     * Migrate database from encrypted to unencrypted format
     * This is a destructive operation - backup recommended
     *
     * @param context Application context
     * @param dbName Database name
     * @param passphrase Passphrase for decryption
     */
    fun decryptDatabase(context: Context, dbName: String, passphrase: ByteArray) {
        val dbPath = context.getDatabasePath(dbName).absolutePath
        val tempDbPath = "$dbPath.decrypted"

        var encryptedDb: SQLiteDatabase? = null
        try {
            // Clean up WAL and SHM files from Room's WAL mode
            deleteWalFiles(dbPath)

            // Open encrypted database using passphrase
            val passphraseHex = passphrase.toHexString()
            encryptedDb = SQLiteDatabase.openOrCreateDatabase(dbPath, passphraseHex, null, null)

            // Verify we can read the encrypted database
            encryptedDb.rawExecSQL("SELECT count(*) FROM sqlite_master;")

            // Export to unencrypted database
            encryptedDb.rawExecSQL("ATTACH DATABASE '$tempDbPath' AS plaintext KEY '';")
            encryptedDb.rawExecSQL("SELECT sqlcipher_export('plaintext');")
            encryptedDb.rawExecSQL("DETACH DATABASE plaintext;")
            encryptedDb.close()
            encryptedDb = null

            // Replace original with unencrypted version
            val originalFile = java.io.File(dbPath)
            val decryptedFile = java.io.File(tempDbPath)

            if (!decryptedFile.exists()) {
                throw IllegalStateException("Decrypted database file was not created")
            }

            originalFile.delete()
            if (!decryptedFile.renameTo(originalFile)) {
                throw IllegalStateException("Failed to rename decrypted database")
            }

            // Clean up any remaining WAL files
            deleteWalFiles(dbPath)

            android.util.Log.i("DatabaseEncryption", "Database decrypted successfully")
        } catch (e: Exception) {
            android.util.Log.e("DatabaseEncryption", "Failed to decrypt database", e)
            // Clean up temporary files
            java.io.File(tempDbPath).delete()
            java.io.File("$tempDbPath-wal").delete()
            java.io.File("$tempDbPath-shm").delete()
            throw e
        } finally {
            encryptedDb?.close()
            passphrase.fill(0)
        }
    }

    /**
     * Re-key the database with a new password
     * This is required when the user changes their app password
     *
     * @param context Application context
     * @param dbName Database name
     * @param oldPassword Old user password
     * @param newPassword New user password
     */
    fun rekeyDatabase(context: Context, dbName: String, oldPassword: String, newPassword: String) {
        val dbPath = context.getDatabasePath(dbName).absolutePath

        var db: SQLiteDatabase? = null
        try {
            // Clean up WAL and SHM files from Room's WAL mode
            deleteWalFiles(dbPath)

            // Get old passphrase
            val oldPassphrase = getDatabasePassphrase(context, oldPassword)
                ?: throw IllegalStateException("No encryption salt found")

            // Open database with old passphrase
            val oldPassphraseHex = oldPassphrase.toHexString()
            db = SQLiteDatabase.openOrCreateDatabase(dbPath, oldPassphraseHex, null, null)

            // Verify we can read the database
            db.rawExecSQL("SELECT count(*) FROM sqlite_master;")

            // Generate new salt and derive new passphrase
            val newSalt = PasswordHashUtil.generateSalt()
            val newPassphrase = PasswordHashUtil.deriveKey(newPassword, newSalt)
            val newPassphraseHex = newPassphrase.toHexString()

            // Re-key the database using SQLCipher PRAGMA
            db.rawExecSQL("PRAGMA rekey = x'$newPassphraseHex';")

            // Update stored salt
            val newSaltHex = newSalt.toHexString()
            getEncryptedPreferences(context)
                .edit()
                .putString(KEY_DB_SALT, newSaltHex)
                .apply()

            // Clean up sensitive data
            oldPassphrase.fill(0)
            newPassphrase.fill(0)

            android.util.Log.i("DatabaseEncryption", "Database re-keyed successfully")
        } catch (e: Exception) {
            android.util.Log.e("DatabaseEncryption", "Failed to re-key database", e)
            throw e
        } finally {
            db?.close()
        }
    }

    /**
     * Delete WAL and SHM files associated with a database
     * Room uses WAL mode by default, which creates these files
     */
    private fun deleteWalFiles(dbPath: String) {
        try {
            java.io.File("$dbPath-wal").delete()
            java.io.File("$dbPath-shm").delete()
            java.io.File("$dbPath-journal").delete()
        } catch (e: Exception) {
            android.util.Log.w("DatabaseEncryption", "Failed to delete WAL files", e)
        }
    }
}
