package com.htt.template.api

import com.htt.template.support.IntegrationTest
import com.htt.template.support.assertStatus
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** Signup rejects a weak password. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SignupWeakPasswordTest : IntegrationTest() {

    @Test
    fun signup_weak_password() {
        val response = env.doJson(
            "POST",
            "/api/signup",
            "",
            mapOf("email" to env.email, "password" to "S1!"),
        )

        assertStatus(400, response)
        assertTrue(
            response.body.path("message").asText().contains("at least 8 characters"),
        ) { "message = ${response.text}, want it to mention at least 8 characters" }
    }
}
