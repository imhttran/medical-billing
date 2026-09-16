package com.htt.template.api

import com.htt.template.support.IntegrationTest
import com.htt.template.support.TestEnv
import com.htt.template.support.assertMessage
import com.htt.template.support.assertStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The gate lift, validation and the unique-violation path, end to end.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ProfileFlowTest : IntegrationTest() {

    /** One rejected form and the message the route must answer it with. */
    private data class InvalidCase(val payload: Map<String, Any?>, val want: String)

    @Test
    fun profile_flow() {
        env.signup()
        val token = env.loginAs(env.email, env.password)

        // Before saving, the profile is a 200 with null (the absence is the gate).
        val empty = env.doJson("GET", "/api/profile", token, null)
        assertStatus(200, empty)
        assertTrue(
            empty.body.path("profile").isNull(),
        ) { "body ${empty.text}" }

        env.fillProfile(token)

        // Second save hits the unique constraint → "Profile already exists".
        val duplicate = env.doJson(
            "POST",
            "/api/profile",
            token,
            TestEnv.profileBody(),
        )
        assertStatus(400, duplicate)
        assertMessage("Profile already exists", duplicate)

        // The saved row comes back with camelCase fields and the US default.
        val saved = env.doJson("GET", "/api/profile", token, null)
        assertStatus(200, saved)
        assertEquals(
            "Test",
            saved.body.path("profile").path("firstName").asText(),
        ) { "body ${saved.text}" }
        assertEquals(
            "email",
            saved
                .body
                .path("profile")
                .path("communicationPreference")
                .asText(),
        ) { "body ${saved.text}" }
        assertEquals(
            "US",
            saved.body.path("profile").path("country").asText(),
        ) { "body ${saved.text}" }
        assertTrue(
            saved.body.path("profile").path("address2").isNull(),
        ) { "body ${saved.text}" }

        // Validation runs before the insert, so these 400s don't mention profiles.
        val cases = listOf(
            InvalidCase(
                mapOf(
                    "firstName" to "Test",
                    "lastName" to "User",
                    "address" to "1 Test St",
                    "state" to "CA",
                    "zip" to "94043",
                    "phone" to "not-a-phone",
                    "communicationPreference" to "email",
                ),
                "Phone number is invalid",
            ),
            InvalidCase(
                mapOf(
                    "firstName" to "Test",
                    "lastName" to "User",
                    "address" to "1 Test St",
                    "state" to "XX",
                    "zip" to "94043",
                    "phone" to "555-123-4567",
                    "communicationPreference" to "email",
                ),
                "State is invalid",
            ),
            InvalidCase(
                mapOf(
                    "firstName" to "Test",
                    "lastName" to "User",
                    "address" to "1 Test St",
                    "state" to "CA",
                    "phone" to "555-123-4567",
                    "communicationPreference" to "email",
                ),
                "Missing required field(s): zip",
            ),
        )
        for (rejected in cases) {
            val response = env.doJson(
                "POST",
                "/api/profile",
                token,
                rejected.payload,
            )
            assertStatus(400, response)
            assertMessage(rejected.want, response)
        }
    }
}
