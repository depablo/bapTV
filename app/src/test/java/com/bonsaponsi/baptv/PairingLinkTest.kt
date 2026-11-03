package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {

    @Test
    fun readsAddressPortAndCodeFromTheTvQrCode() {
        val link = RemoteActivity.parsePairingLink("http://192.168.1.20:8090/pair?code=123456")
        assertEquals("192.168.1.20", link?.host)
        assertEquals(8090, link?.port)
        assertEquals("123456", link?.code)
    }

    @Test
    fun ignoresOtherParametersAroundTheCode() {
        val link = RemoteActivity.parsePairingLink("http://box.local:8090/pair?x=1&code=654321")
        assertEquals("654321", link?.code)
    }

    @Test
    fun rejectsOtherAddressesAndPaths() {
        assertNull(RemoteActivity.parsePairingLink("https://192.168.1.20:8090/pair?code=123456"))
        assertNull(RemoteActivity.parsePairingLink("http://192.168.1.20:8090/remote.html?code=123456"))
        assertNull(RemoteActivity.parsePairingLink("https://example.com/"))
    }

    @Test
    fun rejectsCodesThatAreNotSixDigits() {
        assertNull(RemoteActivity.parsePairingLink("http://192.168.1.20:8090/pair"))
        assertNull(RemoteActivity.parsePairingLink("http://192.168.1.20:8090/pair?code=12345"))
        assertNull(RemoteActivity.parsePairingLink("http://192.168.1.20:8090/pair?code=12345a"))
    }

    @Test
    fun rejectsTextThatIsNotAnAddress() {
        assertNull(RemoteActivity.parsePairingLink("not a link at all"))
        assertNull(RemoteActivity.parsePairingLink(""))
    }
}
