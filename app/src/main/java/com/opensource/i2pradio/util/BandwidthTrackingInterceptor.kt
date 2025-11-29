package com.opensource.i2pradio.util

import android.content.Context
import com.opensource.i2pradio.ui.PreferencesHelper
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException

/**
 * OkHttp interceptor that tracks bandwidth usage for all network requests.
 *
 * Tracks both request and response sizes and updates the bandwidth counters
 * in SharedPreferences for display in the Settings screen.
 */
class BandwidthTrackingInterceptor(private val context: Context) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Track request body size (upload)
        val requestBodySize = request.body?.contentLength() ?: 0L
        if (requestBodySize > 0) {
            PreferencesHelper.addBandwidthUsage(context, requestBodySize)
        }

        // Proceed with the request
        val response = chain.proceed(request)

        // Track response body size (download)
        val responseBodySize = response.body?.contentLength() ?: -1L

        return if (responseBodySize >= 0) {
            // Known content length - track immediately
            PreferencesHelper.addBandwidthUsage(context, responseBodySize)
            response
        } else {
            // Unknown content length (chunked/streaming) - track as bytes are read
            response.newBuilder()
                .body(TrackingResponseBody(response.body!!, context))
                .build()
        }
    }

    /**
     * Wrapper for response body that tracks bytes as they are read.
     * Used for streaming responses where content-length is unknown.
     */
    private class TrackingResponseBody(
        private val responseBody: okhttp3.ResponseBody,
        private val context: Context
    ) : okhttp3.ResponseBody() {

        private val trackingSource: BufferedSource by lazy {
            responseBody.source().trackBandwidth().buffer()
        }

        override fun contentType() = responseBody.contentType()

        override fun contentLength() = responseBody.contentLength()

        override fun source() = trackingSource

        private fun Source.trackBandwidth(): Source {
            return object : ForwardingSource(this) {
                private var totalBytesRead = 0L

                override fun read(sink: Buffer, byteCount: Long): Long {
                    val bytesRead = super.read(sink, byteCount)
                    if (bytesRead > 0) {
                        totalBytesRead += bytesRead
                        // Update bandwidth usage incrementally
                        PreferencesHelper.addBandwidthUsage(context, bytesRead)
                    }
                    return bytesRead
                }
            }
        }
    }
}
