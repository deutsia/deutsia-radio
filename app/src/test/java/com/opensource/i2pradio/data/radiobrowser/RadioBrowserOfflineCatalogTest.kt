package com.opensource.i2pradio.data.radiobrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringReader
import java.io.StringWriter

class RadioBrowserOfflineCatalogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dumpJson = """
        [
          {"stationuuid":"u1","name":"Jazz FM","url_stream":"https://a.example/stream",
           "tags":"jazz,smooth","iso_3166_1":"GB","iso_639":"eng",
           "url_favicon":"https://a.example/f.ico","url_homepage":"https://a.example"},
          {"stationuuid":"u2","name":"Rock\tRadio","url_stream":"https://b.example/stream",
           "tags":"rock","iso_3166_1":"US","iso_639":"eng",
           "url_favicon":null,"url_homepage":null},
          {"stationuuid":"u3","name":"Deutsch Pop","url_stream":"http://c.example/stream",
           "tags":"pop","iso_3166_1":"DE","iso_639":"deu",
           "url_favicon":"","url_homepage":""},
          {"stationuuid":"u4","name":"No URL","url_stream":"","tags":"x",
           "iso_3166_1":"FR","iso_639":"fra"},
          {"stationuuid":"u5","name":"   ","url_stream":"https://e.example/stream",
           "tags":"","iso_3166_1":"FR","iso_639":"fra"}
        ]
    """.trimIndent()

    private fun catalogFile(): File {
        val file = tempFolder.newFile("catalog.tsv")
        val count = StringReader(dumpJson).use { input ->
            StringWriter().use { output ->
                val written = RadioBrowserOfflineCatalog.writeTsv(input, output)
                file.writeText(output.toString())
                written
            }
        }
        assertEquals(3, count)
        return file
    }

    @Test
    fun `writeTsv keeps usable rows and sanitises tabs and nulls`() {
        val rows = catalogFile().readLines()
        assertEquals(3, rows.size)

        val fields = rows.map { it.split('\t') }
        assertEquals("u1", fields[0][0])
        assertEquals("Jazz FM", fields[0][1])
        assertEquals("Rock Radio", fields[1][1]) // tab replaced
        assertEquals("", fields[1][6])            // null favicon preserved as a column
        assertEquals("", fields[1][7])
        assertTrue(fields.all { it.size == 8 })
    }

    @Test
    fun `byUuid finds a station`() {
        val file = catalogFile()
        val station = RadioBrowserOfflineCatalog.byUuid(file, "u3")
        assertEquals("Deutsch Pop", station?.name)
        assertEquals("http://c.example/stream", station?.getStreamUrl())
        assertNull(RadioBrowserOfflineCatalog.byUuid(file, "missing"))
    }

    @Test
    fun `search filters by name and sorts then pages`() {
        val file = catalogFile()
        assertEquals(listOf("Rock Radio"), RadioBrowserOfflineCatalog.search(file, name = "rock").map { it.name })
        assertEquals(listOf("Jazz FM"), RadioBrowserOfflineCatalog.search(file, tag = "jazz").map { it.name })
        assertEquals(
            listOf("Rock Radio"),
            RadioBrowserOfflineCatalog.search(file, name = "o", limit = 1, offset = 1).map { it.name }
        )
    }

    @Test
    fun `search filters by country code country name and language`() {
        val file = catalogFile()
        assertEquals(
            listOf("Rock Radio"),
            RadioBrowserOfflineCatalog.search(file, countrycode = "US").map { it.name }
        )
        assertEquals(
            listOf("Rock Radio"),
            RadioBrowserOfflineCatalog.search(file, country = "United States").map { it.name }
        )
        assertEquals(
            listOf("Deutsch Pop"),
            RadioBrowserOfflineCatalog.search(file, language = "German").map { it.name }
        )
        assertEquals(
            listOf("Jazz FM", "Rock Radio"),
            RadioBrowserOfflineCatalog.search(file, language = "eng").map { it.name }
        )
    }

    @Test
    fun `list returns rows in catalog order`() {
        val file = catalogFile()
        assertEquals(
            listOf("Jazz FM"),
            RadioBrowserOfflineCatalog.list(file, limit = 1, offset = 0).map { it.name }
        )
        assertEquals(
            listOf("Rock Radio", "Deutsch Pop"),
            RadioBrowserOfflineCatalog.list(file, limit = 2, offset = 1).map { it.name }
        )
    }

    @Test
    fun `countries tags and languages are aggregated with counts`() {
        val file = catalogFile()

        val countries = RadioBrowserOfflineCatalog.countries(file).associateBy { it.iso3166_1 }
        assertEquals("United Kingdom", countries["GB"]?.name)
        assertEquals(1, countries["US"]?.stationCount)
        assertEquals(1, countries["DE"]?.stationCount)

        val tags = RadioBrowserOfflineCatalog.tags(file, limit = 10).associateBy { it.name }
        assertEquals(1, tags["jazz"]?.stationCount)
        assertEquals(1, tags["smooth"]?.stationCount)

        val languages = RadioBrowserOfflineCatalog.languages(file, limit = 10).associateBy { it.name }
        assertEquals(2, languages["English"]?.stationCount)
        assertEquals(1, languages["German"]?.stationCount)
    }

    @Test
    fun `latestDumpName picks the newest backup`() {
        val html = """
            <a href="radiobrowser_stations_20261029_185259.json.gz">a</a>
            <a href="radiobrowser_stations_20261028_125025.json.gz">b</a>
            <a href="radiobrowser_stations_20261030_010000.json.gz">c</a>
        """.trimIndent()
        assertEquals(
            "radiobrowser_stations_20261030_010000.json.gz",
            RadioBrowserOfflineCatalog.latestDumpName(html)
        )
        assertNull(RadioBrowserOfflineCatalog.latestDumpName("<html>no dumps</html>"))
    }
}
