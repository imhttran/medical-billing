package com.htt.template.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProfileValidationTest {

    @Test
    fun acceptsACompleteForm() {
        assertNull(ProfileService.validate(valid()))
    }

    @Test
    fun reportsEveryMissingRequiredFieldInOneMessage() {
        val incomplete = ProfileCommand("", "", "", null, "", "", null, "", "", null, null, null)
        assertEquals(
            "Missing required field(s): firstName, lastName, address, state, zip, phone, " +
                "communicationPreference",
            ProfileService.validate(incomplete),
        )
    }

    @Test
    fun rejectsTheFirstBrokenRule() {
        assertEquals(
            "communicationPreference must be one of: email, text, phone",
            ProfileService.validate(withPreference(valid(), "carrier-pigeon")),
        )
        assertEquals("Phone number is invalid", ProfileService.validate(withPhone(valid(), "nope")))
        assertEquals("Zip code is invalid", ProfileService.validate(withZip(valid(), "123")))
        assertEquals("State is invalid", ProfileService.validate(withState(valid(), "XX")))
        assertEquals("Country is invalid", ProfileService.validate(withCountry(valid(), "ZZ")))
        assertEquals(
            "Additional email address is invalid",
            ProfileService.validate(withAltEmail(valid(), "nope")),
        )
        assertEquals("LinkedIn URL is invalid", ProfileService.validate(withLinkedin(valid(), "nope")))
        assertEquals("GitHub URL is invalid", ProfileService.validate(withGithub(valid(), "nope")))
    }

    @Test
    fun acceptsBlankOptionalFields() {
        assertNull(
            ProfileService.validate(
                ProfileCommand(
                    "Test", "User", "1 Test St", "", "CA", "94043", "",
                    "555-123-4567", "email", "", "", "",
                ),
            ),
        )
    }

    private fun valid(): ProfileCommand = ProfileCommand(
        "Test", "User", "1 Test St", null, "CA", "94043", null, "555-123-4567", "email", null, null, null,
    )

    private fun withPreference(base: ProfileCommand, value: String): ProfileCommand = ProfileCommand(
        base.firstName, base.lastName, base.address, base.address2, base.state, base.zip, base.country, base.phone,
        value, base.linkedin, base.github, base.altEmail,
    )

    private fun withPhone(base: ProfileCommand, value: String): ProfileCommand = ProfileCommand(
        base.firstName, base.lastName, base.address, base.address2, base.state, base.zip, base.country, value,
        base.communicationPreference, base.linkedin, base.github, base.altEmail,
    )

    private fun withZip(base: ProfileCommand, value: String): ProfileCommand = ProfileCommand(
        base.firstName, base.lastName, base.address, base.address2, base.state, value, base.country, base.phone,
        base.communicationPreference, base.linkedin, base.github, base.altEmail,
    )

    private fun withState(base: ProfileCommand, value: String): ProfileCommand = ProfileCommand(
        base.firstName, base.lastName, base.address, base.address2, value, base.zip, base.country, base.phone,
        base.communicationPreference, base.linkedin, base.github, base.altEmail,
    )

    private fun withCountry(base: ProfileCommand, value: String): ProfileCommand = ProfileCommand(
        base.firstName, base.lastName, base.address, base.address2, base.state, base.zip, value, base.phone,
        base.communicationPreference, base.linkedin, base.github, base.altEmail,
    )

    private fun withAltEmail(base: ProfileCommand, value: String): ProfileCommand = ProfileCommand(
        base.firstName, base.lastName, base.address, base.address2, base.state, base.zip, base.country, base.phone,
        base.communicationPreference, base.linkedin, base.github, value,
    )

    private fun withLinkedin(base: ProfileCommand, value: String): ProfileCommand = ProfileCommand(
        base.firstName, base.lastName, base.address, base.address2, base.state, base.zip, base.country, base.phone,
        base.communicationPreference, value, base.github, base.altEmail,
    )

    private fun withGithub(base: ProfileCommand, value: String): ProfileCommand = ProfileCommand(
        base.firstName, base.lastName, base.address, base.address2, base.state, base.zip, base.country, base.phone,
        base.communicationPreference, base.linkedin, value, base.altEmail,
    )
}
