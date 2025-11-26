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
 * Secure image loading utility that routes HTTP/HTTPS requests through Tor
 * when Force Tor settings are enabled.
 *
 * Local content (file://, content://) is loaded directly without proxy.
 * Remote URLs (http://, https://) are routed through Tor proxy when configured.
 */
object SecureImageLoader {

    private var cachedImageLoader: ImageLoader? = null
    private var cachedProxyConfig: ProxyConfig? = null

    private data class ProxyConfig(
        val useTor: Boolean,
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

        // Use Tor proxy if:
        // 1. Tor is enabled AND connected
        // 2. AND either Force Tor All or Force Tor Except I2P is enabled
        val useTor = torEnabled && torConnected && (forceTorAll || forceTorExceptI2p)

        return ProxyConfig(
            useTor = useTor,
            host = if (useTor) TorManager.socksHost else "",
            port = if (useTor) TorManager.socksPort else 0
        )
    }

    private fun buildImageLoader(context: Context, config: ProxyConfig): ImageLoader {
        val builder = ImageLoader.Builder(context)

        if (config.useTor && config.host.isNotEmpty() && config.port > 0) {
            // Build OkHttpClient with SOCKS proxy for Tor
            val proxy = Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress(config.host, config.port)
            )

            val okHttpClient = OkHttpClient.Builder()
                .proxy(proxy)
                .build()

            builder.okHttpClient(okHttpClient)

                "Created image loader with Tor proxy: ${config.host}:${config.port}")
        } else {
                "Created image loader without proxy (Tor not active or not required)")
        }

        return builder.build()
    }
}

/**
 * Extension function to load images securely through Tor when enabled.
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
