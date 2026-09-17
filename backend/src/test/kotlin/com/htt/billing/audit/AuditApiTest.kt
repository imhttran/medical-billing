package com.htt.billing.audit

import com.htt.billing.security.RoleCodes
import com.htt.billing.service.audit.AuditService
import com.htt.billing.service.claim.ClaimService
import com.htt.billing.service.payment.PaymentService
import com.htt.billing.service.security.RoleAdminService
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.TestEnv
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired

/**
 * The audit trail over HTTP: what a practice can read back about what its people
 * did, and who gets to read it.
 *
 * The events written elsewhere are covered where they happen
 * (CrossOrganizationAccessTest asserts the role-assignment one, WorkQueueApiTest
 * the queue's); this is the read side, plus the two billing acts Milestone 8 added
 * to it.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class AuditApiTest : BillingApiTest() {

    @Autowired
    private lateinit var roleAdmin: RoleAdminService

    @Test
    fun aClaimSubmissionAndAPatientPaymentAreOnTheTrail() {
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val patientId = createPatient(admin)
        val providerId = createProvider(admin)
        val coverageId = createCoverage(admin, patientId)
        val claimId = createClaim(admin, patientId, providerId, coverageId)

        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/ready", admin.token, null))
        val submitted = env.doJson("POST", "/api/claims/$claimId/submit", admin.token, null)
        assertStatus(200, submitted)
        val claimNumber = submitted.body.path("claim").path("claimNumber").asText()

        // The patient owes 30.00, so paying it settles the claim. Recording it is
        // the billing manager's permission, not the practice administrator's.
        val manager = signIn(RoleCodes.BILLING_MANAGER, practiceA.id)
        val paid = env.doJson(
            "POST",
            "/api/claims/$claimId/patient-payments",
            manager.token,
            mapOf("amount" to "30.00", "paymentMethod" to "CARD", "paymentDate" to "2026-04-01"),
        )
        assertStatus(201, paid)
        assertEquals("PAID", paid.body.path("claim").path("status").asText()) { paid.text }

        val trail = history(admin, "action" to ClaimService.ACTION_CLAIM_SUBMITTED)
        assertStatus(200, trail)
        val submission = trail.body.path("auditEvents").single()
        assertEquals(claimId.toString(), submission.path("entityId").asText())
        assertEquals(ClaimService.ENTITY_CLAIM, submission.path("entityType").asText())
        assertEquals(admin.userId, submission.path("userId").asInt())
        assertEquals(admin.email, submission.path("userEmail").asText())
        assertEquals(practiceA.id, submission.path("organizationId").asInt())
        assertEquals(claimNumber, submission.path("metadata").path("claimNumber").asText())
        assertEquals("ADJUDICATED", submission.path("metadata").path("status").asText())
        assertTrue(submission.path("timestamp").asText().isNotEmpty())

        val payment = history(admin, "action" to PaymentService.ACTION_PAYMENT_RECORDED)
        assertStatus(200, payment)
        val recorded = payment.body.path("auditEvents").single()
        assertEquals("30.00", recorded.path("metadata").path("amount").asText())
        assertEquals(manager.userId, recorded.path("userId").asInt())
    }

    @Test
    fun theTrailStopsAtThePracticeBoundary() {
        val adminA = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val adminB = signIn(RoleCodes.PRACTICE_ADMIN, practiceB.id)

        // Something in practice A worth reading about: a role assignment, which is
        // audited in the same transaction as the assignment itself.
        val target = insertUser("audit-target")
        roleAdmin.assign(adminA.userId, target, RoleCodes.BILLER, practiceA.id)

        val inA = history(adminA)
        assertStatus(200, inA)
        val assigned = inA.body.path("auditEvents")
            .first { it.path("action").asText() == RoleAdminService.ACTION_ROLE_ASSIGNED }
        assertEquals("BILLER", assigned.path("metadata").path("roleCode").asText())

        val inB = history(adminB)
        assertStatus(200, inB)
        assertTrue(inB.body.path("auditEvents").isEmpty) { "practice B saw practice A's trail: ${inB.text}" }

        // Naming the other practice is refused rather than answered with their own.
        assertStatus(403, history(adminB, "organizationId" to practiceA.id))
    }

    @Test
    fun auditingNeedsThePermission() {
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        roleAdmin.assign(admin.userId, insertUser("audit-target-b"), RoleCodes.BILLER, practiceA.id)

        // BILLER works claims and holds no AUDIT_VIEW: the list is empty rather
        // than refused, like every other list derived from grants.
        val biller = signIn(RoleCodes.BILLER, practiceA.id)
        val listed = history(biller)
        assertStatus(200, listed)
        assertTrue(listed.body.path("auditEvents").isEmpty) { listed.text }

        // Asking for a practice they cannot audit is a plain refusal.
        assertStatus(403, history(biller, "organizationId" to practiceA.id))

        // A practice administrator holds AUDIT_VIEW, and reads the same trail.
        assertStatus(200, history(admin, "organizationId" to practiceA.id))
    }

    @Test
    fun platformOperationsAreShownToPlatformReadersOnly() {
        val adminA = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val platformAdmin = signIn(RoleCodes.PLATFORM_ADMIN, null)

        // A platform-wide assignment belongs to no practice, so it is not part of
        // practice A's history...
        roleAdmin.assign(platformAdmin.userId, insertUser("audit-target-c"), RoleCodes.PLATFORM_ADMIN, null)
        val inA = history(adminA)
        assertStatus(200, inA)
        assertTrue(inA.body.path("auditEvents").none { it.path("organizationId").isNull }) { inA.text }

        // ...and is part of the platform administrator's.
        val platform = history(platformAdmin)
        assertStatus(200, platform)
        assertTrue(
            platform.body.path("auditEvents").any {
                it.path("action").asText() == RoleAdminService.ACTION_ROLE_ASSIGNED && it.path("organizationId").isNull
            },
        ) { "the platform trail is missing the platform assignment: ${platform.text}" }
    }

    @Test
    fun theHistoryIsNewestFirstAndBounded() {
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        listOf("audit-first", "audit-second", "audit-third").forEach { tag ->
            roleAdmin.assign(admin.userId, insertUser(tag), RoleCodes.BILLER, practiceA.id)
        }

        val trail = history(admin)
        assertStatus(200, trail)
        val ids = trail.body.path("auditEvents").map { it.path("id").asInt() }
        assertEquals(ids.sortedDescending(), ids) { "the trail is not newest first: ${trail.text}" }

        val limited = history(admin, "limit" to 2)
        assertStatus(200, limited)
        assertEquals(2, limited.body.path("auditEvents").size())

        // A limit is a request, not a way to ask for everything: it is clamped.
        val unlimited = history(admin, "limit" to 100000)
        assertStatus(200, unlimited)
        assertTrue(unlimited.body.path("auditEvents").size() <= AuditService.MAX_LIMIT) { unlimited.text }
    }

    private fun history(session: BillingApiTest.Session, vararg query: Pair<String, Any>): TestEnv.Response {
        val suffix = if (query.isEmpty()) "" else query.joinToString("&", prefix = "?") { "${it.first}=${it.second}" }
        return env.doJson("GET", "/api/audit-events$suffix", session.token, null)
    }

    private fun createPatient(session: BillingApiTest.Session): Int {
        val created = env.doJson(
            "POST",
            "/api/patients",
            session.token,
            mapOf("firstName" to "Jane", "lastName" to "Smith", "dateOfBirth" to "1979-03-14"),
        )
        assertStatus(201, created)
        return created.body.path("patient").path("id").asInt()
    }

    private fun createProvider(session: BillingApiTest.Session): Int {
        val created = env.doJson(
            "POST",
            "/api/providers",
            session.token,
            mapOf("firstName" to "Dana", "lastName" to "Reyes", "npi" to "1245319599"),
        )
        assertStatus(201, created)
        return created.body.path("provider").path("id").asInt()
    }

    private fun createCoverage(session: BillingApiTest.Session, patientId: Int): Int {
        val created = env.doJson(
            "POST",
            "/api/patients/$patientId/coverages",
            session.token,
            mapOf(
                "payerId" to payerId(),
                "memberId" to "T1M3L1N3",
                "groupNumber" to "GRP-77",
                "subscriberName" to "Jane Smith",
                "relationshipToSubscriber" to "SELF",
                "effectiveDate" to "2024-01-01",
                "terminationDate" to null,
                "priority" to 1,
            ),
        )
        assertStatus(201, created)
        return created.body.path("coverage").path("id").asInt()
    }

    private fun createClaim(session: BillingApiTest.Session, patientId: Int, providerId: Int, coverageId: Int): Int {
        val created = env.doJson(
            "POST",
            "/api/claims",
            session.token,
            mapOf(
                "patientId" to patientId,
                "providerId" to providerId,
                "coverageId" to coverageId,
                "serviceDate" to "2026-03-02",
                "diagnoses" to listOf("J06.9"),
                "lines" to listOf(mapOf("procedureCode" to "99213", "quantity" to 1, "chargeAmount" to "150.00")),
            ),
        )
        assertStatus(201, created)
        return created.body.path("claim").path("id").asInt()
    }

    private fun payerId(): Int = jdbc
        .sql("SELECT id FROM payers WHERE payer_code = 'SYN001'")
        .query(Int::class.javaObjectType)
        .single()
}
