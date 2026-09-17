package com.htt.billing.identity

import com.htt.billing.security.RoleCodes
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Who reaches user administration, now that the gate is a permission rather than
 * the ranked `client`/`staff`/`admin` column it replaced.
 *
 * Every billing role holds no `USER_*` permission at all, so only the two
 * administrator roles get in, and `PLATFORM_ADMIN` gets in on its platform-scoped
 * grant.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class UsersRbacTest : BillingApiTest() {

    @Test
    fun theListRefusesEveryRoleThatHoldsNoUserView() {
        // Someone who signed up and was granted nothing at all.
        env.signup()
        val unassigned = env.loginAs(env.email, env.password)
        env.fillProfile(unassigned)
        assertStatus(403, env.doJson("GET", "/api/users", unassigned, null))

        // And each billing role, none of which carries a USER_* permission.
        listOf(
            RoleCodes.BILLING_MANAGER,
            RoleCodes.BILLER,
            RoleCodes.PROVIDER,
            RoleCodes.READ_ONLY,
        ).forEach { code ->
            val session = signIn(code, practiceA.id)
            assertStatus(403, env.doJson("GET", "/api/users", session.token, null))
        }
    }

    @Test
    fun theListAdmitsBothAdministratorRoles() {
        val practiceAdmin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val listed = env.doJson("GET", "/api/users", practiceAdmin.token, null)
        assertStatus(200, listed)
        assertTrue(listed.body.path("users").isArray()) { "missing users key: ${listed.text}" }

        val platformAdmin = signIn(RoleCodes.PLATFORM_ADMIN, null)
        assertStatus(200, env.doJson("GET", "/api/users", platformAdmin.token, null))
    }

    @Test
    fun everyRouteAsksForItsOwnPermission() {
        // Each write names a different code, so a role that held one and not the
        // others would be stopped at the right places. BILLER holds none of them.
        val biller = signIn(RoleCodes.BILLER, practiceA.id)
        val target = insertUser("target")

        assertStatus(
            403,
            env.doJson(
                "POST",
                "/api/users",
                biller.token,
                mapOf("email" to "someone@mail.com", "password" to "Password1234!"),
            ),
        )
        assertStatus(
            403,
            env.doJson("PATCH", "/api/users/$target/verification", biller.token, mapOf("emailVerified" to true)),
        )
        assertStatus(403, env.doJson("POST", "/api/users/$target/resend-verification", biller.token, null))
        assertStatus(403, env.doJson("POST", "/api/users/$target/reset-password", biller.token, null))
        assertStatus(403, env.doJson("DELETE", "/api/users/$target", biller.token, null))
    }
}
