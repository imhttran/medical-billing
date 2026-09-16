package com.htt.template.api

import com.htt.template.support.IntegrationTest
import com.htt.template.support.assertStatus
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Client rejected, staff allowed (non-admin rows only), admin allowed.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class UsersRbacTest : IntegrationTest() {

    @Test
    fun users_rbac() {
        env.signup()
        val token = env.loginAs(env.email, env.password)
        env.fillProfile(token) // lift the profile gate so RBAC is what's under test

        // Client is rejected.
        assertStatus(403, env.doJson("GET", "/api/users", token, null))

        // Staff is allowed.
        env.setRole(env.email, "staff")
        val staff = env.doJson("GET", "/api/users", token, null)
        assertStatus(200, staff)
        assertTrue(
            staff.body.path("users").isArray(),
        ) { "staff: missing users key: ${staff.text}" }

        // Admin is allowed.
        env.setRole(env.email, "admin")
        assertStatus(200, env.doJson("GET", "/api/users", token, null))
    }
}
