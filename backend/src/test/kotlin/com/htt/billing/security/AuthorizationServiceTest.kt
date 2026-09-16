package com.htt.billing.security

import com.htt.billing.security.RbacRepository.Grant
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The authorization rule itself, with no database behind it. These are the cases
 * the tenant boundary rests on.
 */
class AuthorizationServiceTest {

    private val practiceA = 1
    private val practiceB = 2

    @Test
    fun platformGrantSatisfiesAPlatformCheck() {
        val grants = listOf(Grant(Permissions.SYSTEM_RESET, null))
        assertTrue(AuthorizationService.permits(grants, Permissions.SYSTEM_RESET, null))
    }

    @Test
    fun platformGrantAppliesToAnyOrganization() {
        // A platform-scoped ORGANIZATION_EDIT is meant to edit an organization,
        // which is an organization-owned resource.
        val grants = listOf(Grant(Permissions.ORGANIZATION_EDIT, null))
        assertTrue(AuthorizationService.permits(grants, Permissions.ORGANIZATION_EDIT, practiceB))
    }

    @Test
    fun organizationGrantSatisfiesOnlyItsOwnOrganization() {
        val grants = listOf(Grant(Permissions.CLAIM_SUBMIT, practiceA))
        assertTrue(AuthorizationService.permits(grants, Permissions.CLAIM_SUBMIT, practiceA))
        assertFalse(AuthorizationService.permits(grants, Permissions.CLAIM_SUBMIT, practiceB))
    }

    @Test
    fun organizationGrantNeverSatisfiesAPlatformCheck() {
        val grants = listOf(Grant(Permissions.CLAIM_SUBMIT, practiceA))
        assertFalse(AuthorizationService.permits(grants, Permissions.CLAIM_SUBMIT, null))
    }

    @Test
    fun grantForOnePermissionDoesNotSatisfyAnother() {
        val grants = listOf(Grant(Permissions.CLAIM_VIEW, practiceA))
        assertFalse(AuthorizationService.permits(grants, Permissions.CLAIM_SUBMIT, practiceA))
    }

    @Test
    fun holdingNoGrantsDenies() {
        assertFalse(AuthorizationService.permits(emptyList(), Permissions.CLAIM_SUBMIT, practiceA))
    }
}
