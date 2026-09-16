package com.htt.template.api

import com.htt.template.support.IntegrationTest
import com.htt.template.support.assertMessage
import com.htt.template.support.assertStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** Patching a role to something that is not client|staff|admin. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PatchRoleInvalidValueTest : IntegrationTest() {

    @Test
    fun patch_role_invalid_value() {
        env.signup()
        val token = env.loginAs(env.email, env.password)
        env.fillProfile(token)
        env.setRole(env.email, "admin")

        val id = env.ownUserId(token)
        val response = env.doJson(
            "PATCH",
            "/api/users/$id/role",
            token,
            mapOf("role" to "wizard"),
        )
        assertStatus(400, response)
        assertMessage("role must be one of: client, staff, admin", response)
    }
}
