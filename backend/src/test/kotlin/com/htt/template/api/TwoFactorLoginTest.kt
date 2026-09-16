package com.htt.template.api

import com.htt.template.support.IntegrationTest
import com.htt.template.support.assertJsonNotTrue
import com.htt.template.support.assertJsonTrue
import com.htt.template.support.assertStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * New-device login demands a code, wrong codes are rejected, the emailed code yields a real JWT, the
 * device is trusted from then on, and resend rotates the code.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class TwoFactorLoginTest : IntegrationTest() {

    @Test
    fun two_factor_login() {
        assertStatus(201, env.signup())

        // First login from an unknown device → 2FA required, no real JWT.
        val challenged = env.doJson(
            "POST",
            "/api/login",
            "",
            mapOf(
                "email" to env.email,
                "password" to env.password,
                "deviceId" to "dev-1",
            ),
        )
        assertStatus(200, challenged)
        assertJsonTrue("twoFactorRequired", challenged)
        val pending = challenged.body.path("token").asText("")
        assertFalse(
            pending.isEmpty(),
        ) { "2FA login returned no pending token: ${challenged.text}" }

        // Wrong code → 400, and the code stays pending.
        val wrong = env.doJson(
            "POST",
            "/api/login/verify",
            "",
            mapOf("token" to pending, "code" to "0000", "deviceId" to "dev-1"),
        )
        assertStatus(400, wrong)

        // Resend rotates the code; the newest queued email wins.
        val resent = env.doJson(
            "POST",
            "/api/login/resend",
            "",
            mapOf("token" to pending),
        )
        assertStatus(200, resent)

        // Correct (resent) code → real JWT that works on /api/me.
        val verified = env.doJson(
            "POST",
            "/api/login/verify",
            "",
            mapOf(
                "token" to pending,
                "code" to env.fetchLoginCode(env.email),
                "deviceId" to "dev-1",
            ),
        )
        assertStatus(200, verified)
        val token = verified.body.path("token").asText("")
        assertTrue(
            !token.isEmpty() && token != pending,
        ) { "verify returned no real token: ${verified.text}" }
        assertStatus(200, env.doJson("GET", "/api/me", token, null))

        // Same device again → 2FA skipped.
        val trusted = env.doJson(
            "POST",
            "/api/login",
            "",
            mapOf(
                "email" to env.email,
                "password" to env.password,
                "deviceId" to "dev-1",
            ),
        )
        assertStatus(200, trusted)
        assertJsonNotTrue("twoFactorRequired", trusted)
        val token2 = trusted.body.path("token").asText("")
        assertTrue(
            !token2.isEmpty() && token2 != pending,
        ) { trusted.text }
    }
}
