package com.htt.billing.security

import com.htt.billing.common.error.ForbiddenException
import com.htt.billing.common.error.ValidationException
import com.htt.billing.repository.security.RbacRepository
import com.htt.billing.service.security.AuthorizationService
import com.htt.billing.service.security.RoleAdminService
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired

/**
 * The tenant boundary, against a real database. This is Milestone 0's bar — if
 * any of these pass while a practice-A user can reach practice B, the isolation
 * is fake.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CrossOrganizationAccessTest : BillingApiTest() {

    @Autowired
    private lateinit var authorization: AuthorizationService

    @Autowired
    private lateinit var roleAdmin: RoleAdminService

    @Autowired
    private lateinit var rbac: RbacRepository

    @Test
    fun organizationScopedPermissionStopsAtThePracticeBoundary() {
        val biller = insertUser("biller")
        assign(biller, RoleCodes.BILLER, practiceA.id)

        assertTrue(authorization.can(biller, Permissions.CLAIM_SUBMIT, practiceA.id))
        assertFalse(authorization.can(biller, Permissions.CLAIM_SUBMIT, practiceB.id))
        assertThrows(ForbiddenException::class.java) {
            authorization.require(biller, Permissions.CLAIM_SUBMIT, practiceB.id)
        }
    }

    @Test
    fun platformAdministrationCarriesNoPatientOrClaimAccess() {
        val platformAdmin = insertUser("platform-admin")
        assign(platformAdmin, RoleCodes.PLATFORM_ADMIN, null)

        assertTrue(authorization.can(platformAdmin, Permissions.SYSTEM_RESET))
        // Platform scope still applies to an organization-owned resource.
        assertTrue(authorization.can(platformAdmin, Permissions.ORGANIZATION_EDIT, practiceA.id))
        // A platform-scoped ORGANIZATION_VIEW sees every active practice.
        val visible = authorization.permittedOrganizations(platformAdmin, Permissions.ORGANIZATION_VIEW)
        assertTrue(visible.any { it.id == practiceA.id } && visible.any { it.id == practiceB.id }) {
            "platform admin should see both practices, saw ${visible.map { it.id }}"
        }

        // But not the practice's clinical or billing content.
        assertFalse(authorization.can(platformAdmin, Permissions.PATIENT_VIEW, practiceA.id))
        assertFalse(authorization.can(platformAdmin, Permissions.CLAIM_VIEW, practiceA.id))
        assertFalse(authorization.can(platformAdmin, Permissions.CLAIM_SUBMIT, practiceA.id))
        assertFalse(authorization.can(platformAdmin, Permissions.PAYMENT_VIEW, practiceB.id))
    }

    @Test
    fun practiceAdminCannotAssignRolesOutsideTheirPractice() {
        val admin = insertUser("practice-admin")
        assign(admin, RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val target = insertUser("target")

        // Inside their own practice: allowed, and audited in the same transaction.
        val assignment = roleAdmin.assign(admin, target, RoleCodes.BILLER, practiceA.id)
        assertEquals(practiceA.id, assignment.organizationId)
        assertEquals(RoleAdminService.ACTION_ROLE_ASSIGNED, auditActionFor(assignment.id))

        // Into another practice: refused.
        assertThrows(ForbiddenException::class.java) {
            roleAdmin.assign(admin, target, RoleCodes.BILLER, practiceB.id)
        }

        // Their organization-scoped ROLE_ASSIGN must not satisfy a platform check.
        assertThrows(ForbiddenException::class.java) {
            roleAdmin.assign(admin, target, RoleCodes.PLATFORM_ADMIN, null)
        }

        // And a platform role cannot be pinned to a practice at all.
        assertThrows(ValidationException::class.java) {
            roleAdmin.assign(admin, target, RoleCodes.PLATFORM_ADMIN, practiceA.id)
        }
    }

    @Test
    fun organizationListIsDerivedFromTheCallersOwnGrants() {
        val session = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)

        val listed = env.doJson("GET", "/api/organizations", session.token, null)
        assertStatus(200, listed)
        val ids = listed.body.path("organizations").map { it.path("id").asInt() }
        assertEquals(listOf(practiceA.id), ids) { "practice A admin saw ${listed.text}" }
    }

    @Test
    fun permissionCatalogueMatchesTheSeededRows() {
        // Keeps Permissions.kt and V2__billing_rbac.sql from drifting apart — a
        // code in one and not the other is a check that silently always denies.
        assertEquals(Permissions.ALL, rbac.permissionCodes().toSet())
        assertEquals(RoleCodes.ALL, rbac.roleCodes().toSet())
    }

    private fun auditActionFor(assignmentId: Int): String? = jdbc
        .sql(
            """
            SELECT action FROM audit_events
            WHERE entity_type = :entityType AND entity_id = :entityId
            ORDER BY id DESC LIMIT 1
            """,
        )
        .param("entityType", RoleAdminService.ENTITY_USER_ROLE_ASSIGNMENT)
        .param("entityId", assignmentId.toString())
        .query(String::class.java)
        .optional()
        .orElse(null)
}
