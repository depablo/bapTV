package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneRemoteServerTest {

    @Test
    fun deviceNameFromUserAgent() {
        assertEquals("Pixel 7", PhoneRemoteServer.deviceName(
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"))
        assertEquals("SM-G991B", PhoneRemoteServer.deviceName(
            "Mozilla/5.0 (Linux; Android 13; SM-G991B Build/TP1A.220624.014; wv) AppleWebKit/537.36"))
        // Reduced Chrome User-Agent hides the model.
        assertEquals("Android", PhoneRemoteServer.deviceName("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"))
        assertEquals("iPhone", PhoneRemoteServer.deviceName(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"))
        assertEquals("Browser", PhoneRemoteServer.deviceName(null))
    }
}
