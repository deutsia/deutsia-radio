package com.opensource.i2pradio.util

import android.content.Context
import android.widget.ImageView
import coil.ImageLoader
import coil.load
import coil.request.Disposable
import coil.request.ImageRequest
import coil.request.ImageResult
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.ui.PreferencesHelper
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Secure image loading utility that routes HTTP/HTTPS requests through proxy
 * when Force Tor or Force Custom Proxy settings are enabled.
 *
 * Local content (file://, content://) is loaded directly without proxy.
 * Remote URLs (http://, https://) are routed through configured proxy.
 *
 * SECURITY: When any Force proxy mode is enabled but the proxy is not available,
 * remote image loading is BLOCKED to prevent IP leaks.
 */
object SecureImageLoader {

    private const val TAG = "SecureImageLoader"

    private var cachedImageLoader: ImageLoader? = null
    private var cachedProxyConfig: ProxyConfig? = null

    private enum class ProxyMode {
        NONE,           // No forced proxy
        TOR,            // Force Tor
        CUSTOM_SOCKS,   // Force Custom Proxy (SOCKS)
        CUSTOM_HTTP,    // Force Custom Proxy (HTTP)
        BLOCKED         // Proxy required but not available
    }

    private data class ProxyConfig(
        val mode: ProxyMode,
        val host: String,
        val port: Int
    )

    /**
     * Custom DNS resolver that forces DNS resolution through SOCKS5 proxy.
     *
     * By default, OkHttp resolves DNS locally BEFORE connecting through SOCKS,
     * which leaks DNS queries to clearnet. This resolver returns a placeholder
     * address, forcing the SOCKS5 proxy (Tor) to handle DNS resolution.
     */
    private val SOCKS5_DNS = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            android.util.Log.d(TAG, "DNS lookup for '$hostname' - delegating to SOCKS5 proxy")
            return listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
        }
    }

    /**
     * Get an ImageLoader configured based on current proxy settings.
     * Local content URIs bypass the proxy; remote URLs use configured proxy.
     */
    fun getImageLoader(context: Context): ImageLoader {
        val currentConfig = getCurrentProxyConfig(context)

        // Return cached loader if config hasn't changed
        if (cachedImageLoader != null && cachedProxyConfig == currentConfig) {
            return cachedImageLoader!!
        }

        // Build new loader with current config
        val loader = buildImageLoader(context, currentConfig)
        cachedImageLoader = loader
        cachedProxyConfig = currentConfig

        return loader
    }

    /**
     * Execute an image request with proxy-aware loading.
     * Automatically determines if proxy should be used based on URI scheme.
     */
    suspend fun execute(context: Context, request: ImageRequest): ImageResult {
        val loader = getImageLoader(context)
        return loader.execute(request)
    }

    /**
     * Check if a URI should be loaded through proxy.
     * Local content (file://, content://) should bypass proxy.
     */
    fun shouldUseProxy(uri: String?): Boolean {
        if (uri.isNullOrEmpty()) return false
        val lowerUri = uri.lowercase()
        return lowerUri.startsWith("http://") || lowerUri.startsWith("https://")
    }

    /**
     * Invalidate cached loader to force rebuild on next request.
     * Call this when proxy settings change.
     */
    fun invalidateCache() {
        cachedImageLoader = null
        cachedProxyConfig = null
    }

    private fun getCurrentProxyConfig(context: Context): ProxyConfig {
        val torEnabled = PreferencesHelper.isEmbeddedTorEnabled(context)
        val forceTorAll = PreferencesHelper.isForceTorAll(context)
        val forceTorExceptI2P = PreferencesHelper.isForceTorExceptI2P(context)
        val forceCustomProxy = PreferencesHelper.isForceCustomProxy(context)
        val forceCustomProxyExceptTorI2P = PreferencesHelper.isForceCustomProxyExceptTorI2P(context)
        val torConnected = TorManager.isConnected()

        android.util.Log.d(TAG, "getCurrentProxyConfig: forceTorAll=$forceTorAll, forceTorExceptI2P=$forceTorExceptI2P, " +
                "forceCustomProxy=$forceCustomProxy, forceCustomProxyExceptTorI2P=$forceCustomProxyExceptTorI2P, " +
                "torEnabled=$torEnabled, torConnected=$torConnected")

        // Priority 1: Force Tor modes
        if (torEnabled && (forceTorAll || forceTorExceptI2P)) {
            if (!torConnected) {
                android.util.Log.e(TAG, "BLOCKED: Force Tor enabled but Tor not connected")
                return ProxyConfig(ProxyMode.BLOCKED, "", 0)
            }
            val port = TorManager.getProxyPort()
            if (port <= 0) {
                android.util.Log.e(TAG, "BLOCKED: Force Tor enabled but Tor port invalid ($port)")
                return ProxyConfig(ProxyMode.BLOCKED, "", 0)
            }
            return ProxyConfig(ProxyMode.TOR, TorManager.getProxyHost(), port)
        }

        // Priority 2: Force Custom Proxy modes
        if (forceCustomProxy || forceCustomProxyExceptTorI2P) {
            val proxyHost = PreferencesHelper.getCustomProxyHost(context)
            val proxyPort = PreferencesHelper.getCustomProxyPort(context)
            val proxyProtocol = PreferencesHelper.getCustomProxyProtocol(context)

            if (proxyHost.isEmpty() || proxyPort <= 0) {
                android.util.Log.e(TAG, "BLOCKED: Force Custom Proxy enabled but proxy not configured")
                return ProxyConfig(ProxyMode.BLOCKED, "", 0)
            }

            val mode = when (proxyProtocol.uppercase()) {
                "SOCKS4", "SOCKS5", "SOCKS" -> ProxyMode.CUSTOM_SOCKS
                else -> ProxyMode.CUSTOM_HTTP
            }
            android.util.Log.d(TAG, "Using custom proxy: $proxyHost:$proxyPort ($proxyProtocol)")
            return ProxyConfig(mode, proxyHost, proxyPort)
        }

        // No forced proxy - use direct connection
        return ProxyConfig(ProxyMode.NONE, "", 0)
    }

    /**
     * Check if remote image loading is currently blocked due to a Force proxy mode
     * being enabled without an available proxy.
     */
    fun isBlocked(context: Context): Boolean {
        return getCurrentProxyConfig(context).mode == ProxyMode.BLOCKED
    }

    private fun buildImageLoader(context: Context, config: ProxyConfig): ImageLoader {
        val builder = ImageLoader.Builder(context)

        when (config.mode) {
            ProxyMode.BLOCKED -> {
                android.util.Log.e(TAG, "BLOCKING all remote image loading - proxy required but not available")

                val blockingClient = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        android.util.Log.e(TAG, "BLOCKED image request: ${chain.request().url}")
                        throw java.io.IOException("Image loading blocked: Required proxy not available.")
                    }
                    .build()

                builder.okHttpClient(blockingClient)
            }

            ProxyMode.TOR -> {
                android.util.Log.d(TAG, "Creating image loader with Tor SOCKS5 proxy: ${config.host}:${config.port}")

                val okHttpClient = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(config.host, config.port)))
                    .dns(SOCKS5_DNS)  // Force DNS through SOCKS5
                    .build()

                builder.okHttpClient(okHttpClient)
            }

            ProxyMode.CUSTOM_SOCKS -> {
                android.util.Log.d(TAG, "Creating image loader with custom SOCKS proxy: ${config.host}:${config.port}")

                val okHttpClient = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(config.host, config.port)))
                    .dns(SOCKS5_DNS)  // Force DNS through SOCKS5
                    .build()

                builder.okHttpClient(okHttpClient)
            }

            ProxyMode.CUSTOM_HTTP -> {
                android.util.Log.d(TAG, "Creating image loader with custom HTTP proxy: ${config.host}:${config.port}")

                val okHttpClient = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(config.host, config.port)))
                    .build()

                builder.okHttpClient(okHttpClient)
            }

            ProxyMode.NONE -> {
                android.util.Log.d(TAG, "Creating image loader without proxy (direct connection)")

                val okHttpClient = OkHttpClient.Builder()
                    .retryOnConnectionFailure(true)
                    .build()

                builder.okHttpClient(okHttpClient)
            }
        }

        return builder.build()
    }
}

/**
 * Extension function to load images securely through proxy when enabled.
 * Uses SecureImageLoader for remote URLs (http/https).
 * Local content URIs (file://, content://) and drawable resources bypass proxy.
 *
 * @param data The image source (URL string, Uri, DrawableRes, etc.)
 * @param builder Optional builder to customize the image request
 * @return Disposable to cancel the request if needed
 */
fun ImageView.loadSecure(
    data: Any?,
    builder: ImageRequest.Builder.() -> Unit = {}
): Disposable {
    val imageLoader = SecureImageLoader.getImageLoader(context)
    return this.load(data, imageLoader, builder)
}
