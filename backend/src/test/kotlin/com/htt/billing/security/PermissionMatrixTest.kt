package com.htt.billing.security

import com.htt.billing.repository.security.RbacRepository
import com.htt.billing.support.BillingApiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired

/**
 * Milestone 8's permission matrix review, held in place.
 *
 * The matrix is data in V2__billing_rbac.sql, so nothing in the compiler notices
 * when a grant changes. These are the rules that review settled — narrower than
 * pinning every cell (a slice that adds a permission grant on purpose should not
 * have to edit a golden table), but wide enough that an accidental grant is loud.
 * The review itself is written up in docs/FEATURE.md.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PermissionMatrixTest : BillingApiTest() {

    @Autowired
    private lateinit var rbac: RbacRepository

    @Test
    fun platformAdministrationCarriesNoTenantContent() {
        val platformAdmin = permissionsOf(RoleCodes.PLATFORM_ADMIN)

        // Platform operations are theirs; every one of these is a permission that
        // reads or writes a practice's own rows.
        assertEquals(
            emptySet<String>(),
            platformAdmin.intersect(TENANT_CONTENT),
        ) { "PLATFORM_ADMIN holds tenant content: ${platformAdmin.intersect(TENANT_CONTENT)}" }

        // What it does hold, so a mistake the other way — a platform admin who can
        // no longer operate — is caught too.
        assertTrue(platformAdmin.containsAll(setOf(Permissions.SYSTEM_RESET, Permissions.ORGANIZATION_CREATE)))
        assertTrue(platformAdmin.contains(Permissions.ROLE_ASSIGN))
    }

    @Test
    fun everyPermissionIsHeldBySomeRole() {
        val claimed = rbac.permissionMatrix().map { it.permission }.toSet()

        // A permission nobody holds is a check that silently always denies.
        assertEquals(emptySet<String>(), Permissions.ALL - claimed) {
            "${Permissions.ALL - claimed} is seeded but granted to no role"
        }
    }

    @Test
    fun onlyPlatformRolesTouchThePlatform() {
        val matrix = rbac.permissionMatrix()
        val platformOnly = setOf(
            Permissions.SYSTEM_VIEW,
            Permissions.SYSTEM_CONFIGURE,
            Permissions.SYSTEM_RESET,
            Permissions.ORGANIZATION_CREATE,
            Permissions.ORGANIZATION_DISABLE,
        )

        val wrong = matrix.filter {
            it.permission in platformOnly && it.scopeType != ScopeTypes.PLATFORM
        }
        assertEquals(emptyList<RbacRepository.MatrixRow>(), wrong) {
            "an organization-scoped role holds platform administration: $wrong"
        }
    }

    @Test
    fun readOnlyRolesCannotWrite() {
        val writes = rbac.permissionMatrix().filter {
            it.roleCode == RoleCodes.READ_ONLY &&
                    WRITE_SUFFIXES.any { suffix -> it.permission.endsWith(suffix) }
        }

        // READ_ONLY reads clinical, billing and audit content and moves nothing.
        assertEquals(emptyList<RbacRepository.MatrixRow>(), writes) { "READ_ONLY can write: $writes" }
        assertFalse(permissionsOf(RoleCodes.READ_ONLY).isEmpty())
    }

    @Test
    fun theBillingRolesCannotAdministerThePlatformOrTheirOwnRoles() {
        // The point of the split: running the billing workflow does not include
        // deciding who may run it.
        listOf(RoleCodes.BILLER, RoleCodes.BILLING_MANAGER, RoleCodes.PROVIDER).forEach { role ->
            val held = permissionsOf(role)
            assertEquals(
                emptySet<String>(),
                held.intersect(setOf(Permissions.ROLE_ASSIGN, Permissions.ROLE_MANAGE, Permissions.SYSTEM_RESET)),
            ) { "$role holds an administration permission: $held" }
        }

        // BILLER cannot void a claim either — that is the manager's call.
        assertTrue(permissionsOf(RoleCodes.BILLING_MANAGER).contains(Permissions.CLAIM_VOID))
        assertFalse(permissionsOf(RoleCodes.BILLER).contains(Permissions.CLAIM_VOID))
    }

    private fun permissionsOf(roleCode: String): Set<String> = rbac.permissionMatrix()
        .filter { it.roleCode == roleCode }
        .map { it.permission }
        .toSet()

    private companion object {

        /**
         * Everything that reads or writes a practice's patients, claims or money.
         * AUDIT_VIEW is deliberately not here: a platform administrator reading the
         * trail of ids and codes is a capability the review kept, since it is how
         * platform operations are supervised.
         */
        val TENANT_CONTENT = setOf(
            Permissions.PATIENT_VIEW, Permissions.PATIENT_CREATE, Permissions.PATIENT_EDIT,
            Permissions.COVERAGE_VIEW, Permissions.COVERAGE_EDIT,
            Permissions.PROVIDER_VIEW, Permissions.PROVIDER_MANAGE,
            Permissions.CLAIM_VIEW, Permissions.CLAIM_CREATE, Permissions.CLAIM_EDIT,
            Permissions.CLAIM_SUBMIT, Permissions.CLAIM_RESUBMIT, Permissions.CLAIM_VOID,
            Permissions.WORK_QUEUE_VIEW, Permissions.WORK_QUEUE_ASSIGN, Permissions.WORK_QUEUE_RESOLVE,
            Permissions.PAYMENT_VIEW, Permissions.PAYMENT_RECORD,
            Permissions.FHIR_IMPORT, Permissions.FHIR_EXPORT,
        )

        /** How a permission that changes something ends. */
        val WRITE_SUFFIXES = listOf(
            "_CREATE", "_EDIT", "_MANAGE", "_SUBMIT", "_RESUBMIT", "_VOID",
            "_ASSIGN", "_RESOLVE", "_RECORD", "_DISABLE", "_RESET", "_CONFIGURE", "_IMPORT",
        )
    }
}
