package com.htt.billing.identity

import com.htt.billing.security.RoleCodes
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The users list carries each account's billing roles. That is the only place
 * the screen can show them, since the `role` beside them is the legacy ranked
 * column and says nothing about what a user may do.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class UsersListRoleCodesTest : BillingApiTest() {

    @Test
    fun listCarriesEachUsersBillingRoles() {
        // PRACTICE_ADMIN already holds USER_VIEW, which is what the list asks for.
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)

        val billerId = insertUser("biller")
        assign(billerId, RoleCodes.BILLER, practiceA.id)

        val response = env.doJson("GET", "/api/users", admin.token, null)
        assertStatus(200, response)
        val rows = response.body.path("users")

        val biller = rows.first { it.path("id").asInt() == billerId }
        assertEquals(listOf(RoleCodes.BILLER), biller.path("roleCodes").map { it.asText() })

        // And the caller's own row reports the role it signed in with, so the
        // screen isn't blank for the account doing the looking.
        val self = rows.first { it.path("id").asInt() == admin.userId }
        assertEquals(listOf(RoleCodes.PRACTICE_ADMIN), self.path("roleCodes").map { it.asText() })
    }

    @Test
    fun aRevokedRoleIsNotClaimed() {
        // A list that reported revoked assignments would show permissions the
        // user does not have, which is worse than showing none.
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)

        val revokedId = insertUser("revoked")
        assign(revokedId, RoleCodes.BILLER, practiceA.id)
        jdbc.sql("UPDATE user_role_assignments SET active = false WHERE user_id = :id")
            .param("id", revokedId)
            .update()

        val rows = env.doJson("GET", "/api/users", admin.token, null).body.path("users")
        val row = rows.first { it.path("id").asInt() == revokedId }
        assertTrue(row.path("roleCodes").size() == 0) {
            "a revoked assignment should read as no role, got ${row.path("roleCodes")}"
        }
    }
}
