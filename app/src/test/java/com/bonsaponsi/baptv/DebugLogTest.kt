package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogTest {

    @Test
    fun incrementalReadReturnsOnlyNewEntries() {
        DebugLog.clear()
        val start = DebugLog.since(Long.MAX_VALUE, null).next
        DebugLog.log("T", "a")
        DebugLog.log("T", "b")
        val first = DebugLog.since(start, DebugLog.bootId)
        assertEquals(2, first.lines.size)
        assertTrue(first.lines[1].endsWith("I/T: b"))
        DebugLog.log("T", "c")
        val second = DebugLog.since(first.next, DebugLog.bootId)
        assertEquals(1, second.lines.size)
        assertTrue(second.lines[0].endsWith("I/T: c"))
        assertEquals(0, DebugLog.since(second.next, DebugLog.bootId).lines.size)
    }

    @Test
    fun otherBootIdRestartsFromProcessStart() {
        DebugLog.log("T", "x")
        val slice = DebugLog.since(Long.MAX_VALUE, DebugLog.bootId - 1)
        // Without init() the process start is entry 0, so the client gets the whole log.
        assertEquals(DebugLog.snapshot().size, slice.lines.size)
    }

    @Test
    fun numbersStayMonotonicWhenOldEntriesAreDropped() {
        val before = DebugLog.since(Long.MAX_VALUE, null).next
        repeat(3100) { DebugLog.log("T", "n$it") }
        val after = DebugLog.since(before + 3099, null)
        assertEquals(listOf("n3099"), after.lines.map { it.substringAfter("I/T: ") })
        assertEquals(before + 3100, after.next)
    }
}
