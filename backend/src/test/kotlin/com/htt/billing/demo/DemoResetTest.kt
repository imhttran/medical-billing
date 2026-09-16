package com.htt.billing.demo

import com.htt.billing.common.error.ForbiddenException
import com.htt.billing.security.RoleCodes
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired

/**
 * The demo reset, in a context with no dev or demo profile — which is the
 * production shape. The service is exercised directly here; the endpoint's
 * absence is itself the assertion, and [DemoResetEndpointTest] covers the HTTP
 * path where the profile is active.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class DemoResetTest : BillingApiTest() {

    @Autowired
    private lateinit var demo: DemoResetService

    @AfterEach
    fun removeDemoPractice() {
        clearDemoData(jdbc)
    }

    @Test
    fun resetEndpointDoesNotExistWithoutADevOrDemoProfile() {
        val platformAdmin = signIn(RoleCodes.PLATFORM_ADMIN, null)

        // The controller bean is not registered, so there is no route to reach —
        // not a route that refuses. That is the difference the plan asks for.
        assertStatus(404, env.doJson("POST", "/api/system/reset", platformAdmin.token, null))
    }

    @Test
    fun resetRebuildsTheSameDataset() {
        val platformAdminId = insertUser("platform-admin")
        assign(platformAdminId, RoleCodes.PLATFORM_ADMIN, null)

        val first = demo.reset(platformAdminId)
        val organizationId = demoOrganizationId(jdbc)
        assertNotNull(organizationId)
        assertEquals(DemoDataset.PROVIDERS.size, first.providers)
        assertEquals(1, countPatients(jdbc, organizationId!!))
        assertEquals(1, countCoverages(jdbc, organizationId))

        // Something the reset did not put there, which it must therefore remove.
        insertStrayPatient(jdbc, organizationId, "Stray")
        assertEquals(2, countPatients(jdbc, organizationId))

        val second = demo.reset(platformAdminId)
        assertEquals(first.organizationId, second.organizationId)
        assertEquals(1, countPatients(jdbc, organizationId))
        assertEquals(DemoDataset.PROVIDERS.size, countProviders(jdbc, organizationId))

        // The fixed values, not just "a patient exists".
        val patientId = demoPatientId(jdbc, organizationId)
        assertNotNull(patientId)
        assertEquals(
            DemoDataset.COVERAGE_MEMBER_ID,
            demoCoverageMemberId(jdbc, patientId!!),
        )
    }

    @Test
    fun resetIsAudited() {
        val platformAdminId = insertUser("platform-admin")
        assign(platformAdminId, RoleCodes.PLATFORM_ADMIN, null)

        assertEquals(0, auditCountFor(jdbc, DemoResetService.ACTION_DEMO_RESET, platformAdminId))
        demo.reset(platformAdminId)
        assertEquals(1, auditCountFor(jdbc, DemoResetService.ACTION_DEMO_RESET, platformAdminId))
    }

    @Test
    fun seedIfAbsentSeedsAnEmptyPracticeAndThenLeavesItAlone() {
        val platformAdminId = insertUser("platform-admin")
        assign(platformAdminId, RoleCodes.PLATFORM_ADMIN, null)
        // Another context (app.env=development) may have seeded already, so start
        // from a known empty practice rather than assuming one.
        clearDemoData(jdbc)

        val seeded = demo.seedIfAbsent()
        assertNotNull(seeded)

        // The restart case: data is present, so nothing is destroyed.
        val organizationId = demoOrganizationId(jdbc)!!
        val stray = insertStrayPatient(jdbc, organizationId, "Keep")
        assertNull(demo.seedIfAbsent())
        assertTrue(countPatients(jdbc, organizationId) == 2) { "a boot wiped existing work" }
        assertNotNull(stray)
    }

    @Test
    fun resetRequiresThePlatformScopedPermission() {
        val platformAdminId = insertUser("platform-admin")
        assign(platformAdminId, RoleCodes.PLATFORM_ADMIN, null)
        val practiceAdminId = insertUser("practice-admin")
        assign(practiceAdminId, RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val billerId = insertUser("biller")
        assign(billerId, RoleCodes.BILLER, practiceA.id)

        // A practice-scoped grant cannot satisfy a platform-scoped check, and
        // none of the practice roles hold SYSTEM_RESET at all.
        val before = demoOrganizationId(jdbc)?.let { countPatients(jdbc, it) } ?: 0
        assertThrows(ForbiddenException::class.java) { demo.reset(practiceAdminId) }
        assertThrows(ForbiddenException::class.java) { demo.reset(billerId) }

        // Refused means nothing changed, whatever was there beforehand.
        val after = demoOrganizationId(jdbc)?.let { countPatients(jdbc, it) } ?: 0
        assertEquals(before, after)
    }
}
