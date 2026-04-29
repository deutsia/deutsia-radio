package com.opensource.i2pradio.playback

import com.opensource.i2pradio.data.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [DiscoverEngine]. Pure ranking logic, no I/O.
 *
 * Coverage:
 *   - Empty / single-station library: no candidate.
 *   - Tag overlap drives ranking when both stations have non-empty tags.
 *   - Country code is a tiebreaker when tag scores are close.
 *   - Pre-migration stations (empty `tags`, populated `genre`) fall back
 *     to genre-equality.
 *   - The current station is excluded by id, then by uuid, then by url.
 *   - Recently-played stations are excluded from the candidate pool.
 *   - Network tags (`i2p`, `tor`) are ignored so privacy stations don't
 *     cluster on their privacy layer alone.
 */
class DiscoverEngineTest {

    private fun station(
        id: Long = 0L,
        name: String = "S$id",
        tags: String = "",
        genre: String = "Other",
        country: String = "",
        countryCode: String = "",
        url: String = "http://s$id",
        uuid: String? = null,
        lastPlayedAt: Long = 0L
    ): RadioStation = RadioStation(
        id = id,
        name = name,
        streamUrl = url,
        tags = tags,
        genre = genre,
        country = country,
        countryCode = countryCode,
        radioBrowserUuid = uuid,
        lastPlayedAt = lastPlayedAt
    )

    @Test
    fun `empty library yields no suggestion`() {
        val current = station(id = 1, tags = "rock,indie")
        assertNull(DiscoverEngine.suggestNext(current, emptyList()))
    }

    @Test
    fun `library with only the current station yields no suggestion`() {
        val current = station(id = 1, tags = "rock")
        assertNull(DiscoverEngine.suggestNext(current, listOf(current)))
    }

    @Test
    fun `tag overlap drives selection`() {
        val current = station(id = 1, tags = "rock,indie,80s")
        val library = listOf(
            current,
            station(id = 2, tags = "jazz,blues"),
            station(id = 3, tags = "rock,indie"),
            station(id = 4, tags = "electronic,house")
        )
        val pick = DiscoverEngine.suggestNext(current, library)
        assertEquals(3L, pick?.id)
    }

    @Test
    fun `country tiebreaker only matters when tag scores are equal`() {
        val current = station(id = 1, tags = "pop", countryCode = "FR")
        val library = listOf(
            current,
            station(id = 2, tags = "pop", countryCode = "DE"),
            // Same tag overlap as id=2, but matching country wins the tie.
            station(id = 3, tags = "pop", countryCode = "FR")
        )
        val pick = DiscoverEngine.suggestNext(current, library)
        assertEquals(3L, pick?.id)
    }

    @Test
    fun `genre fallback applies only when tags overlap is zero`() {
        val current = station(id = 1, tags = "", genre = "Jazz")
        val library = listOf(
            current,
            // Tags but unrelated.
            station(id = 2, tags = "pop,top40", genre = "Pop"),
            // Same genre, no tag info — picked via fallback.
            station(id = 3, tags = "", genre = "Jazz")
        )
        val pick = DiscoverEngine.suggestNext(current, library)
        assertEquals(3L, pick?.id)
    }

    @Test
    fun `current station is excluded by id`() {
        val current = station(id = 1, tags = "rock")
        // A station that *would* otherwise score perfectly against itself.
        val library = listOf(current, station(id = 2, tags = "rock"))
        val pick = DiscoverEngine.suggestNext(current, library)
        assertEquals(2L, pick?.id)
    }

    @Test
    fun `current station is excluded by stream url when ids are zero`() {
        val current = station(id = 0, tags = "rock", url = "http://x")
        val twin = station(id = 0, tags = "rock", url = "http://x")
        val other = station(id = 0, tags = "rock", url = "http://y")
        val pick = DiscoverEngine.suggestNext(current, listOf(twin, other))
        assertNotEquals("http://x", pick?.streamUrl)
        assertEquals("http://y", pick?.streamUrl)
    }

    @Test
    fun `current station is excluded by radio browser uuid`() {
        val current = station(id = 1, uuid = "abc", tags = "rock")
        // Same uuid, different id — still the same station from RadioBrowser.
        val twin = station(id = 2, uuid = "abc", tags = "rock")
        val other = station(id = 3, uuid = "xyz", tags = "rock")
        val pick = DiscoverEngine.suggestNext(current, listOf(current, twin, other))
        assertEquals(3L, pick?.id)
    }

    @Test
    fun `recently played stations are excluded`() {
        val current = station(id = 1, tags = "rock")
        val now = 100_000L
        // Five recent stations + one fresh candidate. The recent ones each
        // have a perfect tag match but they're skipped.
        val recent = (2..6L).map { id ->
            station(id = id, tags = "rock", lastPlayedAt = now - id)
        }
        val candidate = station(id = 99, tags = "rock", lastPlayedAt = 0L)
        val pick = DiscoverEngine.suggestNext(current, listOf(current) + recent + candidate)
        assertEquals(99L, pick?.id)
    }

    @Test
    fun `network tags do not cluster privacy stations alone`() {
        val current = station(id = 1, tags = "tor,jazz")
        val library = listOf(
            current,
            // Same network tag but unrelated genre — should NOT win the
            // pick on the network tag alone.
            station(id = 2, tags = "tor,polka"),
            // Different network, matching genre — better choice.
            station(id = 3, tags = "i2p,jazz")
        )
        val pick = DiscoverEngine.suggestNext(current, library)
        assertEquals(3L, pick?.id)
    }

    @Test
    fun `no candidate scores positively yields null`() {
        val current = station(id = 1, tags = "")
        val library = listOf(current, station(id = 2, tags = ""))
        // Both have empty tags, no genre match (default Other == Other but
        // engine ignores Other), no country. Score is 0 → null.
        assertNull(DiscoverEngine.suggestNext(current, library))
    }
}
