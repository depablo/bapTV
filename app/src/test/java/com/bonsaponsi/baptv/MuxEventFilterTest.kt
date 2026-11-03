package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MuxEventFilterTest {

    private val filter = MuxEventFilter(settleMs = 5000)

    @Test
    fun endAfterProgressCountsOnce() {
        filter.started(0)
        assertEquals(MuxEventFilter.Verdict.IGNORE, filter.onEvent("running", 1000))
        assertEquals(MuxEventFilter.Verdict.END, filter.onEvent("completed", 2000))
        // progress=100 and "completed" both arrive – the second one is ignored.
        assertEquals(MuxEventFilter.Verdict.IGNORE, filter.onEvent("complete", 2100))
    }

    @Test
    fun lateEndOfThePreviousMultiplexDoesNotEndTheNextOne() {
        filter.started(0)
        filter.timedOut()
        // The previous multiplex's end arrives just after the next request.
        filter.started(120_000)
        assertEquals(MuxEventFilter.Verdict.DEFER, filter.onEvent("completed", 121_000))
        // The new multiplex is being installed – the deferred event was a late one.
        assertEquals(MuxEventFilter.Verdict.IGNORE, filter.onEvent("running", 122_000))
        assertNull(filter.settle())
        assertEquals(MuxEventFilter.Verdict.END, filter.onEvent("failed", 130_000))
    }

    @Test
    fun quickEndWithoutProgressStandsAfterSettling() {
        filter.started(0)
        assertEquals(MuxEventFilter.Verdict.DEFER, filter.onEvent("completed", 500))
        assertEquals(5000L, filter.settleAtMs)
        assertEquals("completed", filter.settle())
        assertEquals(MuxEventFilter.Verdict.IGNORE, filter.onEvent("completed", 6000))
    }

    @Test
    fun endWithoutProgressLaterThanSettleCounts() {
        filter.started(0)
        assertEquals(MuxEventFilter.Verdict.END, filter.onEvent("cancelled", 6000))
    }

    @Test
    fun eventsAfterTimeoutAreIgnored() {
        filter.started(0)
        filter.timedOut()
        assertEquals(MuxEventFilter.Verdict.IGNORE, filter.onEvent("completed", 1000))
    }
}
