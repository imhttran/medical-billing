package com.htt.billing.identity

import com.htt.billing.demo.DemoDataset
import com.htt.billing.support.NoScheduledEmailWorker
import com.htt.billing.support.TestEnv
import java.util.function.Supplier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Development creates (and re-creates idempotently) one login per billing role,
 * each with a pre-filled profile, so local dev needs no manual set-role and a
 * demo can move between accounts.
 *
 * Its own context, because the seed only runs when app.env is development —
 * every other test here runs with app.env=test precisely so it doesn't.
 */
@SpringBootTest(
    properties = [
        TestEnv.APP_ENV_DEVELOPMENT,
        TestEnv.EMAIL_VERIFICATION_NOT_REQUIRED,
        TestEnv.FRONTEND_URL,
        TestEnv.JWT_SECRET,
        TestEnv.ALLOW_BEAN_OVERRIDE,
    ],
)
@Import(NoScheduledEmailWorker::class) // these tests drive the queue themselves
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // close the context (and its 10-connection pool) after each class
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class DevUserSeedTest {

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var seeder: DevUserSeeder

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun databaseUrl(registry: DynamicPropertyRegistry) {
            registry.add("app.database-url", Supplier { TestEnv.databaseUrl() })
        }
    }

    @AfterEach
    fun cleanup() {
        // Cascades to the profiles, then the queued email rows.
        jdbc.sql("DELETE FROM users WHERE email IN (:emails)")
            .param("emails", DemoDataset.DEV_TEAM.map { it.email })
            .update()
        jdbc.sql("DELETE FROM email_queue WHERE \"to\" IN (:emails)")
            .param("emails", DemoDataset.DEV_TEAM.map { it.email })
            .update()
    }

    @Test
    fun seedsOneAccountPerBillingRole() {
        // The seeder already ran at context startup; a second run is a no-op.
        seeder.run(DefaultApplicationArguments())

        DemoDataset.DEV_TEAM.forEach { member ->
            assertEquals(
                member.coarseRole,
                coarseRoleOf(member.email),
                "${member.email} should carry the coarse role its billing role implies",
            )
            assertTrue(hasProfile(member.email), "${member.email} should have a profile")
        }
    }

    @Test
    fun coversEveryBillingRoleAndNothingElse() {
        // The point of the team: each role the matrix describes can be logged into.
        val covered = DemoDataset.DEV_TEAM.flatMap { it.billingRoles }.toSet()
        assertEquals(
            setOf(
                "PLATFORM_ADMIN",
                "PRACTICE_ADMIN",
                "BILLING_MANAGER",
                "BILLER",
                "PROVIDER",
                "READ_ONLY",
            ),
            covered,
        )
    }

    @Test
    fun onlyUserAdministratorsTakeAdmin() {
        // users.role gates /api/users. The four billing roles hold no USER_*
        // permission, so a coarse admin would hand them a screen the matrix denies.
        val userAdministrators = setOf("PLATFORM_ADMIN", "PRACTICE_ADMIN")
        DemoDataset.DEV_TEAM.forEach { member ->
            val expected = if (member.billingRoles.any { it in userAdministrators }) {
                "admin"
            } else {
                "client"
            }
            assertEquals(expected, member.coarseRole, "${member.email}")
        }
    }

    private fun coarseRoleOf(email: String): String? = jdbc
        .sql("SELECT role FROM users WHERE email = :email")
        .param("email", email)
        .query(String::class.java)
        .optional()
        .orElse(null)

    private fun hasProfile(email: String): Boolean = jdbc
        .sql(
            """
            SELECT EXISTS (
                SELECT 1 FROM user_profiles p JOIN users u ON u.id = p.user_id
                WHERE u.email = :email
            )
            """,
        )
        .param("email", email)
        .query(Boolean::class.javaObjectType)
        .single()
}
