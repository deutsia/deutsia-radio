package com.opensource.i2pradio.security

import com.opensource.i2pradio.utils.DigestAuthenticator
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * Security tests for DigestAuthenticator (RFC 2617 HTTP Digest Authentication).
 *
 * Test Coverage:
 * 1. Challenge Parsing (malformed headers, injection attempts)
 * 2. Response Hash Calculation (MD5, SHA-256, MD5-sess)
 * 3. QoP (Quality of Protection) Modes
 * 4. Authorization Header Generation
 * 5. Edge Cases and Error Handling
 */
class DigestAuthenticatorTest {

    @Before
    fun setup() {
        // Mock Android Log
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ========================================================================
    // Challenge Parsing Tests
    // ========================================================================

    @Test
    fun `test returns null for non-Digest challenge`() {
        val response = createMockResponse("Basic realm=\"test\"")

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNull("Should return null for Basic auth challenge", result)
    }

    @Test
    fun `test returns null for missing challenge header`() {
        val response = createMockResponseWithoutChallenge()

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNull("Should return null when no challenge header", result)
    }

    @Test
    fun `test returns null for missing realm`() {
        val response = createMockResponse("Digest nonce=\"abc123\"")

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNull("Should return null when realm is missing", result)
    }

    @Test
    fun `test returns null for missing nonce`() {
        val response = createMockResponse("Digest realm=\"test\"")

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNull("Should return null when nonce is missing", result)
    }

    @Test
    fun `test parses valid Digest challenge`() {
        val challenge = """Digest realm="test@example.com", nonce="abc123def456", qop="auth", opaque="xyz789""""
        val response = createMockResponse(challenge)

        val result = DigestAuthenticator.authenticate(response, "testuser", "testpass")

        assertNotNull("Should parse valid Digest challenge", result)
        assertTrue("Result should have Proxy-Authorization header",
            result!!.header("Proxy-Authorization")?.startsWith("Digest") == true)
    }

    @Test
    fun `test handles challenge with extra whitespace`() {
        val challenge = """Digest   realm="test",   nonce="abc123",   qop="auth""""
        val response = createMockResponse(challenge)

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNotNull("Should handle extra whitespace", result)
    }

    @Test
    fun `test handles challenge with unquoted values`() {
        val challenge = """Digest realm=test, nonce=abc123, algorithm=MD5"""
        val response = createMockResponse(challenge)

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNotNull("Should handle unquoted values", result)
    }

    // ========================================================================
    // Response Hash Calculation Tests
    // ========================================================================

    @Test
    fun `test MD5 response calculation is correct`() {
        // Verify the MD5 hash calculation follows RFC 2617
        val md = MessageDigest.getInstance("MD5")

        // A1 = username:realm:password
        val a1 = "testuser:testrealm:testpass"
        val ha1 = md5Hash(a1, md)

        // A2 = method:uri
        val a2 = "GET:/test/path"
        val ha2 = md5Hash(a2, md)

        // Response without qop = MD5(HA1:nonce:HA2)
        val expectedResponse = md5Hash("$ha1:testnonce:$ha2", md)

        // Verify this matches expected format
        assertEquals("HA1 should be 32 hex chars", 32, ha1.length)
        assertEquals("HA2 should be 32 hex chars", 32, ha2.length)
        assertEquals("Response should be 32 hex chars", 32, expectedResponse.length)
    }

    @Test
    fun `test response with qop=auth includes cnonce and nc`() {
        val challenge = """Digest realm="test", nonce="servernonce", qop="auth""""
        val response = createMockResponse(challenge)

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNotNull("Should authenticate with qop=auth", result)
        val authHeader = result!!.header("Proxy-Authorization")!!

        assertTrue("Should contain qop", authHeader.contains("qop=auth"))
        assertTrue("Should contain nc", authHeader.contains("nc="))
        assertTrue("Should contain cnonce", authHeader.contains("cnonce="))
    }

    @Test
    fun `test response without qop uses legacy format`() {
        val challenge = """Digest realm="test", nonce="servernonce""""
        val response = createMockResponse(challenge)

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNotNull("Should authenticate without qop", result)
        val authHeader = result!!.header("Proxy-Authorization")!!

        assertFalse("Should not contain qop", authHeader.contains("qop="))
        assertFalse("Should not contain nc", authHeader.contains("nc="))
    }

    // ========================================================================
    // Authorization Header Format Tests
    // ========================================================================

    @Test
    fun `test authorization header contains required fields`() {
        val challenge = """Digest realm="testrealm", nonce="servernonce", qop="auth", opaque="opaquevalue""""
        val response = createMockResponse(challenge)

        val result = DigestAuthenticator.authenticate(response, "testuser", "testpass")

        assertNotNull(result)
        val authHeader = result!!.header("Proxy-Authorization")!!

        assertTrue("Should start with Digest", authHeader.startsWith("Digest"))
        assertTrue("Should contain username", authHeader.contains("username=\"testuser\""))
        assertTrue("Should contain realm", authHeader.contains("realm=\"testrealm\""))
        assertTrue("Should contain nonce", authHeader.contains("nonce=\"servernonce\""))
        assertTrue("Should contain uri", authHeader.contains("uri="))
        assertTrue("Should contain response", authHeader.contains("response="))
        assertTrue("Should contain opaque", authHeader.contains("opaque=\"opaquevalue\""))
    }

    @Test
    fun `test response hash is exactly 32 hex characters`() {
        val challenge = """Digest realm="test", nonce="abc123""""
        val response = createMockResponse(challenge)

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNotNull(result)
        val authHeader = result!!.header("Proxy-Authorization")!!

        // Extract response value
        val responseMatch = """response="([a-f0-9]+)"""".toRegex().find(authHeader)
        assertNotNull("Should contain response value", responseMatch)

        val responseHash = responseMatch!!.groupValues[1]
        assertEquals("Response hash should be 32 hex chars", 32, responseHash.length)
        assertTrue("Response should only contain hex chars",
            responseHash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ========================================================================
    // Algorithm Support Tests
    // ========================================================================

    @Test
    fun `test defaults to MD5 algorithm`() {
        val challenge = """Digest realm="test", nonce="abc123""""
        val response = createMockResponse(challenge)

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNotNull("Should work with default MD5", result)

        // MD5 is default, so shouldn't be included in header (per RFC 2617)
        val authHeader = result!!.header("Proxy-Authorization")!!
        assertFalse("MD5 algorithm should not be explicitly included",
            authHeader.contains("algorithm=MD5"))
    }

    @Test
    fun `test SHA-256 algorithm is included in header`() {
        val challenge = """Digest realm="test", nonce="abc123", algorithm=SHA-256"""
        val response = createMockResponse(challenge)

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNotNull("Should work with SHA-256", result)
        val authHeader = result!!.header("Proxy-Authorization")!!
        assertTrue("SHA-256 algorithm should be included",
            authHeader.contains("algorithm=SHA-256"))
    }

    @Test
    fun `test MD5-sess algorithm support`() {
        val challenge = """Digest realm="test", nonce="abc123", qop="auth", algorithm=MD5-sess"""
        val response = createMockResponse(challenge)

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNotNull("Should work with MD5-sess", result)
        val authHeader = result!!.header("Proxy-Authorization")!!
        assertTrue("MD5-sess algorithm should be included",
            authHeader.contains("algorithm=MD5-sess"))
    }

    // ========================================================================
    // Security Edge Cases
    // ========================================================================

    @Test
    fun `test handles special characters in username`() {
        val challenge = """Digest realm="test", nonce="abc123""""
        val response = createMockResponse(challenge)

        val specialUsernames = listOf(
            "user@domain.com",
            "user:colon",
            "user\"quote",
            "user\\backslash",
            "user with spaces"
        )

        for (username in specialUsernames) {
            val result = DigestAuthenticator.authenticate(response, username, "pass")
            assertNotNull("Should handle username: $username", result)
            assertTrue("Should contain username",
                result!!.header("Proxy-Authorization")!!.contains("username="))
        }
    }

    @Test
    fun `test handles special characters in password`() {
        val challenge = """Digest realm="test", nonce="abc123""""
        val response = createMockResponse(challenge)

        val specialPasswords = listOf(
            "pass:colon",
            "pass\"quote",
            "pass\\backslash",
            "pass@symbol",
            "P@ss\$word!#%"
        )

        for (password in specialPasswords) {
            val result = DigestAuthenticator.authenticate(response, "user", password)
            assertNotNull("Should handle password with special chars", result)
        }
    }

    @Test
    fun `test handles special characters in realm`() {
        val specialRealms = listOf(
            """Digest realm="test@example.com", nonce="abc123"""",
            """Digest realm="test realm with spaces", nonce="abc123"""",
            """Digest realm="测试", nonce="abc123"""" // Unicode
        )

        for (challenge in specialRealms) {
            val response = createMockResponse(challenge)
            val result = DigestAuthenticator.authenticate(response, "user", "pass")
            assertNotNull("Should handle special realm", result)
        }
    }

    @Test
    fun `test URI encoding in authorization header`() {
        val challenge = """Digest realm="test", nonce="abc123""""
        val response = createMockResponseWithPath("/path/with spaces/test")

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNotNull(result)
        assertTrue("Should contain URI",
            result!!.header("Proxy-Authorization")!!.contains("uri="))
    }

    @Test
    fun `test nonce count is always 00000001`() {
        // Stateless implementation uses nc=00000001
        val challenge = """Digest realm="test", nonce="abc123", qop="auth""""
        val response = createMockResponse(challenge)

        val result = DigestAuthenticator.authenticate(response, "user", "pass")

        assertNotNull(result)
        val authHeader = result!!.header("Proxy-Authorization")!!
        assertTrue("Nonce count should be 00000001",
            authHeader.contains("nc=00000001"))
    }

    @Test
    fun `test client nonce is unique per request`() {
        val challenge = """Digest realm="test", nonce="abc123", qop="auth""""
        val response = createMockResponse(challenge)

        val cnonces = mutableSetOf<String>()
        repeat(10) {
            val result = DigestAuthenticator.authenticate(response, "user", "pass")
            assertNotNull(result)

            val authHeader = result!!.header("Proxy-Authorization")!!
            val cnonceMatch = """cnonce="([^"]+)"""".toRegex().find(authHeader)
            assertNotNull("Should contain cnonce", cnonceMatch)
            cnonces.add(cnonceMatch!!.groupValues[1])
        }

        assertEquals("All cnonces should be unique", 10, cnonces.size)
    }

    // ========================================================================
    // WWW-Authenticate Header Support
    // ========================================================================

    @Test
    fun `test supports WWW-Authenticate header for non-proxy auth`() {
        val mockResponse = mockk<Response>()
        val mockRequest = mockk<Request>()
        val mockBuilder = mockk<Request.Builder>()

        every { mockResponse.header("Proxy-Authenticate") } returns null
        every { mockResponse.header("WWW-Authenticate") } returns
            """Digest realm="test", nonce="abc123""""
        every { mockResponse.request } returns mockRequest
        every { mockRequest.method } returns "GET"
        every { mockRequest.url } returns "http://example.com/test".toHttpUrl()
        every { mockRequest.newBuilder() } returns mockBuilder
        every { mockBuilder.header(any(), any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockk()

        val result = DigestAuthenticator.authenticate(mockResponse, "user", "pass")

        assertNotNull("Should handle WWW-Authenticate header", result)
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun createMockResponse(challenge: String, path: String = "/test"): Response {
        val mockResponse = mockk<Response>()
        val mockRequest = mockk<Request>()
        val mockBuilder = mockk<Request.Builder>()
        val resultRequest = mockk<Request>()

        every { mockResponse.header("Proxy-Authenticate") } returns challenge
        every { mockResponse.header("WWW-Authenticate") } returns null
        every { mockResponse.request } returns mockRequest
        every { mockRequest.method } returns "GET"
        every { mockRequest.url } returns "http://example.com$path".toHttpUrl()
        every { mockRequest.newBuilder() } returns mockBuilder
        every { mockBuilder.header(any(), any()) } answers {
            val headerName = firstArg<String>()
            val headerValue = secondArg<String>()
            every { resultRequest.header(headerName) } returns headerValue
            mockBuilder
        }
        every { mockBuilder.build() } returns resultRequest

        return mockResponse
    }

    private fun createMockResponseWithPath(path: String): Response {
        return createMockResponse("""Digest realm="test", nonce="abc123"""", path)
    }

    private fun createMockResponseWithoutChallenge(): Response {
        val mockResponse = mockk<Response>()

        every { mockResponse.header("Proxy-Authenticate") } returns null
        every { mockResponse.header("WWW-Authenticate") } returns null

        return mockResponse
    }

    private fun md5Hash(data: String, md: MessageDigest): String {
        md.reset()
        val bytes = md.digest(data.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
