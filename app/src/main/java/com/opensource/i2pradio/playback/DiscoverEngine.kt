package com.opensource.i2pradio.playback

import com.opensource.i2pradio.data.RadioStation

/**
 * Local-only "Discover similar stations" engine.
 *
 * When the queue ends and the user has Discover enabled, the service asks
 * this engine for one more station. The pick is drawn from the user's own
 * library (no network, no API call), ranked by tag overlap with the current
 * station and broken with country / language tiebreakers.
 *
 * This is intentionally simple. API-backed Discover (asking RadioBrowser for
 * tag-similar stations beyond the user's library) is a separate, opt-in
 * feature with its own privacy considerations and lives elsewhere.
 *
 * Privacy: this class does no I/O, no network access, and has no logging of
 * listening intent. Safe to call in any Force mode.
 */
object DiscoverEngine {

    /**
     * How many recently-played stations to exclude from the candidate pool.
     * Keeps Discover from immediately looping back to a station the user just
     * heard. Tuned conservatively; can be lifted to a setting later.
     */
    private const val RECENT_EXCLUSION_COUNT = 5

    /**
     * Pick the best Discover candidate from [library] given the [current]
     * station the user is finishing.
     *
     * Ranking is:
     *   1. Tag overlap (Jaccard-style: |intersection| / |union|).
     *   2. Same country code as a tiebreaker (+0.1 bonus).
     *   3. Same primary genre as a fallback for stations whose `tags` column
     *      is empty (mostly pre-migration user-added stations).
     *
     * Excludes:
     *   - the current station itself (matched by id, then by streamUrl)
     *   - the most recent [RECENT_EXCLUSION_COUNT] stations the user has
     *     played (using `lastPlayedAt`)
     *   - stations with no signal at all (no tags, no genre, no country)
     *
     * @return the best candidate, or null if nothing scored above zero.
     */
    fun suggestNext(
        current: RadioStation,
        library: List<RadioStation>
    ): RadioStation? {
        if (library.isEmpty()) return null

        val recentlyPlayedIds = library
            .filter { it.lastPlayedAt > 0L }
            .sortedByDescending { it.lastPlayedAt }
            .take(RECENT_EXCLUSION_COUNT)
            .mapNotNull { if (it.id != 0L) it.id else null }
            .toSet()

        val currentTags = tokenize(current.tags.ifBlank { current.genre })
        val currentCountry = current.countryCode.lowercase().ifBlank { current.country.lowercase() }
        val currentGenre = current.genre.lowercase()

        return library
            .asSequence()
            .filter { it !== current }
            .filter { !isSameStation(it, current) }
            .filter { it.id == 0L || it.id !in recentlyPlayedIds }
            .map { candidate -> candidate to score(candidate, currentTags, currentCountry, currentGenre) }
            .filter { it.second > 0.0 }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Score a candidate against the current station. Higher is more similar.
     */
    private fun score(
        candidate: RadioStation,
        currentTags: Set<String>,
        currentCountry: String,
        currentGenre: String
    ): Double {
        val candidateTags = tokenize(candidate.tags.ifBlank { candidate.genre })

        // Jaccard tag similarity. Range [0, 1].
        val tagScore = if (currentTags.isEmpty() && candidateTags.isEmpty()) {
            0.0
        } else {
            val intersection = currentTags.intersect(candidateTags).size.toDouble()
            val union = currentTags.union(candidateTags).size.toDouble()
            if (union > 0.0) intersection / union else 0.0
        }

        // Country tiebreaker. Small fixed bonus so it only matters when tag
        // scores are close.
        val countryBonus = if (currentCountry.isNotBlank() &&
            (candidate.countryCode.lowercase() == currentCountry ||
                candidate.country.lowercase() == currentCountry)
        ) 0.1 else 0.0

        // Genre fallback for stations that have no tags. Only contributes
        // when tag overlap is zero — otherwise tags already covered it.
        val genreFallback = if (tagScore == 0.0 &&
            currentGenre.isNotBlank() &&
            currentGenre != "other" &&
            candidate.genre.lowercase() == currentGenre
        ) 0.5 else 0.0

        return tagScore + countryBonus + genreFallback
    }

    /**
     * Two stations represent the same source if their RadioBrowser UUIDs
     * match, or — for ad-hoc / pre-RadioBrowser stations — their stream URLs
     * match. Falls back to id equality so saved-library stations don't
     * suggest themselves.
     */
    private fun isSameStation(a: RadioStation, b: RadioStation): Boolean {
        if (a.id != 0L && a.id == b.id) return true
        val au = a.radioBrowserUuid
        val bu = b.radioBrowserUuid
        if (!au.isNullOrEmpty() && au == bu) return true
        return a.streamUrl.isNotBlank() && a.streamUrl == b.streamUrl
    }

    /**
     * Split a comma-separated tag string into a normalized set of lowercase
     * tokens. Strips empties, trims whitespace, and skips network-type tags
     * (`i2p`, `tor`) so a station's privacy layer doesn't artificially
     * cluster it with other privacy stations of unrelated genres.
     */
    private fun tokenize(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return raw.split(',')
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .filter { it != "i2p" && it != "tor" }
            .toSet()
    }
}
