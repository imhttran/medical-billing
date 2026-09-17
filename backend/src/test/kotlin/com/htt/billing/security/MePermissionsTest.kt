package com.htt.billing.security

import com.fasterxml.jackson.databind.JsonNode
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.TestEnv
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The permission codes `/api/me` reports. The nav gates on them, so a role that
 * gains or loses a grant has to fail here rather than quietly keep offering a
 * page the caller cannot fill.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MePermissionsTest : BillingApiTest() {

    @Test
    fun meReportsThePermissionsTheNavGatesOn() {
        // A biller works claims, sees patients and the queue, takes payments — and
        // holds neither the audit trail nor the permissions that decide who works
        // a claim.
        val biller = permissionsFor(signIn(RoleCodes.BILLER, practiceA.id).token)
        assertTrue(biller.containsAll(listOf(Permissions.CLAIM_VIEW, Permissions.PATIENT_VIEW, Permissions.WORK_QUEUE_VIEW))) {
            "biller holds $biller"
        }
        assertFalse(biller.contains(Permissions.AUDIT_VIEW)) { "biller holds $biller" }
        assertFalse(biller.contains(Permissions.CLAIM_VOID)) { "biller holds $biller" }
        assertFalse(biller.contains(Permissions.WORK_QUEUE_ASSIGN)) { "biller holds $biller" }

        // Read-only is the other direction: the audit trail, nothing that moves.
        val reader = permissionsFor(signIn(RoleCodes.READ_ONLY, practiceA.id).token)
        assertTrue(reader.contains(Permissions.AUDIT_VIEW)) { "read-only holds $reader" }
        assertFalse(reader.contains(Permissions.CLAIM_SUBMIT)) { "read-only holds $reader" }
    }

    @Test
    fun anUnassignedUserHoldsNothing() {
        // What a fresh signup looks like: no assignment, so no nav destinations
        // beyond the dashboard, and nothing to fill them with either.
        env.signup()
        val token = env.loginAs(env.email, TestEnv.PASSWORD)
        assertTrue(permissionsFor(token).isEmpty()) { "unassigned user should hold nothing" }
    }

    private fun permissionsFor(token: String): List<String> {
        val me = env.doJson("GET", "/api/me", token, null)
        assertStatus(200, me)
        val permissions = me.body.path("user").path("permissions")
        assertTrue(permissions.isArray) { "/api/me returned no permissions array: ${me.text}" }
        return permissions.map(JsonNode::asText)
    }
}
