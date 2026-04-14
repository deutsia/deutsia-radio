package com.opensource.i2pradio.util

import android.util.Log
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * Resolves playlist pointer URLs (.pls / .m3u / .asx / .xspf) into direct stream URLs.
 *
 * URL resolution is kept strictly separate from format/codec detection:
 * this class only answers the question "where is the real stream?". It does
 * NOT decide the ExoPlayer source type - that happens downstream using
 * additional signals (codec hint, hls hint, URL extension, content-type).
 *
 * Resolution is bounded by MAX_DEPTH so chained pointers (.pls -> .m3u ->
 * stream) work, but we abort before infinite recursion. The same OkHttpClient
 * supplied by the caller is used so Tor / I2P / custom proxy settings remain
 * consistent with subsequent playback and recording traffic.
 */
object PlaylistResolver {

    private const val TAG = "PlaylistResolver"

    /** Maximum chained pointer resolutions (pls -> m3u -> stream is 2 hops). */
    const val MAX_DEPTH: Int = 2

    private const val FETCH_TIMEOUT_SECONDS = 15L
    private const val MAX_PLAYLIST_BYTES = 256 * 1024L

    /**
     * Result of a resolution attempt.
     *
     * - [Resolved]: returned URL is a direct stream (possibly same as input
     *   if no resolution was needed).
     * - [Failed]: URL was a pointer but couldn't be parsed / fetched.
     */
    sealed class Result {
        data class Resolved(val url: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Resolve a URL that may be a playlist pointer into a direct stream URL.
     *
     * This performs blocking I/O and MUST be called from a worker thread.
     *
     * @param originalUrl URL as provided by the station source (may already
     *                    be a direct stream).
     * @param httpClient  Shared OkHttpClient used for playback/recording.
     *                    Critical: reusing this client keeps the proxy route
     *                    consistent with downstream traffic.
     * @return [Result.Resolved] with the final direct URL, or [Result.Failed]
     *         if resolution failed.
     */
    fun resolve(originalUrl: String, httpClient: OkHttpClient): Result {
        return resolveInternal(originalUrl, httpClient, depth = 0)
    }

    private fun resolveInternal(url: String, httpClient: OkHttpClient, depth: Int): Result {
        if (depth > MAX_DEPTH) {
            Log.w(TAG, "Exceeded max resolution depth ($MAX_DEPTH) for $url")
            return Result.Failed("pointer chain too deep")
        }

        val extHint = extensionHint(url)
        // If the URL has a clearly-direct extension, short-circuit and skip
        // the network round-trip. Anything ambiguous (extensionless, .php,
        // ?format=pls) falls through to a content-type probe.
        if (extHint == PointerFormat.NONE && !hasAmbiguousExtension(url)) {
            return Result.Resolved(url)
        }

        // For known pointer extensions, no need to probe - just download.
        val formatFromExt = extHint
        val client = httpClient.newBuilder()
            // Short read timeout for playlist fetches; the shared client keeps
            // read timeout at 0 for continuous audio streaming.
            .readTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "DeutsiaRadio/1.0")
            .header("Accept", "*/*")
            .build()

        val body: String
        val contentType: String
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.Failed("HTTP ${response.code}")
                }
                contentType = response.header("Content-Type").orEmpty().lowercase()
                val format = formatFromExt.takeUnless { it == PointerFormat.NONE }
                    ?: formatFromContentType(contentType)
                    ?: return Result.Resolved(url)  // Not a recognised pointer

                body = readBoundedBody(response)
                    ?: return Result.Failed("empty playlist body")

                // HLS masquerading as .m3u: if content-type is m3u-ish and body
                // looks like HLS, DO NOT resolve - downstream code will handle
                // it via HlsMediaSource once the hls signal is recognised.
                if (format == PointerFormat.M3U && looksLikeHls(body)) {
                    return Result.Resolved(url)
                }

                val parsed = when (format) {
                    PointerFormat.PLS -> parsePls(body)
                    PointerFormat.M3U -> parseM3u(body)
                    PointerFormat.ASX -> parseAsx(body)
                    PointerFormat.XSPF -> parseXspf(body)
                    PointerFormat.NONE -> null
                } ?: return Result.Failed("no entries in ${format.name} playlist")

                val next = absolutise(url, parsed)
                Log.d(TAG, "Resolved ${format.name} pointer $url -> $next (depth=$depth)")
                return resolveInternal(next, httpClient, depth + 1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Playlist fetch failed for $url: ${e.message}")
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------------
    // Format detection
    // ---------------------------------------------------------------------

    private enum class PointerFormat { NONE, PLS, M3U, ASX, XSPF }

    private fun extensionHint(url: String): PointerFormat {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".pls") -> PointerFormat.PLS
            // .m3u8 is HLS, NOT a playlist pointer - leave it alone.
            path.endsWith(".m3u") -> PointerFormat.M3U
            path.endsWith(".asx") ||
                path.endsWith(".wax") ||
                path.endsWith(".wvx") -> PointerFormat.ASX
            path.endsWith(".xspf") -> PointerFormat.XSPF
            else -> PointerFormat.NONE
        }
    }

    private fun hasAmbiguousExtension(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val hasQuery = '?' in url
        val lastDot = path.lastIndexOf('.')
        val lastSlash = path.lastIndexOf('/')
        val extIsUnknown = lastDot < 0 || lastDot < lastSlash
        val knownAudio = path.endsWith(".mp3") || path.endsWith(".aac") ||
            path.endsWith(".ogg") || path.endsWith(".opus") ||
            path.endsWith(".flac") || path.endsWith(".m4a") ||
            path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            path.endsWith(".ts") || path.endsWith(".wav")
        // Extensionless, query-heavy (e.g. ?format=pls), or script extensions
        // (.php / .aspx) should be probed via Content-Type.
        return !knownAudio && (extIsUnknown || hasQuery ||
            path.endsWith(".php") || path.endsWith(".aspx"))
    }

    private fun formatFromContentType(contentType: String): PointerFormat? {
        return when {
            contentType.contains("audio/x-scpls") ||
                contentType.contains("application/pls+xml") -> PointerFormat.PLS
            contentType.contains("audio/x-mpegurl") ||
                contentType.contains("application/x-mpegurl") ||
                contentType.contains("application/vnd.apple.mpegurl") -> PointerFormat.M3U
            contentType.contains("video/x-ms-asf") ||
                contentType.contains("video/x-ms-wax") ||
                contentType.contains("video/x-ms-wvx") -> PointerFormat.ASX
            contentType.contains("application/xspf+xml") -> PointerFormat.XSPF
            else -> null
        }
    }

    private fun looksLikeHls(body: String): Boolean {
        // A real HLS playlist has #EXTM3U AND at least one segment / stream
        // directive. A plain .m3u pointer is just a list of URLs.
        if (!body.trimStart().startsWith("#EXTM3U")) return false
        return body.contains("#EXT-X-STREAM-INF") ||
            body.contains("#EXTINF") ||
            body.contains("#EXT-X-TARGETDURATION") ||
            body.contains("#EXT-X-MEDIA-SEQUENCE")
    }

    // ---------------------------------------------------------------------
    // Parsers
    // ---------------------------------------------------------------------

    /**
     * Parse a PLS file. Picks File1= if present, otherwise the first FileN=.
     */
    internal fun parsePls(body: String): String? {
        var firstAny: String? = null
        var file1: String? = null
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("File", ignoreCase = true)) return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (value.isEmpty()) return@forEach
            if (key.equals("File1", ignoreCase = true)) {
                file1 = value
            }
            if (firstAny == null && key.length > 4 &&
                key.substring(4).all { it.isDigit() }) {
                firstAny = value
            }
        }
        return file1 ?: firstAny
    }

    /**
     * Parse an M3U file (non-HLS). First line that looks like a URL wins.
     * Comment lines (# ...) are skipped.
     */
    internal fun parseM3u(body: String): String? {
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#")) return@forEach
            if (line.startsWith("http://", ignoreCase = true) ||
                line.startsWith("https://", ignoreCase = true) ||
                line.startsWith("mms://", ignoreCase = true) ||
                line.startsWith("rtsp://", ignoreCase = true)) {
                return line
            }
        }
        return null
    }

    /**
     * Parse an ASX / WAX / WVX file. Finds the first <Ref href="..."/> inside
     * the first <Entry>, falling back to the first <Ref> anywhere.
     */
    internal fun parseAsx(body: String): String? {
        return parseXmlHrefOrLocation(body, preferredTag = "ref", hrefAttr = "href")
    }

    /**
     * Parse an XSPF file. Finds the first <location> inside <track>.
     */
    internal fun parseXspf(body: String): String? {
        return parseXmlHrefOrLocation(body, preferredTag = "location", hrefAttr = null)
    }

    private fun parseXmlHrefOrLocation(
        body: String,
        preferredTag: String,
        hrefAttr: String?
    ): String? {
        return try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(StringReader(body))
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG &&
                    parser.name.equals(preferredTag, ignoreCase = true)) {
                    if (hrefAttr != null) {
                        for (i in 0 until parser.attributeCount) {
                            if (parser.getAttributeName(i).equals(hrefAttr, ignoreCase = true)) {
                                val v = parser.getAttributeValue(i)?.trim()
                                if (!v.isNullOrEmpty()) return v
                            }
                        }
                    } else {
                        val text = parser.nextText()?.trim()
                        if (!text.isNullOrEmpty()) return text
                    }
                }
                event = parser.next()
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "XML parse failed: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun absolutise(baseUrl: String, resolved: String): String {
        if (resolved.startsWith("http://", ignoreCase = true) ||
            resolved.startsWith("https://", ignoreCase = true) ||
            resolved.startsWith("mms://", ignoreCase = true) ||
            resolved.startsWith("rtsp://", ignoreCase = true)) {
            return resolved
        }
        return try {
            java.net.URI(baseUrl).resolve(resolved).toString()
        } catch (e: Exception) {
            resolved
        }
    }

    private fun readBoundedBody(response: Response): String? {
        val body = response.body ?: return null
        val stream = body.byteStream()
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n <= 0) break
            total += n
            if (total > MAX_PLAYLIST_BYTES) {
                Log.w(TAG, "Playlist exceeded ${MAX_PLAYLIST_BYTES / 1024}KB - truncating")
                out.write(buffer, 0, (buffer.size - (total - MAX_PLAYLIST_BYTES).toInt()).coerceAtLeast(0))
                break
            }
            out.write(buffer, 0, n)
        }
        if (out.size() == 0) return null
        return out.toString(Charsets.UTF_8.name())
    }
}
