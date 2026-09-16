package com.htt.template.api

import com.htt.template.config.DevAdminSeeder
import com.htt.template.support.NoScheduledEmailWorker
import com.htt.template.support.TestEnv
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
import java.util.function.Supplier

/**
 * Development creates (and
 * re-creates idempotently) admin@mail.com with a pre-filled profile, so local
 * dev needs no manual set-role.
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
class DevAdminSeedTest {

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var seeder: DevAdminSeeder

    companion object {
        private const val DEV_ADMIN_EMAIL = "admin@mail.com"

        @JvmStatic
        @DynamicPropertySource
        fun databaseUrl(registry: DynamicPropertyRegistry) {
            registry.add("app.database-url", Supplier { TestEnv.databaseUrl() })
        }
    }

    @AfterEach
    fun cleanup() {
        // Cascades to the profile, then the queued email rows.
        jdbc.sql("DELETE FROM users WHERE email = :email")
            .param("email", DEV_ADMIN_EMAIL)
            .update()
        jdbc.sql("DELETE FROM email_queue WHERE \"to\" = :email")
            .param("email", DEV_ADMIN_EMAIL)
            .update()
    }

    @Test
    fun dev_admin_seed() {
        // The seeder already ran at context startup; a second run is a no-op.
        seeder.run(DefaultApplicationArguments())

        val role = jdbc
            .sql("SELECT role FROM users WHERE email = :email")
            .param("email", DEV_ADMIN_EMAIL)
            .query(String::class.java)
            .optional()
            .orElse(null)
        assertEquals("admin", role)

        val profileExists = jdbc
            .sql(
                """
                SELECT EXISTS (
                    SELECT 1 FROM user_profiles p JOIN users u ON u.id = p.user_id
                    WHERE u.email = :email
                )
                """,
            )
            .param("email", DEV_ADMIN_EMAIL)
            .query(Boolean::class.javaObjectType)
            .single()
        assertTrue(profileExists, "seeded admin should have a profile")
    }
}
