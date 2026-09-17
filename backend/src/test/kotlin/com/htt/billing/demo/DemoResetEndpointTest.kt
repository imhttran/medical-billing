package com.htt.billing.demo

import com.htt.billing.identity.DevAdminSeeder
import com.htt.billing.security.RoleCodes
import com.htt.billing.service.demo.DemoResetService
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.test.context.TestPropertySource

/**
 * The reset endpoint where it is meant to exist. `app.env=demo` rather than
 * development, so the reset beans register while the dev-admin seeder (which is
 * development only) stays out of this context. Autowiring [DemoDataSeeder] here
 * also proves the gate, since the bean would not exist without it.
 *
 * [DemoResetTest] covers the same capability from the production shape, where the
 * route is absent.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@TestPropertySource(properties = [DemoEnvironment.APP_ENV + "=" + DemoEnvironment.DEMO])
class DemoResetEndpointTest : BillingApiTest() {

    @Autowired
    private lateinit var seeder: DemoDataSeeder

    @AfterEach
    fun removeDemoPractice() {
        clearDemoData(jdbc)
        // Fabricated for the grant test below.
        jdbc.sql("DELETE FROM users WHERE email = :email")
            .param("email", DevAdminSeeder.DEV_ADMIN_EMAIL)
            .update()
    }

    @Test
    fun platformAdminCanResetThroughTheEndpoint() {
        val platformAdmin = signIn(RoleCodes.PLATFORM_ADMIN, null)

        val response = env.doJson("POST", "/api/system/reset", platformAdmin.token, null)
        assertStatus(200, response)
        assertEquals(DemoDataset.PATIENTS.size, response.body.path("dataset").path("patients").asInt())
        assertEquals(DemoDataset.PROVIDERS.size, response.body.path("dataset").path("providers").asInt())
        assertEquals(DemoDataset.CLAIMS.size, response.body.path("dataset").path("claims").asInt())

        val organizationId = demoOrganizationId(jdbc)
        assertNotNull(organizationId)
        assertEquals(DemoDataset.PATIENTS.size, countPatients(jdbc, organizationId!!))
        assertEquals(DemoDataset.CLAIMS.size, countClaims(jdbc, organizationId))
        assertEquals(1, auditCountFor(jdbc, DemoResetService.ACTION_DEMO_RESET, platformAdmin.userId))
    }

    @Test
    fun practiceAdminIsRefusedByTheEndpoint() {
        val practiceAdmin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)

        assertStatus(403, env.doJson("POST", "/api/system/reset", practiceAdmin.token, null))
    }

    @Test
    fun resetGivesTheDevAdminAccessToTheSeededPractice() {
        // The local login is a platform account with no billing role, and the
        // billing screens are scoped to the caller's grants, so without this the
        // seeded practice would be invisible to the only account a developer has.
        val devAdminId = insertDevAdmin()
        val platformAdmin = signIn(RoleCodes.PLATFORM_ADMIN, null)

        assertStatus(200, env.doJson("POST", "/api/system/reset", platformAdmin.token, null))
        // Both roles, so one local login can supervise the practice and operate
        // its claims.
        assertEquals(DemoDataset.DEV_ADMIN_ROLES.size, assignmentsFor(devAdminId))

        // Idempotent, so every reset does not stack up duplicate assignments.
        assertStatus(200, env.doJson("POST", "/api/system/reset", platformAdmin.token, null))
        assertEquals(DemoDataset.DEV_ADMIN_ROLES.size, assignmentsFor(devAdminId))
    }

    private fun insertDevAdmin(): Int {
        val inserted = jdbc
            .sql(
                """
                INSERT INTO users (email, password, email_verified)
                VALUES (:email, 'not-a-real-hash', true)
                ON CONFLICT (email) DO NOTHING
                RETURNING id
                """,
            )
            .param("email", DevAdminSeeder.DEV_ADMIN_EMAIL)
            .query(Int::class.javaObjectType)
            .optional()
            .orElse(null)
        if (inserted != null) return inserted
        return jdbc
            .sql("SELECT id FROM users WHERE email = :email")
            .param("email", DevAdminSeeder.DEV_ADMIN_EMAIL)
            .query(Int::class.javaObjectType)
            .single()
    }

    private fun assignmentsFor(userId: Int): Int = jdbc
        .sql(
            """
            SELECT count(*)
            FROM user_role_assignments a
            JOIN roles r ON r.id = a.role_id
            WHERE a.user_id = :userId AND a.active AND r.code IN (:roleCodes)
            """,
        )
        .param("userId", userId)
        .param("roleCodes", DemoDataset.DEV_ADMIN_ROLES)
        .query(Int::class.javaObjectType)
        .single()

    @Test
    fun seederLaysDownTheDatasetOnABoot() {
        // The runner the application boots with, invoked directly so this does
        // not depend on which test ran first and cleared the practice out.
        seeder.run(DefaultApplicationArguments())

        val organizationId = demoOrganizationId(jdbc)
        assertNotNull(organizationId) { "the seeder created no demo practice" }
        assertEquals(DemoDataset.PATIENTS.size, countPatients(jdbc, organizationId!!))
        assertEquals(DemoDataset.PROVIDERS.size, countProviders(jdbc, organizationId))

        val patientId = demoPatientId(jdbc, organizationId)
        assertNotNull(patientId)
        assertEquals(
            DemoDataset.JANE.coverages.first().memberId,
            demoCoverageMemberId(jdbc, patientId!!),
        )
    }
}
