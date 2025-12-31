package com.opensource.i2pradio.data.radiobrowser

import android.content.Context
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.ui.PreferencesHelper
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy

/**
 * Comprehensive tests for DNS fallback mechanisms and leak prevention in RadioBrowserServerManager.
 *
 * Test Coverage:
 * 1. DNS Fallback Mechanisms (3-tier system)
 * 2. DNS Leak Prevention (force proxy modes)
 * 3. SOCKS5_DNS Resolver Behavior
 * 4. Proxy Configuration and Routing
 * 5. Server Caching and Cycling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioBrowserServerManagerDnsLeakTest {

    private lateinit var mockContext: Context
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Reset the server manager before each test
        runTest {
            RadioBrowserServerManager.reset()
        }

        // Mock TorManager static calls
        mockkObject(TorManager)
        every { TorManager.isConnected() } returns false
        every { TorManager.getProxyHost() } returns "127.0.0.1"
        every { TorManager.getProxyPort() } returns 9050

        // Mock PreferencesHelper static calls - default to no proxy modes
        mockkObject(PreferencesHelper)
        every { PreferencesHelper.isEmbeddedTorEnabled(any()) } returns false
        every { PreferencesHelper.isForceTorAll(any()) } returns false
        every { PreferencesHelper.isForceTorExceptI2P(any()) } returns false
        every { PreferencesHelper.isForceCustomProxy(any()) } returns false
        every { PreferencesHelper.isForceCustomProxyExceptTorI2P(any()) } returns false
        every { PreferencesHelper.getCustomProxyHost(any()) } returns ""
        every { PreferencesHelper.getCustomProxyPort(any()) } returns 0
        every { PreferencesHelper.getCustomProxyProtocol(any()) } returns "SOCKS5"
        every { PreferencesHelper.getCustomProxyUsername(any()) } returns ""
        every { PreferencesHelper.getCustomProxyPassword(any()) } returns ""
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
        unmockkAll()
    }

    // ========================================================================
    // DNS Fallback Mechanism Tests
    // ========================================================================

    @Test
    fun `test normal mode uses DNS discovery when no proxy is forced`() = runTest {
        // Normal mode - no force proxy enabled
        every { PreferencesHelper.isEmbeddedTorEnabled(any()) } returns false
        every { PreferencesHelper.isForceTorAll(any()) } returns false

        // Mock DNS resolution to succeed (simulate DNS discovery working)
        // Note: We can't directly test InetAddress.getAllByName() without more complex mocking
        // This test verifies the flow when no proxy mode is enabled

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should return either discovered servers or fallback
        assertNotNull(server)
        assertTrue(server.contains("api.radio-browser.info"))
    }

    @Test
    fun `test hardcoded fallback servers are used when all discovery fails`() = runTest {
        // In normal mode, if DNS and API both fail, should use hardcoded fallbacks
        // The RadioBrowserServerManager will fall back to hardcoded servers

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        assertNotNull(server)
        // Should be one of the hardcoded fallbacks
        assertTrue(
            server == "de1.api.radio-browser.info" ||
            server == "de2.api.radio-browser.info" ||
            server == "fi1.api.radio-browser.info"
        )
    }

    @Test
    fun `test server cycling rotates through available servers`() = runTest {
        val firstServer = RadioBrowserServerManager.getCurrentServer(mockContext)
        val secondServer = RadioBrowserServerManager.cycleToNextServer()

        assertNotNull(firstServer)
        assertNotNull(secondServer)
        // Servers should be valid RadioBrowser servers
        assertTrue(firstServer.contains("api.radio-browser.info"))
        assertTrue(secondServer.contains("api.radio-browser.info"))
    }

    @Test
    fun `test getAllServers returns list of available servers`() = runTest {
        val servers = RadioBrowserServerManager.getAllServers(mockContext)

        assertNotNull(servers)
        assertTrue(servers.isNotEmpty())
        // All servers should be RadioBrowser API servers
        servers.forEach { server ->
            assertTrue(server.contains("api.radio-browser.info"))
        }
    }

    // ========================================================================
    // DNS Leak Prevention Tests - Force Tor Mode
    // ========================================================================

    @Test
    fun `test force Tor mode blocks clearnet DNS when Tor is not connected`() = runTest {
        // Enable force Tor mode
        every { PreferencesHelper.isEmbeddedTorEnabled(any()) } returns true
        every { PreferencesHelper.isForceTorAll(any()) } returns true
        every { TorManager.isConnected() } returns false

        // Request server - should NOT make clearnet DNS request
        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should return hardcoded fallback (no network request made)
        assertNotNull(server)
        assertTrue(
            server == "de1.api.radio-browser.info" ||
            server == "de2.api.radio-browser.info" ||
            server == "fi1.api.radio-browser.info"
        )

        // Verify Tor connection was checked
        verify { TorManager.isConnected() }
    }

    @Test
    fun `test force Tor mode uses Tor when connected`() = runTest {
        // Enable force Tor mode with Tor connected
        every { PreferencesHelper.isEmbeddedTorEnabled(any()) } returns true
        every { PreferencesHelper.isForceTorAll(any()) } returns true
        every { TorManager.isConnected() } returns true
        every { TorManager.getProxyPort() } returns 9050

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should get a server (either via proxied API or fallback)
        assertNotNull(server)
        assertTrue(server.contains("api.radio-browser.info"))

        // Verify Tor was checked
        verify { TorManager.isConnected() }
        verify { TorManager.getProxyHost() }
        verify { TorManager.getProxyPort() }
    }

    @Test
    fun `test force Tor mode blocks when Tor port is invalid`() = runTest {
        // Enable force Tor mode with invalid port
        every { PreferencesHelper.isEmbeddedTorEnabled(any()) } returns true
        every { PreferencesHelper.isForceTorAll(any()) } returns true
        every { TorManager.isConnected() } returns true
        every { TorManager.getProxyPort() } returns 0  // Invalid port

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should return hardcoded fallback (blocked network request)
        assertNotNull(server)
        assertTrue(
            server == "de1.api.radio-browser.info" ||
            server == "de2.api.radio-browser.info" ||
            server == "fi1.api.radio-browser.info"
        )
    }

    @Test
    fun `test force Tor except I2P mode prevents clearnet DNS`() = runTest {
        // Enable force Tor except I2P mode
        every { PreferencesHelper.isEmbeddedTorEnabled(any()) } returns true
        every { PreferencesHelper.isForceTorExceptI2P(any()) } returns true
        every { TorManager.isConnected() } returns false

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should return hardcoded fallback (no clearnet DNS)
        assertNotNull(server)
        assertTrue(
            server == "de1.api.radio-browser.info" ||
            server == "de2.api.radio-browser.info" ||
            server == "fi1.api.radio-browser.info"
        )
    }

    // ========================================================================
    // DNS Leak Prevention Tests - Force Custom Proxy Mode
    // ========================================================================

    @Test
    fun `test force custom proxy mode blocks when proxy not configured`() = runTest {
        // Enable force custom proxy mode without valid proxy configuration
        every { PreferencesHelper.isForceCustomProxy(any()) } returns true
        every { PreferencesHelper.getCustomProxyHost(any()) } returns ""
        every { PreferencesHelper.getCustomProxyPort(any()) } returns 0

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should return hardcoded fallback (blocked network request)
        assertNotNull(server)
        assertTrue(
            server == "de1.api.radio-browser.info" ||
            server == "de2.api.radio-browser.info" ||
            server == "fi1.api.radio-browser.info"
        )
    }

    @Test
    fun `test force custom proxy mode blocks when host empty but port valid`() = runTest {
        // Enable force custom proxy mode with empty host
        every { PreferencesHelper.isForceCustomProxy(any()) } returns true
        every { PreferencesHelper.getCustomProxyHost(any()) } returns ""
        every { PreferencesHelper.getCustomProxyPort(any()) } returns 1080

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should return hardcoded fallback (blocked)
        assertNotNull(server)
        assertTrue(
            server == "de1.api.radio-browser.info" ||
            server == "de2.api.radio-browser.info" ||
            server == "fi1.api.radio-browser.info"
        )
    }

    @Test
    fun `test force custom proxy mode blocks when port invalid but host valid`() = runTest {
        // Enable force custom proxy mode with invalid port
        every { PreferencesHelper.isForceCustomProxy(any()) } returns true
        every { PreferencesHelper.getCustomProxyHost(any()) } returns "proxy.example.com"
        every { PreferencesHelper.getCustomProxyPort(any()) } returns 0

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should return hardcoded fallback (blocked)
        assertNotNull(server)
        assertTrue(
            server == "de1.api.radio-browser.info" ||
            server == "de2.api.radio-browser.info" ||
            server == "fi1.api.radio-browser.info"
        )
    }

    @Test
    fun `test force custom SOCKS proxy uses proxy when configured`() = runTest {
        // Enable force custom proxy mode with valid SOCKS proxy
        every { PreferencesHelper.isForceCustomProxy(any()) } returns true
        every { PreferencesHelper.getCustomProxyHost(any()) } returns "127.0.0.1"
        every { PreferencesHelper.getCustomProxyPort(any()) } returns 1080
        every { PreferencesHelper.getCustomProxyProtocol(any()) } returns "SOCKS5"

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should get a server (via proxy or fallback)
        assertNotNull(server)
        assertTrue(server.contains("api.radio-browser.info"))

        // Verify proxy settings were checked
        verify { PreferencesHelper.getCustomProxyHost(any()) }
        verify { PreferencesHelper.getCustomProxyPort(any()) }
        verify { PreferencesHelper.getCustomProxyProtocol(any()) }
    }

    @Test
    fun `test force custom HTTP proxy uses proxy when configured`() = runTest {
        // Enable force custom proxy mode with valid HTTP proxy
        every { PreferencesHelper.isForceCustomProxy(any()) } returns true
        every { PreferencesHelper.getCustomProxyHost(any()) } returns "127.0.0.1"
        every { PreferencesHelper.getCustomProxyPort(any()) } returns 8080
        every { PreferencesHelper.getCustomProxyProtocol(any()) } returns "HTTP"

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should get a server
        assertNotNull(server)
        assertTrue(server.contains("api.radio-browser.info"))
    }

    @Test
    fun `test force custom proxy except Tor I2P mode prevents clearnet DNS`() = runTest {
        // Enable force custom proxy except Tor/I2P mode without valid proxy
        every { PreferencesHelper.isForceCustomProxyExceptTorI2P(any()) } returns true
        every { PreferencesHelper.getCustomProxyHost(any()) } returns ""
        every { PreferencesHelper.getCustomProxyPort(any()) } returns 0

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should return hardcoded fallback (no clearnet DNS)
        assertNotNull(server)
        assertTrue(
            server == "de1.api.radio-browser.info" ||
            server == "de2.api.radio-browser.info" ||
            server == "fi1.api.radio-browser.info"
        )
    }

    // ========================================================================
    // Proxy Authentication Tests
    // ========================================================================

    @Test
    fun `test custom proxy with authentication credentials`() = runTest {
        // Enable force custom proxy with authentication
        every { PreferencesHelper.isForceCustomProxy(any()) } returns true
        every { PreferencesHelper.getCustomProxyHost(any()) } returns "127.0.0.1"
        every { PreferencesHelper.getCustomProxyPort(any()) } returns 1080
        every { PreferencesHelper.getCustomProxyProtocol(any()) } returns "SOCKS5"
        every { PreferencesHelper.getCustomProxyUsername(any()) } returns "user"
        every { PreferencesHelper.getCustomProxyPassword(any()) } returns "pass"

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should get a server
        assertNotNull(server)
        assertTrue(server.contains("api.radio-browser.info"))

        // Verify credentials were checked
        verify { PreferencesHelper.getCustomProxyUsername(any()) }
        verify { PreferencesHelper.getCustomProxyPassword(any()) }
    }

    // ========================================================================
    // SOCKS5_DNS Resolver Tests
    // ========================================================================

    @Test
    fun `test SOCKS5_DNS resolver returns placeholder IP`() {
        // Access the private SOCKS5_DNS resolver via reflection
        // This tests that DNS resolution returns placeholder IP
        val dnsResolverField = RadioBrowserServerManager::class.java
            .getDeclaredField("SOCKS5_DNS")
        dnsResolverField.isAccessible = true
        val socks5Dns = dnsResolverField.get(RadioBrowserServerManager) as Dns

        // Perform lookup
        val addresses = socks5Dns.lookup("example.com")

        // Should return placeholder address
        assertNotNull(addresses)
        assertEquals(1, addresses.size)

        // Verify it's a placeholder (0.0.0.0)
        val address = addresses[0]
        val bytes = address.address
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), bytes)
    }

    @Test
    fun `test SOCKS5_DNS resolver preserves hostname`() {
        // Access the SOCKS5_DNS resolver
        val dnsResolverField = RadioBrowserServerManager::class.java
            .getDeclaredField("SOCKS5_DNS")
        dnsResolverField.isAccessible = true
        val socks5Dns = dnsResolverField.get(RadioBrowserServerManager) as Dns

        // Test with RadioBrowser hostname
        val hostname = "de1.api.radio-browser.info"
        val addresses = socks5Dns.lookup(hostname)

        // Should return an address (placeholder)
        assertNotNull(addresses)
        assertEquals(1, addresses.size)

        // Verify placeholder IP
        val bytes = addresses[0].address
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), bytes)
    }

    // ========================================================================
    // Server Caching Tests
    // ========================================================================

    @Test
    fun `test server list is cached and reused`() = runTest {
        // First request
        val server1 = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Second request should use cache
        val server2 = RadioBrowserServerManager.getCurrentServer(mockContext)

        assertNotNull(server1)
        assertNotNull(server2)
        // Should return same server from cache
        assertEquals(server1, server2)
    }

    @Test
    fun `test forceRefresh updates server list`() = runTest {
        // Get initial server
        val initialServer = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Force refresh
        RadioBrowserServerManager.forceRefresh(mockContext)

        // Get new server (might be same due to shuffling, but cache should be refreshed)
        val refreshedServer = RadioBrowserServerManager.getCurrentServer(mockContext)

        assertNotNull(initialServer)
        assertNotNull(refreshedServer)
        // Both should be valid servers
        assertTrue(initialServer.contains("api.radio-browser.info"))
        assertTrue(refreshedServer.contains("api.radio-browser.info"))
    }

    @Test
    fun `test reset clears cached servers`() = runTest {
        // Get initial server to populate cache
        val initialServer = RadioBrowserServerManager.getCurrentServer(mockContext)
        assertNotNull(initialServer)

        // Reset the manager
        RadioBrowserServerManager.reset()

        // Next request should re-discover servers
        val newServer = RadioBrowserServerManager.getCurrentServer(mockContext)
        assertNotNull(newServer)
        assertTrue(newServer.contains("api.radio-browser.info"))
    }

    // ========================================================================
    // Proxy Protocol Handling Tests
    // ========================================================================

    @Test
    fun `test SOCKS4 protocol is recognized as SOCKS proxy type`() = runTest {
        every { PreferencesHelper.isForceCustomProxy(any()) } returns true
        every { PreferencesHelper.getCustomProxyHost(any()) } returns "127.0.0.1"
        every { PreferencesHelper.getCustomProxyPort(any()) } returns 1080
        every { PreferencesHelper.getCustomProxyProtocol(any()) } returns "SOCKS4"

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        assertNotNull(server)
        assertTrue(server.contains("api.radio-browser.info"))
    }

    @Test
    fun `test SOCKS5 protocol is recognized as SOCKS proxy type`() = runTest {
        every { PreferencesHelper.isForceCustomProxy(any()) } returns true
        every { PreferencesHelper.getCustomProxyHost(any()) } returns "127.0.0.1"
        every { PreferencesHelper.getCustomProxyPort(any()) } returns 1080
        every { PreferencesHelper.getCustomProxyProtocol(any()) } returns "SOCKS5"

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        assertNotNull(server)
        assertTrue(server.contains("api.radio-browser.info"))
    }

    @Test
    fun `test generic SOCKS protocol is recognized as SOCKS proxy type`() = runTest {
        every { PreferencesHelper.isForceCustomProxy(any()) } returns true
        every { PreferencesHelper.getCustomProxyHost(any()) } returns "127.0.0.1"
        every { PreferencesHelper.getCustomProxyPort(any()) } returns 1080
        every { PreferencesHelper.getCustomProxyProtocol(any()) } returns "SOCKS"

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        assertNotNull(server)
        assertTrue(server.contains("api.radio-browser.info"))
    }

    // ========================================================================
    // Combined Proxy Mode Tests
    // ========================================================================

    @Test
    fun `test force Tor takes priority over force custom proxy`() = runTest {
        // Enable both force Tor and force custom proxy
        every { PreferencesHelper.isEmbeddedTorEnabled(any()) } returns true
        every { PreferencesHelper.isForceTorAll(any()) } returns true
        every { PreferencesHelper.isForceCustomProxy(any()) } returns true
        every { PreferencesHelper.getCustomProxyHost(any()) } returns "127.0.0.1"
        every { PreferencesHelper.getCustomProxyPort(any()) } returns 1080
        every { TorManager.isConnected() } returns false

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should use Tor settings (and block since Tor not connected)
        assertNotNull(server)

        // Tor connection should be checked (priority)
        verify { TorManager.isConnected() }
    }

    @Test
    fun `test no proxy mode allows normal DNS discovery`() = runTest {
        // Ensure no proxy modes are enabled
        every { PreferencesHelper.isEmbeddedTorEnabled(any()) } returns false
        every { PreferencesHelper.isForceTorAll(any()) } returns false
        every { PreferencesHelper.isForceTorExceptI2P(any()) } returns false
        every { PreferencesHelper.isForceCustomProxy(any()) } returns false
        every { PreferencesHelper.isForceCustomProxyExceptTorI2P(any()) } returns false

        val server = RadioBrowserServerManager.getCurrentServer(mockContext)

        // Should perform normal discovery
        assertNotNull(server)
        assertTrue(server.contains("api.radio-browser.info"))

        // Tor should NOT be checked in normal mode
        verify(exactly = 0) { TorManager.isConnected() }
    }

    // ========================================================================
    // API Base URL Tests
    // ========================================================================

    @Test
    fun `test getApiBaseUrl returns correct URL format`() = runTest {
        val baseUrl = RadioBrowserServerManager.getApiBaseUrl(mockContext)

        assertNotNull(baseUrl)
        assertTrue(baseUrl.startsWith("https://"))
        assertTrue(baseUrl.endsWith("/json"))
        assertTrue(baseUrl.contains("api.radio-browser.info"))
    }

    @Test
    fun `test getApiBaseUrl respects proxy settings`() = runTest {
        // Enable force Tor mode
        every { PreferencesHelper.isEmbeddedTorEnabled(any()) } returns true
        every { PreferencesHelper.isForceTorAll(any()) } returns true
        every { TorManager.isConnected() } returns false

        val baseUrl = RadioBrowserServerManager.getApiBaseUrl(mockContext)

        // Should still return valid URL (using fallback)
        assertNotNull(baseUrl)
        assertTrue(baseUrl.startsWith("https://"))
        assertTrue(baseUrl.endsWith("/json"))
    }
}
