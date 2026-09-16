package com.htt.template.api

import com.htt.template.support.IntegrationTest
import com.htt.template.support.assertStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** /api/me with no token, with a bad token, and with a valid one. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MeRequiresTokenTest : IntegrationTest() {

    @Test
    fun me_requires_token() {
        assertStatus(401, env.doJson("GET", "/api/me", "", null))

        env.signup()
        val token = env.loginAs(env.email, env.password)

        val me = env.doJson("GET", "/api/me", token, null)
        assertStatus(200, me)
        assertEquals(env.email, me.body.path("user").path("email").asText()) { "me.user.email = ${me.text}" }
    }
}
