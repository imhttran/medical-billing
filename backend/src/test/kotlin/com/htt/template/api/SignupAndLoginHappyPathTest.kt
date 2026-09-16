package com.htt.template.api

import com.htt.template.support.IntegrationTest
import com.htt.template.support.assertJsonTrue
import com.htt.template.support.assertStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** Signup then login, end to end. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SignupAndLoginHappyPathTest : IntegrationTest() {

    @Test
    fun signup_and_login_happy_path() {
        val signup = env.doJson(
            "POST",
            "/api/signup",
            "",
            mapOf("email" to env.email, "password" to env.password),
        )
        assertStatus(201, signup)
        assertJsonTrue("success", signup)

        val login = env.doJson(
            "POST",
            "/api/login",
            "",
            mapOf("email" to env.email, "password" to env.password),
        )
        assertStatus(200, login)
        assertFalse(
            login.body.path("token").asText("").isEmpty(),
        ) { "login returned no token: ${login.text}" }
    }
}
