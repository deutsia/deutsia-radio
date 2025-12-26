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
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Secure image loading utility that routes HTTP/HTTPS requests through Tor or
 * custom proxy when Force Tor or Force Custom Proxy settings are enabled.
 *
 * Local content (file://, content://) is loaded directly without proxy.
 * Remote URLs (http://, https://) are routed through proxy when configured.
 *
 * Priority order:
 * 1. Tor proxy (when Force Tor All or Force Tor Except I2P is enabled and Tor is connected)
 * 2. Custom proxy (when Force Custom Proxy or Force Custom Proxy Except Tor/I2P is enabled)
 * 3. Block all requests (when any force mode is enabled but no valid proxy is configured)
 * 4. Direct clearnet (when no force mode is enabled)
 */
object SecureImageLoader {

    private var cachedImageLoader: ImageLoader? = null
    private var cachedProxyConfig: ProxyConfig? = null

    private data class ProxyConfig(
        val useTor: Boolean,
        val useCustomProxy: Boolean,
        val proxyType: java.net.Proxy.Type,
        val host: String,
        val port: Int
    )

    /**
     * Get an ImageLoader configured based on current Tor settings.
     * Local content URIs bypass the proxy; remote URLs use Tor when enabled.
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
     * Call this when Tor settings change.
     */
    fun invalidateCache() {
        cachedImageLoader = null
        cachedProxyConfig = null
    }

    private fun getCurrentProxyConfig(context: Context): ProxyConfig {
        val torEnabled = PreferencesHelper.isEmbeddedTorEnabled(context)
        val forceTorAll = PreferencesHelper.isForceTorAll(context)
        val forceTorExceptI2p = PreferencesHelper.isForceTorExceptI2P(context)
        val torConnected = TorManager.isConnected()

        // Check custom proxy settings
        val forceCustomProxy = PreferencesHelper.isForceCustomProxy(context)
        val forceCustomProxyExceptTorI2P = PreferencesHelper.isForceCustomProxyExceptTorI2P(context)

        // Priority 1: Use Tor proxy if:
        // 1. Tor is enabled AND connected
        // 2. AND either Force Tor All or Force Tor Except I2P is enabled
        val useTor = torEnabled && torConnected && (forceTorAll || forceTorExceptI2p)

        if (useTor) {
            return ProxyConfig(
                useTor = true,
                useCustomProxy = false,
                proxyType = Proxy.Type.SOCKS,
                host = TorManager.socksHost,
                port = TorManager.socksPort
            )
        }

        // Priority 2: Use custom proxy if Force Custom Proxy is enabled
        // This applies to ALL image loading (cover art from RadioBrowser, etc.)
        val useCustomProxy = forceCustomProxy || forceCustomProxyExceptTorI2P

        if (useCustomProxy) {
            val customProxyHost = PreferencesHelper.getCustomProxyHost(context)
            val customProxyPort = PreferencesHelper.getCustomProxyPort(context)
            val customProxyProtocol = PreferencesHelper.getCustomProxyProtocol(context)

            // Only use custom proxy if it's properly configured
            if (customProxyHost.isNotEmpty() && customProxyPort > 0) {
                val proxyType = when (customProxyProtocol.uppercase()) {
                    "SOCKS4", "SOCKS5" -> Proxy.Type.SOCKS
                    else -> Proxy.Type.HTTP  // HTTP, HTTPS use HTTP proxy type
                }

                return ProxyConfig(
                    useTor = false,
                    useCustomProxy = true,
                    proxyType = proxyType,
                    host = customProxyHost,
                    port = customProxyPort
                )
            }
        }

        // No proxy configured - but if force modes are enabled, we should NOT fall back to clearnet
        // Return an empty config that will be handled by buildImageLoader
        return ProxyConfig(
            useTor = false,
            useCustomProxy = false,
            proxyType = Proxy.Type.DIRECT,
            host = "",
            port = 0
        )
    }

    private fun buildImageLoader(context: Context, config: ProxyConfig): ImageLoader {
        val builder = ImageLoader.Builder(context)

        // Check if any force mode is enabled - used to determine if we should block clearnet
        val forceTorAll = PreferencesHelper.isForceTorAll(context)
        val forceTorExceptI2p = PreferencesHelper.isForceTorExceptI2P(context)
        val forceCustomProxy = PreferencesHelper.isForceCustomProxy(context)
        val forceCustomProxyExceptTorI2P = PreferencesHelper.isForceCustomProxyExceptTorI2P(context)
        val anyForceModeEnabled = forceTorAll || forceTorExceptI2p || forceCustomProxy || forceCustomProxyExceptTorI2P

        when {
            // Priority 1: Tor proxy
            config.useTor && config.host.isNotEmpty() && config.port > 0 -> {
                val proxy = Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress(config.host, config.port)
                )

                val okHttpClient = OkHttpClient.Builder()
                    .proxy(proxy)
                    .build()

                builder.okHttpClient(okHttpClient)

                android.util.Log.d("SecureImageLoader",
                    "Created image loader with Tor proxy: ${config.host}:${config.port}")
            }

            // Priority 2: Custom proxy
            config.useCustomProxy && config.host.isNotEmpty() && config.port > 0 -> {
                val proxy = Proxy(
                    config.proxyType,
                    InetSocketAddress(config.host, config.port)
                )

                val okHttpClient = OkHttpClient.Builder()
                    .proxy(proxy)
                    .build()

                builder.okHttpClient(okHttpClient)

                android.util.Log.d("SecureImageLoader",
                    "Created image loader with custom proxy (${config.proxyType}): ${config.host}:${config.port}")
            }

            // Force mode enabled but no valid proxy configured - BLOCK all image loading
            // This prevents clearnet leaks when user expects privacy protection
            anyForceModeEnabled -> {
                // Create an OkHttpClient that will fail all requests
                // by pointing to an invalid proxy that doesn't exist
                val blockingProxy = Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress("127.0.0.1", 1)  // Invalid port, will fail
                )

                val okHttpClient = OkHttpClient.Builder()
                    .proxy(blockingProxy)
                    .connectTimeout(1, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()

                builder.okHttpClient(okHttpClient)

                android.util.Log.w("SecureImageLoader",
                    "Force proxy mode enabled but no valid proxy configured - blocking image loading to prevent clearnet leak")
            }

            // No force mode and no proxy - allow direct clearnet access
            else -> {
                val okHttpClient = OkHttpClient.Builder()
                    .retryOnConnectionFailure(true)
                    .build()

                builder.okHttpClient(okHttpClient)

                android.util.Log.d("SecureImageLoader",
                    "Created image loader without proxy (no force mode active)")
            }
        }

        return builder.build()
    }
}

/**
 * Extension function to load images securely through Tor or custom proxy when enabled.
 * Uses SecureImageLoader for remote URLs (http/https).
 * Local content URIs (file://, content://) and drawable resources bypass proxy.
 *
 * When any force proxy mode is enabled, images will ONLY load through the configured
 * proxy. If no valid proxy is configured, image loading will fail (no clearnet fallback).
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
