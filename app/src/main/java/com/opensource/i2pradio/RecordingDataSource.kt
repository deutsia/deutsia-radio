package com.opensource.i2pradio

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A DataSource wrapper that tees (copies) all read data to an output stream
 * for recording purposes while passing it through to the player.
 *
 * Uses an async write queue to prevent audio hitching - stream data is queued
 * and written to disk on a background thread, ensuring audio playback is never
 * blocked by disk I/O.
 *
 * The recording output stream can be dynamically set/cleared without
 * recreating the player, allowing seamless recording toggle.
 */
class RecordingDataSource(
    private val upstream: DataSource,
    private val writeQueue: ArrayBlockingQueue<ByteArray>,
    private val isRecordingActive: AtomicBoolean
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

        // Queue data for async writing if recording is active
        if (bytesRead > 0 && isRecordingActive.get()) {
            try {
                // Copy the data to avoid buffer reuse issues
                val dataCopy = ByteArray(bytesRead)
                System.arraycopy(buffer, offset, dataCopy, 0, bytesRead)
                // Use offer() which is non-blocking - if queue is full, drop this chunk
                // This prevents audio hitching at the cost of potentially losing some data
                // Queue size is large enough (1000 chunks) that this should rarely happen
                if (!writeQueue.offer(dataCopy)) {
                    android.util.Log.w("RecordingDataSource", "Write queue full, dropping chunk")
                }
            } catch (e: Exception) {
                android.util.Log.e("RecordingDataSource", "Error queueing recording data", e)
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
     * Uses a shared write queue for async disk writes, preventing audio hitching.
     */
    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val writeQueue: ArrayBlockingQueue<ByteArray>,
        private val isRecordingActive: AtomicBoolean
    ) : DataSource.Factory {

        override fun createDataSource(): DataSource {
            return RecordingDataSource(
                upstreamFactory.createDataSource(),
                writeQueue,
                isRecordingActive
            )
        }
    }

    companion object {
        /**
         * Creates and starts a background writer thread that drains the write queue
         * and writes data to the output stream.
         */
        fun startWriterThread(
            writeQueue: ArrayBlockingQueue<ByteArray>,
            outputStreamHolder: AtomicReference<OutputStream?>,
            isRecordingActive: AtomicBoolean
        ): Thread {
            return Thread({
                android.util.Log.d("RecordingDataSource", "Writer thread started")
                var bufferedStream: BufferedOutputStream? = null
                var currentOutputStream: OutputStream? = null

                while (true) {
                    try {
                        // Poll with timeout to check if we should exit
                        val data = writeQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)

                        // Check if output stream has changed
                        val newOutputStream = outputStreamHolder.get()
                        if (newOutputStream != currentOutputStream) {
                            // Flush and update the buffered stream
                            bufferedStream?.flush()
                            currentOutputStream = newOutputStream
                            bufferedStream = if (newOutputStream != null) {
                                BufferedOutputStream(newOutputStream, 64 * 1024) // 64KB buffer
                            } else {
                                null
                            }
                        }

                        if (data != null && bufferedStream != null) {
                            bufferedStream.write(data)
                        }

                        // Periodic flush to ensure data is written (every ~1 second worth of data)
                        if (writeQueue.isEmpty() && bufferedStream != null) {
                            bufferedStream.flush()
                        }

                        // Exit if recording stopped and queue is drained
                        if (!isRecordingActive.get() && writeQueue.isEmpty() && outputStreamHolder.get() == null) {
                            android.util.Log.d("RecordingDataSource", "Writer thread exiting - recording stopped")
                            break
                        }
                    } catch (e: InterruptedException) {
                        android.util.Log.d("RecordingDataSource", "Writer thread interrupted")
                        break
                    } catch (e: Exception) {
                        android.util.Log.e("RecordingDataSource", "Error in writer thread", e)
                    }
                }

                // Final flush
                try {
                    bufferedStream?.flush()
                } catch (e: Exception) {
                    android.util.Log.e("RecordingDataSource", "Error flushing final buffer", e)
                }
                android.util.Log.d("RecordingDataSource", "Writer thread ended")
            }, "RecordingWriter").apply {
                priority = Thread.MIN_PRIORITY // Low priority to not interfere with audio
            }
        }
    }
}
