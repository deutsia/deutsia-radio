# DNS Fallback and Leak Prevention Tests

This directory contains comprehensive tests for the RadioBrowserServerManager DNS fallback system and leak prevention mechanisms.

## Test Files

### RadioBrowserServerManagerDnsLeakTest.kt
Primary test suite covering DNS leak prevention and fallback mechanisms.

**Test Coverage:**

#### 1. DNS Fallback Mechanisms (3-Tier System)
- ✅ Normal mode DNS discovery flow
- ✅ Hardcoded fallback when all discovery methods fail
- ✅ Server cycling and rotation
- ✅ Server list retrieval

#### 2. DNS Leak Prevention - Force Tor Mode
- ✅ Blocks clearnet DNS when Tor is not connected
- ✅ Uses Tor proxy when connected
- ✅ Blocks when Tor port is invalid
- ✅ Force Tor All mode prevents clearnet DNS
- ✅ Force Tor Except I2P mode prevents clearnet DNS

#### 3. DNS Leak Prevention - Force Custom Proxy Mode
- ✅ Blocks when proxy not configured (empty host)
- ✅ Blocks when proxy not configured (invalid port)
- ✅ Blocks when host empty but port valid
- ✅ Blocks when port invalid but host valid
- ✅ Uses SOCKS proxy when properly configured
- ✅ Uses HTTP proxy when properly configured
- ✅ Force Custom Proxy Except Tor/I2P mode prevents clearnet DNS

#### 4. Proxy Authentication
- ✅ Custom proxy with authentication credentials

#### 5. SOCKS5_DNS Resolver
- ✅ Returns placeholder IP address (0.0.0.0)
- ✅ Preserves hostname for proxy-side resolution
- ✅ Prevents local DNS resolution before SOCKS connection

#### 6. Server Caching
- ✅ Server list caching and reuse
- ✅ Force refresh updates server list
- ✅ Reset clears cached servers
- ✅ Cache expiration

#### 7. Proxy Protocol Handling
- ✅ SOCKS4 protocol recognition
- ✅ SOCKS5 protocol recognition
- ✅ Generic SOCKS protocol recognition
- ✅ HTTP proxy protocol

#### 8. Combined Proxy Modes
- ✅ Force Tor takes priority over Force Custom Proxy
- ✅ Normal mode allows DNS discovery

#### 9. API Base URL
- ✅ Correct URL format generation
- ✅ Respects proxy settings

### RadioBrowserServerManagerApiTest.kt
Integration-style tests for API behavior and edge cases.

**Test Coverage:**

#### 1. API Response Handling
- ✅ Valid server list parsing
- ✅ Empty server list fallback

#### 2. Leak Prevention Verification
- ✅ Force Tor mode never makes clearnet API request
- ✅ Force Custom Proxy mode never makes clearnet API request

#### 3. Server Discovery Flow
- ✅ Consistent results from cache
- ✅ Force refresh triggers new discovery

#### 4. Proxy Configuration Validation
- ✅ Tor proxy settings validated before use
- ✅ Custom SOCKS proxy settings validated
- ✅ Proxy authentication only used when credentials provided

#### 5. Edge Cases
- ✅ Null context handling in normal mode
- ✅ Null context in getApiBaseUrl
- ✅ Null context in getAllServers
- ✅ Server cycling wrap-around
- ✅ Reset clears all state

#### 6. Multi-Mode Proxy Tests
- ✅ Force Tor All mode blocks clearnet
- ✅ Force Tor Except I2P mode blocks clearnet
- ✅ Force Custom Proxy mode blocks clearnet
- ✅ Force Custom Proxy Except Tor/I2P mode blocks clearnet

#### 7. Proxy Protocol Case Sensitivity
- ✅ Case-insensitive SOCKS protocol detection
- ✅ Unknown protocol defaults to HTTP

## Running the Tests

### Run all DNS leak tests:
```bash
./gradlew test --tests "com.opensource.i2pradio.data.radiobrowser.RadioBrowserServerManagerDnsLeakTest"
```

### Run all API tests:
```bash
./gradlew test --tests "com.opensource.i2pradio.data.radiobrowser.RadioBrowserServerManagerApiTest"
```

### Run all RadioBrowserServerManager tests:
```bash
./gradlew test --tests "com.opensource.i2pradio.data.radiobrowser.*"
```

### Run a specific test:
```bash
./gradlew test --tests "com.opensource.i2pradio.data.radiobrowser.RadioBrowserServerManagerDnsLeakTest.test force Tor mode blocks clearnet DNS when Tor is not connected"
```

## Test Dependencies

The following test dependencies are required (already added to `app/build.gradle.kts`):

- **JUnit 4.13.2** - Testing framework
- **MockK 1.13.8** - Mocking library for Kotlin
- **Kotlinx Coroutines Test 1.7.3** - Coroutines testing utilities
- **OkHttp MockWebServer 4.12.0** - HTTP mock server for integration tests

## Key Test Scenarios

### Critical Security Tests

1. **DNS Leak Prevention in Force Tor Mode**
   - When Force Tor is enabled but Tor is not connected, the system MUST NOT make any clearnet DNS requests
   - Test: `test force Tor mode blocks clearnet DNS when Tor is not connected`

2. **DNS Leak Prevention in Force Custom Proxy Mode**
   - When Force Custom Proxy is enabled but proxy is not configured, the system MUST NOT make any clearnet requests
   - Test: `test force custom proxy mode blocks when proxy not configured`

3. **SOCKS5_DNS Resolver Behavior**
   - The SOCKS5_DNS resolver MUST return a placeholder IP to force DNS resolution through the SOCKS proxy
   - Test: `test SOCKS5_DNS resolver returns placeholder IP`

4. **Proxy Unavailability Handling**
   - When force proxy mode is enabled but the proxy is unavailable, the system MUST use hardcoded fallbacks without making network requests
   - Tests: Multiple tests verify this across different proxy configurations

### Fallback Mechanism Tests

1. **3-Tier Fallback System**
   - Tier 1: DNS lookup of all.api.radio-browser.info
   - Tier 2: HTTP API endpoint fallback
   - Tier 3: Hardcoded servers fallback

2. **Server Caching**
   - Server lists are cached for 30 minutes
   - Cache can be force-refreshed
   - Servers are shuffled for load distribution

## Test Mocking Strategy

### MockK Usage
- **TorManager**: Mocked to simulate Tor connection states and proxy configuration
- **PreferencesHelper**: Mocked to simulate various proxy mode configurations
- **Context**: Mocked as Android Context for preference access

### Test Isolation
- Each test includes `@Before` setup that resets the RadioBrowserServerManager
- All mocks are cleared in `@After` to prevent test interference

## Continuous Integration

These tests should be run:
- On every commit to verify DNS leak prevention
- Before releases to ensure security features work correctly
- When modifying proxy or server discovery code

## Security Implications

These tests verify critical privacy and security features:
- **Zero DNS leaks** when force proxy modes are enabled
- **Fail-secure behavior** when proxy is unavailable
- **Proper proxy routing** for all network requests
- **SOCKS5 DNS delegation** to prevent local DNS resolution

Any test failures in this suite should be treated as **critical security issues** and investigated immediately.

## Future Enhancements

Potential areas for additional test coverage:
- Network timeout handling
- Concurrent server requests
- Cache expiration timing
- DNS resolution performance
- Proxy authentication failures
- IPv6 address handling
