package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackVariantsTest {

    @Test
    fun replacesTheCodecClassOfAFourFieldId() {
        assertEquals(1, TrackVariants.codecClass("103:pol:0:1"))
        assertEquals("103:pol:0:3", TrackVariants.withClass("103:pol:0:1", 3))
        assertEquals("108:aux:1:0", TrackVariants.withClass("108:aux:1:1", 0))
    }

    @Test
    fun otherIdsAreLeftAlone() {
        assertNull(TrackVariants.codecClass("749"))
        assertNull(TrackVariants.withClass("749", 3))
        assertNull(TrackVariants.withClass("a:pol:0:1", 3))
        assertNull(TrackVariants.codecClass(null))
    }
}
