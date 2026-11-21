package com.bonsaponsi.baptv

/**
 * The order in which channel discovery probes TvContract ids of the tuner input.
 *
 * A scan gives its channels consecutive ids, so discovery walks down one id at a time from the highest id and
 * stops after a run of misses. Two things break a plain walk:
 * - rows of other inputs (internet channels, preview channels) take ids above or between the tuner's blocks;
 *   their ids are visible in TvContract, so they are skipped without a probe and do not count as misses;
 * - a single-frequency scan replaces one multiplex with a new block at the top, leaving a gap where the old one was.
 *   When a run of misses ends, the walk continues at the next of the [seeds] (channels with a guide, the last
 *   discovered list) instead of stopping: it probes the seed, walks up from it, then down from it.
 *
 * Without a known highest id the walk first jumps every [COARSE_STEP] ids from [COARSE_MAX] to find a block.
 */
class ProbeOrder(startId: Long?, seeds: Collection<Long>, private val skip: Set<Long>) {
    private val checked = HashSet<Long>()
    private val pendingSeeds = seeds.filter { it >= 1 && it !in skip }.toSortedSet(reverseOrder())
    private var coarse = startId == null
    private var nextId = startId ?: COARSE_MAX
    private var misses = 0
    private var runLimit = MAX_MISSES_BEFORE_FIRST
    /** Walking up from [seedBase] before walking down from it. */
    private var upward = false
    private var seedBase = 0L

    /** Ids probed so far. */
    val probed: Int get() = checked.size

    /** The next id to probe, or null when discovery is done. */
    fun next(): Long? {
        val id = pick() ?: return null
        checked += id
        pendingSeeds.remove(id)
        return id
    }

    /** The probe of [id] (returned by [next]) found a channel or not. */
    fun report(id: Long, exists: Boolean) {
        if (!exists) {
            misses++
            return
        }
        misses = 0
        runLimit = MAX_MISSES
        if (coarse) {
            // Hit a block – now go one by one from above its possible top downward.
            coarse = false
            nextId = id + BLOCK_SPAN
            runLimit = BLOCK_SPAN.toInt() + 1
        }
    }

    private fun pick(): Long? {
        if (checked.size >= MAX_PROBES) return pendingSeeds.firstOrNull { it !in checked }
        if (coarse) {
            while (nextId >= 1 && (nextId in checked || nextId in skip)) nextId -= COARSE_STEP
            if (nextId >= 1) return nextId.also { nextId -= COARSE_STEP }
            coarse = false
            return jumpToSeed()
        }
        if (upward) {
            while (nextId in skip) nextId++
            // Up to the end of the block, or to ids already walked.
            if (misses < MAX_MISSES && nextId !in checked) return nextId++
            upward = false
            nextId = seedBase - 1
            misses = 0
        }
        while (nextId >= 1 && (nextId in checked || nextId in skip)) nextId--
        if (misses < runLimit && nextId >= 1) return nextId--
        return jumpToSeed()
    }

    /** Probes the highest seed not probed yet, then walks up and down from it; null when none is left. */
    private fun jumpToSeed(): Long? {
        val seed = pendingSeeds.firstOrNull { it !in checked } ?: return null
        seedBase = seed
        upward = true
        nextId = seed + 1
        misses = 0
        runLimit = MAX_MISSES
        return seed
    }

    companion object {
        const val MAX_MISSES = 8
        const val MAX_MISSES_BEFORE_FIRST = 20
        const val MAX_PROBES = 600
        const val COARSE_MAX = 3000L
        const val COARSE_STEP = 30L
        const val BLOCK_SPAN = 60L
    }
}
