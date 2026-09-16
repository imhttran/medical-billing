package com.htt.template.api

import com.htt.template.support.IntegrationTest
import com.htt.template.support.assertMessage
import com.htt.template.support.assertStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * An admin deleting their own account is blocked before the DB is touched.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class DeleteOwnAccountTest : IntegrationTest() {

    @Test
    fun delete_own_account() {
        env.signup()
        val token = env.loginAs(env.email, env.password)
        env.fillProfile(token)
        env.setRole(env.email, "admin")

        val id = env.ownUserId(token)
        val response = env.doJson("DELETE", "/api/users/$id", token, null)
        assertStatus(400, response)
        assertMessage("Cannot delete your own account", response)
    }
}
