package com.opensource.i2pradio

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records an HLS (HTTP Live Streaming) audio stream by repeatedly fetching
 * the media playlist and downloading new segments, concatenating the raw
 * segment bytes into the provided output stream.
 *
 * Supports:
 * - Master playlists (selects the highest-bandwidth variant).
 * - Media playlists, both VOD (#EXT-X-ENDLIST) and live.
 * - Mid-recording stream switching via [switchRequested] / [newStreamUrlProvider].
 * - Cooperative cancellation via [isActive].
 *
 * Segment deduplication uses the media sequence number so that re-polling a
 * live playlist only appends newly-added segments.
 */
class HlsRecorder(
    private val httpClientProvider: () -> OkHttpClient,
    private val isActive: AtomicBoolean,
    private val switchRequested: AtomicBoolean,
    private val newStreamUrlProvider: () -> String?,
    private val activeCallSetter: (Call?) -> Unit,
) {
    data class Variant(val uri: String, val bandwidth: Long)

    data class ParsedPlaylist(
        val isMaster: Boolean,
        val variants: List<Variant>,
        val segments: List<String>,
        val targetDurationSec: Double,
        val mediaSequence: Long,
        val hasEndList: Boolean,
    )

    data class RecordResult(
        val bytesWritten: Long,
        val segmentExtension: String,
        val completedNormally: Boolean,
    )

    /**
     * Runs the HLS recording loop until [isActive] is cleared, a switch fails,
     * a VOD playlist ends, or an unrecoverable error occurs.
     */
    fun record(
        initialUrl: String,
        outputStream: BufferedOutputStream,
        onProgress: (Long) -> Unit,
    ): RecordResult {
        var totalBytesWritten = 0L
        var currentUrl = initialUrl
        var client = httpClientProvider()
        var segmentExtension: String? = null
        val downloadedSegmentKeys = LinkedHashSet<String>()
        var lastFlushBytes = 0L
        val flushInterval = 64 * 1024L
        var consecutivePlaylistFailures = 0
        val maxConsecutivePlaylistFailures = 5

        try {
            while (isActive.get() && !Thread.currentThread().isInterrupted) {
                if (switchRequested.compareAndSet(true, false)) {
                    val newUrl = newStreamUrlProvider()
                    if (newUrl != null && newUrl.isNotEmpty()) {
                        android.util.Log.d(TAG, "Switching HLS recording to: $newUrl")
                        currentUrl = newUrl
                        client = httpClientProvider()
                        downloadedSegmentKeys.clear()
                        consecutivePlaylistFailures = 0
                    }
                }

                val playlistText = fetchUrlAsText(client, currentUrl)
                if (playlistText == null) {
                    consecutivePlaylistFailures++
                    if (consecutivePlaylistFailures >= maxConsecutivePlaylistFailures) {
                        android.util.Log.e(TAG, "Giving up after $maxConsecutivePlaylistFailures playlist fetch failures")
                        return RecordResult(totalBytesWritten, segmentExtension ?: DEFAULT_EXTENSION, false)
                    }
                    sleepInterruptibly(2000)
                    continue
                }
                consecutivePlaylistFailures = 0

                val parsed = parsePlaylist(playlistText)

                val mediaPlaylistUrl: String
                val mediaPlaylist: ParsedPlaylist
                if (parsed.isMaster) {
                    val variant = parsed.variants.maxByOrNull { it.bandwidth }
                    if (variant == null) {
                        android.util.Log.e(TAG, "Master playlist has no variants")
                        return RecordResult(totalBytesWritten, segmentExtension ?: DEFAULT_EXTENSION, false)
                    }
                    mediaPlaylistUrl = resolveUrl(currentUrl, variant.uri)
                    android.util.Log.d(TAG, "Selected HLS variant bw=${variant.bandwidth}: $mediaPlaylistUrl")
                    val mediaText = fetchUrlAsText(client, mediaPlaylistUrl)
                    if (mediaText == null) {
                        sleepInterruptibly(2000)
                        continue
                    }
                    mediaPlaylist = parsePlaylist(mediaText)
                } else {
                    mediaPlaylistUrl = currentUrl
                    mediaPlaylist = parsed
                }

                var segmentIndex = mediaPlaylist.mediaSequence
                var newSegmentsDownloaded = 0
                for (segmentUri in mediaPlaylist.segments) {
                    if (!isActive.get() || switchRequested.get() || Thread.currentThread().isInterrupted) break

                    val key = "$segmentIndex:$segmentUri"
                    if (downloadedSegmentKeys.contains(key)) {
                        segmentIndex++
                        continue
                    }

                    if (segmentExtension == null) {
                        segmentExtension = detectSegmentExtension(segmentUri)
                        android.util.Log.d(TAG, "Detected HLS segment extension: $segmentExtension")
                    }

                    val segmentUrl = resolveUrl(mediaPlaylistUrl, segmentUri)
                    try {
                        val bytes = downloadSegment(client, segmentUrl, outputStream)
                        totalBytesWritten += bytes
                        newSegmentsDownloaded++
                        onProgress(totalBytesWritten)

                        if (totalBytesWritten - lastFlushBytes >= flushInterval) {
                            try {
                                outputStream.flush()
                            } catch (e: IOException) {
                                android.util.Log.w(TAG, "Flush error: ${e.message}")
                            }
                            lastFlushBytes = totalBytesWritten
                        }
                    } catch (e: IOException) {
                        if (isActive.get() && !switchRequested.get()) {
                            android.util.Log.w(TAG, "Segment download failed: ${e.message}")
                        }
                    }

                    downloadedSegmentKeys.add(key)
                    if (downloadedSegmentKeys.size > MAX_DEDUP_CACHE_SIZE) {
                        val iter = downloadedSegmentKeys.iterator()
                        val toRemove = downloadedSegmentKeys.size - MAX_DEDUP_CACHE_SIZE
                        repeat(toRemove) {
                            if (iter.hasNext()) {
                                iter.next()
                                iter.remove()
                            }
                        }
                    }
                    segmentIndex++
                }

                if (mediaPlaylist.hasEndList) {
                    android.util.Log.d(TAG, "HLS VOD stream completed (#EXT-X-ENDLIST)")
                    try {
                        outputStream.flush()
                    } catch (e: IOException) {
                        android.util.Log.w(TAG, "Final flush error: ${e.message}")
                    }
                    return RecordResult(totalBytesWritten, segmentExtension ?: DEFAULT_EXTENSION, true)
                }

                val targetMs = (mediaPlaylist.targetDurationSec * 1000).toLong()
                val sleepMs = if (newSegmentsDownloaded == 0) {
                    (targetMs / 2).coerceIn(500L, 10_000L)
                } else {
                    (targetMs / 2).coerceIn(250L, 5_000L)
                }
                sleepInterruptibly(sleepMs)
            }
        } catch (e: InterruptedException) {
            android.util.Log.d(TAG, "HLS recorder interrupted")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "HLS recorder error: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            try {
                outputStream.flush()
            } catch (e: IOException) {
                android.util.Log.w(TAG, "Final flush error: ${e.message}")
            }
            activeCallSetter(null)
        }

        return RecordResult(totalBytesWritten, segmentExtension ?: DEFAULT_EXTENSION, true)
    }

    private fun fetchUrlAsText(client: OkHttpClient, url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "DeutsiaRadio-Recorder/1.0")
            .header("Accept", "*/*")
            .build()
        val call = client.newCall(request)
        activeCallSetter(call)
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w(TAG, "Playlist fetch HTTP ${response.code}: $url")
                    return null
                }
                response.body?.string()
            }
        } catch (e: IOException) {
            if (isActive.get() && !switchRequested.get()) {
                android.util.Log.w(TAG, "Playlist fetch error: ${e.message}")
            }
            null
        }
    }

    private fun downloadSegment(
        client: OkHttpClient,
        url: String,
        outputStream: BufferedOutputStream,
    ): Long {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "DeutsiaRadio-Recorder/1.0")
            .header("Accept", "*/*")
            .build()
        val call = client.newCall(request)
        activeCallSetter(call)
        var bytesWritten = 0L
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Segment HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Segment has no body")
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (isActive.get() && !switchRequested.get() && !Thread.currentThread().isInterrupted) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                        bytesWritten += read
                    }
                }
            }
        }
        return bytesWritten
    }

    private fun sleepInterruptibly(ms: Long) {
        if (ms <= 0) return
        val end = System.currentTimeMillis() + ms
        while (isActive.get() && !switchRequested.get() && !Thread.currentThread().isInterrupted) {
            val remaining = end - System.currentTimeMillis()
            if (remaining <= 0) break
            try {
                Thread.sleep(remaining.coerceAtMost(200L))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    companion object {
        private const val TAG = "HlsRecorder"
        private const val MAX_DEDUP_CACHE_SIZE = 1000
        private const val DEFAULT_EXTENSION = "ts"

        fun parsePlaylist(text: String): ParsedPlaylist {
            val lines = text.lines()
            val segments = mutableListOf<String>()
            val variants = mutableListOf<Variant>()
            var isMaster = false
            var targetDuration = 6.0
            var mediaSequence = 0L
            var hasEndList = false
            var pendingBandwidth: Long? = null

            for (raw in lines) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> {}
                    line.startsWith("#EXT-X-STREAM-INF") -> {
                        isMaster = true
                        val bwMatch = Regex("BANDWIDTH=(\\d+)").find(line)
                        pendingBandwidth = bwMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    }
                    line.startsWith("#EXT-X-TARGETDURATION:") -> {
                        targetDuration = line.substringAfter(":").trim().toDoubleOrNull() ?: 6.0
                    }
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                        mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                    }
                    line.startsWith("#EXT-X-ENDLIST") -> {
                        hasEndList = true
                    }
                    line.startsWith("#") -> {
                        // Other tags - ignored
                    }
                    else -> {
                        if (pendingBandwidth != null) {
                            variants.add(Variant(line, pendingBandwidth!!))
                            pendingBandwidth = null
                        } else {
                            segments.add(line)
                        }
                    }
                }
            }

            return ParsedPlaylist(
                isMaster = isMaster,
                variants = variants,
                segments = segments,
                targetDurationSec = targetDuration,
                mediaSequence = mediaSequence,
                hasEndList = hasEndList,
            )
        }

        fun resolveUrl(baseUrl: String, relativeUri: String): String {
            if (relativeUri.startsWith("http://", ignoreCase = true) ||
                relativeUri.startsWith("https://", ignoreCase = true)
            ) {
                return relativeUri
            }
            return try {
                URI(baseUrl).resolve(relativeUri).toString()
            } catch (e: Exception) {
                relativeUri
            }
        }

        fun detectSegmentExtension(segmentUri: String): String {
            val path = try {
                val uri = URI(segmentUri)
                uri.path ?: segmentUri
            } catch (e: Exception) {
                segmentUri.substringBefore('?')
            }
            val lower = path.lowercase()
            return when {
                lower.endsWith(".ts") -> "ts"
                lower.endsWith(".aac") -> "aac"
                lower.endsWith(".mp3") -> "mp3"
                lower.endsWith(".m4s") -> "m4s"
                lower.endsWith(".mp4") -> "mp4"
                lower.endsWith(".m4a") -> "m4a"
                else -> DEFAULT_EXTENSION
            }
        }

        fun mimeTypeForExtension(extension: String): String {
            return when (extension.lowercase()) {
                "ts" -> "video/mp2t"
                "aac" -> "audio/aac"
                "mp3" -> "audio/mpeg"
                "m4s", "mp4", "m4a" -> "audio/mp4"
                else -> "video/mp2t"
            }
        }
    }
}
