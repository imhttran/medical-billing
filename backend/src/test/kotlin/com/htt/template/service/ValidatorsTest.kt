package com.htt.template.service

import com.htt.template.config.AppProperties
import java.util.regex.Pattern
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidatorsTest {

    @Test
    fun passwords() {
        assertNull(Validators.validatePassword("Valid123!"))
        assertEquals("Password must be at least 8 characters long", Validators.validatePassword("Ab1!"))
        assertEquals(
            "Password must contain at least one uppercase letter",
            Validators.validatePassword("lowercase1!"),
        )
        assertEquals("Password must contain at least one number", Validators.validatePassword("NoNumbers!"))
        assertEquals(
            "Password must contain at least one special character",
            Validators.validatePassword("NoSpecial1"),
        )
    }

    @Test
    fun emails() {
        assertTrue(Validators.isEmail("a@b.c"))
        assertFalse(Validators.isEmail("no-at-sign"))
        assertFalse(Validators.isEmail("no@domain"))
        assertFalse(Validators.isEmail("spaces in@email.com"))
    }

    @Test
    fun usStatesAndCountries() {
        assertTrue(Validators.isUsState("CA"))
        assertFalse(Validators.isUsState("XX"))
        assertTrue(Validators.isCountry("US"))
        assertFalse(Validators.isCountry("ZZ"))
    }

    @Test
    fun phoneNumbers() {
        assertTrue(Validators.isPhone("555-123-4567"))
        assertTrue(Validators.isPhone("(555) 123-4567"))
        assertTrue(Validators.isPhone("+1 555 123 4567"))
        assertFalse(Validators.isPhone("12345"))
    }

    @Test
    fun zipCodes() {
        assertTrue(Validators.isZip("94043"))
        assertFalse(Validators.isZip("9404"))
        assertFalse(Validators.isZip("94043-1234"))
    }

    @Test
    fun urls() {
        assertTrue(Validators.isUrl("https://example.com"))
        assertTrue(Validators.isUrl("http://example.com/profile"))
        assertFalse(Validators.isUrl("ftp://example.com"))
        assertFalse(Validators.isUrl("not a url"))
    }

    @Test
    fun developmentLoginCodesAreFixedAndOthersAreFourDigits() {
        val development = AppProperties(env = "development")
        assertEquals("1234", Tokens(development).randomCode())

        val test = AppProperties(env = "test")
        val fourDigits = Pattern.compile("\\d{4}")
        val tokens = Tokens(test)
        for (i in 0 until 50) {
            assertTrue(fourDigits.matcher(tokens.randomCode()).matches())
        }
    }
}
