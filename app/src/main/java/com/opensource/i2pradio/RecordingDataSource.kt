package com.opensource.i2pradio

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * A DataSource wrapper that tees (copies) all read data to an output stream
 * for recording purposes while passing it through to the player.
 *
 * The recording output stream can be dynamically set/cleared without
 * recreating the player, allowing seamless recording toggle.
 */
class RecordingDataSource(
    private val upstream: DataSource,
    private val outputStreamHolder: AtomicReference<OutputStream?>
) : DataSource {

    private var dataSpec: DataSpec? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = upstream.read(buffer, offset, length)

        // Get the current output stream (may be null if not recording)
        val outputStream = outputStreamHolder.get()
        if (bytesRead > 0 && outputStream != null) {
            try {
                outputStream.write(buffer, offset, bytesRead)
            } catch (e: Exception) {
                android.util.Log.e("RecordingDataSource", "Error writing to recording file", e)
            }
        }

        return bytesRead
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        upstream.close()
        // Don't close the recording stream here - it's managed by RadioService
    }

    /**
     * Factory for creating RecordingDataSource instances.
     * Uses a shared AtomicReference for the output stream, allowing
     * recording to be toggled without recreating the player.
     */
    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val outputStreamHolder: AtomicReference<OutputStream?>
    ) : DataSource.Factory {

        override fun createDataSource(): DataSource {
            return RecordingDataSource(
                upstreamFactory.createDataSource(),
                outputStreamHolder
            )
        }
    }
}
