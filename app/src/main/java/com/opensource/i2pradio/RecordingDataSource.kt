package com.opensource.i2pradio

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.OutputStream

/**
 * A DataSource wrapper that tees (copies) all read data to an output stream
 * for recording purposes while passing it through to the player.
 */
class RecordingDataSource(
    private val upstream: DataSource,
    private val recordingOutputStream: OutputStream?
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

        if (bytesRead > 0 && recordingOutputStream != null) {
            try {
                recordingOutputStream.write(buffer, offset, bytesRead)
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
     * Factory for creating RecordingDataSource instances
     */
    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val getRecordingOutputStream: () -> OutputStream?
    ) : DataSource.Factory {

        override fun createDataSource(): DataSource {
            return RecordingDataSource(
                upstreamFactory.createDataSource(),
                getRecordingOutputStream()
            )
        }
    }
}
