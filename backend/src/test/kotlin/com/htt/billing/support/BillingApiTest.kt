package com.htt.billing.support

import com.htt.billing.repository.practice.OrganizationRepository
import com.htt.billing.repository.practice.OrganizationRepository.Organization
import java.sql.Types
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired

/**
 * Fixtures the billing integration tests share: two practices, users holding a
 * role in one of them, and the cleanup that removes both.
 *
 * JUnit's `@EnabledIfEnvironmentVariable` does not carry to subclasses (Spring's
 * annotations do, JUnit's do not), so every concrete subclass still carries its
 * own gate.
 */
abstract class BillingApiTest : IntegrationTest() {

    @Autowired
    protected lateinit var organizations: OrganizationRepository

    protected lateinit var practiceA: Organization
    protected lateinit var practiceB: Organization

    /** A signed-in user, and the identity their token resolves to. */
    data class Session(val token: String, val userId: Int, val email: String)

    @BeforeEach
    fun createPractices() {
        val tag = System.nanoTime()
        practiceA = organizations.insert("Billing fixture A $tag", null, null)
        practiceB = organizations.insert("Billing fixture B $tag", null, null)
    }

    @AfterEach
    fun removeFixtures() {
        if (!::practiceA.isInitialized) {
            return
        }
        // Order matters: each statement below deletes rows that reference the
        // practices or the users that go next. Claims go first — their diagnoses,
        // lines and adjudications cascade, and they reference patients, providers
        // and coverages.
        jdbc.sql("DELETE FROM claims WHERE organization_id IN (:practiceA, :practiceB)")
            .param("practiceA", practiceA.id).param("practiceB", practiceB.id).update()
        jdbc.sql("DELETE FROM coverages WHERE organization_id IN (:practiceA, :practiceB)")
            .param("practiceA", practiceA.id).param("practiceB", practiceB.id).update()
        jdbc.sql("DELETE FROM patients WHERE organization_id IN (:practiceA, :practiceB)")
            .param("practiceA", practiceA.id).param("practiceB", practiceB.id).update()
        jdbc.sql("DELETE FROM providers WHERE organization_id IN (:practiceA, :practiceB)")
            .param("practiceA", practiceA.id).param("practiceB", practiceB.id).update()
        jdbc.sql(
            """
            DELETE FROM user_role_assignments
            WHERE organization_id IN (:practiceA, :practiceB)
               OR user_id IN (SELECT id FROM users WHERE email LIKE :prefix)
            """,
        ).param("practiceA", practiceA.id).param("practiceB", practiceB.id).param("prefix", FIXTURE_EMAIL).update()
        jdbc.sql(
            """
            DELETE FROM audit_events
            WHERE organization_id IN (:practiceA, :practiceB)
               OR user_id IN (SELECT id FROM users WHERE email LIKE :prefix)
            """,
        ).param("practiceA", practiceA.id).param("practiceB", practiceB.id).param("prefix", FIXTURE_EMAIL).update()
        jdbc.sql("DELETE FROM organizations WHERE id IN (:practiceA, :practiceB)")
            .param("practiceA", practiceA.id).param("practiceB", practiceB.id).update()
        jdbc.sql("DELETE FROM users WHERE email LIKE :prefix")
            .param("prefix", FIXTURE_EMAIL).update()
        // Signup queues a welcome and a verification email per fixture user. Left
        // behind, they pile up across the suite and starve the mail-queue test,
        // which drains a fixed number of rows and expects its own to be among
        // them.
        jdbc.sql("DELETE FROM email_queue WHERE \"to\" LIKE :prefix")
            .param("prefix", FIXTURE_EMAIL).update()
    }

    /**
     * A user row with no usable password, for tests that authorize by id and
     * never log in as the fixture user.
     */
    protected fun insertUser(tag: String): Int = jdbc
        .sql(
            """
            INSERT INTO users (email, password, email_verified)
            VALUES (:email, 'not-a-real-hash', true)
            RETURNING id
            """,
        )
        .param("email", fixtureEmail(tag))
        .query(Int::class.javaObjectType)
        .single()

    /**
     * The synthetic payer the seeded fee schedule belongs to — the only one with
     * rates, so a coverage written by hand has to name it.
     */
    protected fun payerId(): Int = jdbc
        .sql("SELECT id FROM payers WHERE payer_code = 'SYN001'")
        .query(Int::class.javaObjectType)
        .single()

    /**
     * A signed-in user holding [roleCode] at [organizationId] (null for a
     * platform-scoped role).
     */
    protected fun signIn(roleCode: String, organizationId: Int?): Session {
        val email = fixtureEmail(roleCode.lowercase())
        env.doJson("POST", "/api/signup", "", mapOf("email" to email, "password" to TestEnv.PASSWORD))
        val token = env.loginAs(email, TestEnv.PASSWORD)
        env.fillProfile(token)
        val userId = env.ownUserId(token)
        assign(userId, roleCode, organizationId)
        return Session(token, userId, email)
    }

    /**
     * Writes the assignment straight to the store. The first assignment in a
     * system has no holder of ROLE_ASSIGN to authorize it, so bootstrapping goes
     * through SQL — the same reason the dev admin is seeded rather than granted
     * over HTTP. RoleAdminService is what tests the authorized path.
     */
    protected fun assign(userId: Int, roleCode: String, organizationId: Int?) {
        jdbc.sql(
            """
            INSERT INTO user_role_assignments (user_id, role_id, organization_id)
            SELECT :userId, r.id, :organizationId FROM roles r WHERE r.code = :roleCode
            """,
        )
            .param("userId", userId)
            .param("organizationId", organizationId, Types.INTEGER)
            .param("roleCode", roleCode)
            .update()
    }

    private fun fixtureEmail(tag: String): String = "billing-fixture-$tag-${System.nanoTime()}@mail.com"

    private companion object {
        /** Matches every fixture email this class creates. */
        const val FIXTURE_EMAIL = "billing-fixture-%"
    }
}
