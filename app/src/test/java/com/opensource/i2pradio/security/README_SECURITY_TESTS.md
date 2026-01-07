# Password and Cryptographic Security Tests

This directory contains comprehensive security tests for password hashing, encryption, authentication utilities, and anti-forensic patterns in deutsia radio. These testsa are designed to ensure the app's security features are implemented correctly. Please note that this app alone cannot protect against foresic level extraction. Full security requires hardware-level support; eg. a Pixel with GrapheneOS. 

**Total: 200+ security tests**


## Test Files

### PasswordHashUtilTest.kt (30+ tests)
Tests for PBKDF2-HMAC-SHA256 password hashing.

**Test Coverage:**

#### 1. Salt Generation
- Salt produces 32-byte (256-bit) output
- Salt uniqueness across multiple generations
- Salt has sufficient entropy (diverse byte values)

#### 2. Password Hashing
- Valid hash format (salt$hash Base64-encoded)
- Same password produces different hashes (unique salts)
- Different passwords produce different hashes
- Correct output lengths (32-byte salt + 32-byte hash)

#### 3. Password Verification
- Correct password verification succeeds
- Incorrect password verification fails
- Similar password variations fail (case, length)
- Empty password handling
- Malformed hash graceful handling
- Invalid Base64 graceful handling

#### 4. Key Derivation
- Produces 32-byte key suitable for AES-256
- Deterministic with same salt
- Different keys with different salts
- Different keys with different passwords
- Invalid salt length rejection

#### 5. Edge Cases
- Special characters (unicode, emoji, control chars)
- Very long passwords (10KB+)
- Single character passwords
- Null bytes in passwords

#### 6. Timing Attack Resistance
- Constant-time comparison verification
- Timing doesn't leak password length

---

### PasswordEncryptionUtilTest.kt (25+ tests)
Tests for AES-256-GCM field-level encryption.

**Test Coverage:**

#### 1. AES-256-GCM Encryption/Decryption
- Valid ciphertext format (IV + encrypted + tag)
- Correct plaintext recovery
- Unique ciphertexts for same plaintext (unique IVs)

#### 2. IV (Initialization Vector)
- Exactly 12 bytes for GCM
- Unique IVs prevent ciphertext reuse attacks

#### 3. AAD (Additional Authenticated Data)
- Wrong AAD causes decryption failure
- Missing AAD causes decryption failure
- Prevents ciphertext swapping between contexts

#### 4. GCM Authentication Tag
- Tampered ciphertext is detected
- Truncated ciphertext is detected
- Wrong key is detected

#### 5. Edge Cases
- Special character handling
- Very long password encryption
- Key entropy verification

#### 6. Base64 Format
- Valid Base64 output (NO_WRAP)
- Invalid Base64 rejection

---

### DigestAuthenticatorTest.kt (25+ tests)
Tests for HTTP Digest Authentication (RFC 2617).

**Test Coverage:**

#### 1. Challenge Parsing
- Non-Digest challenges rejected
- Missing header handling
- Missing realm/nonce handling
- Valid challenge parsing
- Whitespace tolerance
- Unquoted value handling

#### 2. Response Hash Calculation
- MD5 response correctness
- qop=auth mode includes cnonce and nc
- Legacy mode without qop

#### 3. Authorization Header Format
- Required fields presence
- Response hash format (32 hex chars)

#### 4. Algorithm Support
- Default MD5 algorithm
- SHA-256 algorithm
- MD5-sess algorithm

#### 5. Security Edge Cases
- Special characters in username/password
- Special characters in realm
- URI encoding
- Nonce count handling
- Client nonce uniqueness

---

### CryptographicSecurityTest.kt (35+ tests)
Core cryptographic security validation.

**Test Coverage:**

#### 1. PBKDF2 Key Derivation Security
- Iteration count meets OWASP 2023 (600,000+)
- Consistent output
- Salt affects output
- Correct output length
- Computationally expensive

#### 2. AES-256 Key Security
- Correct key size (32 bytes)
- Cryptographic randomness
- Key entropy

#### 3. SecureRandom
- Unpredictable output
- Salt generation uniqueness
- IV generation uniqueness

#### 4. Constant-Time Comparison
- MessageDigest.isEqual() behavior
- Different length rejection
- Single bit difference detection

#### 5. Memory Safety Patterns
- PBEKeySpec.clearPassword()
- CharArray zeroing
- ByteArray zeroing

#### 6. Algorithm Correctness
- PBKDF2WithHmacSHA256 availability
- AES/GCM/NoPadding availability
- GCM tag length
- MD5/SHA-256 output lengths

#### 7. Edge Cases
- Empty password handling
- Null byte in password
- Maximum length password
- Salt unpredictability

#### 8. Cryptographic Binding
- GCM AAD context binding
- Key derivation determinism
- Derived keys suitable for AES-256

---

### InputValidationSecurityTest.kt (30+ tests)
Input validation and attack prevention.

**Test Coverage:**

#### 1. Malformed Base64 Input
- Invalid character rejection
- Truncated input handling
- Padding variations

#### 2. Password Boundary Tests
- Zero-length password
- Single byte password
- Block boundary alignment (16-byte AES blocks)

#### 3. Unicode and Encoding
- Multi-byte UTF-8 characters
- Control character handling

#### 4. Injection Prevention
- Format string characters preserved
- SQL injection patterns preserved (not processed)

#### 5. IV/Nonce Validation
- Zero IV detection
- Short IV handling

#### 6. Error Message Security
- AEADBadTagException doesn't leak plaintext
- IllegalArgumentException doesn't leak sensitive info

#### 7. Resource Exhaustion Prevention
- Large input handling (1MB+)
- Concurrent operation thread safety

---

### MemorySafetyTest.kt (30+ tests)
Memory safety and anti-forensic protection.

**Test Coverage:**

#### 1. Password Memory Wiping
- CharArray password wiping
- ByteArray key wiping
- PBEKeySpec clearPassword verification
- Multi-pass secure wiping

#### 2. String Pool Avoidance
- CharArray not interned (unlike String)
- Avoid String creation from passwords
- Independent CharArray copies

#### 3. Exception Safety
- Decryption failures don't leak plaintext
- Base64 errors don't leak context
- Key derivation errors don't expose passwords

#### 4. Object Reference Safety
- Sensitive objects can be garbage collected
- No strong references after use
- WeakReference patterns

#### 5. Secure Comparison
- Timing-safe comparison for all byte positions
- No position leak in comparison results

#### 6. Defensive Copies
- Safe array copying patterns
- Input defensive copies
- Output defensive copies

---

### SecureObjectLifecycleTest.kt (35+ tests)
Secure object lifecycle and serialization safety.

**Test Coverage:**

#### 1. Secure Credential Container
- toString() doesn't expose password
- Use-then-clear pattern
- AutoCloseable/try-with-resources

#### 2. toString() Safety
- ByteArray toString is safe
- CharArray toString is safe
- SecretKeySpec toString is safe

#### 3. Serialization Safety
- Demonstrate naive serialization vulnerabilities
- Transient field pattern for passwords
- Encryption key serialization warnings

#### 4. hashCode() Safety
- Password hashCode doesn't expose content
- Identity-based hashCode pattern

#### 5. equals() Safety
- Constant-time comparison in equals
- No short-circuit timing leaks

#### 6. Clone Safety
- Array clone creates independent copy
- Deep clone pattern

#### 7. Thread Safety
- Concurrent access to credentials
- Volatile and synchronized patterns

---

### DataResidueSecurityTest.kt (35+ tests)
Data residue and forensic resistance.

**Test Coverage:**

#### 1. ByteBuffer/CharBuffer Residue
- ByteBuffer.clear() doesn't wipe (demonstrates problem)
- Secure ByteBuffer wiping pattern
- CharBuffer secure wipe

#### 2. StringBuilder/StringBuffer Residue
- StringBuilder leaves residue (demonstrates problem)
- Secure StringBuilder clearing pattern
- Prefer CharArray over StringBuilder

#### 3. Collection Residue
- ArrayList clear leaves items in memory
- Secure collection clearing pattern
- HashMap value residue

#### 4. Encoding/Decoding Residue
- Base64 copy management
- String to ByteArray copy tracking
- CharArray to String interning warning

#### 5. Crypto Operation Residue
- Cipher intermediate copies
- PBEKeySpec cleanup pattern

#### 6. String Concatenation Residue
- String + creates multiple objects
- Secure CharArray concatenation

#### 7. Array Resizing Residue
- copyOf leaves original
- ArrayList grow residue

#### 8. Secure Data Structure Patterns
- Fixed-size secure buffer
- Secure password entry pattern
- Constant-time zero check

---

## Running the Tests

### Run all security tests:
```bash
./gradlew test --tests "com.opensource.i2pradio.security.*"
```

### Run specific test class:
```bash
./gradlew test --tests "com.opensource.i2pradio.security.PasswordHashUtilTest"
./gradlew test --tests "com.opensource.i2pradio.security.PasswordEncryptionUtilTest"
./gradlew test --tests "com.opensource.i2pradio.security.DigestAuthenticatorTest"
./gradlew test --tests "com.opensource.i2pradio.security.CryptographicSecurityTest"
./gradlew test --tests "com.opensource.i2pradio.security.InputValidationSecurityTest"
./gradlew test --tests "com.opensource.i2pradio.security.MemorySafetyTest"
./gradlew test --tests "com.opensource.i2pradio.security.SecureObjectLifecycleTest"
./gradlew test --tests "com.opensource.i2pradio.security.DataResidueSecurityTest"
```

### Run a specific test:
```bash
./gradlew test --tests "com.opensource.i2pradio.security.MemorySafetyTest.test CharArray password can be wiped"
```

---

## Test Dependencies

- **JUnit 4.13.2** - Testing framework
- **MockK 1.13.8** - Mocking library for Kotlin
- **OkHttp 4.12.0** - For DigestAuthenticator tests

---

## Security Standards Verified

| Standard | Implementation | Tests |
|----------|---------------|-------|
| OWASP 2023 Password Hashing | PBKDF2-HMAC-SHA256, 600k iterations | PasswordHashUtilTest |
| AES-256-GCM (AEAD) | 256-bit key, 128-bit tag, 96-bit IV | PasswordEncryptionUtilTest |
| Constant-Time Comparison | MessageDigest.isEqual() | CryptographicSecurityTest |
| Memory Safety | CharArray/ByteArray zeroing | MemorySafetyTest |
| Anti-Forensic Patterns | Buffer wiping, no residue | DataResidueSecurityTest |
| Serialization Safety | Transient fields, no secrets | SecureObjectLifecycleTest |
| RFC 2617 | HTTP Digest Authentication | DigestAuthenticatorTest |

---

## Anti-Forensic Protection

These tests verify protection against forensic extraction tools:

### Memory Analysis Protection
- **Cellebrite UFED** - Tests verify passwords/keys are wiped after use
- **Oxygen Forensics** - Tests verify no residue in buffers/collections
- **Magnet AXIOM** - Tests verify serialized objects don't contain secrets

### Key Vulnerabilities Tested
1. **String interning** - Passwords as CharArray, not String
2. **Buffer residue** - ByteBuffer/CharBuffer secure wiping
3. **Collection residue** - Items wiped before collection.clear()
4. **StringBuilder residue** - Overwrite before setLength(0)
5. **Serialization leaks** - Transient fields for secrets
6. **Exception messages** - No secrets in error text
7. **toString()/hashCode()** - Identity-based, not content-based

---

## Critical Security Tests

Any failures in these tests should be treated as **critical security issues**:

1. **Constant-Time Comparison** - Prevents timing attacks
2. **PBKDF2 Iterations** - Prevents brute-force attacks
3. **GCM Tag Verification** - Detects tampering
4. **AAD Binding** - Prevents ciphertext swapping
5. **IV Uniqueness** - Prevents key stream reuse
6. **Memory Wiping** - Prevents forensic extraction
7. **No Serialization Leaks** - Prevents data recovery

---

## Continuous Integration

These tests should be run:
- On every commit touching security utilities
- Before releases
- After cryptographic library updates
- When modifying authentication code
- After Android SDK updates (security behavior may change)

---

## Limitations

Again, these tests verify defensive patterns at the application level. Application-level protections reduce the attack surface but cannot guarantee protection against physical device access with forensic tools. If you need this, use GrapheneOS on a Google Pixel. 
