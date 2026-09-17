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
        // user does not have, which is worse than showing none. A second
        // assignment stays active, so the account is still in the caller's
        // practice and the revoked one is what is under test.
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)

        val targetId = insertUser("revoked")
        assign(targetId, RoleCodes.BILLER, practiceA.id)
        assign(targetId, RoleCodes.PROVIDER, practiceA.id)
        jdbc.sql(
            """
            UPDATE user_role_assignments SET active = false
            WHERE user_id = :id
              AND role_id = (SELECT id FROM roles WHERE code = :code)
            """,
        )
            .param("id", targetId)
            .param("code", RoleCodes.BILLER)
            .update()

        val rows = env.doJson("GET", "/api/users", admin.token, null).body.path("users")
        val row = rows.first { it.path("id").asInt() == targetId }
        assertEquals(
            listOf(RoleCodes.PROVIDER),
            row.path("roleCodes").map { it.asText() },
        )
    }

    @Test
    fun anAccountWithNoAssignmentLeavesTheCallersList() {
        // A user belongs to a practice through an assignment, so revoking the
        // last one takes them out of it. This is the boundary that confines a
        // practice administrator to their own practice.
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)

        val targetId = insertUser("unassigned")
        assign(targetId, RoleCodes.BILLER, practiceA.id)

        val before = env.doJson("GET", "/api/users", admin.token, null).body.path("users")
        assertTrue(before.any { it.path("id").asInt() == targetId }) {
            "an assigned account should be visible to an administrator of its practice"
        }

        jdbc.sql("UPDATE user_role_assignments SET active = false WHERE user_id = :id")
            .param("id", targetId)
            .update()

        val after = env.doJson("GET", "/api/users", admin.token, null).body.path("users")
        assertTrue(after.none { it.path("id").asInt() == targetId }) {
            "an account with no assignment belongs to no practice"
        }
    }
}
