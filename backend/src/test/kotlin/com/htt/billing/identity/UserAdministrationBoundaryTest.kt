package com.htt.billing.identity

import com.htt.billing.security.RoleCodes
import com.htt.billing.service.security.RoleAdminService
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.assertMessage
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Milestone 0's criterion 9, which nothing held before — a practice
 * administrator manages users only within their practice.
 *
 * The boundary is not the permission, since both administrators hold `USER_VIEW`.
 * It is whether the caller's grants reach the account they named, which for an
 * account means one of the scopes it holds an assignment at.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class UserAdministrationBoundaryTest : BillingApiTest() {

    @Test
    fun aPracticeAdminCannotSeeAnotherPracticesUser() {
        val stranger = insertUser("stranger")
        assign(stranger, RoleCodes.BILLER, practiceB.id)

        val adminA = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val listed = env.doJson("GET", "/api/users", adminA.token, null)
        assertStatus(200, listed)
        val ids = listed.body.path("users").map { it.path("id").asInt() }

        assertFalse(stranger in ids) { "a practice-A admin saw a practice-B account: ${listed.text}" }
        assertTrue(adminA.userId in ids) { "and should still see its own account" }
    }

    @Test
    fun aPracticeAdminCannotMutateAnotherPracticesUser() {
        // Every per-user route, against an account that belongs only to B. Each
        // answers "not found" rather than "forbidden", so the routes cannot be
        // used to discover which accounts exist elsewhere.
        val stranger = insertUser("stranger")
        assign(stranger, RoleCodes.BILLER, practiceB.id)
        val adminA = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)

        val unverify = env.doJson(
            "PATCH",
            "/api/users/$stranger/verification",
            adminA.token,
            mapOf("emailVerified" to false),
        )
        assertStatus(404, unverify)
        assertMessage("User not found", unverify)

        assertStatus(404, env.doJson("POST", "/api/users/$stranger/resend-verification", adminA.token, null))
        assertStatus(404, env.doJson("POST", "/api/users/$stranger/reset-password", adminA.token, null))
        assertStatus(404, env.doJson("DELETE", "/api/users/$stranger", adminA.token, null))

        // And nothing happened to the account.
        assertTrue(stillExists(stranger), "a refused delete should leave the account alone")
    }

    @Test
    fun aPracticeAdminCannotPlaceAnAccountInAnotherPractice() {
        val target = insertUser("target")
        val adminA = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)

        // Naming practice B explicitly is refused by the scope check...
        assertStatus(
            403,
            env.doJson(
                "POST",
                "/api/users/$target/roles",
                adminA.token,
                mapOf("roleCode" to RoleCodes.BILLER, "organizationId" to practiceB.id),
            ),
        )

        // ...and naming no practice resolves to their own, not to someone else's.
        val resolved = env.doJson(
            "POST",
            "/api/users/$target/roles",
            adminA.token,
            mapOf("roleCode" to RoleCodes.BILLER),
        )
        assertStatus(201, resolved)
        val assigned = resolved.body.path("assignment").path("organizationId").asInt()
        assertTrue(assigned == practiceA.id) { "an unqualified grant landed in practice $assigned" }
    }

    @Test
    fun aPracticeAdminCanAssignInsideTheirOwnPractice() {
        // The half that has to keep working, so the boundary is not just a wall.
        val target = insertUser("target")
        val adminA = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)

        val granted = env.doJson(
            "POST",
            "/api/users/$target/roles",
            adminA.token,
            mapOf("roleCode" to RoleCodes.BILLER, "organizationId" to practiceA.id),
        )
        assertStatus(201, granted)
        assertTrue(audited(granted.body.path("assignment").path("id").asInt())) {
            "an assignment should be audited in the same transaction"
        }

        // A platform role is not theirs to hand out.
        assertStatus(
            403,
            env.doJson(
                "POST",
                "/api/users/$target/roles",
                adminA.token,
                mapOf("roleCode" to RoleCodes.PLATFORM_ADMIN),
            ),
        )
    }

    @Test
    fun createPlacesTheAccountInTheCallersPractice() {
        val adminA = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val email = "billing-fixture-created-${System.nanoTime()}@mail.com"

        val created = env.doJson(
            "POST",
            "/api/users",
            adminA.token,
            mapOf("email" to email, "password" to "Password1234!", "roleCode" to RoleCodes.BILLER),
        )
        assertStatus(201, created)
        val newId = created.body.path("user").path("id").asInt()

        // The point of assigning in the same act — otherwise the account belongs
        // to no practice and the administrator cannot see what they just made.
        val listed = env.doJson("GET", "/api/users", adminA.token, null).body.path("users")
        val row = listed.first { it.path("id").asInt() == newId }
        assertTrue(row.path("roleCodes").map { it.asText() }.contains(RoleCodes.BILLER)) {
            "the new account should carry the role it was created with: $row"
        }

        jdbc.sql("DELETE FROM users WHERE email = :email").param("email", email).update()
    }

    @Test
    fun createWithoutARoleIsRefused() {
        // An account with no assignment belongs to no practice, so the role is
        // not optional.
        val adminA = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val response = env.doJson(
            "POST",
            "/api/users",
            adminA.token,
            mapOf("email" to "billing-fixture-norole-${System.nanoTime()}@mail.com", "password" to "Password1234!"),
        )
        assertStatus(400, response)
    }

    @Test
    fun aPlatformAdminSeesEveryPracticeAndTheUnplacedAccounts() {
        val inA = insertUser("in-a")
        assign(inA, RoleCodes.BILLER, practiceA.id)
        val inB = insertUser("in-b")
        assign(inB, RoleCodes.BILLER, practiceB.id)
        val unplaced = insertUser("unplaced")

        val platform = signIn(RoleCodes.PLATFORM_ADMIN, null)
        val ids = env.doJson("GET", "/api/users", platform.token, null)
            .body.path("users")
            .map { it.path("id").asInt() }

        assertTrue(inA in ids && inB in ids) { "platform administration sees every practice" }
        assertTrue(unplaced in ids) { "and the accounts nobody has placed yet, which it is there to place" }
    }

    private fun stillExists(userId: Int): Boolean = jdbc
        .sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = :id)")
        .param("id", userId)
        .query(Boolean::class.javaObjectType)
        .single()

    private fun audited(assignmentId: Int): Boolean = jdbc
        .sql(
            """
            SELECT EXISTS (
                SELECT 1 FROM audit_events
                WHERE entity_type = :entityType AND entity_id = :entityId
            )
            """,
        )
        .param("entityType", RoleAdminService.ENTITY_USER_ROLE_ASSIGNMENT)
        .param("entityId", assignmentId.toString())
        .query(Boolean::class.javaObjectType)
        .single()
}
