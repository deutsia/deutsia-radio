package com.opensource.i2pradio

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
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
 * Optimized for minimal overhead when not recording to prevent audio glitching:
 * - Uses a buffer pool to reduce allocations
 * - Volatile boolean check is extremely fast when not recording
 * - No synchronization on the hot path when not recording
 */
class RecordingDataSource(
    private val upstream: DataSource,
    private val writeQueue: ArrayBlockingQueue<ByteArray>,
    private val isRecordingActive: AtomicBoolean,
    private val bufferPool: ConcurrentLinkedQueue<ByteArray>
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

        // Fast path: skip everything if not recording (just a volatile read)
        // This ensures minimal overhead during normal playback
        if (bytesRead > 0 && isRecordingActive.get()) {
            queueForRecording(buffer, offset, bytesRead)
        }

        return bytesRead
    }

    /**
     * Queue data for async writing to disk.
     * Uses a buffer pool to reduce allocations and GC pressure.
     */
    private fun queueForRecording(buffer: ByteArray, offset: Int, bytesRead: Int) {
        try {
            // Try to get a buffer from the pool, or create a new one
            val dataCopy = if (bytesRead <= BUFFER_SIZE) {
                bufferPool.poll() ?: ByteArray(BUFFER_SIZE)
            } else {
                // Large read - allocate exact size (rare case)
                ByteArray(bytesRead)
            }

            System.arraycopy(buffer, offset, dataCopy, 0, bytesRead)

            // Create exact-sized copy for the queue
            val exactCopy = if (bytesRead == dataCopy.size) {
                dataCopy
            } else {
                // Return pooled buffer and create exact copy
                if (dataCopy.size == BUFFER_SIZE && bufferPool.size < MAX_POOL_SIZE) {
                    bufferPool.offer(dataCopy)
                }
                dataCopy.copyOf(bytesRead)
            }

            // Use offer() which is non-blocking - if queue is full, drop this chunk
            if (!writeQueue.offer(exactCopy)) {
                android.util.Log.w("RecordingDataSource", "Write queue full, dropping chunk")
            }
        } catch (e: Exception) {
            android.util.Log.e("RecordingDataSource", "Error queueing recording data", e)
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        upstream.close()
        // Don't close the recording stream here - it's managed by RadioService
    }

    /**
     * Factory for creating RecordingDataSource instances.
     * Uses a shared write queue and buffer pool for async disk writes.
     */
    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val writeQueue: ArrayBlockingQueue<ByteArray>,
        private val isRecordingActive: AtomicBoolean
    ) : DataSource.Factory {
        // Shared buffer pool across all data source instances
        private val bufferPool = ConcurrentLinkedQueue<ByteArray>()

        override fun createDataSource(): DataSource {
            return RecordingDataSource(
                upstreamFactory.createDataSource(),
                writeQueue,
                isRecordingActive,
                bufferPool
            )
        }
    }

    companion object {
        // Standard buffer size for audio chunks - matches typical network read sizes
        private const val BUFFER_SIZE = 8192
        // Maximum number of buffers to keep in the pool
        private const val MAX_POOL_SIZE = 50

        /**
         * Creates and starts a background writer thread that drains the write queue
         * and writes data to the output stream.
         *
         * Uses a larger buffer for more efficient disk writes and reduces
         * the frequency of flush operations.
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
                var bytesWrittenSinceFlush = 0
                val flushThreshold = 256 * 1024 // Flush every 256KB to reduce disk I/O frequency

                while (true) {
                    try {
                        // Poll with timeout to check if we should exit
                        val data = writeQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)

                        // Check if output stream has changed
                        val newOutputStream = outputStreamHolder.get()
                        if (newOutputStream != currentOutputStream) {
                            // Flush and update the buffered stream
                            bufferedStream?.flush()
                            bytesWrittenSinceFlush = 0
                            currentOutputStream = newOutputStream
                            bufferedStream = if (newOutputStream != null) {
                                BufferedOutputStream(newOutputStream, 256 * 1024) // 256KB buffer for efficient disk writes
                            } else {
                                null
                            }
                        }

                        if (data != null && bufferedStream != null) {
                            bufferedStream.write(data)
                            bytesWrittenSinceFlush += data.size

                            // Periodic flush based on bytes written, not queue state
                            // This is more efficient than flushing on every empty queue
                            if (bytesWrittenSinceFlush >= flushThreshold) {
                                bufferedStream.flush()
                                bytesWrittenSinceFlush = 0
                            }
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
