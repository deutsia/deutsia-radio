package com.opensource.i2pradio.data.radioregistry

import android.content.Context
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.ui.PreferencesHelper
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for RadioRegistryClient's .onion to clearnet-over-Tor fallback behavior.
 *
 * When Force Tor mode is active and the .onion API mirror is down, the client
 * should automatically retry using the clearnet URL still routed through Tor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioRegistryClientTorFallbackTest {

    private lateinit var mockContext: Context
    private lateinit var client: RadioRegistryClient

    /** Track all URLs that were requested, in order. */
    private val requestedUrls = mutableListOf<String>()

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)

        // Mock TorManager - Tor is connected
        mockkObject(TorManager)
        every { TorManager.isConnected() } returns true
        every { TorManager.getProxyHost() } returns "127.0.0.1"
        every { TorManager.getProxyPort() } returns 9050

        // Mock PreferencesHelper
        mockkObject(PreferencesHelper)
        every { PreferencesHelper.isEmbeddedTorEnabled(any()) } returns true
        every { PreferencesHelper.isForceTorAll(any()) } returns true
        every { PreferencesHelper.isForceTorExceptI2P(any()) } returns false
        every { PreferencesHelper.isForceCustomProxy(any()) } returns false
        every { PreferencesHelper.isForceCustomProxyExceptTorI2P(any()) } returns false
        every { PreferencesHelper.isRadioRegistryApiDisabled(any()) } returns false
        every { PreferencesHelper.getCustomProxyHost(any()) } returns ""
        every { PreferencesHelper.getCustomProxyPort(any()) } returns 0
        every { PreferencesHelper.getCustomProxyProtocol(any()) } returns "SOCKS5"
        every { PreferencesHelper.getCustomProxyUsername(any()) } returns ""
        every { PreferencesHelper.getCustomProxyPassword(any()) } returns ""

        requestedUrls.clear()
        client = RadioRegistryClient(mockContext)
    }

    @After
    fun teardown() {
        RadioRegistryClient.testInterceptor = null
        unmockkAll()
    }

    private val STATIONS_JSON = """
        {
            "stations": [
                {
                    "id": "test-1",
                    "name": "Test Station",
                    "stream_url": "http://test.onion/stream",
                    "network": "tor",
                    "genre": "Electronic",
                    "online": true
                }
            ],
            "total": 1,
            "limit": 50,
            "offset": 0
        }
    """.trimIndent()

    /**
     * Create an interceptor that returns different responses based on the request URL host.
     */
    private fun createInterceptor(
        onionResponse: (() -> Response)? = null,
        clearnetResponse: (() -> Response)? = null
    ): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            requestedUrls.add(url)

            val host = request.url.host
            when {
                host.endsWith(".onion") -> {
                    onionResponse?.invoke()
                        ?: throw java.io.IOException("Simulated .onion connection failure")
                }
                else -> {
                    clearnetResponse?.invoke()
                        ?: Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(STATIONS_JSON.toResponseBody("application/json".toMediaType()))
                            .build()
                }
            }
        }
    }

    private fun successResponse(request: okhttp3.Request, body: String = STATIONS_JSON): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    // ========================================================================
    // Fallback Behavior Tests
    // ========================================================================

    @Test
    fun `onion failure with HTTP error falls back to clearnet over Tor`() = runTest {
        RadioRegistryClient.testInterceptor = Interceptor { chain ->
            val request = chain.request()
            requestedUrls.add(request.url.toString())

            if (request.url.host.endsWith(".onion")) {
                // .onion returns 503
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Service Unavailable")
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            } else {
                // Clearnet returns success
                successResponse(request)
            }
        }

        val result = client.getTorStations()

        assertTrue("Expected Success but got $result", result is RadioRegistryResult.Success)
        // First request should be to .onion, second to clearnet
        assertEquals(2, requestedUrls.size)
        assertTrue("First request should be to .onion", requestedUrls[0].contains(".onion"))
        assertTrue("Second request should be to clearnet",
            requestedUrls[1].contains(RadioRegistryClient.CLEARNET_BASE_URL))
    }

    @Test
    fun `onion failure with connection exception falls back to clearnet over Tor`() = runTest {
        RadioRegistryClient.testInterceptor = createInterceptor(
            onionResponse = { throw java.io.IOException("Simulated .onion timeout") },
            clearnetResponse = null // defaults to success
        )

        val result = client.getTorStations()

        assertTrue("Expected Success but got $result", result is RadioRegistryResult.Success)
        assertEquals(2, requestedUrls.size)
        assertTrue("First request should be to .onion", requestedUrls[0].contains(".onion"))
        assertTrue("Second request should be to clearnet",
            requestedUrls[1].contains(RadioRegistryClient.CLEARNET_BASE_URL))
    }

    @Test
    fun `onion success does not trigger fallback`() = runTest {
        RadioRegistryClient.testInterceptor = Interceptor { chain ->
            val request = chain.request()
            requestedUrls.add(request.url.toString())
            // Always return success regardless of URL
            successResponse(request)
        }

        val result = client.getTorStations()

        assertTrue("Expected Success but got $result", result is RadioRegistryResult.Success)
        // Only one request should be made - to .onion
        assertEquals(1, requestedUrls.size)
        assertTrue("Request should be to .onion", requestedUrls[0].contains(".onion"))
    }

    @Test
    fun `both onion and clearnet failure returns error`() = runTest {
        RadioRegistryClient.testInterceptor = Interceptor { chain ->
            val request = chain.request()
            requestedUrls.add(request.url.toString())
            // All requests fail
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Service Unavailable")
                .body("".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val result = client.getTorStations()

        assertTrue("Expected Error but got $result", result is RadioRegistryResult.Error)
        assertEquals(2, requestedUrls.size)
        assertTrue(requestedUrls[0].contains(".onion"))
        assertTrue(requestedUrls[1].contains(RadioRegistryClient.CLEARNET_BASE_URL))
    }

    @Test
    fun `both onion and clearnet exception returns error with message`() = runTest {
        RadioRegistryClient.testInterceptor = Interceptor { chain ->
            val request = chain.request()
            requestedUrls.add(request.url.toString())
            throw java.io.IOException("Network unreachable")
        }

        val result = client.getTorStations()

        assertTrue("Expected Error but got $result", result is RadioRegistryResult.Error)
        val error = result as RadioRegistryResult.Error
        assertTrue("Error should mention both failures",
            error.message.contains("Both onion service and clearnet-over-Tor failed"))
        assertEquals(2, requestedUrls.size)
    }

    // ========================================================================
    // Non-Tor Mode: No Fallback Tests
    // ========================================================================

    @Test
    fun `no fallback in direct clearnet mode`() = runTest {
        // Disable Force Tor - use direct clearnet
        every { PreferencesHelper.isEmbeddedTorEnabled(any()) } returns false
        every { PreferencesHelper.isForceTorAll(any()) } returns false

        RadioRegistryClient.testInterceptor = Interceptor { chain ->
            val request = chain.request()
            requestedUrls.add(request.url.toString())
            // Fail the request
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Server Error")
                .body("".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val result = client.getTorStations()

        assertTrue("Expected Error but got $result", result is RadioRegistryResult.Error)
        // Should only make one request - no fallback
        assertEquals(1, requestedUrls.size)
    }

    // ========================================================================
    // ForceTorExceptI2P Fallback Tests
    // ========================================================================

    @Test
    fun `fallback also works with ForceTorExceptI2P mode`() = runTest {
        every { PreferencesHelper.isForceTorAll(any()) } returns false
        every { PreferencesHelper.isForceTorExceptI2P(any()) } returns true

        RadioRegistryClient.testInterceptor = createInterceptor(
            onionResponse = { throw java.io.IOException("Simulated .onion failure") },
            clearnetResponse = null // defaults to success
        )

        val result = client.getTorStations()

        assertTrue("Expected Success but got $result", result is RadioRegistryResult.Success)
        assertEquals(2, requestedUrls.size)
        assertTrue(requestedUrls[0].contains(".onion"))
        assertTrue(requestedUrls[1].contains(RadioRegistryClient.CLEARNET_BASE_URL))
    }

    // ========================================================================
    // Tor Disconnected: Still Blocked Tests
    // ========================================================================

    @Test
    fun `fallback does not bypass Tor when Tor is disconnected`() = runTest {
        every { TorManager.isConnected() } returns false

        RadioRegistryClient.testInterceptor = Interceptor { chain ->
            val request = chain.request()
            requestedUrls.add(request.url.toString())
            successResponse(request)
        }

        val result = client.getTorStations()

        // Should be blocked entirely - no requests made
        assertTrue("Expected Error but got $result", result is RadioRegistryResult.Error)
        val error = result as RadioRegistryResult.Error
        assertTrue("Should mention Tor not connected", error.message.contains("blocked"))
        assertEquals("No requests should be made when Tor is disconnected",
            0, requestedUrls.size)
    }

    // ========================================================================
    // Endpoint Preservation Tests
    // ========================================================================

    @Test
    fun `fallback preserves the original API endpoint and query params`() = runTest {
        RadioRegistryClient.testInterceptor = createInterceptor(
            onionResponse = { throw java.io.IOException("Simulated .onion failure") },
            clearnetResponse = null // defaults to success
        )

        client.getTorStations(onlineOnly = true)

        assertEquals(2, requestedUrls.size)
        // Both requests should have the same path and query params
        val onionPath = requestedUrls[0].substringAfter(".onion")
        val clearnetPath = requestedUrls[1].substringAfter(RadioRegistryClient.CLEARNET_BASE_URL)
        assertEquals("Endpoint should be preserved in fallback", onionPath, clearnetPath)
        assertTrue("Should contain network=tor", onionPath.contains("network=tor"))
    }
}
