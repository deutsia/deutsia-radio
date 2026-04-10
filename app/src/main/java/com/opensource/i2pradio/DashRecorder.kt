package com.opensource.i2pradio

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records a DASH (Dynamic Adaptive Streaming over HTTP) audio stream by
 * parsing MPD manifests and downloading fMP4/CMAF segments (init + media),
 * concatenating the raw segment bytes into the provided output stream.
 *
 * Supports:
 * - Static (VOD) and dynamic (live) MPD manifests.
 * - SegmentTemplate with $Number$ and $Time$ substitution.
 * - SegmentTemplate with SegmentTimeline.
 * - Initialization segments (init templates).
 * - Mid-recording stream switching via [switchRequested] / [newStreamUrlProvider].
 * - Cooperative cancellation via [isActive].
 *
 * Selects the highest-bandwidth audio AdaptationSet/Representation.
 */
class DashRecorder(
    private val httpClientProvider: () -> OkHttpClient,
    private val isActive: AtomicBoolean,
    private val switchRequested: AtomicBoolean,
    private val newStreamUrlProvider: () -> String?,
    private val activeCallSetter: (Call?) -> Unit,
) {
    data class Representation(
        val id: String,
        val bandwidth: Long,
        val mimeType: String,
        val codecs: String,
        val audioSamplingRate: String,
    )

    data class SegmentTemplateInfo(
        val initTemplate: String?,
        val mediaTemplate: String?,
        val startNumber: Long,
        val timescale: Long,
        val timelineEntries: List<TimelineEntry>,
    )

    data class TimelineEntry(
        val time: Long?,
        val duration: Long,
        val repeatCount: Int,
    )

    data class ParsedMpd(
        val isDynamic: Boolean,
        val minUpdatePeriodSec: Double,
        val mediaPresentationDurationSec: Double,
        val baseUrl: String?,
        val representation: Representation?,
        val segmentTemplate: SegmentTemplateInfo?,
    )

    data class RecordResult(
        val bytesWritten: Long,
        val segmentExtension: String,
        val completedNormally: Boolean,
    )

    fun record(
        initialUrl: String,
        outputStream: BufferedOutputStream,
        onProgress: (Long) -> Unit,
    ): RecordResult {
        var totalBytesWritten = 0L
        var currentUrl = initialUrl
        var client = httpClientProvider()
        var initSegmentWritten = false
        var currentSegmentNumber = -1L
        var currentSegmentTime = 0L
        var lastFlushBytes = 0L
        val flushInterval = 64 * 1024L
        var consecutiveFailures = 0
        val maxConsecutiveFailures = 5

        try {
            while (isActive.get() && !Thread.currentThread().isInterrupted) {
                if (switchRequested.compareAndSet(true, false)) {
                    val newUrl = newStreamUrlProvider()
                    if (newUrl != null && newUrl.isNotEmpty()) {
                        android.util.Log.d(TAG, "Switching DASH recording to: $newUrl")
                        currentUrl = newUrl
                        client = httpClientProvider()
                        initSegmentWritten = false
                        currentSegmentNumber = -1L
                        currentSegmentTime = 0L
                        consecutiveFailures = 0
                    }
                }

                val mpdText = fetchUrlAsText(client, currentUrl)
                if (mpdText == null) {
                    consecutiveFailures++
                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        android.util.Log.e(TAG, "Giving up after $maxConsecutiveFailures MPD fetch failures")
                        return RecordResult(totalBytesWritten, EXTENSION, false)
                    }
                    sleepInterruptibly(2000)
                    continue
                }
                consecutiveFailures = 0

                val parsed = parseMpd(mpdText, currentUrl)
                if (parsed.representation == null || parsed.segmentTemplate == null) {
                    android.util.Log.e(TAG, "MPD has no usable audio representation or segment template")
                    return RecordResult(totalBytesWritten, EXTENSION, false)
                }

                val rep = parsed.representation
                val template = parsed.segmentTemplate
                val baseUrl = parsed.baseUrl ?: ""

                // Download init segment
                if (!initSegmentWritten && template.initTemplate != null) {
                    val initPath = template.initTemplate
                        .replace("\$RepresentationID\$", rep.id)
                        .replace("\$Bandwidth\$", rep.bandwidth.toString())
                    val initUrl = resolveUrl(currentUrl, baseUrl, initPath)
                    try {
                        val initBuffer = ByteArrayOutputStream()
                        val initBytes = downloadSegment(client, initUrl, initBuffer)
                        initBuffer.writeTo(outputStream)
                        totalBytesWritten += initBytes
                        initSegmentWritten = true
                        android.util.Log.d(TAG, "Downloaded DASH init segment: $initBytes bytes from $initUrl")
                        onProgress(totalBytesWritten)
                    } catch (e: IOException) {
                        android.util.Log.w(TAG, "Init segment download failed: ${e.message}")
                        sleepInterruptibly(2000)
                        continue
                    }
                }

                if (currentSegmentNumber < 0) {
                    currentSegmentNumber = template.startNumber
                }

                // Download media segments
                var newSegmentsDownloaded = 0

                if (template.timelineEntries.isNotEmpty()) {
                    // SegmentTimeline mode
                    var timePos = 0L
                    for (entry in template.timelineEntries) {
                        val startTime = entry.time ?: timePos
                        timePos = startTime
                        for (r in 0..entry.repeatCount) {
                            if (!isActive.get() || switchRequested.get() || Thread.currentThread().isInterrupted) break

                            if (timePos < currentSegmentTime) {
                                timePos += entry.duration
                                continue
                            }

                            val mediaPath = template.mediaTemplate
                                ?.replace("\$RepresentationID\$", rep.id)
                                ?.replace("\$Bandwidth\$", rep.bandwidth.toString())
                                ?.replace("\$Number\$", currentSegmentNumber.toString())
                                ?.replace("\$Time\$", timePos.toString())
                                ?: continue
                            val segmentUrl = resolveUrl(currentUrl, baseUrl, mediaPath)

                            try {
                                val segBuffer = ByteArrayOutputStream()
                                val bytes = downloadSegment(client, segmentUrl, segBuffer)
                                segBuffer.writeTo(outputStream)
                                totalBytesWritten += bytes
                                newSegmentsDownloaded++
                                onProgress(totalBytesWritten)
                            } catch (e: IOException) {
                                if (isActive.get() && !switchRequested.get()) {
                                    android.util.Log.w(TAG, "Segment download failed: ${e.message}")
                                }
                            }

                            currentSegmentTime = timePos + entry.duration
                            currentSegmentNumber++
                            timePos += entry.duration

                            if (totalBytesWritten - lastFlushBytes >= flushInterval) {
                                try { outputStream.flush() } catch (_: IOException) {}
                                lastFlushBytes = totalBytesWritten
                            }
                        }
                    }
                } else if (template.mediaTemplate != null) {
                    // Simple $Number$ mode - download a batch of segments
                    val batchSize = 5
                    for (i in 0 until batchSize) {
                        if (!isActive.get() || switchRequested.get() || Thread.currentThread().isInterrupted) break

                        val mediaPath = template.mediaTemplate
                            .replace("\$RepresentationID\$", rep.id)
                            .replace("\$Bandwidth\$", rep.bandwidth.toString())
                            .replace("\$Number\$", currentSegmentNumber.toString())
                            .replace("\$Time\$", currentSegmentTime.toString())
                        val segmentUrl = resolveUrl(currentUrl, baseUrl, mediaPath)

                        try {
                            val segBuffer = ByteArrayOutputStream()
                            val bytes = downloadSegment(client, segmentUrl, segBuffer)
                            if (bytes == 0L) break // no more segments
                            segBuffer.writeTo(outputStream)
                            totalBytesWritten += bytes
                            newSegmentsDownloaded++
                            currentSegmentNumber++
                            onProgress(totalBytesWritten)
                        } catch (e: IOException) {
                            if (isActive.get() && !switchRequested.get()) {
                                android.util.Log.w(TAG, "Segment $currentSegmentNumber download failed: ${e.message}")
                            }
                            break
                        }

                        if (totalBytesWritten - lastFlushBytes >= flushInterval) {
                            try { outputStream.flush() } catch (_: IOException) {}
                            lastFlushBytes = totalBytesWritten
                        }
                    }
                }

                // Static (VOD) manifest - we're done when no new segments
                if (!parsed.isDynamic && newSegmentsDownloaded == 0) {
                    android.util.Log.d(TAG, "DASH static manifest completed")
                    try { outputStream.flush() } catch (_: IOException) {}
                    return RecordResult(totalBytesWritten, EXTENSION, true)
                }

                // For live manifests, wait before re-fetching MPD
                val sleepMs = if (parsed.isDynamic) {
                    if (newSegmentsDownloaded == 0) {
                        (parsed.minUpdatePeriodSec * 1000).toLong().coerceIn(500L, 10_000L)
                    } else {
                        (parsed.minUpdatePeriodSec * 500).toLong().coerceIn(250L, 5_000L)
                    }
                } else {
                    1000L
                }
                sleepInterruptibly(sleepMs)
            }
        } catch (e: InterruptedException) {
            android.util.Log.d(TAG, "DASH recorder interrupted")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "DASH recorder error: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            try { outputStream.flush() } catch (_: IOException) {}
            activeCallSetter(null)
        }

        return RecordResult(totalBytesWritten, EXTENSION, true)
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
                    android.util.Log.w(TAG, "MPD fetch HTTP ${response.code}: $url")
                    return null
                }
                response.body?.string()
            }
        } catch (e: IOException) {
            if (isActive.get() && !switchRequested.get()) {
                android.util.Log.w(TAG, "MPD fetch error: ${e.message}")
            }
            null
        }
    }

    private fun downloadSegment(
        client: OkHttpClient,
        url: String,
        outputStream: java.io.OutputStream,
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
        private const val TAG = "DashRecorder"
        private const val EXTENSION = "m4a"

        fun resolveUrl(mpdUrl: String, baseUrl: String, path: String): String {
            if (path.startsWith("http://", ignoreCase = true) ||
                path.startsWith("https://", ignoreCase = true)
            ) {
                return path
            }
            val fullPath = if (baseUrl.isNotEmpty() && !baseUrl.startsWith("http")) {
                "$baseUrl$path"
            } else if (baseUrl.startsWith("http")) {
                return try {
                    URI(baseUrl).resolve(path).toString()
                } catch (e: Exception) { path }
            } else {
                path
            }
            return try {
                URI(mpdUrl).resolve(fullPath).toString()
            } catch (e: Exception) {
                fullPath
            }
        }

        /**
         * Lightweight XML parser for DASH MPD manifests.
         * Extracts the highest-bandwidth audio Representation and its SegmentTemplate.
         * Does not depend on any XML library beyond basic string operations.
         */
        fun parseMpd(xml: String, mpdUrl: String): ParsedMpd {
            val isDynamic = xml.contains("type=\"dynamic\"", ignoreCase = true)

            val minUpdatePeriod = Regex("""minimumUpdatePeriod="PT([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(xml)?.let { parseDuration(it.groupValues[1]) } ?: 2.0

            val mediaDuration = Regex("""mediaPresentationDuration="PT([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(xml)?.let { parseDuration(it.groupValues[1]) } ?: 0.0

            // Extract BaseURL at MPD level
            val mpdBaseUrl = Regex("""<BaseURL>([^<]+)</BaseURL>""", RegexOption.IGNORE_CASE)
                .find(xml)?.groupValues?.get(1)

            // Find audio AdaptationSets
            val adaptationSets = findAudioAdaptationSets(xml)

            var bestRep: Representation? = null
            var bestTemplate: SegmentTemplateInfo? = null
            var bestBandwidth = -1L

            for (adaptationXml in adaptationSets) {
                // SegmentTemplate may be at AdaptationSet or Representation level
                val setTemplate = parseSegmentTemplate(adaptationXml)

                val representations = findRepresentations(adaptationXml)
                for (repXml in representations) {
                    val id = extractAttr(repXml, "id") ?: "1"
                    val bw = extractAttr(repXml, "bandwidth")?.toLongOrNull() ?: 0L
                    val mime = extractAttr(repXml, "mimeType")
                        ?: extractAttr(adaptationXml, "mimeType") ?: "audio/mp4"
                    val codecs = extractAttr(repXml, "codecs")
                        ?: extractAttr(adaptationXml, "codecs") ?: ""
                    val sampleRate = extractAttr(repXml, "audioSamplingRate")
                        ?: extractAttr(adaptationXml, "audioSamplingRate") ?: ""

                    if (bw > bestBandwidth) {
                        bestBandwidth = bw
                        bestRep = Representation(id, bw, mime, codecs, sampleRate)
                        // Representation-level template overrides AdaptationSet-level
                        bestTemplate = parseSegmentTemplate(repXml) ?: setTemplate
                    }
                }

                // If no Representation found but AdaptationSet has template info
                if (representations.isEmpty() && setTemplate != null && bestTemplate == null) {
                    bestRep = Representation("1", 0, "audio/mp4", "", "")
                    bestTemplate = setTemplate
                }
            }

            return ParsedMpd(
                isDynamic = isDynamic,
                minUpdatePeriodSec = minUpdatePeriod,
                mediaPresentationDurationSec = mediaDuration,
                baseUrl = mpdBaseUrl,
                representation = bestRep,
                segmentTemplate = bestTemplate,
            )
        }

        private fun findAudioAdaptationSets(xml: String): List<String> {
            val results = mutableListOf<String>()
            // Match <AdaptationSet ... contentType="audio" ...> blocks
            val pattern = Regex("""<AdaptationSet\b[^>]*>[\s\S]*?</AdaptationSet>""", RegexOption.IGNORE_CASE)
            for (match in pattern.findAll(xml)) {
                val block = match.value
                val tag = block.substringBefore(">")
                val contentType = extractAttr(tag, "contentType")?.lowercase()
                val mimeType = extractAttr(tag, "mimeType")?.lowercase()
                if (contentType == "audio" || mimeType?.startsWith("audio/") == true) {
                    results.add(block)
                }
            }
            // If no content type, try matching by mimeType in Representations
            if (results.isEmpty()) {
                for (match in pattern.findAll(xml)) {
                    val block = match.value
                    if (block.contains("audio/", ignoreCase = true)) {
                        results.add(block)
                    }
                }
            }
            return results
        }

        private fun findRepresentations(adaptationXml: String): List<String> {
            val results = mutableListOf<String>()
            val pattern = Regex("""<Representation\b[^>]*(?:/>|>[\s\S]*?</Representation>)""", RegexOption.IGNORE_CASE)
            for (match in pattern.findAll(adaptationXml)) {
                results.add(match.value)
            }
            return results
        }

        private fun parseSegmentTemplate(xml: String): SegmentTemplateInfo? {
            val templateMatch = Regex("""<SegmentTemplate\b([^>]*(?:/>|>[\s\S]*?</SegmentTemplate>))""", RegexOption.IGNORE_CASE)
                .find(xml) ?: return null
            val templateBlock = templateMatch.value
            val tagAttrs = templateBlock.substringBefore(">")

            val init = extractAttr(tagAttrs, "initialization")
            val media = extractAttr(tagAttrs, "media")
            val startNumber = extractAttr(tagAttrs, "startNumber")?.toLongOrNull() ?: 1L
            val timescale = extractAttr(tagAttrs, "timescale")?.toLongOrNull() ?: 1L

            // Parse SegmentTimeline entries
            val timelineEntries = mutableListOf<TimelineEntry>()
            val timelineMatch = Regex("""<SegmentTimeline>([\s\S]*?)</SegmentTimeline>""", RegexOption.IGNORE_CASE)
                .find(templateBlock)
            if (timelineMatch != null) {
                val sPattern = Regex("""<S\b([^>]*)/>""", RegexOption.IGNORE_CASE)
                for (sMatch in sPattern.findAll(timelineMatch.groupValues[1])) {
                    val sAttrs = sMatch.groupValues[1]
                    val t = extractAttr(sAttrs, "t")?.toLongOrNull()
                    val d = extractAttr(sAttrs, "d")?.toLongOrNull() ?: continue
                    val r = extractAttr(sAttrs, "r")?.toIntOrNull() ?: 0
                    timelineEntries.add(TimelineEntry(t, d, r))
                }
            }

            return SegmentTemplateInfo(
                initTemplate = init,
                mediaTemplate = media,
                startNumber = startNumber,
                timescale = timescale,
                timelineEntries = timelineEntries,
            )
        }

        private fun extractAttr(xml: String, attrName: String): String? {
            val pattern = Regex("""$attrName\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            return pattern.find(xml)?.groupValues?.get(1)
        }

        private fun parseDuration(ptStr: String): Double {
            // Parse ISO 8601 duration fragments like "4S", "2.5S", "1M30S", "1H2M3S"
            var total = 0.0
            val hours = Regex("""(\d+(?:\.\d+)?)H""").find(ptStr)
            val minutes = Regex("""(\d+(?:\.\d+)?)M""").find(ptStr)
            val seconds = Regex("""(\d+(?:\.\d+)?)S""").find(ptStr)
            if (hours != null) total += hours.groupValues[1].toDouble() * 3600
            if (minutes != null) total += minutes.groupValues[1].toDouble() * 60
            if (seconds != null) total += seconds.groupValues[1].toDouble()
            return if (total > 0) total else 2.0
        }

        fun mimeTypeForExtension(): String = "audio/mp4"
    }
}
