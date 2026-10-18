package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAccessTest {

    @Test
    fun hostMustBeIpOrLocalhost() {
        assertTrue(ServerAccess.hostAllowed("192.168.1.109:8080"))
        assertTrue(ServerAccess.hostAllowed("127.0.0.1"))
        assertTrue(ServerAccess.hostAllowed("localhost:8080"))
        assertTrue(ServerAccess.hostAllowed("[::1]:8080"))
        assertTrue(ServerAccess.hostAllowed(null))
        // DNS rebinding: a domain name pointing at the device's address.
        assertFalse(ServerAccess.hostAllowed("evil.example.com:8080"))
        assertFalse(ServerAccess.hostAllowed("192.168.1.109.nip.io"))
    }

    @Test
    fun crossSiteRequestsAreDetected() {
        assertFalse(ServerAccess.crossSite(mapOf("host" to "192.168.1.109:8080")))
        assertFalse(ServerAccess.crossSite(mapOf("host" to "192.168.1.109:8080", "origin" to "http://192.168.1.109:8080")))
        assertTrue(ServerAccess.crossSite(mapOf("host" to "127.0.0.1:8080", "origin" to "https://hbb-prod.tvp.pl")))
        assertTrue(ServerAccess.crossSite(mapOf("host" to "192.168.1.109:8080", "sec-fetch-site" to "cross-site")))
        assertFalse(ServerAccess.crossSite(mapOf("host" to "192.168.1.109:8080", "sec-fetch-site" to "same-origin")))
    }

    @Test
    fun tokenFromHeaderQueryOrCookie() {
        assertEquals("a", ServerAccess.presentedToken(mapOf("x-tv-token" to "a"), emptyMap()))
        assertEquals("b", ServerAccess.presentedToken(emptyMap(), mapOf("token" to "b")))
        assertEquals("c", ServerAccess.presentedToken(mapOf("cookie" to "x=1; tvtoken=c"), emptyMap()))
        assertEquals(null, ServerAccess.presentedToken(emptyMap(), emptyMap()))
        assertTrue(ServerAccess.isProtected("/recordings/123.ts"))
        assertFalse(ServerAccess.isProtected("/signal.json"))
    }
}
