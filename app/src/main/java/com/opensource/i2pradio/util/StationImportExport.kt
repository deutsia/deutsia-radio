package com.opensource.i2pradio.util

import android.content.Context
import android.net.Uri
import com.opensource.i2pradio.data.ProxyType
import com.opensource.i2pradio.data.RadioStation
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * Utility class for importing and exporting radio stations in various formats.
 * Supports CSV, JSON, M3U, and PLS formats.
 */
object StationImportExport {

    /**
     * Supported file formats for import/export
     */
    enum class FileFormat(val extension: String, val mimeType: String, val displayName: String) {
        CSV("csv", "text/csv", "CSV"),
        JSON("json", "application/json", "JSON"),
        M3U("m3u", "audio/x-mpegurl", "M3U Playlist"),
        PLS("pls", "audio/x-scpls", "PLS Playlist")
    }

    /**
     * Result of an import operation
     */
    data class ImportResult(
        val stations: List<RadioStation>,
        val errors: List<String>,
        val format: FileFormat?
    )

    // ==================== EXPORT FUNCTIONS ====================

    /**
     * Export stations to CSV format
     */
    fun exportToCsv(stations: List<RadioStation>, outputStream: OutputStream) {
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            // Header row
            writer.write("name,url,proxyType,proxyHost,proxyPort,genre,coverArtUri,isLiked\n")

            for (station in stations) {
                val proxyType = if (station.useProxy) station.proxyType else "NONE"
                val line = listOf(
                    escapeCsvField(station.name),
                    escapeCsvField(station.streamUrl),
                    proxyType,
                    escapeCsvField(station.proxyHost),
                    station.proxyPort.toString(),
                    escapeCsvField(station.genre),
                    escapeCsvField(station.coverArtUri ?: ""),
                    if (station.isLiked) "true" else "false"
                ).joinToString(",")
                writer.write("$line\n")
            }
        }
    }

    /**
     * Export stations to JSON format
     */
    fun exportToJson(stations: List<RadioStation>, outputStream: OutputStream) {
        val jsonArray = JSONArray()

        for (station in stations) {
            val jsonObj = JSONObject().apply {
                put("name", station.name)
                put("streamUrl", station.streamUrl)
                put("proxyType", if (station.useProxy) station.proxyType else "NONE")
                put("proxyHost", station.proxyHost)
                put("proxyPort", station.proxyPort)
                put("genre", station.genre)
                put("coverArtUri", station.coverArtUri ?: JSONObject.NULL)
                put("isLiked", station.isLiked)
            }
            jsonArray.put(jsonObj)
        }

        val rootObj = JSONObject().apply {
            put("version", 1)
            put("app", "deutsia radio")
            put("stations", jsonArray)
        }

        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(rootObj.toString(2))
        }
    }

    /**
     * Export stations to M3U format
     */
    fun exportToM3u(stations: List<RadioStation>, outputStream: OutputStream) {
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("#EXTM3U\n")
            writer.write("# deutsia radio Station Export\n\n")

            for (station in stations) {
                // Extended M3U info: #EXTINF:duration,title
                // For streams, duration is -1 (unknown/live)
                val proxyInfo = if (station.useProxy) {
                    when (ProxyType.fromString(station.proxyType)) {
                        ProxyType.I2P -> " [I2P]"
                        ProxyType.TOR -> " [Tor]"
                        ProxyType.CUSTOM -> " [Custom]"
                        ProxyType.NONE -> ""
                    }
                } else ""

                writer.write("#EXTINF:-1,${station.name}$proxyInfo\n")

                // Add custom attributes as comments for import compatibility
                writer.write("#I2PRADIO:genre=${station.genre}\n")
                if (station.useProxy) {
                    writer.write("#I2PRADIO:proxyType=${station.proxyType}\n")
                    writer.write("#I2PRADIO:proxyHost=${station.proxyHost}\n")
                    writer.write("#I2PRADIO:proxyPort=${station.proxyPort}\n")
                }
                if (!station.coverArtUri.isNullOrEmpty()) {
                    writer.write("#I2PRADIO:coverArt=${station.coverArtUri}\n")
                }
                if (station.isLiked) {
                    writer.write("#I2PRADIO:liked=true\n")
                }

                writer.write("${station.streamUrl}\n\n")
            }
        }
    }

    /**
     * Export stations to PLS format
     */
    fun exportToPls(stations: List<RadioStation>, outputStream: OutputStream) {
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("[playlist]\n")
            writer.write("NumberOfEntries=${stations.size}\n\n")

            stations.forEachIndexed { index, station ->
                val num = index + 1
                writer.write("File$num=${station.streamUrl}\n")

                val proxyInfo = if (station.useProxy) {
                    when (ProxyType.fromString(station.proxyType)) {
                        ProxyType.I2P -> " [I2P]"
                        ProxyType.TOR -> " [Tor]"
                        ProxyType.CUSTOM -> " [Custom]"
                        ProxyType.NONE -> ""
                    }
                } else ""
                writer.write("Title$num=${station.name}$proxyInfo\n")
                writer.write("Length$num=-1\n")

                // Add custom attributes as comments
                writer.write("; I2PRADIO:genre=${station.genre}\n")
                if (station.useProxy) {
                    writer.write("; I2PRADIO:proxyType=${station.proxyType}\n")
                    writer.write("; I2PRADIO:proxyHost=${station.proxyHost}\n")
                    writer.write("; I2PRADIO:proxyPort=${station.proxyPort}\n")
                }
                if (!station.coverArtUri.isNullOrEmpty()) {
                    writer.write("; I2PRADIO:coverArt=${station.coverArtUri}\n")
                }
                if (station.isLiked) {
                    writer.write("; I2PRADIO:liked=true\n")
                }
                writer.write("\n")
            }

            writer.write("Version=2\n")
        }
    }

    // ==================== IMPORT FUNCTIONS ====================

    /**
     * Import stations from a file, auto-detecting format
     */
    fun importFromUri(context: Context, uri: Uri): ImportResult {
        val errors = mutableListOf<String>()

        // Try to detect format from content
        val content = try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).readText()
            } ?: return ImportResult(emptyList(), listOf("Could not open file"), null)
        } catch (e: Exception) {
            return ImportResult(emptyList(), listOf("Error reading file: ${e.message}"), null)
        }

        // Detect format
        val format = detectFormat(content, uri.toString())

        return when (format) {
            FileFormat.CSV -> importFromCsv(content, errors)
            FileFormat.JSON -> importFromJson(content, errors)
            FileFormat.M3U -> importFromM3u(content, errors)
            FileFormat.PLS -> importFromPls(content, errors)
            null -> {
                // Try to extract stations from plain text with URLs
                val textResult = importFromPlainText(content, errors)
                if (textResult.stations.isNotEmpty()) {
                    textResult
                } else {
                    ImportResult(emptyList(), listOf("Unknown file format"), null)
                }
            }
        }
    }

    /**
     * Detect file format from content
     */
    private fun detectFormat(content: String, filename: String): FileFormat? {
        val lowerFilename = filename.lowercase()
        val trimmedContent = content.trim()

        // Check by file extension first
        when {
            lowerFilename.endsWith(".csv") -> return FileFormat.CSV
            lowerFilename.endsWith(".json") -> return FileFormat.JSON
            lowerFilename.endsWith(".m3u") || lowerFilename.endsWith(".m3u8") -> return FileFormat.M3U
            lowerFilename.endsWith(".pls") -> return FileFormat.PLS
        }

        // Detect by content
        return when {
            trimmedContent.startsWith("{") || trimmedContent.startsWith("[") -> FileFormat.JSON
            trimmedContent.startsWith("#EXTM3U") ||
                (trimmedContent.contains("#EXTINF:") && trimmedContent.contains("http")) -> FileFormat.M3U
            trimmedContent.startsWith("[playlist]", ignoreCase = true) -> FileFormat.PLS
            trimmedContent.contains(",") &&
                (trimmedContent.lowercase().contains("name,url") ||
                 trimmedContent.lowercase().contains("name,stream")) -> FileFormat.CSV
            else -> null
        }
    }

    /**
     * Import stations from CSV content
     */
    private fun importFromCsv(content: String, errors: MutableList<String>): ImportResult {
        val stations = mutableListOf<RadioStation>()
        val lines = content.lines().filter { it.isNotBlank() }

        if (lines.isEmpty()) {
            return ImportResult(emptyList(), listOf("Empty CSV file"), FileFormat.CSV)
        }

        // Parse header to get column indices
        val header = parseCsvLine(lines[0])
        val nameIdx = header.indexOfFirst { it.lowercase() in listOf("name", "title", "station") }
        val urlIdx = header.indexOfFirst { it.lowercase() in listOf("url", "streamurl", "stream_url", "stream") }
        val proxyTypeIdx = header.indexOfFirst { it.lowercase() in listOf("proxytype", "proxy_type", "proxy") }
        val proxyHostIdx = header.indexOfFirst { it.lowercase() in listOf("proxyhost", "proxy_host") }
        val proxyPortIdx = header.indexOfFirst { it.lowercase() in listOf("proxyport", "proxy_port") }
        val genreIdx = header.indexOfFirst { it.lowercase() in listOf("genre", "category") }
        val coverArtIdx = header.indexOfFirst { it.lowercase() in listOf("coverarturi", "cover_art", "coverart", "image") }
        val likedIdx = header.indexOfFirst { it.lowercase() in listOf("isliked", "liked", "favorite") }

        if (nameIdx == -1 || urlIdx == -1) {
            errors.add("CSV must have 'name' and 'url' columns")
            return ImportResult(emptyList(), errors, FileFormat.CSV)
        }

        // Parse data rows
        for (i in 1 until lines.size) {
            try {
                val fields = parseCsvLine(lines[i])
                if (fields.size <= maxOf(nameIdx, urlIdx)) {
                    errors.add("Row $i: Not enough columns")
                    continue
                }

                val name = fields.getOrNull(nameIdx)?.trim() ?: ""
                val url = fields.getOrNull(urlIdx)?.trim() ?: ""

                if (name.isEmpty() || url.isEmpty()) {
                    errors.add("Row $i: Name or URL is empty")
                    continue
                }

                val proxyTypeStr = fields.getOrNull(proxyTypeIdx)?.trim()?.uppercase() ?: "NONE"
                // Auto-detect proxy type from URL if not explicitly set to something other than NONE
                val proxyType = if (proxyTypeStr == "NONE") {
                    detectProxyTypeFromUrl(url)
                } else {
                    ProxyType.fromString(proxyTypeStr)
                }
                val useProxy = proxyType != ProxyType.NONE

                val station = RadioStation(
                    name = name,
                    streamUrl = url,
                    proxyType = proxyType.name,
                    proxyHost = fields.getOrNull(proxyHostIdx)?.trim() ?: proxyType.getDefaultHost(),
                    proxyPort = fields.getOrNull(proxyPortIdx)?.toIntOrNull() ?: proxyType.getDefaultPort(),
                    useProxy = useProxy,
                    genre = fields.getOrNull(genreIdx)?.trim() ?: "Other",
                    coverArtUri = fields.getOrNull(coverArtIdx)?.trim()?.takeIf { it.isNotEmpty() },
                    isLiked = fields.getOrNull(likedIdx)?.trim()?.lowercase() in listOf("true", "1", "yes")
                )

                stations.add(station)
            } catch (e: Exception) {
                errors.add("Row $i: ${e.message}")
            }
        }

        return ImportResult(stations, errors, FileFormat.CSV)
    }

    /**
     * Import stations from JSON content (public convenience method)
     */
    fun importFromJson(content: String): ImportResult {
        val errors = mutableListOf<String>()
        return importFromJson(content, errors)
    }

    /**
     * Import stations from JSON content
     */
    private fun importFromJson(content: String, errors: MutableList<String>): ImportResult {
        val stations = mutableListOf<RadioStation>()

        try {
            val json = if (content.trim().startsWith("[")) {
                JSONArray(content)
            } else {
                val rootObj = JSONObject(content)
                rootObj.optJSONArray("stations") ?: JSONArray()
            }

            for (i in 0 until json.length()) {
                try {
                    val obj = json.getJSONObject(i)
                    val name = obj.optString("name", "").takeIf { it.isNotEmpty() }
                        ?: obj.optString("title", "")
                    val url = obj.optString("streamUrl", "").takeIf { it.isNotEmpty() }
                        ?: obj.optString("url", "").takeIf { it.isNotEmpty() }
                        ?: obj.optString("stream", "")

                    if (name.isEmpty() || url.isEmpty()) {
                        errors.add("Entry $i: Missing name or URL")
                        continue
                    }

                    val proxyTypeStr = obj.optString("proxyType", "NONE").uppercase()
                    // Auto-detect proxy type from URL if not explicitly set
                    val proxyType = if (proxyTypeStr == "NONE") {
                        detectProxyTypeFromUrl(url)
                    } else {
                        ProxyType.fromString(proxyTypeStr)
                    }
                    val useProxy = proxyType != ProxyType.NONE

                    val station = RadioStation(
                        name = name,
                        streamUrl = url,
                        proxyType = proxyType.name,
                        proxyHost = obj.optString("proxyHost", proxyType.getDefaultHost()),
                        proxyPort = obj.optInt("proxyPort", proxyType.getDefaultPort()),
                        useProxy = useProxy,
                        genre = obj.optString("genre", "Other"),
                        coverArtUri = obj.optString("coverArtUri", null)?.takeIf { it.isNotEmpty() && it != "null" },
                        isLiked = obj.optBoolean("isLiked", false)
                    )

                    stations.add(station)
                } catch (e: Exception) {
                    errors.add("Entry $i: ${e.message}")
                }
            }
        } catch (e: Exception) {
            errors.add("Invalid JSON format: ${e.message}")
        }

        return ImportResult(stations, errors, FileFormat.JSON)
    }

    /**
     * Import stations from M3U content
     */
    private fun importFromM3u(content: String, errors: MutableList<String>): ImportResult {
        val stations = mutableListOf<RadioStation>()
        val lines = content.lines()

        var currentName: String? = null
        var currentGenre = "Other"
        var currentProxyType = ProxyType.NONE
        var currentProxyHost = ""
        var currentProxyPort = 0
        var currentCoverArt: String? = null
        var currentLiked = false

        for (line in lines) {
            val trimmed = line.trim()

            when {
                trimmed.startsWith("#EXTINF:") -> {
                    // Parse: #EXTINF:duration,title [info]
                    val info = trimmed.substringAfter(",").trim()
                    // Remove proxy indicators from name
                    currentName = info.replace(Regex("\\s*\\[(I2P|Tor|Custom)\\]\\s*$"), "").trim()

                    // Detect proxy type from indicator
                    when {
                        info.contains("[I2P]") -> {
                            currentProxyType = ProxyType.I2P
                            currentProxyHost = ProxyType.I2P.getDefaultHost()
                            currentProxyPort = ProxyType.I2P.getDefaultPort()
                        }
                        info.contains("[Tor]") -> {
                            currentProxyType = ProxyType.TOR
                            currentProxyHost = ProxyType.TOR.getDefaultHost()
                            currentProxyPort = ProxyType.TOR.getDefaultPort()
                        }
                        info.contains("[Custom]") -> {
                            currentProxyType = ProxyType.CUSTOM
                            // Custom proxy requires explicit host/port from metadata
                        }
                    }
                }
                trimmed.startsWith("#I2PRADIO:") -> {
                    // Parse custom I2P Radio attributes
                    val attr = trimmed.substringAfter("#I2PRADIO:")
                    val (key, value) = attr.split("=", limit = 2).let {
                        it.getOrElse(0) { "" } to it.getOrElse(1) { "" }
                    }
                    when (key.lowercase()) {
                        "genre" -> currentGenre = value
                        "proxytype" -> {
                            currentProxyType = ProxyType.fromString(value)
                            if (currentProxyHost.isEmpty()) {
                                currentProxyHost = currentProxyType.getDefaultHost()
                                currentProxyPort = currentProxyType.getDefaultPort()
                            }
                        }
                        "proxyhost" -> currentProxyHost = value
                        "proxyport" -> currentProxyPort = value.toIntOrNull() ?: currentProxyPort
                        "coverart" -> currentCoverArt = value.takeIf { it.isNotEmpty() }
                        "liked" -> currentLiked = value.lowercase() in listOf("true", "1", "yes")
                    }
                }
                trimmed.isNotEmpty() && !trimmed.startsWith("#") -> {
                    // This is a URL line
                    val url = trimmed
                    val name = currentName ?: url.substringAfterLast("/").substringBefore(".")

                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        // Auto-detect proxy type from URL if not explicitly set
                        val detectedProxyType = if (currentProxyType == ProxyType.NONE) {
                            detectProxyTypeFromUrl(url)
                        } else {
                            currentProxyType
                        }
                        val useProxy = detectedProxyType != ProxyType.NONE
                        val station = RadioStation(
                            name = name,
                            streamUrl = url,
                            proxyType = detectedProxyType.name,
                            proxyHost = if (useProxy) (currentProxyHost.takeIf { it.isNotEmpty() } ?: detectedProxyType.getDefaultHost()) else "",
                            proxyPort = if (useProxy) (currentProxyPort.takeIf { it > 0 } ?: detectedProxyType.getDefaultPort()) else 0,
                            useProxy = useProxy,
                            genre = currentGenre,
                            coverArtUri = currentCoverArt,
                            isLiked = currentLiked
                        )
                        stations.add(station)
                    }

                    // Reset for next entry
                    currentName = null
                    currentGenre = "Other"
                    currentProxyType = ProxyType.NONE
                    currentProxyHost = ""
                    currentProxyPort = 0
                    currentCoverArt = null
                    currentLiked = false
                }
            }
        }

        return ImportResult(stations, errors, FileFormat.M3U)
    }

    /**
     * Import stations from PLS content
     */
    private fun importFromPls(content: String, errors: MutableList<String>): ImportResult {
        val stations = mutableListOf<RadioStation>()
        val lines = content.lines()

        // Parse PLS entries - collect File, Title, and attributes
        val files = mutableMapOf<Int, String>()
        val titles = mutableMapOf<Int, String>()
        val genres = mutableMapOf<Int, String>()
        val proxyTypes = mutableMapOf<Int, ProxyType>()
        val proxyHosts = mutableMapOf<Int, String>()
        val proxyPorts = mutableMapOf<Int, Int>()
        val coverArts = mutableMapOf<Int, String>()
        val liked = mutableMapOf<Int, Boolean>()

        var currentEntryNum = 0

        for (line in lines) {
            val trimmed = line.trim()

            when {
                trimmed.startsWith("File", ignoreCase = true) -> {
                    val match = Regex("File(\\d+)=(.+)", RegexOption.IGNORE_CASE).find(trimmed)
                    if (match != null) {
                        val num = match.groupValues[1].toIntOrNull() ?: continue
                        files[num] = match.groupValues[2]
                        currentEntryNum = num
                    }
                }
                trimmed.startsWith("Title", ignoreCase = true) -> {
                    val match = Regex("Title(\\d+)=(.+)", RegexOption.IGNORE_CASE).find(trimmed)
                    if (match != null) {
                        val num = match.groupValues[1].toIntOrNull() ?: continue
                        var title = match.groupValues[2]

                        // Detect and remove proxy indicators
                        when {
                            title.contains("[I2P]") -> {
                                proxyTypes[num] = ProxyType.I2P
                                title = title.replace(Regex("\\s*\\[I2P\\]\\s*"), "")
                            }
                            title.contains("[Tor]") -> {
                                proxyTypes[num] = ProxyType.TOR
                                title = title.replace(Regex("\\s*\\[Tor\\]\\s*"), "")
                            }
                            title.contains("[Custom]") -> {
                                proxyTypes[num] = ProxyType.CUSTOM
                                title = title.replace(Regex("\\s*\\[Custom\\]\\s*"), "")
                            }
                        }
                        titles[num] = title.trim()
                    }
                }
                trimmed.startsWith("; I2PRADIO:") -> {
                    val attr = trimmed.substringAfter("; I2PRADIO:")
                    val (key, value) = attr.split("=", limit = 2).let {
                        it.getOrElse(0) { "" } to it.getOrElse(1) { "" }
                    }
                    when (key.lowercase()) {
                        "genre" -> genres[currentEntryNum] = value
                        "proxytype" -> proxyTypes[currentEntryNum] = ProxyType.fromString(value)
                        "proxyhost" -> proxyHosts[currentEntryNum] = value
                        "proxyport" -> proxyPorts[currentEntryNum] = value.toIntOrNull() ?: 0
                        "coverart" -> coverArts[currentEntryNum] = value
                        "liked" -> liked[currentEntryNum] = value.lowercase() in listOf("true", "1", "yes")
                    }
                }
            }
        }

        // Build stations from parsed data
        for ((num, url) in files) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue

            val title = titles[num] ?: url.substringAfterLast("/").substringBefore(".")
            // Auto-detect proxy type from URL if not explicitly set
            val detectedProxyType = proxyTypes[num] ?: detectProxyTypeFromUrl(url)
            val useProxy = detectedProxyType != ProxyType.NONE

            val station = RadioStation(
                name = title,
                streamUrl = url,
                proxyType = detectedProxyType.name,
                proxyHost = proxyHosts[num] ?: detectedProxyType.getDefaultHost(),
                proxyPort = proxyPorts[num] ?: detectedProxyType.getDefaultPort(),
                useProxy = useProxy,
                genre = genres[num] ?: "Other",
                coverArtUri = coverArts[num]?.takeIf { it.isNotEmpty() },
                isLiked = liked[num] ?: false
            )
            stations.add(station)
        }

        return ImportResult(stations, errors, FileFormat.PLS)
    }

    // ==================== HELPER FUNCTIONS ====================

    /**
     * Escape a field for CSV output
     */
    private fun escapeCsvField(field: String): String {
        return if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }

    /**
     * Parse a CSV line, handling quoted fields
     */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        // Escaped quote
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())

        return fields
    }

    /**
     * Auto-detect proxy type from URL
     */
    private fun detectProxyTypeFromUrl(url: String): ProxyType {
        return when {
            url.contains(".i2p", ignoreCase = true) -> ProxyType.I2P
            url.contains(".onion", ignoreCase = true) -> ProxyType.TOR
            else -> ProxyType.NONE
        }
    }

    /**
     * Extract cover art URL from various text patterns
     */
    private fun extractCoverArt(text: String): String? {
        // Try to find image URLs in common patterns
        val imagePatterns = listOf(
            Regex("""(?:cover|art|image|logo)[:=]\s*([^\s,;"'\n]+\.(?:jpg|jpeg|png|gif|webp))""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s,;"'\n]+\.(?:jpg|jpeg|png|gif|webp))""", RegexOption.IGNORE_CASE),
            Regex(""""([^"]+\.(?:jpg|jpeg|png|gif|webp))" """, RegexOption.IGNORE_CASE)
        )

        for (pattern in imagePatterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues.getOrNull(1)?.trim()
            }
        }
        return null
    }

    /**
     * Import stations from plain text containing URLs
     * This is a fallback for malformed files that just contain stream URLs
     */
    private fun importFromPlainText(content: String, errors: MutableList<String>): ImportResult {
        val stations = mutableListOf<RadioStation>()
        val lines = content.lines()

        // Regex to find HTTP/HTTPS URLs
        val urlRegex = Regex("""(https?://[^\s<>"{}|\\^`\[\]]+)""", RegexOption.IGNORE_CASE)

        var currentName: String? = null
        var currentCoverArt: String? = null

        for ((lineNum, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") && !trimmed.contains("http")) continue

            // Try to extract cover art from this line
            val coverArt = extractCoverArt(trimmed)
            if (coverArt != null && currentCoverArt == null) {
                currentCoverArt = coverArt
            }

            // Find all URLs in this line
            val urls = urlRegex.findAll(trimmed).map { it.value }.toList()

            for (url in urls) {
                // Skip image URLs as they're cover art
                if (url.matches(Regex(""".*\.(?:jpg|jpeg|png|gif|webp)$""", RegexOption.IGNORE_CASE))) {
                    if (currentCoverArt == null) currentCoverArt = url
                    continue
                }

                // Check if this looks like a stream URL
                if (!url.contains("stream", ignoreCase = true) &&
                    !url.endsWith(".mp3") && !url.endsWith(".m3u") &&
                    !url.endsWith(".pls") && !url.endsWith(".ogg") &&
                    !url.endsWith(".aac") && !url.contains("radio", ignoreCase = true) &&
                    !url.contains(".i2p") && !url.contains(".onion")) {
                    // Might be an image or other URL, try to use as cover art
                    if (url.matches(Regex(""".*\.(?:jpg|jpeg|png|gif|webp)""", RegexOption.IGNORE_CASE))) {
                        if (currentCoverArt == null) currentCoverArt = url
                    }
                    continue
                }

                // Try to extract station name from the line
                val name = when {
                    // If there's text before the URL, use it as name
                    trimmed.indexOf(url) > 0 -> {
                        val beforeUrl = trimmed.substring(0, trimmed.indexOf(url)).trim()
                        // Remove common prefixes
                        beforeUrl.replace(Regex("""^[#\-*•]\s*"""), "")
                               .replace(Regex("""[:=]\s*$"""), "")
                               .trim()
                               .takeIf { it.isNotEmpty() && it.length < 100 }
                    }
                    // Check previous line for a name
                    lineNum > 0 && currentName != null -> currentName
                    // Use URL-based name
                    else -> null
                } ?: url.substringAfter("//")
                       .substringBefore("/")
                       .replace(Regex("""^www\."""), "")
                       .split(".").firstOrNull()
                       ?.replaceFirstChar { it.uppercase() }
                       ?: "Radio Station"

                // Auto-detect proxy type
                val proxyType = detectProxyTypeFromUrl(url)
                val useProxy = proxyType != ProxyType.NONE

                val station = RadioStation(
                    name = name,
                    streamUrl = url,
                    proxyType = proxyType.name,
                    proxyHost = if (useProxy) proxyType.getDefaultHost() else "",
                    proxyPort = if (useProxy) proxyType.getDefaultPort() else 0,
                    useProxy = useProxy,
                    genre = "Other",
                    coverArtUri = currentCoverArt,
                    isLiked = false
                )

                stations.add(station)
                currentName = null
                currentCoverArt = null
            }

            // If line has no URLs but has text, might be a station name for next line
            if (urls.isEmpty() && trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                currentName = trimmed.take(100) // Limit name length
            }
        }

        if (stations.isEmpty()) {
            errors.add("No valid stream URLs found in file")
        }

        return ImportResult(stations, errors, null)
    }
}
