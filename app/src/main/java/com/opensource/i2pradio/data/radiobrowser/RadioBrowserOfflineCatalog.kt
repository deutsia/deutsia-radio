package com.opensource.i2pradio.data.radiobrowser

import android.content.Context
import android.util.Log
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.Writer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Local station catalog used when the Radio Browser API is unavailable.
 *
 * The API backend occasionally (and, during the 2026 outage, persistently)
 * answers every request with `503 no available server`. Playback and saved
 * stations are unaffected, but browsing/search die because they need the
 * directory. The project's nightly database dumps are served from a separate
 * host that stays up, so we download the newest dump, reduce it to a compact
 * TSV file in app-private storage, and answer browse/search queries locally.
 *
 * The catalog is only downloaded after an API request has already failed.
 * It is refreshed at most once per day; a stale copy keeps working if the
 * backup host is unreachable. Download and queries use the caller's OkHttp
 * client so Tor/custom-proxy settings are honoured.
 */
object RadioBrowserOfflineCatalog {

    private const val TAG = "RadioBrowserOffline"

    private const val BACKUP_HOST = "https://backups.radio-browser.info/"
    private const val DUMP_REGEX = "radiobrowser_stations_[0-9]{8}_[0-9]{6}\\.json\\.gz"
    private const val CACHE_FILE = "radio_browser_catalog.tsv"
    private const val VERSION_FILE = "radio_browser_catalog.version"

    private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private const val FAILURE_BACKOFF_MS = 10L * 60L * 1000L
    private const val DOWNLOAD_CONNECT_TIMEOUT_SECONDS = 20L
    private const val DOWNLOAD_READ_TIMEOUT_SECONDS = 120L
    private const val MAX_DOWNLOAD_BYTES = 32L * 1024L * 1024L
    private const val MAX_STATIONS = 250_000

    private const val USER_AGENT =
        "DeutsiaRadio/1.0 (Android; +https://github.com/deutsia/i2pradio)"

    // Column order of the cached TSV. Values are sanitised so tabs/newlines
    // can never appear inside a field.
    private const val COL_UUID = 0
    private const val COL_NAME = 1
    private const val COL_URL = 2
    private const val COL_TAGS = 3
    private const val COL_COUNTRY_CODE = 4
    private const val COL_LANGUAGE_CODE = 5
    private const val COL_FAVICON = 6
    private const val COL_HOMEPAGE = 7
    private const val COLUMN_COUNT = 8

    private val refreshMutex = Mutex()

    @Volatile
    private var lastFailureAt = 0L

    fun cacheFile(context: Context): File = File(context.filesDir, CACHE_FILE)

    fun isCached(context: Context): Boolean = cacheFile(context).let {
        it.isFile && it.length() > 0L
    }

    /**
     * Ensure a usable catalog exists. Returns true when [cacheFile] can be
     * queried. Never throws: on failure it falls back to an existing (possibly
     * stale) catalog.
     */
    suspend fun ensureAvailable(context: Context, client: OkHttpClient): Boolean =
        refreshMutex.withLock {
            val cache = cacheFile(context)
            if (isFresh(cache)) return@withLock true

            val hadCache = cache.isFile && cache.length() > 0L
            if (System.currentTimeMillis() - lastFailureAt < FAILURE_BACKOFF_MS) {
                return@withLock hadCache
            }

            try {
                refresh(context, client, cache)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Offline catalog refresh failed: ${e.message}")
                lastFailureAt = System.currentTimeMillis()
                hadCache
            }
        }

    private fun isFresh(cache: File): Boolean =
        cache.isFile && cache.length() > 0L &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS

    private fun refresh(context: Context, client: OkHttpClient, cache: File) {
        val downloadClient = client.newBuilder()
            .connectTimeout(DOWNLOAD_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        val dumpName = latestDumpName(fetchIndex(downloadClient))
            ?: throw IllegalStateException("backup host listed no station dump")

        val versionFile = File(context.filesDir, VERSION_FILE)
        val currentVersion = if (versionFile.isFile) versionFile.readText().trim() else ""

        // Same dump we already have: just extend its freshness window.
        if (cache.isFile && cache.length() > 0L && currentVersion == dumpName) {
            cache.setLastModified(System.currentTimeMillis())
            return
        }

        val gzFile = File(context.filesDir, "$CACHE_FILE.gz.tmp")
        val tsvFile = File(context.filesDir, "$CACHE_FILE.tmp")
        var parsedCount = 0
        try {
            download(downloadClient, BACKUP_HOST + dumpName, gzFile)
            gzFile.inputStream().use { raw ->
                GZIPInputStream(raw).use { gzip ->
                    InputStreamReader(gzip, Charsets.UTF_8).use { json ->
                        java.io.BufferedWriter(
                            OutputStreamWriter(java.io.FileOutputStream(tsvFile), Charsets.UTF_8)
                        ).use { out ->
                            parsedCount = writeTsv(json, out)
                            Log.i(TAG, "Parsed $parsedCount stations from $dumpName")
                        }
                    }
                }
            }
            if (parsedCount <= 0) {
                throw IllegalStateException("dump contained no usable stations")
            }
            if (!tsvFile.renameTo(cache)) {
                tsvFile.copyTo(cache, overwrite = true)
            }
            versionFile.writeText(dumpName)
            cache.setLastModified(System.currentTimeMillis())
        } finally {
            gzFile.delete()
            tsvFile.delete()
        }
    }

    private fun fetchIndex(client: OkHttpClient): String {
        val request = Request.Builder()
            .url(BACKUP_HOST)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("backup index HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
    }

    private fun download(client: OkHttpClient, url: String, target: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("dump HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("dump has no body")
            target.outputStream().use { out ->
                val stream = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw IllegalStateException("dump exceeds size limit")
                    }
                    out.write(buffer, 0, read)
                }
            }
        }
    }

    /**
     * Stream the dump's JSON array into the compact TSV cache. Only the fields
     * browsing/search needs are kept, which cuts ~27 MB of JSON to ~14 MB.
     */
    internal fun writeTsv(input: Reader, output: Writer): Int {
        var written = 0
        JsonReader(input).use { reader ->
            reader.beginArray()
            while (reader.hasNext() && written < MAX_STATIONS) {
                reader.beginObject()
                var uuid = ""
                var name = ""
                var url = ""
                var tags = ""
                var countryCode = ""
                var languageCode = ""
                var favicon = ""
                var homepage = ""

                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "stationuuid" -> uuid = nextString(reader)
                        "name" -> name = nextString(reader)
                        "url_stream" -> url = nextString(reader)
                        "tags" -> tags = nextString(reader)
                        "iso_3166_1" -> countryCode = nextString(reader)
                        "iso_639" -> languageCode = nextString(reader)
                        "url_favicon" -> favicon = nextString(reader)
                        "url_homepage" -> homepage = nextString(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                if (name.isNotBlank() && isHttpUrl(url)) {
                    output.write(
                        listOf(
                            uuid, name, url, tags, countryCode, languageCode, favicon, homepage
                        ).joinToString("\t") { sanitize(it) }
                    )
                    output.write("\n")
                    written++
                }
            }
            if (!reader.hasNext()) reader.endArray()
        }
        return written
    }

    private fun nextString(reader: JsonReader): String {
        return if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            ""
        } else {
            runCatching { reader.nextString() }.getOrDefault("")
        }
    }

    private fun sanitize(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)


    internal fun latestDumpName(indexHtml: String): String? =
        Regex(DUMP_REGEX).findAll(indexHtml).map { it.value }.maxOrNull()


    // ---------------------------------------------------------------------
    // Queries (pure file access; safe to call from a background thread)
    // ---------------------------------------------------------------------

    internal fun search(
        file: File,
        name: String? = null,
        tag: String? = null,
        country: String? = null,
        countrycode: String? = null,
        language: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<RadioBrowserStation> {
        if (limit <= 0) return emptyList()

        val nameQuery = name?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val tagQuery = tag?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val countryQuery = country?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val countryCodeQuery = countrycode?.trim()?.takeIf { it.isNotEmpty() }
        val languageQuery = language?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

        // No filters: avoid materialising and sorting the whole catalog.
        if (nameQuery == null && tagQuery == null && countryQuery == null &&
            countryCodeQuery == null && languageQuery == null
        ) {
            return list(file, limit, offset)
        }

        val matches = ArrayList<RadioBrowserStation>()
        forEachRow(file) { row ->
            if (nameQuery != null && !row[COL_NAME].lowercase(Locale.ROOT).contains(nameQuery)) {
                return@forEachRow true
            }
            if (tagQuery != null &&
                !row[COL_TAGS].lowercase(Locale.ROOT).contains(tagQuery)
            ) {
                return@forEachRow true
            }
            if (countryQuery != null) {
                val code = row[COL_COUNTRY_CODE]
                val matchesCountry = code.equals(countryQuery, ignoreCase = true) ||
                    countryName(code).lowercase(Locale.ROOT).contains(countryQuery)
                if (!matchesCountry) return@forEachRow true
            }
            if (countryCodeQuery != null &&
                !row[COL_COUNTRY_CODE].equals(countryCodeQuery, ignoreCase = true)
            ) {
                return@forEachRow true
            }
            if (languageQuery != null && !matchesLanguage(row[COL_LANGUAGE_CODE], languageQuery)) {
                return@forEachRow true
            }
            matches.add(toStation(row))
            true
        }

        return matches
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .drop(offset)
            .take(limit)
    }

    internal fun list(file: File, limit: Int = 50, offset: Int = 0): List<RadioBrowserStation> {
        if (limit <= 0) return emptyList()
        val result = ArrayList<RadioBrowserStation>(limit)
        var index = 0
        forEachRow(file) { row ->
            if (index++ >= offset) result.add(toStation(row))
            result.size < limit
        }
        return result
    }

    internal fun byUuid(file: File, uuid: String): RadioBrowserStation? {
        var found: RadioBrowserStation? = null
        forEachRow(file) { row ->
            if (row[COL_UUID].equals(uuid, ignoreCase = true)) {
                found = toStation(row)
                false
            } else {
                true
            }
        }
        return found
    }

    internal fun countries(file: File): List<CountryInfo> {
        val counts = HashMap<String, Int>()
        forEachRow(file) { row ->
            val code = row[COL_COUNTRY_CODE].uppercase(Locale.ROOT)
            if (code.isNotEmpty()) counts[code] = (counts[code] ?: 0) + 1
            true
        }
        return counts.map { (code, count) -> CountryInfo(countryName(code), code, count) }
            .sortedByDescending { it.stationCount }
    }

    internal fun tags(file: File, limit: Int = 200): List<TagInfo> {
        if (limit <= 0) return emptyList()
        val counts = HashMap<String, Int>()
        forEachRow(file) { row ->
            row[COL_TAGS].split(',').forEach { raw ->
                val tag = raw.trim().lowercase(Locale.ROOT)
                if (tag.isNotEmpty()) counts[tag] = (counts[tag] ?: 0) + 1
            }
            true
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { TagInfo(it.key, it.value) }
    }

    internal fun languages(file: File, limit: Int = 200): List<LanguageInfo> {
        if (limit <= 0) return emptyList()
        val counts = HashMap<String, Int>()
        forEachRow(file) { row ->
            val code = row[COL_LANGUAGE_CODE].trim()
            if (code.isNotEmpty()) {
                val name = languageName(code)
                counts[name] = (counts[name] ?: 0) + 1
            }
            true
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { LanguageInfo(it.key, it.value) }
    }


    /**
     * Scan the TSV, invoking [action] for each row. Returning false stops the
     * scan early (used by list()/byUuid()).
     */
    private inline fun forEachRow(file: File, action: (Array<String>) -> Boolean) {
        if (!file.isFile || file.length() == 0L) return
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                val parts = line.split('\t', limit = COLUMN_COUNT).toMutableList()
                // Kotlin may drop trailing empty fields; keep the column count
                // stable so an empty favicon/homepage can't shift the row.
                while (parts.size < COLUMN_COUNT) parts.add("")
                if (!action(parts.toTypedArray())) break
            }
        }
    }

    private fun toStation(row: Array<String>): RadioBrowserStation {
        val url = row[COL_URL]
        return RadioBrowserStation(
            stationuuid = row[COL_UUID],
            name = row[COL_NAME],
            url = url,
            urlResolved = url,
            homepage = row[COL_HOMEPAGE],
            favicon = row[COL_FAVICON],
            tags = row[COL_TAGS],
            country = countryName(row[COL_COUNTRY_CODE]),
            countrycode = row[COL_COUNTRY_CODE],
            state = "",
            language = languageName(row[COL_LANGUAGE_CODE]),
            languagecodes = row[COL_LANGUAGE_CODE],
            votes = 0,
            lastchangetime = "",
            codec = "",
            bitrate = 0,
            hls = url.contains(".m3u8", ignoreCase = true),
            // The dump has no health column; keep stations visible rather than
            // hide the whole catalog.
            lastcheckok = true,
            clickcount = 0,
            clicktrend = 0,
            sslError = false,
            geoLat = null,
            geoLong = null
        )
    }

    private fun matchesLanguage(code: String, query: String): Boolean {
        if (code.isBlank()) return false
        if (code.equals(query, ignoreCase = true)) return true
        val displayName = languageName(code).lowercase(Locale.ROOT)
        return displayName == query || displayName.contains(query) ||
            code.lowercase(Locale.ROOT).contains(query)
    }


    internal fun countryName(code: String): String {
        val key = code.trim().uppercase(Locale.ROOT)
        if (key.isEmpty()) return ""
        return countryNames.getOrPut(key) {
            val display = runCatching {
                Locale.Builder().setRegion(key).build().getDisplayCountry(Locale.ENGLISH)
            }.getOrNull()
            display?.takeIf { it.isNotBlank() && !it.equals(key, ignoreCase = true) } ?: key
        }
    }

    internal fun languageName(code: String): String {
        val key = code.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return ""
        return languageNames.getOrPut(key) {
            COMMON_LANGUAGES[key]
                ?: runCatching {
                    Locale.forLanguageTag(key).getDisplayLanguage(Locale.ENGLISH)
                }.getOrNull()?.takeIf { it.isNotBlank() && !it.equals(key, ignoreCase = true) }
                ?: key.uppercase(Locale.ROOT)
        }
    }

    private val countryNames = ConcurrentHashMap<String, String>()
    private val languageNames = ConcurrentHashMap<String, String>()

    /**
     * ISO 639-3 codes used by Radio Browser. Locale's 3-letter lookup only
     * resolves a subset, so the common languages are listed explicitly.
     */
    private val COMMON_LANGUAGES = mapOf(
        "afr" to "Afrikaans", "amh" to "Amharic", "ara" to "Arabic",
        "aze" to "Azerbaijani", "bel" to "Belarusian", "ben" to "Bengali",
        "bos" to "Bosnian", "bul" to "Bulgarian", "cat" to "Catalan",
        "ces" to "Czech", "cym" to "Welsh", "dan" to "Danish",
        "deu" to "German", "ell" to "Greek", "eng" to "English",
        "epo" to "Esperanto", "est" to "Estonian", "eus" to "Basque",
        "fas" to "Persian", "fil" to "Filipino", "fin" to "Finnish",
        "fra" to "French", "gle" to "Irish", "glg" to "Galician",
        "guj" to "Gujarati", "heb" to "Hebrew", "hin" to "Hindi",
        "hrv" to "Croatian", "hun" to "Hungarian", "ibo" to "Igbo",
        "ind" to "Indonesian", "isl" to "Icelandic", "ita" to "Italian",
        "jpn" to "Japanese", "kan" to "Kannada", "kaz" to "Kazakh",
        "khm" to "Khmer", "kor" to "Korean", "lao" to "Lao",
        "lat" to "Latin", "lav" to "Latvian", "lit" to "Lithuanian",
        "mal" to "Malayalam", "mar" to "Marathi", "mkd" to "Macedonian",
        "mon" to "Mongolian", "msa" to "Malay", "mya" to "Burmese",
        "nep" to "Nepali", "nld" to "Dutch", "nor" to "Norwegian",
        "pan" to "Punjabi", "pol" to "Polish", "por" to "Portuguese",
        "ron" to "Romanian", "rus" to "Russian", "sin" to "Sinhala",
        "slk" to "Slovak", "slv" to "Slovenian", "som" to "Somali",
        "spa" to "Spanish", "sqi" to "Albanian", "srp" to "Serbian",
        "swa" to "Swahili", "swe" to "Swedish", "tam" to "Tamil",
        "tel" to "Telugu", "tha" to "Thai", "tur" to "Turkish",
        "ukr" to "Ukrainian", "urd" to "Urdu", "vie" to "Vietnamese",
        "xho" to "Xhosa", "yor" to "Yoruba", "zho" to "Chinese",
        "zul" to "Zulu"
    )
}

