package com.opensource.i2pradio.util

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the SomaFM "Couldn't read playlist" failure.
 *
 * SomaFM's ice*.somafm.com servers close the TLS connection with no HTTP
 * response when the request carries a "DeutsiaRadio/1.0" User-Agent. Its
 * <station>.pls points at an extensionless stream URL, so the resolver
 * fetches the pointer AND probes the stream before playback. Both requests
 * must therefore identify themselves with [StreamUserAgent.VALUE].
 */
class PlaylistResolverUserAgentTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `pls pointer and extensionless stream probe send shared stream user agent`() {
        // Unique port per test keeps PlaylistResolver's process-wide cache
        // from returning a URL resolved by an earlier test.
        val streamUrl = server.url("/groovesalad-128-mp3").toString()
        val pointerUrl = server.url("/groovesalad.pls").toString()

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/x-scpls")
                .setBody(
                    """
                    [playlist]
                    numberofentries=1
                    File1=$streamUrl
                    Title1=Groove Salad
                    Length1=-1
                    version=2
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/mpeg")
                .setBody("not-consumed-audio-bytes")
        )

        val result = PlaylistResolver.resolve(pointerUrl, client)

        assertTrue("expected resolution to succeed, got $result", result is PlaylistResolver.Result.Resolved)
        assertEquals(streamUrl, (result as PlaylistResolver.Result.Resolved).url)

        val pointerRequest = server.takeRequest()
        val probeRequest = server.takeRequest()
        assertEquals(
            "playlist fetch must use the shared stream User-Agent",
            StreamUserAgent.VALUE,
            pointerRequest.getHeader("User-Agent")
        )
        assertEquals(
            "extensionless stream probe must use the shared stream User-Agent",
            StreamUserAgent.VALUE,
            probeRequest.getHeader("User-Agent")
        )
    }

    @Test
    fun `extensionless stream with no pointer sends shared stream user agent`() {
        val streamUrl = server.url("/ambient-128-mp3").toString()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/aac")
                .setBody("not-consumed-audio-bytes")
        )

        val result = PlaylistResolver.resolve(streamUrl, client)

        assertTrue("expected resolution to succeed, got $result", result is PlaylistResolver.Result.Resolved)
        assertEquals(streamUrl, (result as PlaylistResolver.Result.Resolved).url)
        assertEquals(
            StreamUserAgent.VALUE,
            server.takeRequest().getHeader("User-Agent")
        )
    }

    @Test
    fun `stream user agent does not reuse the blocked DeutsiaRadio identifier`() {
        assertFalse(
            "SomaFM closes the connection for DeutsiaRadio-prefixed agents",
            StreamUserAgent.VALUE.contains("DeutsiaRadio", ignoreCase = true)
        )
    }
}
