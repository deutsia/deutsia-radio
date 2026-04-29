package com.opensource.i2pradio.playback

import com.opensource.i2pradio.data.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Queue]. Pure-data class with no Android dependencies.
 *
 * Coverage:
 *   - Empty queue: next/previous return null, both `hasNext`/`hasPrevious`
 *     are false, and `isExhausted` is true.
 *   - Context-only queue: forward + backward walk respects the cursor and
 *     stops at the ends; `hasNext`/`hasPrevious` track the cursor.
 *   - Manual queue: FIFO play order; popping is one-way (skip-previous
 *     never re-inserts a popped manual entry).
 *   - Manual + context: manual entries play first, then context resumes
 *     from where the cursor left off.
 *   - Discover hook: `appendToContext` extends the list so a subsequent
 *     `next` returns the new station.
 *   - `replaceContextKeepingCurrent` finds the same station in a re-sorted
 *     list (by id, falls back to streamUrl), or pins the cursor at 0 when
 *     the station isn't present.
 */
class QueueTest {

    private fun station(id: Long, name: String = "S$id", url: String = "http://s$id"): RadioStation =
        RadioStation(id = id, name = name, streamUrl = url)

    @Test
    fun `empty queue returns null for next and previous`() {
        val q = Queue()
        assertNull(q.next())
        assertNull(q.previous())
        assertFalse(q.hasNext())
        assertFalse(q.hasPrevious())
        assertTrue(q.isExhausted())
    }

    @Test
    fun `setContext clamps the index to the bounds of the list`() {
        val q = Queue()
        val ctx = listOf(station(1), station(2), station(3))

        q.setContext(ctx, -5)
        assertEquals(0, q.contextIndex)

        q.setContext(ctx, 99)
        assertEquals(2, q.contextIndex)

        q.setContext(emptyList(), 0)
        assertEquals(-1, q.contextIndex)
    }

    @Test
    fun `next walks the context forward and stops at the end`() {
        val q = Queue()
        val ctx = listOf(station(1), station(2), station(3))
        q.setContext(ctx, 0)

        assertTrue(q.hasNext())
        assertEquals(2L, q.next()?.id)
        assertEquals(3L, q.next()?.id)
        assertFalse(q.hasNext())
        assertNull(q.next())
        assertTrue(q.isExhausted())
    }

    @Test
    fun `previous walks the context backward and stops at the start`() {
        val q = Queue()
        val ctx = listOf(station(1), station(2), station(3))
        q.setContext(ctx, 2)

        assertTrue(q.hasPrevious())
        assertEquals(2L, q.previous()?.id)
        assertEquals(1L, q.previous()?.id)
        assertFalse(q.hasPrevious())
        assertNull(q.previous())
    }

    @Test
    fun `manual queue plays before context resumes`() {
        val q = Queue()
        val ctx = listOf(station(1), station(2), station(3))
        q.setContext(ctx, 0)

        q.addToQueue(station(100))
        q.addToQueue(station(101))

        // Manual entries pop FIFO before walking context.
        assertEquals(100L, q.next()?.id)
        assertEquals(101L, q.next()?.id)
        // Context resumes from where the cursor left off.
        assertEquals(2L, q.next()?.id)
    }

    @Test
    fun `playNext jumps to the head of the manual queue`() {
        val q = Queue()
        q.setContext(listOf(station(1)), 0)

        q.addToQueue(station(50))
        q.addToQueue(station(51))
        q.playNext(station(99))

        assertEquals(99L, q.next()?.id)
        assertEquals(50L, q.next()?.id)
        assertEquals(51L, q.next()?.id)
    }

    @Test
    fun `previous never re-inserts popped manual entries`() {
        val q = Queue()
        val ctx = listOf(station(1), station(2))
        q.setContext(ctx, 0)
        q.addToQueue(station(50))

        // Pop manual.
        assertEquals(50L, q.next()?.id)
        // skip-previous walks context only, not the popped manual entry.
        assertNull(q.previous())
        assertEquals(0, q.contextIndex)
    }

    @Test
    fun `appendToContext extends the queue so Discover picks land in next`() {
        val q = Queue()
        val ctx = listOf(station(1), station(2))
        q.setContext(ctx, 1)
        // Cursor at end.
        assertFalse(q.hasNext())
        // Discover suggests one more.
        q.appendToContext(station(3))
        assertTrue(q.hasNext())
        assertEquals(3L, q.next()?.id)
    }

    @Test
    fun `removeFromManual drops the matching entry`() {
        val q = Queue()
        val a = station(10)
        val b = station(20)
        q.addToQueue(a)
        q.addToQueue(b)

        assertTrue(q.removeFromManual(a))
        assertEquals(listOf(20L), q.manualSnapshot().map { it.id })
        assertFalse(q.removeFromManual(a))  // already gone
    }

    @Test
    fun `clearManual empties manual queue but leaves context alone`() {
        val q = Queue()
        val ctx = listOf(station(1), station(2))
        q.setContext(ctx, 0)
        q.addToQueue(station(50))

        q.clearManual()
        assertTrue(q.manualSnapshot().isEmpty())
        // Context still walks.
        assertEquals(2L, q.next()?.id)
    }

    @Test
    fun `replaceContextKeepingCurrent finds the same station in a re-sorted list`() {
        val q = Queue()
        val original = listOf(station(1), station(2), station(3))
        q.setContext(original, 1)
        // User flips sort order — same stations, different order.
        val reordered = listOf(station(3), station(2), station(1))
        q.replaceContextKeepingCurrent(reordered, station(2))
        assertEquals(1, q.contextIndex)
        assertEquals(2L, q.context[q.contextIndex].id)
    }

    @Test
    fun `replaceContextKeepingCurrent falls back to streamUrl for id-zero stations`() {
        val q = Queue()
        val sameUrl = "http://example.com/stream"
        val a = RadioStation(id = 0L, name = "A", streamUrl = sameUrl)
        val b = RadioStation(id = 0L, name = "B", streamUrl = "http://other")
        q.setContext(listOf(a, b), 0)

        val reordered = listOf(b, a)
        q.replaceContextKeepingCurrent(reordered, a)
        assertEquals(1, q.contextIndex)
    }

    @Test
    fun `replaceContextKeepingCurrent pins to zero when station is gone`() {
        val q = Queue()
        q.setContext(listOf(station(1), station(2)), 1)
        q.replaceContextKeepingCurrent(listOf(station(3), station(4)), station(2))
        assertEquals(0, q.contextIndex)
    }

    @Test
    fun `setting an empty context resets the cursor`() {
        val q = Queue()
        q.setContext(listOf(station(1), station(2)), 1)
        q.setContext(emptyList(), 0)
        assertEquals(-1, q.contextIndex)
        assertFalse(q.hasNext())
        assertFalse(q.hasPrevious())
    }
}
