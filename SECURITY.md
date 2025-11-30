# Security Implementation Documentation

## Overview

This document describes the comprehensive security measures implemented to protect sensitive credentials (proxy passwords) in Deutsia Radio.

## Critical Security Fixes Implemented

### 🔐 1. Database Encryption with SQLCipher

**Technology:** SQLCipher 4.5.4 (AES-256-CBC encryption)

**Implementation:**
- Database encryption key generated using SecureRandom (256-bit)
- Key stored in EncryptedSharedPreferences (never in plaintext)
- All database operations automatically encrypted/decrypted
- No plaintext data ever written to disk

**Files Modified:**
- `app/build.gradle.kts` - Added SQLCipher dependency
- `app/src/main/java/com/opensource/i2pradio/data/RadioDatabase.kt` - Integrated SQLCipher

**Verification:**
```bash
# Database file should be binary/encrypted, not readable as text
file app/databases/radio_database
# Should output: "data" (binary) not "SQLite format 3" (plaintext)
```

---

### 🔒 2. EncryptedSharedPreferences for Passwords

**Technology:** AndroidX Security Crypto 1.1.0-alpha06

**Encryption Scheme:**
- Keys: AES256-SIV (Synthetic Initialization Vector)
- Values: AES256-GCM (Galois/Counter Mode with authentication)
- Master Key: Android Keystore System (hardware-backed when available)

**Implementation:**
- `SecurePreferencesManager.kt` - Wrapper for encrypted storage
- All passwords stored encrypted at rest
- Automatic migration from plaintext to encrypted storage

**Protected Data:**
- Proxy passwords (`custom_proxy_password`)
- Proxy usernames (`custom_proxy_username`)
- Database encryption keys

**Files:**
- `app/src/main/java/com/opensource/i2pradio/security/SecurePreferencesManager.kt`
- `app/src/main/java/com/opensource/i2pradio/ui/PreferenceHelper.kt` (updated)

---

### 🚫 3. Backup Exclusion Rules

**Technology:** Android Auto Backup with exclusion rules

**Implementation:**
- `backup_rules.xml` explicitly excludes sensitive data from backups
- Prevents password exposure via ADB backup or cloud backup
- Encrypted database excluded from backups

**Protected:**
- `deutsia_radio_secure_prefs.xml` - Encrypted credentials
- `radio_database*` - Encrypted database files
- `DeutsiaRadioPrefs.xml` - Legacy preferences (may contain sensitive data)

**Files:**
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/AndroidManifest.xml` (updated with backup rules reference)

---

### 🔍 4. Security Audit Logger

**Purpose:** Debug and verify encryption is working correctly

**Features:**
- Logs all encryption/decryption operations (without revealing passwords)
- Tracks password storage/retrieval events
- Verifies encrypted format
- No password values ever logged

**Usage:**
```kotlin
SecurityAuditLogger.logPasswordUsage("Context", passwordLength, isEncrypted = true)
SecurityAuditLogger.logSecureStorage(key, "[REDACTED]", true)
```

**Files:**
- `app/src/main/java/com/opensource/i2pradio/security/SecurityAuditLogger.kt`

---

### 🛡️ 5. Runtime Security Checker

**Purpose:** Comprehensive security validation on startup

**Checks Performed:**
1. **Database Encryption** - Verifies SQLCipher is active
2. **SharedPreferences Encryption** - Confirms encrypted format
3. **Plaintext Password Detection** - Scans for unencrypted passwords
4. **Root Detection** - Warns if device is rooted
5. **Debug Mode Detection** - Flags debug builds
6. **Backup Exposure** - Verifies backup exclusions

**Usage:**
Automatically runs on app startup. View results in logcat:

```bash
adb logcat | grep "SecurityChecker"
```

**Files:**
- `app/src/main/java/com/opensource/i2pradio/security/RuntimeSecurityChecker.kt`

---

### 🔄 6. Automatic Security Migration

**Purpose:** Migrate existing plaintext passwords to encrypted storage

**Process:**
1. Detect first run after security update
2. Read plaintext passwords from legacy storage
3. Write to encrypted storage
4. Verify migration succeeded
5. Delete plaintext data
6. Mark migration complete

**Safety:**
- Safe to run multiple times (idempotent)
- Verification before deletion
- Runs automatically on app startup

**Files:**
- `app/src/main/java/com/opensource/i2pradio/security/SecurityMigration.kt`

---

### 🧹 7. Password Obfuscation Utility

**Purpose:** Additional defense-in-depth for password handling

**Features:**
- Base64 encoding with random salt
- Memory wiping utilities
- Secure CharArray handling
- Prevents password caching in String pool

**Note:** This is obfuscation, NOT encryption. True encryption is handled by EncryptedSharedPreferences.

**Files:**
- `app/src/main/java/com/opensource/i2pradio/security/PasswordObfuscator.kt`

---

## Security Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Application Layer                     │
├─────────────────────────────────────────────────────────┤
│  PreferenceHelper (Password Storage/Retrieval)          │
│         ↓                                                │
│  SecurePreferencesManager                               │
│    ├─ EncryptedSharedPreferences (AES256-GCM)          │
│    └─ Android Keystore (Master Key)                     │
├─────────────────────────────────────────────────────────┤
│  RadioDatabase (Station Storage)                        │
│         ↓                                                │
│  SQLCipher (AES-256-CBC)                                │
│    └─ Key stored in EncryptedSharedPreferences          │
├─────────────────────────────────────────────────────────┤
│  Security Audit Layer                                   │
│    ├─ SecurityAuditLogger (Debug/Verification)          │
│    ├─ RuntimeSecurityChecker (Validation)               │
│    └─ SecurityMigration (Automatic Migration)           │
└─────────────────────────────────────────────────────────┘
```

---

## Threat Model

### Threats Mitigated:

✅ **Local Storage Extraction**
- Database files are encrypted with SQLCipher
- SharedPreferences encrypted with AES256-GCM
- No plaintext passwords on disk

✅ **ADB Backup Extraction**
- Sensitive files excluded from backups
- Encrypted data excluded via backup rules

✅ **Memory Dumps**
- Passwords not stored in String pool (where possible)
- Utilities provided for secure memory wiping

✅ **Root Access**
- Android Keystore provides hardware-backed encryption (when available)
- Detection warnings for rooted devices

✅ **Inter-Process Communication**
- Passwords transmitted via Intents (internal only)
- Service not exported (no external access)

### Residual Risks:

⚠️ **Rooted Devices**
- Hardware-backed encryption may be bypassed
- Root can access Android Keystore
- **Mitigation:** Root detection warnings

⚠️ **Memory Forensics**
- Kotlin Strings are immutable (cannot be truly wiped)
- **Mitigation:** CharArray utilities provided for sensitive operations

⚠️ **Physical Device Access**
- Encryption only protects at-rest data
- Device screen lock is user's responsibility
- **Mitigation:** None - OS-level security required

---

## Verification & Testing

### 1. Verify Database Encryption

```bash
# Connect to device
adb shell

# Navigate to app data
cd /data/data/com.opensource.i2pradio/databases/

# Try to read database
cat radio_database
# Should output binary gibberish, not readable SQL

# Try to open with sqlite3 (should fail)
sqlite3 radio_database ".schema"
# Should error: "file is not a database"
```

### 2. Verify SharedPreferences Encryption

```bash
# View encrypted preferences
adb shell cat /data/data/com.opensource.i2pradio/shared_prefs/deutsia_radio_secure_prefs.xml

# Should contain base64-encoded values, not plaintext passwords
# Example:
# <string name="__androidx_security_crypto_encrypted_prefs_key_keyset__">AY4nF...</string>
# <string name="__androidx_security_crypto_encrypted_prefs_value_keyset__">ASj...</string>
```

### 3. Verify Backup Exclusion

```bash
# Create backup
adb backup -f backup.ab com.opensource.i2pradio

# Convert to tar
dd if=backup.ab bs=24 skip=1 | openssl zlib -d > backup.tar

# Extract and search for sensitive files
tar -xvf backup.tar
# Should NOT contain radio_database or deutsia_radio_secure_prefs.xml
```

### 4. Monitor Security Logs

```bash
# View security audit logs
adb logcat | grep "SecurityAudit"

# View security check results
adb logcat | grep "SecurityChecker"

# View app initialization
adb logcat | grep "I2PRadioApp"
```

---

## Debugging

### Enable Security Audit Logging

Security audit logging is enabled by default in debug builds.

To manually control:

```kotlin
// In your Application onCreate()
SecurityAuditLogger.setEnabled(true)  // Enable
SecurityAuditLogger.setEnabled(false) // Disable for production
```

### View Security Report on Startup

Check logcat after app launch:

```bash
adb logcat -s I2PRadioApp:* SecurityChecker:* SecurityAudit:*
```

You should see:
```
═══════════════════════════════════════════
    SECURITY SUBSYSTEM INITIALIZATION
═══════════════════════════════════════════
✓ SecurePreferences initialized
✓ Security migration completed
╔═══════════════════════════════════════════╗
║     SECURITY AUDIT REPORT                 ║
╠═══════════════════════════════════════════╣
║ 🟢 ✓ Database Encryption
║   ✓ Database is encrypted with SQLCipher
╠───────────────────────────────────────────╣
...
```

---

## Best Practices for Developers

### ✅ DO:
- Use `PreferencesHelper.setCustomProxyPassword()` for password storage
- Check security audit logs during development
- Test with SecurityChecker before production releases
- Use CharArray for password handling when possible

### ❌ DON'T:
- Store passwords in regular SharedPreferences
- Log password values (even in debug builds)
- Store passwords in database without encryption
- Disable backup exclusion rules
- Skip security migration on updates

---

## Dependencies

```gradle
// Security dependencies
implementation("androidx.security:security-crypto:1.1.0-alpha06")
implementation("net.zetetic:android-database-sqlcipher:4.5.4")
implementation("androidx.sqlite:sqlite-ktx:2.4.0")
```

---

## Compliance

This implementation addresses:

- **CWE-256**: Plaintext Storage of a Password ✅ FIXED
- **CWE-311**: Missing Encryption of Sensitive Data ✅ FIXED
- **CWE-316**: Cleartext Storage in Memory ⚠️ MITIGATED
- **CWE-530**: Exposure of Backup File ✅ FIXED
- **CWE-257**: Storing Passwords in Recoverable Format ✅ FIXED

---

## Maintenance

### Adding New Sensitive Fields

1. Store in `SecurePreferencesManager`, not regular SharedPreferences
2. Add to backup exclusion rules if necessary
3. Update `SecurityMigration` if migrating from plaintext
4. Add security check in `RuntimeSecurityChecker` if needed

### Debugging Encryption Issues

1. Check `SecurityAuditLogger` output
2. Run `RuntimeSecurityChecker` manually
3. Verify Android Keystore is accessible
4. Check for rooted device warnings
5. Ensure app has storage permissions

---

## Support

For security issues, please report privately to the maintainers.

**DO NOT** create public issues for security vulnerabilities.

---

## Changelog

### Version 1.1 (Current)
- ✅ Implemented SQLCipher database encryption
- ✅ Implemented EncryptedSharedPreferences
- ✅ Added backup exclusion rules
- ✅ Added security audit logging
- ✅ Added runtime security checker
- ✅ Added automatic migration from plaintext
- ✅ Added password obfuscation utilities

### Version 1.0
- ❌ Passwords stored in plaintext (INSECURE)

---

## License

Same as parent project.
