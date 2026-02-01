package com.opensource.i2pradio.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SleepTimerUtils.
 *
 * Tests cover:
 * 1. Duration formatting for various input values
 * 2. Boundary conditions (1 min, 720 min / 12 hours)
 * 3. Edge cases (0, negative values)
 * 4. Validation and coercion functions
 */
class SleepTimerUtilsTest {

    // ========================================================================
    // formatDuration Tests
    // ========================================================================

    @Test
    fun `formatDuration returns Off for zero minutes`() {
        assertEquals("Off", SleepTimerUtils.formatDuration(0))
    }

    @Test
    fun `formatDuration returns Off for negative minutes`() {
        assertEquals("Off", SleepTimerUtils.formatDuration(-1))
        assertEquals("Off", SleepTimerUtils.formatDuration(-100))
    }

    @Test
    fun `formatDuration formats minutes only for values under 60`() {
        assertEquals("1 min", SleepTimerUtils.formatDuration(1))
        assertEquals("5 min", SleepTimerUtils.formatDuration(5))
        assertEquals("15 min", SleepTimerUtils.formatDuration(15))
        assertEquals("30 min", SleepTimerUtils.formatDuration(30))
        assertEquals("45 min", SleepTimerUtils.formatDuration(45))
        assertEquals("59 min", SleepTimerUtils.formatDuration(59))
    }

    @Test
    fun `formatDuration formats hours only for exact hour values`() {
        assertEquals("1h", SleepTimerUtils.formatDuration(60))
        assertEquals("2h", SleepTimerUtils.formatDuration(120))
        assertEquals("3h", SleepTimerUtils.formatDuration(180))
        assertEquals("6h", SleepTimerUtils.formatDuration(360))
        assertEquals("12h", SleepTimerUtils.formatDuration(720))
    }

    @Test
    fun `formatDuration formats hours and minutes for mixed values`() {
        assertEquals("1h 1m", SleepTimerUtils.formatDuration(61))
        assertEquals("1h 30m", SleepTimerUtils.formatDuration(90))
        assertEquals("1h 45m", SleepTimerUtils.formatDuration(105))
        assertEquals("2h 15m", SleepTimerUtils.formatDuration(135))
        assertEquals("11h 59m", SleepTimerUtils.formatDuration(719))
    }

    @Test
    fun `formatDuration handles boundary value of 1 minute`() {
        assertEquals("1 min", SleepTimerUtils.formatDuration(SleepTimerUtils.MIN_DURATION_MINUTES))
    }

    @Test
    fun `formatDuration handles boundary value of 12 hours`() {
        assertEquals("12h", SleepTimerUtils.formatDuration(SleepTimerUtils.MAX_DURATION_MINUTES))
    }

    // ========================================================================
    // isValidDuration Tests
    // ========================================================================

    @Test
    fun `isValidDuration returns true for zero (off)`() {
        assertTrue(SleepTimerUtils.isValidDuration(0))
    }

    @Test
    fun `isValidDuration returns true for minimum value`() {
        assertTrue(SleepTimerUtils.isValidDuration(1))
    }

    @Test
    fun `isValidDuration returns true for maximum value`() {
        assertTrue(SleepTimerUtils.isValidDuration(720))
    }

    @Test
    fun `isValidDuration returns true for values within range`() {
        assertTrue(SleepTimerUtils.isValidDuration(15))
        assertTrue(SleepTimerUtils.isValidDuration(60))
        assertTrue(SleepTimerUtils.isValidDuration(360))
    }

    @Test
    fun `isValidDuration returns false for negative values`() {
        assertFalse(SleepTimerUtils.isValidDuration(-1))
        assertFalse(SleepTimerUtils.isValidDuration(-100))
    }

    @Test
    fun `isValidDuration returns false for values exceeding maximum`() {
        assertFalse(SleepTimerUtils.isValidDuration(721))
        assertFalse(SleepTimerUtils.isValidDuration(1000))
    }

    // ========================================================================
    // coerceDuration Tests
    // ========================================================================

    @Test
    fun `coerceDuration returns value unchanged when within range`() {
        assertEquals(1, SleepTimerUtils.coerceDuration(1))
        assertEquals(60, SleepTimerUtils.coerceDuration(60))
        assertEquals(360, SleepTimerUtils.coerceDuration(360))
        assertEquals(720, SleepTimerUtils.coerceDuration(720))
    }

    @Test
    fun `coerceDuration returns minimum for values below range`() {
        assertEquals(1, SleepTimerUtils.coerceDuration(0))
        assertEquals(1, SleepTimerUtils.coerceDuration(-1))
        assertEquals(1, SleepTimerUtils.coerceDuration(-100))
    }

    @Test
    fun `coerceDuration returns maximum for values above range`() {
        assertEquals(720, SleepTimerUtils.coerceDuration(721))
        assertEquals(720, SleepTimerUtils.coerceDuration(1000))
        assertEquals(720, SleepTimerUtils.coerceDuration(Int.MAX_VALUE))
    }

    // ========================================================================
    // Constants Tests
    // ========================================================================

    @Test
    fun `MIN_DURATION_MINUTES is 1`() {
        assertEquals(1, SleepTimerUtils.MIN_DURATION_MINUTES)
    }

    @Test
    fun `MAX_DURATION_MINUTES is 720 (12 hours)`() {
        assertEquals(720, SleepTimerUtils.MAX_DURATION_MINUTES)
    }
}
