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
 * - Enable/disable database encryption
 * - Generate and store secure database passphrases
 * - Migrate between encrypted and unencrypted databases
 * - Use hardware-backed keystore when available
 */
object DatabaseEncryptionManager {
    private const val ENCRYPTED_PREFS_NAME = "db_encryption_prefs"
    private const val KEY_DB_ENCRYPTION_ENABLED = "db_encryption_enabled"
    private const val KEY_DB_PASSPHRASE = "db_passphrase"
    private const val PASSPHRASE_LENGTH = 32 // 256 bits

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
     */
    private fun getEncryptedPreferences(context: Context): android.content.SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                getMasterKey(context),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            throw RuntimeException("Failed to create EncryptedSharedPreferences", e)
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
     * Enable database encryption
     * Generates a new secure passphrase and stores it in EncryptedSharedPreferences
     *
     * @return The generated passphrase (do NOT store this anywhere else)
     */
    fun enableDatabaseEncryption(context: Context): ByteArray {
        // Generate a cryptographically secure random passphrase
        val passphrase = ByteArray(PASSPHRASE_LENGTH)
        SecureRandom().nextBytes(passphrase)

        // Store passphrase securely in EncryptedSharedPreferences
        val passphraseHex = passphrase.toHexString()
        getEncryptedPreferences(context)
            .edit()
            .putString(KEY_DB_PASSPHRASE, passphraseHex)
            .putBoolean(KEY_DB_ENCRYPTION_ENABLED, true)
            .apply()

        return passphrase
    }

    /**
     * Disable database encryption
     * WARNING: This should trigger database migration to unencrypted format
     */
    fun disableDatabaseEncryption(context: Context) {
        getEncryptedPreferences(context)
            .edit()
            .remove(KEY_DB_PASSPHRASE)
            .putBoolean(KEY_DB_ENCRYPTION_ENABLED, false)
            .apply()
    }

    /**
     * Get the database passphrase for opening encrypted database
     *
     * @return The passphrase as ByteArray, or null if encryption is not enabled
     */
    fun getDatabasePassphrase(context: Context): ByteArray? {
        if (!isDatabaseEncryptionEnabled(context)) {
            return null
        }

        val passphraseHex = getEncryptedPreferences(context)
            .getString(KEY_DB_PASSPHRASE, null) ?: return null

        return passphraseHex.hexToByteArray()
    }

    /**
     * Get SupportFactory for Room database with optional encryption
     *
     * @return SupportFactory for encrypted database, or null for unencrypted
     */
    fun getSupportFactory(context: Context): SupportFactory? {
        val passphrase = getDatabasePassphrase(context) ?: return null

        return try {
            SupportFactory(passphrase)
        } finally {
            // Clear passphrase from memory
            passphrase.fill(0)
        }
    }

    /**
     * Initialize SQLCipher library
     * Should be called once on app startup
     */
    fun initializeSQLCipher(context: Context) {
        SQLiteDatabase.loadLibs(context)
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

        try {
            // Open unencrypted database
            val unencryptedDb = SQLiteDatabase.openDatabase(
                dbPath,
                ByteArray(0),
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null
            )

            // Export to encrypted database
            unencryptedDb.rawExecSQL("ATTACH DATABASE '$tempDbPath' AS encrypted KEY '${passphrase.toHexString()}'")
            unencryptedDb.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            unencryptedDb.rawExecSQL("DETACH DATABASE encrypted")
            unencryptedDb.close()

            // Replace original with encrypted version
            val originalFile = java.io.File(dbPath)
            val encryptedFile = java.io.File(tempDbPath)

            originalFile.delete()
            encryptedFile.renameTo(originalFile)

            android.util.Log.i("DatabaseEncryption", "Database encrypted successfully")
        } catch (e: Exception) {
            android.util.Log.e("DatabaseEncryption", "Failed to encrypt database", e)
            // Clean up temporary file
            java.io.File(tempDbPath).delete()
            throw e
        } finally {
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

        try {
            // Open encrypted database
            val encryptedDb = SQLiteDatabase.openDatabase(
                dbPath,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null
            )

            // Export to unencrypted database
            encryptedDb.rawExecSQL("ATTACH DATABASE '$tempDbPath' AS plaintext KEY ''")
            encryptedDb.rawExecSQL("SELECT sqlcipher_export('plaintext')")
            encryptedDb.rawExecSQL("DETACH DATABASE plaintext")
            encryptedDb.close()

            // Replace original with unencrypted version
            val originalFile = java.io.File(dbPath)
            val decryptedFile = java.io.File(tempDbPath)

            originalFile.delete()
            decryptedFile.renameTo(originalFile)

            android.util.Log.i("DatabaseEncryption", "Database decrypted successfully")
        } catch (e: Exception) {
            android.util.Log.e("DatabaseEncryption", "Failed to decrypt database", e)
            // Clean up temporary file
            java.io.File(tempDbPath).delete()
            throw e
        } finally {
            passphrase.fill(0)
        }
    }
}
