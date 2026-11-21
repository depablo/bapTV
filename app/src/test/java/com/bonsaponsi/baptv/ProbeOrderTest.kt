package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeOrderTest {

    /** Runs discovery against a set of existing channel ids; returns the ids found and the number of probes. */
    private fun discover(order: ProbeOrder, existing: Set<Long>): Pair<Set<Long>, Int> {
        val found = HashSet<Long>()
        while (true) {
            val id = order.next() ?: break
            val exists = id in existing
            if (exists) found += id
            order.report(id, exists)
        }
        return found to order.probed
    }

    @Test
    fun oneBlockBelowTheCeiling() {
        val existing = (3914L..3971L).toSet()
        val (found, probes) = discover(ProbeOrder(3971, emptySet(), emptySet()), existing)
        assertEquals(existing, found)
        assertEquals(58 + ProbeOrder.MAX_MISSES, probes)
    }

    @Test
    fun otherInputsAboveTheBlockAreSkipped() {
        // 30 internet channels were added after the scan: the old walk stopped after 20 misses and found nothing.
        val existing = (3914L..3971L).toSet()
        val iptv = (3972L..4001L).toSet()
        val (found, probes) = discover(ProbeOrder(4001, emptySet(), iptv), existing)
        assertEquals(existing, found)
        assertEquals(58 + ProbeOrder.MAX_MISSES, probes)
    }

    @Test
    fun gapBetweenBlocksIsCrossedAtASeed() {
        // A single-frequency rescan put one multiplex at the top; the others stayed 40 ids lower.
        val lower = (3900L..3920L).toSet()
        val upper = (3961L..3970L).toSet()
        val seeds = setOf(3905L, 3962L)
        val (found, _) = discover(ProbeOrder(3970, seeds, emptySet()), lower + upper)
        assertEquals(lower + upper, found)
    }

    @Test
    fun withoutSeedsTheGapStopsTheWalk() {
        val lower = (3900L..3920L).toSet()
        val upper = (3961L..3970L).toSet()
        val (found, _) = discover(ProbeOrder(3970, emptySet(), emptySet()), lower + upper)
        assertEquals(upper, found)
    }

    @Test
    fun seedsOfOtherInputsAreIgnored() {
        // Channel 15 has a guide but belongs to another app: it must not become a probe.
        val existing = (100L..110L).toSet()
        val order = ProbeOrder(110, setOf(15L), setOf(15L))
        val (found, probes) = discover(order, existing)
        assertEquals(existing, found)
        assertEquals(11 + ProbeOrder.MAX_MISSES, probes)
    }

    @Test
    fun coarseSearchFindsTheWholeBlock() {
        // No known highest id: jumps of 30 hit the block, then it is walked from above its possible top.
        val existing = (1203L..1240L).toSet()
        val (found, _) = discover(ProbeOrder(null, emptySet(), emptySet()), existing)
        assertEquals(existing, found)
    }

    @Test
    fun probeBudgetEndsWithSeedsOnly() {
        val order = ProbeOrder(10_000, setOf(5L), emptySet())
        var last = 0L
        while (order.probed < ProbeOrder.MAX_PROBES) {
            val id = order.next() ?: break
            order.report(id, exists = true)
            last = id
        }
        assertTrue(last > 5)
        assertEquals(5L, order.next())
        order.report(5, exists = false)
        assertEquals(null, order.next())
    }
}
