package com.whitevpn.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L2tpProfileValidationTest {
    private val example = L2tpProfile(
        name = "Офис", host = "vpn.example.com",
        username = "testuser", password = "somepassword",
        preSharedKey = "verysecretpsk"
    )

    @Test fun acceptsStandardPskServer() {
        assertTrue(L2tpProfileValidation.isValid(example))
    }

    @Test fun validatesRequiredIpsecAndLoginFields() {
        assertFalse(L2tpProfileValidation.isValid(example.copy(host = "-badhost")))
        assertFalse(L2tpProfileValidation.isValid(example.copy(password = "")))
        assertFalse(L2tpProfileValidation.isValid(example.copy(preSharedKey = "")))
        assertFalse(L2tpProfileValidation.isValid(example.copy(host = "example.com;rm")))
    }

    @Test fun rejectsNewlineInjection() {
        assertFalse(L2tpProfileValidation.isValid(
            example.copy(name = "Office\nBad")
        ))
        assertFalse(L2tpProfileValidation.isValid(
            example.copy(preSharedKey = "key\nInjected")
        ))
    }
}
