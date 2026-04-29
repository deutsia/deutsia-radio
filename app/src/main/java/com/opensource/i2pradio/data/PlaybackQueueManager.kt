package com.opensource.i2pradio.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Three-layer queue model.
 *
 * Layers consulted in priority order when picking the next station:
 *   1. Manual queue   - explicit "Add to queue" entries, FIFO.
 *   2. Context list   - snapshot of the visible filtered/sorted list at play
 *                       time. Walking forward/back walks this list.
 *   3. Discover       - opt-in. When the two layers above are exhausted, the
 *                       caller may consult [discoverHint] and ask Radio
 *                       Browser for a similar station.
 *
 * The manager itself does not perform network calls; Discover is triggered
 * by [RadioViewModel] using the hint exposed here.
 */
object PlaybackQueueManager {

    private val manualQueueList = mutableListOf<RadioStation>()
    private val _manualQueue = MutableLiveData<List<RadioStation>>(emptyList())
    val manualQueue: LiveData<List<RadioStation>> = _manualQueue

    private var contextList: List<RadioStation> = emptyList()
    private var contextIndex: Int = -1

    private val _contextSize = MutableLiveData(0)
    val contextSize: LiveData<Int> = _contextSize

    fun addToManualQueue(station: RadioStation) {
        manualQueueList.add(station)
        publishManualQueue()
    }

    fun removeFromManualQueueAt(index: Int) {
        if (index in manualQueueList.indices) {
            manualQueueList.removeAt(index)
            publishManualQueue()
        }
    }

    fun clearManualQueue() {
        if (manualQueueList.isEmpty()) return
        manualQueueList.clear()
        publishManualQueue()
    }

    fun getManualQueue(): List<RadioStation> = manualQueueList.toList()

    fun isManualQueueEmpty(): Boolean = manualQueueList.isEmpty()

    /**
     * Snapshot the visible filtered+sorted list at play time. The current
     * station's position in that list is tracked so [advanceContext] /
     * [rewindContext] can walk forward/back through the list.
     *
     * Stations are matched first by stable id, falling back to streamUrl for
     * unsaved (id == 0) stations from Browse.
     */
    fun setContext(stations: List<RadioStation>, currentStation: RadioStation?) {
        contextList = stations.toList()
        contextIndex = currentStation?.let { findIndexOf(it) } ?: -1
        _contextSize.value = contextList.size
    }

    fun clearContext() {
        contextList = emptyList()
        contextIndex = -1
        _contextSize.value = 0
    }

    fun getContextSize(): Int = contextList.size

    fun getContextIndex(): Int = contextIndex

    /**
     * Pop the head of the manual queue, if any. Returned station is removed.
     */
    fun popManualQueue(): RadioStation? {
        if (manualQueueList.isEmpty()) return null
        val station = manualQueueList.removeAt(0)
        publishManualQueue()
        return station
    }

    /**
     * Advance forward through the context list. Returns null when the list
     * has no further entries (caller can then try Discover).
     */
    fun advanceContext(): RadioStation? {
        if (contextList.isEmpty()) return null
        val next = contextIndex + 1
        if (next !in contextList.indices) return null
        contextIndex = next
        return contextList[next]
    }

    /**
     * Step back one entry in the context list. Returns null at the top.
     * The manual queue is forward-only and is not affected by this call.
     */
    fun rewindContext(): RadioStation? {
        if (contextList.isEmpty()) return null
        val prev = contextIndex - 1
        if (prev !in contextList.indices) return null
        contextIndex = prev
        return contextList[prev]
    }

    /**
     * Notify the manager that [station] has just started playing so the
     * context cursor stays in sync. Useful when the user picks a station
     * directly out of the manual queue.
     */
    fun notifyNowPlaying(station: RadioStation) {
        val idx = findIndexOf(station)
        if (idx >= 0) contextIndex = idx
    }

    /**
     * Hint used by the Discover layer: tag/country pulled off the most
     * recently played station so Radio Browser can pick something similar.
     */
    data class DiscoverHint(
        val tag: String,
        val countryCode: String,
        val excludeUuids: Set<String>
    )

    fun discoverHint(currentStation: RadioStation?): DiscoverHint? {
        val seed = currentStation
            ?: contextList.getOrNull(contextIndex)
            ?: return null

        val excluded = buildSet {
            contextList.forEach { it.radioBrowserUuid?.takeIf(String::isNotEmpty)?.let(::add) }
            manualQueueList.forEach { it.radioBrowserUuid?.takeIf(String::isNotEmpty)?.let(::add) }
            seed.radioBrowserUuid?.takeIf(String::isNotEmpty)?.let(::add)
        }

        return DiscoverHint(
            tag = seed.genre,
            countryCode = seed.countryCode,
            excludeUuids = excluded
        )
    }

    private fun findIndexOf(station: RadioStation): Int {
        if (station.id != 0L) {
            val byId = contextList.indexOfFirst { it.id == station.id }
            if (byId >= 0) return byId
        }
        if (!station.radioBrowserUuid.isNullOrEmpty()) {
            val byUuid = contextList.indexOfFirst {
                it.radioBrowserUuid == station.radioBrowserUuid
            }
            if (byUuid >= 0) return byUuid
        }
        return contextList.indexOfFirst { it.streamUrl == station.streamUrl }
    }

    private fun publishManualQueue() {
        _manualQueue.value = manualQueueList.toList()
    }
}
