package com.opensource.i2pradio.auto

import android.content.Context
import com.opensource.i2pradio.data.ProxyType
import com.opensource.i2pradio.data.RadioStation
import com.opensource.i2pradio.tor.TorManager
import com.opensource.i2pradio.ui.PreferencesHelper
import com.opensource.i2pradio.utils.DigestAuthenticator
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * Builds an [OkHttpClient] for streaming a single radio station from the
 * Android Auto media library service.
 *
 * This is a deliberately *narrow* subset of the routing logic in
 * [com.opensource.i2pradio.RadioService]. The AA service is hard-blocked
 * by [PreferencesHelper.isAndroidAutoBlockedByForceProxy] whenever any
 * force-proxy setting is on, so we only need to honor the per-station
 * proxy fields here:
 *
 *  - [ProxyType.NONE]   → direct HTTP, no proxy.
 *  - [ProxyType.TOR]    → use embedded Tor's SOCKS port if connected,
 *                         otherwise fall back to the station's stored
 *                         [RadioStation.proxyHost] / [RadioStation.proxyPort]
 *                         (e.g. an external Orbot / InviZible on
 *                         127.0.0.1:9050). DNS goes through SOCKS to avoid
 *                         leaks.
 *  - [ProxyType.I2P]    → station's stored host:port, treated as HTTP proxy
 *                         (matches I2P's HTTP tunnel convention used
 *                         elsewhere in the app, default 127.0.0.1:4444).
 *  - [ProxyType.CUSTOM] → station's stored host:port, protocol from
 *                         [RadioStation.customProxyProtocol], plus optional
 *                         basic / digest authentication.
 *
 * Force-proxy modes are intentionally not handled here because AA is
 * already disabled in those modes.
 */
internal object AndroidAutoProxyHttp {

    /**
     * Custom DNS resolver that forces DNS resolution through the SOCKS5
     * proxy by handing OkHttp a placeholder address per hostname. Mirrors
     * the SOCKS5_DNS used by the main player to prevent DNS leaks when
     * routing through Tor.
     */
    private val socksDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
        }
    }

    fun buildClient(context: Context, station: RadioStation): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        when (station.getProxyTypeEnum()) {
            ProxyType.NONE -> {
                builder.connectTimeout(30, TimeUnit.SECONDS)
            }

            ProxyType.TOR -> {
                val embeddedTorOn = PreferencesHelper.isEmbeddedTorEnabled(context)
                val (host, port) = if (embeddedTorOn && TorManager.isConnected()) {
                    TorManager.getProxyHost() to TorManager.getProxyPort()
                } else if (station.proxyHost.isNotEmpty()) {
                    station.proxyHost to station.proxyPort
                } else {
                    "127.0.0.1" to 9050
                }
                builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
                builder.dns(socksDns)
                builder.connectTimeout(60, TimeUnit.SECONDS)
            }

            ProxyType.I2P -> {
                val host = station.proxyHost.ifEmpty { "127.0.0.1" }
                val port = if (station.proxyPort > 0) station.proxyPort else 4444
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
                builder.connectTimeout(60, TimeUnit.SECONDS)
            }

            ProxyType.CUSTOM -> {
                val protocol = station.customProxyProtocol.uppercase()
                val javaType = when (protocol) {
                    "SOCKS4", "SOCKS5" -> Proxy.Type.SOCKS
                    "HTTP", "HTTPS" -> Proxy.Type.HTTP
                    else -> Proxy.Type.HTTP
                }
                builder.proxy(
                    Proxy(javaType, InetSocketAddress(station.proxyHost, station.proxyPort))
                )
                if (javaType == Proxy.Type.SOCKS) {
                    builder.dns(socksDns)
                }
                if (station.hasProxyAuthentication()) {
                    val user = station.proxyUsername
                    val pass = station.proxyPassword
                    val authType = station.proxyAuthType.uppercase()
                    builder.proxyAuthenticator { _, response ->
                        // Avoid retry loops if the previous attempt already
                        // included credentials.
                        if (response.request.header("Proxy-Authorization") != null) {
                            return@proxyAuthenticator null
                        }
                        when (authType) {
                            "DIGEST" -> DigestAuthenticator.authenticate(response, user, pass)
                            else -> response.request.newBuilder()
                                .header("Proxy-Authorization", Credentials.basic(user, pass))
                                .build()
                        }
                    }
                }
                val timeout = station.getEffectiveConnectionTimeout().toLong()
                builder.connectTimeout(timeout, TimeUnit.SECONDS)
            }
        }

        return builder.build()
    }
}
