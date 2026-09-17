package com.htt.billing.identity

import com.htt.billing.security.RoleCodes
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.assertMessage
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * An admin deleting their own account is blocked before the DB is touched. The
 * permission gate is satisfied first, so this is the rule being exercised rather
 * than the gate.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class DeleteOwnAccountTest : BillingApiTest() {

    @Test
    fun delete_own_account() {
        // USER_DISABLE is what the route asks for, and a platform administrator
        // holds it. Deleting yourself is refused whatever you hold.
        val admin = signIn(RoleCodes.PLATFORM_ADMIN, null)

        val response = env.doJson("DELETE", "/api/users/${admin.userId}", admin.token, null)
        assertStatus(400, response)
        assertMessage("Cannot delete your own account", response)
    }
}
