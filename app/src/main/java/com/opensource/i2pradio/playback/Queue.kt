package com.opensource.i2pradio.playback

import com.opensource.i2pradio.data.RadioStation

/**
 * Playback queue model.
 *
 * Three sources of "what plays next," in priority order:
 *
 *  1. **Manual queue** — stations the user explicitly enqueued via "Add to
 *     queue" or "Play next." Consumed FIFO; once a manual entry plays, it is
 *     removed. Skip-previous never walks back into the manual queue (that
 *     would re-insert popped entries, which is surprising). Manual entries
 *     are inserted *before* the rest of the context list resumes, so a user
 *     who tapped "Add to queue" hears their pick after the current station
 *     ends without losing the list they were browsing.
 *  2. **Context list** — the visible filtered+sorted list at play time
 *     (Library / Favorites / a Browse result page). [contextIndex] is the
 *     cursor; skip-next/skip-previous walk it.
 *  3. **Discover** — handled outside this class. When [next] returns null
 *     and Discover is enabled, the caller asks DiscoverEngine for one more
 *     station and appends it via [appendToContext].
 *
 * The class is pure data — no Android dependencies, no I/O. Persistence and
 * playback wiring live in the service.
 */
class Queue {

    private val manual = ArrayDeque<RadioStation>()

    /** Stations passed in as the "list the user was browsing" at play time. */
    var context: List<RadioStation> = emptyList()
        private set

    /** Index into [context]. -1 means "context not started yet." */
    var contextIndex: Int = -1
        private set

    /**
     * The list of pending manual-queue entries, in the order they will play.
     * Returned as a snapshot so callers can render UI without worrying about
     * concurrent mutation.
     */
    fun manualSnapshot(): List<RadioStation> = manual.toList()

    /**
     * Replace the context list and set the playing cursor. Use this when the
     * user taps a station in a list — the list is the context, the tapped
     * station is current. Manual queue is preserved.
     *
     * If [index] is out of range, falls back to 0 (or -1 for an empty list).
     */
    fun setContext(stations: List<RadioStation>, index: Int) {
        context = stations
        contextIndex = when {
            stations.isEmpty() -> -1
            index < 0 -> 0
            index >= stations.size -> stations.size - 1
            else -> index
        }
    }

    /**
     * Replace the context list while keeping the same current station. Used
     * when the user re-sorts or re-filters the library mid-playback so that
     * skip-next/prev follows the new order, and when the persisted context
     * is restored from prefs after a service rebirth.
     *
     * If [keepCurrent] is in [stations] (matched by id, then by streamUrl
     * as a fallback for ad-hoc Browse stations with id=0), the cursor lands
     * on its new index. Otherwise the cursor goes to 0 — the user keeps
     * playing what they're playing, and the next skip walks the new list.
     */
    fun replaceContextKeepingCurrent(stations: List<RadioStation>, keepCurrent: RadioStation?) {
        context = stations
        contextIndex = when {
            stations.isEmpty() -> -1
            keepCurrent == null -> 0
            else -> {
                val byId = stations.indexOfFirst { it.id != 0L && it.id == keepCurrent.id }
                if (byId >= 0) byId
                else stations.indexOfFirst { it.streamUrl == keepCurrent.streamUrl }.coerceAtLeast(0)
            }
        }
    }

    /**
     * Add a station to play immediately after the current one finishes,
     * ahead of any other manual entries. Equivalent to "Play next."
     */
    fun playNext(station: RadioStation) {
        manual.addFirst(station)
    }

    /**
     * Add a station to the tail of the manual queue. Equivalent to
     * "Add to queue."
     */
    fun addToQueue(station: RadioStation) {
        manual.addLast(station)
    }

    /**
     * Remove a manual-queue entry by reference. Used by the queue UI.
     */
    fun removeFromManual(station: RadioStation): Boolean = manual.remove(station)

    /**
     * Clear the manual queue. Used when the user explicitly empties it from
     * the queue UI; not used during normal playback.
     */
    fun clearManual() {
        manual.clear()
    }

    /**
     * Pop the head of the manual queue if non-empty; otherwise advance the
     * context cursor by one.
     *
     * @return the next station to play, or null if both manual and context
     *   are exhausted (caller decides whether to wrap, stop, or invoke
     *   Discover).
     */
    fun next(): RadioStation? {
        if (manual.isNotEmpty()) {
            return manual.removeFirst()
        }
        if (context.isEmpty()) return null
        // contextIndex == -1 means "context not started" — first next() lands
        // on index 0. Normal advance from index N lands on N+1.
        val target = contextIndex + 1
        if (target >= context.size) return null
        contextIndex = target
        return context[target]
    }

    /**
     * Walk the context cursor back by one. Manual-queue entries are not
     * re-inserted — popping them is one-way by design.
     *
     * @return the previous context station, or null if already at the start
     *   of the context (or the context is empty).
     */
    fun previous(): RadioStation? {
        if (context.isEmpty()) return null
        val target = contextIndex - 1
        if (target < 0) return null
        contextIndex = target
        return context[target]
    }

    /**
     * Append a station to the tail of the context list. Used by Discover to
     * extend the queue when both manual and context are exhausted, so a
     * subsequent [next] returns it.
     */
    fun appendToContext(station: RadioStation) {
        context = context + station
    }

    /** True when [next] would return null without Discover. */
    fun isExhausted(): Boolean {
        if (manual.isNotEmpty()) return false
        if (context.isEmpty()) return true
        return contextIndex + 1 >= context.size
    }

    /**
     * Whether the user can meaningfully press skip-next right now. False when
     * the queue is empty or already at the last context entry with no manual
     * entries waiting. UI should hide / disable the next button accordingly.
     */
    fun hasNext(): Boolean = !isExhausted()

    /**
     * Whether the user can meaningfully press skip-previous right now. Only
     * the context list supports going back; manual entries don't.
     */
    fun hasPrevious(): Boolean = context.isNotEmpty() && contextIndex > 0
}
