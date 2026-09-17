package com.htt.billing.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.htt.billing.claim.ClaimStatus
import com.htt.billing.security.RoleCodes
import com.htt.billing.service.workflow.WorkQueueService
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.TestEnv
import com.htt.billing.support.assertStatus
import java.sql.Date
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The work queue over HTTP: what the payer's answers put on it, what a biller does
 * with it, and who may do it.
 *
 * The rows a claim needs are written straight to the store, as in PaymentApiTest —
 * the subject here is the queue, not patient entry — while the claim itself goes
 * through the API, so the items come from the real submission path.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class WorkQueueApiTest : BillingApiTest() {

    /**
     * The unpriced procedure has to be a real code, or validation refuses the claim
     * before any payer sees it. Seeded by the claim suite too, so this is idempotent.
     */
    @BeforeEach
    fun addTheUnpricedCode() {
        jdbc.sql(
            """
            INSERT INTO procedure_codes (code, code_system, description)
            VALUES (:code, 'CPT', 'Unpriced in the demo contract')
            ON CONFLICT (code) DO NOTHING
            """,
        ).param("code", UNPRICED_CODE).update()
    }

    @AfterEach
    fun removeTheUnpricedCode() {
        jdbc.sql("DELETE FROM procedure_codes WHERE code = :code").param("code", UNPRICED_CODE).update()
    }

    @Test
    fun aRejectedClaimIsFollowUpWorkWithThePayersReason() {
        // A coverage that begins after the service date: the only rejection rule the
        // simulated payer has.
        val claimId = rejectedClaim()

        val item = openItemFor(claimId)
        assertEquals("REJECTION", item.path("type").asText())
        assertEquals("OPEN", item.path("status").asText())
        assertEquals("MEMBER_NOT_ELIGIBLE", item.path("reasonCode").asText())
        assertTrue(item.path("reasonText").asText().contains("coverage began")) { item.toString() }
        // The row says what it is about, so it is actionable from the list itself.
        assertEquals("REJECTED", item.path("claimStatus").asText())
        assertTrue(item.path("claimNumber").asText().startsWith("CLM-")) { item.toString() }
        assertTrue(item.path("patientName").asText().contains("Queue")) { item.toString() }
        assertTrue(item.path("assignedUserId").isNull) { item.toString() }
    }

    @Test
    fun aServiceThePayerWillNotCoverIsFollowUpWorkEvenWhenTheClaimIsPaid() {
        val claimId = deniedLineClaim()

        val item = openItemFor(claimId)
        assertEquals("DENIAL", item.path("type").asText())
        assertEquals("SERVICE_NOT_COVERED", item.path("reasonCode").asText())
        assertTrue(item.path("reasonText").asText().contains(UNPRICED_CODE)) { item.toString() }
        // Adjudicated rather than denied: the other line was covered and paid.
        assertEquals(ClaimStatus.ADJUDICATED.name, item.path("claimStatus").asText())
    }

    @Test
    fun resubmittingTheClaimResolvesItsItems() {
        val claimId = rejectedClaim()
        val itemId = openItemFor(claimId).path("id").asInt()

        // Correct it, which is what the rejection asked for, then send it back.
        assertStatus(
            200,
            env.doJson("PUT", "/api/claims/$claimId", fixer.token, claimBody(AFTER_COVERAGE_BEGINS)),
        )
        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/submit", fixer.token, null))

        // The follow-up the item was waiting on has happened, so it closes itself.
        assertTrue(openQueue(fixer.token).none { it.path("claimId").asInt() == claimId }) {
            "the claim's item is still on the open queue"
        }

        val resolved = env.doJson("GET", "/api/work-items/$itemId", fixer.token, null)
        assertStatus(200, resolved)
        assertEquals("RESOLVED", resolved.body.path("workItem").path("status").asText())
        assertTrue(resolved.body.path("workItem").path("resolvedAt").isTextual) { resolved.text }

        // And the trail says who closed it and that the resubmission was the reason,
        // against the item's own row rather than the claim's.
        val audited = jdbc.sql(
            """
            SELECT count(*) FROM audit_events
            WHERE action = :action AND entity_type = 'WorkItem' AND entity_id = :entityId
            """,
        )
            .param("action", WorkQueueService.ACTION_RESOLVED)
            .param("entityId", itemId.toString())
            .query(Int::class.javaObjectType)
            .single()
        assertEquals(1, audited) { "the automatic resolution was not audited against the item" }
    }

    @Test
    fun aRejectionAfterAResubmissionIsANewItem() {
        val claimId = rejectedClaim()
        val first = openItemFor(claimId).path("id").asInt()

        // The payer refuses it twice: correcting the claim to a date the coverage
        // still does not cover is the reachable version.
        assertStatus(
            200,
            env.doJson(
                "PUT",
                "/api/claims/$claimId",
                fixer.token,
                claimBody(BEFORE_COVERAGE_BEGINS),
            ),
        )
        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/submit", fixer.token, null))

        val item = openItemFor(claimId)
        assertTrue(item.path("id").asInt() != first) { "the resolved item was reopened" }
        assertEquals("OPEN", item.path("status").asText())
        assertTrue(item.path("reasonText").asText().contains("coverage began")) { item.toString() }
    }

    @Test
    fun aBillerResolvesAnItemTheyAreNotGoingToWork() {
        val claimId = deniedLineClaim()
        val itemId = openItemFor(claimId).path("id").asInt()

        val resolved = env.doJson("POST", "/api/work-items/$itemId/resolve", fixer.token, null)
        assertStatus(200, resolved)
        assertEquals("RESOLVED", resolved.body.path("workItem").path("status").asText())

        // Resolving twice is refused rather than re-dated.
        assertStatus(400, env.doJson("POST", "/api/work-items/$itemId/resolve", fixer.token, null))

        // The default list is what is open; the history has to be asked for.
        assertTrue(openQueue(fixer.token).none { it.path("id").asInt() == itemId })
        val history = env.doJson("GET", "/api/work-items?status=all", fixer.token, null)
        assertStatus(200, history)
        val listed = workItems(history).first { it.path("id").asInt() == itemId }
        assertEquals("RESOLVED", listed.path("status").asText())
    }

    @Test
    fun anItemIsAssignedOnlyToSomeoneWhoCanWorkIt() {
        val claimId = rejectedClaim()
        val itemId = openItemFor(claimId).path("id").asInt()
        val manager = signIn(RoleCodes.BILLING_MANAGER, practiceA.id)

        val assigned = env.doJson(
            "POST",
            "/api/work-items/$itemId/assign",
            manager.token,
            mapOf("userId" to fixer.userId),
        )
        assertStatus(200, assigned)
        assertEquals(fixer.userId, assigned.body.path("workItem").path("assignedUserId").asInt())
        assertEquals(fixer.email, assigned.body.path("workItem").path("assignedUserEmail").asText())

        // Work cannot be given to someone with no grant in this practice, nor to an
        // id that names nobody.
        val outsider = signIn(RoleCodes.BILLER, practiceB.id)
        assertStatus(
            400,
            env.doJson(
                "POST",
                "/api/work-items/$itemId/assign",
                manager.token,
                mapOf("userId" to outsider.userId),
            ),
        )
        assertStatus(
            400,
            env.doJson("POST", "/api/work-items/$itemId/assign", manager.token, mapOf("userId" to 999999)),
        )

        // And a biller holds RESOLVE but not ASSIGN.
        assertStatus(
            403,
            env.doJson(
                "POST",
                "/api/work-items/$itemId/assign",
                fixer.token,
                mapOf("userId" to fixer.userId),
            ),
        )
    }

    @Test
    fun theQueueIsScopedToThePracticeThatOwnsTheClaim() {
        rejectedClaim()
        val outsider = signIn(RoleCodes.BILLER, practiceB.id)

        assertTrue(openQueue(outsider.token).isEmpty()) { "practice B saw practice A's queue" }
        val itemId = openQueue(fixer.token).first().path("id").asInt()
        // Out of practice answers "not found" rather than "forbidden".
        assertStatus(404, env.doJson("GET", "/api/work-items/$itemId", outsider.token, null))
        assertStatus(404, env.doJson("POST", "/api/work-items/$itemId/resolve", outsider.token, null))
    }

    @Test
    fun readingTheQueueIsPermittedWhereWorkingItIsNot() {
        rejectedClaim()
        val reader = signIn(RoleCodes.READ_ONLY, practiceA.id)
        val itemId = openQueue(fixer.token).first().path("id").asInt()

        assertStatus(200, env.doJson("GET", "/api/work-items", reader.token, null))
        assertStatus(403, env.doJson("POST", "/api/work-items/$itemId/resolve", reader.token, null))
        // PROVIDER holds nothing on the queue. The list is derived from the caller's
        // grants, as the claim list is, so it gets an empty queue rather than a
        // refusal, and an item it cannot see is "not found" rather than forbidden.
        val provider = signIn(RoleCodes.PROVIDER, practiceA.id)
        val forProvider = env.doJson("GET", "/api/work-items?status=all", provider.token, null)
        assertStatus(200, forProvider)
        assertTrue(workItems(forProvider).isEmpty()) { forProvider.text }
        assertStatus(404, env.doJson("POST", "/api/work-items/$itemId/resolve", provider.token, null))
    }

    @Test
    fun anUnknownStatusFilterIsRefused() {
        assertStatus(400, env.doJson("GET", "/api/work-items?status=maybe", fixer.token, null))
    }

    /** The one open item on a claim, which is what most of these tests are about. */
    private fun openItemFor(claimId: Int): JsonNode =
        openQueue(fixer.token).first { it.path("claimId").asInt() == claimId }

    private fun openQueue(token: String): List<JsonNode> {
        val response = env.doJson("GET", "/api/work-items", token, null)
        assertStatus(200, response)
        return workItems(response)
    }

    private fun workItems(response: TestEnv.Response): List<JsonNode> =
        response.body.path("workItems").toList()

    /** A claim the payer refuses, so the queue has a rejection on it. */
    private fun rejectedClaim(): Int {
        val id = createClaim(BEFORE_COVERAGE_BEGINS)
        assertStatus(200, env.doJson("POST", "/api/claims/$id/ready", fixer.token, null))
        val submitted = env.doJson("POST", "/api/claims/$id/submit", fixer.token, null)
        assertStatus(200, submitted)
        assertEquals(ClaimStatus.REJECTED.name, submitted.body.path("claim").path("status").asText())
        return id
    }

    /** A claim with one covered line and one the payer has no rate for. */
    private fun deniedLineClaim(): Int {
        val id = createClaim(AFTER_COVERAGE_BEGINS)
        assertStatus(
            200,
            env.doJson(
                "PUT",
                "/api/claims/$id",
                fixer.token,
                claimBody(AFTER_COVERAGE_BEGINS, lines = listOf(COVERED_LINE, UNPRICED_LINE)),
            ),
        )
        assertStatus(200, env.doJson("POST", "/api/claims/$id/ready", fixer.token, null))
        val submitted = env.doJson("POST", "/api/claims/$id/submit", fixer.token, null)
        assertStatus(200, submitted)
        assertEquals(ClaimStatus.ADJUDICATED.name, submitted.body.path("claim").path("status").asText())
        return id
    }

    private fun createClaim(serviceDate: String): Int {
        val created = env.doJson(
            "POST",
            "/api/claims",
            fixer.token,
            claimBody(serviceDate, lines = listOf(COVERED_LINE)),
        )
        assertStatus(201, created)
        return created.body.path("claim").path("id").asInt()
    }

    private fun claimBody(
        serviceDate: String,
        lines: List<Map<String, Any?>> = listOf(COVERED_LINE),
    ): Map<String, Any?> = mapOf(
        "patientId" to patientId,
        "providerId" to providerId,
        "coverageId" to coverageId,
        "serviceDate" to serviceDate,
        "diagnoses" to listOf("J06.9"),
        "lines" to lines,
    )

    /** BILLER holds CLAIM_*, WORK_QUEUE_VIEW and WORK_QUEUE_RESOLVE, not ASSIGN. */
    private val fixer by lazy { signIn(RoleCodes.BILLER, practiceA.id) }

    private val patientId: Int by lazy {
        jdbc.sql(
            """
            INSERT INTO patients (organization_id, first_name, last_name, date_of_birth)
            VALUES (:organizationId, 'Queue', 'Fixture', '1980-01-01')
            RETURNING id
            """,
        ).param("organizationId", practiceA.id).query(Int::class.javaObjectType).single()
    }

    private val providerId: Int by lazy {
        jdbc.sql(
            """
            INSERT INTO providers (organization_id, first_name, last_name, npi)
            VALUES (:organizationId, 'Dana', 'Reyes', '1245319599')
            RETURNING id
            """,
        ).param("organizationId", practiceA.id).query(Int::class.javaObjectType).single()
    }

    /** Begins between the two service dates these tests use. */
    private val coverageId: Int by lazy {
        jdbc.sql(
            """
            INSERT INTO coverages
                (organization_id, patient_id, payer_id, member_id, effective_date, priority)
            VALUES
                (:organizationId, :patientId, :payerId, 'QUEUE123456', :effectiveDate, 1)
            RETURNING id
            """,
        )
            .param("organizationId", practiceA.id)
            .param("patientId", patientId)
            .param("payerId", payerId())
            .param("effectiveDate", Date.valueOf(AFTER_COVERAGE_BEGINS))
            .query(Int::class.javaObjectType)
            .single()
    }

    private fun payerId(): Int = jdbc
        .sql("SELECT id FROM payers WHERE payer_code = 'SYN001'")
        .query(Int::class.javaObjectType)
        .single()

    private companion object {
        /** Before the coverage begins, so the payer refuses the claim. */
        const val BEFORE_COVERAGE_BEGINS = "2026-01-05"

        /** On the day it begins, so the services are covered. */
        const val AFTER_COVERAGE_BEGINS = "2026-03-02"

        /** Deliberately absent from the seeded fee schedule. */
        const val UNPRICED_CODE = "99999"

        val COVERED_LINE = mapOf("procedureCode" to "99213", "quantity" to 1, "chargeAmount" to "150.00")
        val UNPRICED_LINE = mapOf("procedureCode" to UNPRICED_CODE, "quantity" to 1, "chargeAmount" to "40.00")
    }
}
