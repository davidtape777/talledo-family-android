package com.talledofamily.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyAccessTest {
    @Test fun acceptsOnlySixDigits() {
        assertTrue(isValidFamilyCode("777777"))
        assertFalse(isValidFamilyCode("12345"))
        assertFalse(isValidFamilyCode("ABC123"))
    }
}
