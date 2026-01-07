package com.opensource.i2pradio

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicLong

/**
 * A DataSource wrapper that tracks stream activity for live radio streams.
 *
 * This enables data-flow-based reconnection logic instead of relying on
 * ExoPlayer's STATE_ENDED which can falsely trigger during song transitions
 * on some streams.
 *
 * The approach mimics browser behavior: keep playing as long as data is
 * flowing, only reconnect when the stream truly stalls.
 */
class LiveStreamDataSource(
    private val upstream: DataSource,
    private val lastDataReceivedTime: AtomicLong
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // Mark activity on open
        lastDataReceivedTime.set(System.currentTimeMillis())
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = upstream.read(buffer, offset, length)

        if (bytesRead > 0) {
            // Update timestamp whenever we receive data
            lastDataReceivedTime.set(System.currentTimeMillis())
        }

        return bytesRead
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        upstream.close()
    }

    /**
     * Factory for creating LiveStreamDataSource instances.
     * Shares the lastDataReceivedTime atomic across all instances.
     */
    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val lastDataReceivedTime: AtomicLong
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return LiveStreamDataSource(
                upstreamFactory.createDataSource(),
                lastDataReceivedTime
            )
        }
    }
}
