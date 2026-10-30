package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun versionComparison() {
        assertTrue(UpdateChecker.isNewer("1.4.1", "1.4.0"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.3"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.4.1-debug"))
        assertFalse(UpdateChecker.isNewer("1.4.1", "1.4.1-debug"))
        assertFalse(UpdateChecker.isNewer("1.4.0", "1.4.1"))
        assertFalse(UpdateChecker.isNewer("1.4", "1.4.0"))
    }

    @Test
    fun notesTakeTheChangesSection() {
        val body = """
            ### Changes
            - **Phone remote.** Scan the QR code
              on the TV.
            - Internet channels play
              next to broadcast ones. Streams are checked.
            - Uses `Media3`.

            ### Installation
            Download the APK.
        """.trimIndent()
        assertEquals("- Phone remote.\n- Internet channels play next to broadcast ones.\n- Uses Media3.", UpdateChecker.notes(body))
        assertEquals("Plain notes", UpdateChecker.notes("Plain notes"))
    }
}
