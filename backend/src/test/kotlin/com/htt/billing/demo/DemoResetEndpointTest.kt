package com.htt.billing.demo

import com.htt.billing.identity.DevUserSeeder
import com.htt.billing.security.RoleCodes
import com.htt.billing.service.demo.DemoResetService
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.test.context.TestPropertySource

/**
 * The reset endpoint where it is meant to exist. `app.env=demo` rather than
 * development, so the reset beans register while the dev-user seeder (which is
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
        // Fabricated for the grant tests below.
        jdbc.sql("DELETE FROM users WHERE email IN (:emails)")
            .param("emails", FABRICATED_ACCOUNTS)
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
        // The local login is a plain identity with no billing role, and the
        // billing screens are scoped to the caller's grants, so without this the
        // seeded practice would be invisible to the only account a developer has.
        val devAdminId = insertAccount(DevUserSeeder.DEV_ADMIN_EMAIL)
        val platformAdmin = signIn(RoleCodes.PLATFORM_ADMIN, null)

        assertStatus(200, env.doJson("POST", "/api/system/reset", platformAdmin.token, null))
        // Both roles, so one local login can supervise the practice and operate
        // its claims.
        assertEquals(DemoDataset.DEV_ADMIN_ROLES.size, assignmentsFor(devAdminId))

        // Idempotent, so every reset does not stack up duplicate assignments.
        assertStatus(200, env.doJson("POST", "/api/system/reset", platformAdmin.token, null))
        assertEquals(DemoDataset.DEV_ADMIN_ROLES.size, assignmentsFor(devAdminId))
    }

    @Test
    fun resetPairsEachRolesScopeWithTheRightAssignment() {
        // A platform role belongs to no practice. Assigning one against the demo
        // organization would still resolve to the same permissions but would put
        // the account inside the tenant boundary it is supposed to sit above.
        val platformId = insertAccount("platform@mail.com")
        val billerId = insertAccount("biller@mail.com")
        val platformAdmin = signIn(RoleCodes.PLATFORM_ADMIN, null)

        assertStatus(200, env.doJson("POST", "/api/system/reset", platformAdmin.token, null))
        val organizationId = demoOrganizationId(jdbc)
        assertNotNull(organizationId)

        assertNull(organizationFor(platformId), "PLATFORM_ADMIN should be assigned with no practice")
        assertEquals(organizationId, organizationFor(billerId), "BILLER should be assigned with the practice")
    }

    private fun insertAccount(email: String): Int {
        val inserted = jdbc
            .sql(
                """
                INSERT INTO users (email, password, email_verified)
                VALUES (:email, 'not-a-real-hash', true)
                ON CONFLICT (email) DO NOTHING
                RETURNING id
                """,
            )
            .param("email", email)
            .query(Int::class.javaObjectType)
            .optional()
            .orElse(null)
        if (inserted != null) return inserted
        return jdbc
            .sql("SELECT id FROM users WHERE email = :email")
            .param("email", email)
            .query(Int::class.javaObjectType)
            .single()
    }

    /** The practice a user's single active assignment is scoped to, or null. */
    private fun organizationFor(userId: Int): Int? = jdbc
        .sql(
            """
            SELECT a.organization_id
            FROM user_role_assignments a
            WHERE a.user_id = :userId AND a.active
            """,
        )
        .param("userId", userId)
        .query(Int::class.javaObjectType)
        .optional()
        .orElse(null)

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

    private companion object {
        /** Accounts the grant tests fabricate, since this context seeds no users. */
        val FABRICATED_ACCOUNTS = listOf(
            DevUserSeeder.DEV_ADMIN_EMAIL,
            "platform@mail.com",
            "biller@mail.com",
        )
    }
}
