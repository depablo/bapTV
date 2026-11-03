package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PhoneRemoteTest {

    @Test
    fun proofDependsOnTokenAndNonce() {
        val hash = PhoneRemote.tokenHash("0123456789abcdef0123456789abcdef")
        val proof = PhoneRemote.proof(hash, "00112233445566778899aabbccddeeff")
        assertEquals(64, proof.length)
        assertEquals(proof, PhoneRemote.proof(hash, "00112233445566778899aabbccddeeff"))
        assertNotEquals(proof, PhoneRemote.proof(hash, "ffeeddccbbaa99887766554433221100"))
        assertNotEquals(proof, PhoneRemote.proof(PhoneRemote.tokenHash("other"), "00112233445566778899aabbccddeeff"))
    }
}
