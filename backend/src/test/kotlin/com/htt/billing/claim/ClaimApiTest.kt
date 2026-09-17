package com.htt.billing.claim

import com.htt.billing.security.RoleCodes
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The golden path, end to end over HTTP: create a claim, validate it, mark it
 * ready, submit it, and read back the numbers the plan specifies.
 *
 * 99213 charges 150.00; the payer allows 110.00 with a 30.00 copay, so the
 * contractual adjustment is 40.00, the payer owes 80.00 and the patient owes
 * 30.00.
 *
 * The roles here are decided by the seeded permission matrix rather than by this
 * test: PRACTICE_ADMIN sets the practice up (patients, providers, coverage) and,
 * since V5, works claims as well; BILLING_MANAGER and BILLER hold the same claim
 * operations; READ_ONLY holds CLAIM_VIEW and nothing that moves a claim.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ClaimApiTest : BillingApiTest() {

    @AfterEach
    fun removeExtraTerminology() {
        // Only the code this suite adds for the unpriced-procedure case.
        jdbc.sql("DELETE FROM procedure_codes WHERE code = :code").param("code", UNPRICED_CODE).update()
    }

    @Test
    fun goldenPathPricesTheClaimThePlanSpecifies() {
        val fix = fixture()

        val created = env.doJson("POST", "/api/claims", fix.billingToken, claimBody(fix.data))
        assertStatus(201, created)
        val claimId = created.body.path("claim").path("id").asInt()
        assertEquals("DRAFT", created.body.path("claim").path("status").asText())
        assertTrue(created.body.path("claim").path("claimNumber").asText().startsWith("CLM-")) {
            created.text
        }

        val validated = env.doJson("POST", "/api/claims/$claimId/validate", fix.billingToken, null)
        assertStatus(200, validated)
        assertTrue(validated.body.path("valid").asBoolean()) { validated.text }

        val ready = env.doJson("POST", "/api/claims/$claimId/ready", fix.billingToken, null)
        assertStatus(200, ready)
        assertEquals("READY", ready.body.path("claim").path("status").asText())

        val submitted = env.doJson("POST", "/api/claims/$claimId/submit", fix.billingToken, null)
        assertStatus(200, submitted)
        assertEquals("ADJUDICATED", submitted.body.path("claim").path("status").asText())

        val adjudication = submitted.body.path("adjudication")
        assertEquals(150.0, adjudication.path("totalCharge").asDouble())
        assertEquals(110.0, adjudication.path("totalAllowed").asDouble())
        assertEquals(40.0, adjudication.path("totalAdjustment").asDouble())
        assertEquals(80.0, adjudication.path("payerResponsibility").asDouble())
        assertEquals(30.0, adjudication.path("patientResponsibility").asDouble())

        val line = submitted.body.path("lines").get(0)
        assertEquals("99213", line.path("procedureCode").asText())
        assertEquals(110.0, line.path("allowedAmount").asDouble())
        assertEquals("PAID", line.path("status").asText())

        // Reloading gives the same figures, so they were persisted rather than
        // just computed for the response.
        val reloaded = env.doJson("GET", "/api/claims/$claimId", fix.adminToken, null)
        assertStatus(200, reloaded)
        assertEquals("ADJUDICATED", reloaded.body.path("claim").path("status").asText())
        assertEquals(110.0, reloaded.body.path("adjudication").path("totalAllowed").asDouble())
        assertEquals(
            line.path("payerAmount").asDouble(),
            reloaded.body.path("lines").get(0).path("payerAmount").asDouble(),
        )
    }

    @Test
    fun submittingADraftIsRefusedByTheStateMachine() {
        val fix = fixture()
        val claimId = createClaim(fix.billingToken, fix.data)

        // DRAFT to SUBMITTED is not a move the machine has, so validating and
        // submitting stay separate steps.
        val refused = env.doJson("POST", "/api/claims/$claimId/submit", fix.billingToken, null)
        assertStatus(400, refused)
        assertTrue(refused.body.path("message").asText().contains("DRAFT")) { refused.text }
    }

    @Test
    fun anIncompleteClaimCannotBeMarkedReady() {
        val fix = fixture()
        val created = env.doJson(
            "POST",
            "/api/claims",
            fix.billingToken,
            claimBody(fix.data, diagnoses = emptyList(), serviceDate = ""),
        )
        assertStatus(201, created)
        val claimId = created.body.path("claim").path("id").asInt()

        val ready = env.doJson("POST", "/api/claims/$claimId/ready", fix.billingToken, null)
        assertStatus(400, ready)
        val codes = ready.body.path("issues").map { it.path("code").asText() }
        assertTrue(codes.contains("CLAIM_NO_DIAGNOSES")) { ready.text }
        assertTrue(codes.contains("CLAIM_SERVICE_DATE_MISSING")) { ready.text }
    }

    @Test
    fun validateReportsEveryProblemWithoutRefusing() {
        val fix = fixture()
        val created = env.doJson(
            "POST",
            "/api/claims",
            fix.billingToken,
            claimBody(fix.data, diagnoses = listOf("NOPE"), procedureCode = "00000", charge = "0.00"),
        )
        assertStatus(201, created)
        val claimId = created.body.path("claim").path("id").asInt()

        val validated = env.doJson("POST", "/api/claims/$claimId/validate", fix.billingToken, null)
        assertStatus(200, validated)
        assertFalse(validated.body.path("valid").asBoolean())
        val codes = validated.body.path("issues").map { it.path("code").asText() }
        assertTrue(codes.contains("CLAIM_UNKNOWN_DIAGNOSIS")) { validated.text }
        assertTrue(codes.contains("CLAIM_UNKNOWN_PROCEDURE")) { validated.text }
        assertTrue(codes.contains("CLAIM_LINE_CHARGE_INVALID")) { validated.text }
    }

    @Test
    fun aProcedureThePayerDoesNotPriceIsDeniedRatherThanGuessed() {
        val fix = fixture()
        // A real code with no fee schedule row, which is the reachable version of
        // "service not covered".
        jdbc.sql(
            """
            INSERT INTO procedure_codes (code, code_system, description)
            VALUES (:code, 'CPT', 'Unpriced in the demo contract')
            ON CONFLICT (code) DO NOTHING
            """,
        ).param("code", UNPRICED_CODE).update()

        val claimId = createClaim(
            fix.billingToken,
            fix.data,
            claimBody(fix.data, procedureCode = UNPRICED_CODE, charge = "75.00"),
        )

        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/ready", fix.billingToken, null))
        val submitted = env.doJson("POST", "/api/claims/$claimId/submit", fix.billingToken, null)
        assertStatus(200, submitted)

        assertEquals("DENIED", submitted.body.path("claim").path("status").asText())
        assertEquals("DENIED", submitted.body.path("adjudication").path("outcome").asText())
        assertEquals(75.0, submitted.body.path("adjudication").path("totalCharge").asDouble())
        assertEquals(0.0, submitted.body.path("adjudication").path("totalAllowed").asDouble())
        assertEquals("DENIED", submitted.body.path("lines").get(0).path("status").asText())
    }

    @Test
    fun aClaimThePayerCoversInFullIsPaidWithoutAPatientShare() {
        val fix = fixture()
        // A preventive visit the fee schedule covers with no copay, so nothing is
        // left owed once the payer has answered.
        val claimId = createClaim(
            fix.billingToken,
            fix.data,
            claimBody(
                fix.data,
                diagnoses = listOf("Z00.00"),
                procedureCode = "99396",
                charge = "220.00",
            ),
        )

        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/ready", fix.billingToken, null))
        val submitted = env.doJson("POST", "/api/claims/$claimId/submit", fix.billingToken, null)
        assertStatus(200, submitted)

        assertEquals("PAID", submitted.body.path("claim").path("status").asText())
        assertEquals(165.0, submitted.body.path("adjudication").path("payerResponsibility").asDouble())
        assertEquals(0.0, submitted.body.path("adjudication").path("patientResponsibility").asDouble())
    }

    @Test
    fun anotherPracticesClaimIsNotFound() {
        val inB = fixture(practiceB.id)
        val claimInB = createClaim(inB.billingToken, inB.data)
        val inA = fixture()

        assertStatus(404, env.doJson("GET", "/api/claims/$claimInB", inA.billingToken, null))
        assertStatus(404, env.doJson("POST", "/api/claims/$claimInB/submit", inA.billingToken, null))

        // And it is absent from the list, so the id cannot be discovered either.
        val listed = env.doJson("GET", "/api/claims", inA.billingToken, null)
        assertStatus(200, listed)
        assertTrue(listed.body.path("claims").isEmpty) { "leaked across practices: ${listed.text}" }
    }

    @Test
    fun aBillerCanCreateAndSubmitAClaim() {
        val fix = fixture()
        val biller = signIn(RoleCodes.BILLER, practiceA.id)

        val claimId = createClaim(biller.token, fix.data)
        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/ready", biller.token, null))
        val submitted = env.doJson("POST", "/api/claims/$claimId/submit", biller.token, null)
        assertStatus(200, submitted)
        assertEquals("ADJUDICATED", submitted.body.path("claim").path("status").asText())
    }

    @Test
    fun aPracticeAdminCanAlsoCreateAndSubmitAClaim() {
        val fix = fixture()

        val claimId = createClaim(fix.adminToken, fix.data)
        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/ready", fix.adminToken, null))
        val submitted = env.doJson("POST", "/api/claims/$claimId/submit", fix.adminToken, null)
        assertStatus(200, submitted)
        assertEquals("ADJUDICATED", submitted.body.path("claim").path("status").asText())
    }

    @Test
    fun aMemberNotCoveredOnTheServiceDateIsRejectedRatherThanAdjudicated() {
        // The coverage ended before the service date, and validation is content:
        // the claim is complete and the coverage flag is on. Eligibility on the
        // date of service is the payer's call, not a rule the practice applies.
        val fix = fixture(coverageTerminationDate = "2025-12-31")
        val claimId = createClaim(fix.billingToken, fix.data)

        val validated = env.doJson("POST", "/api/claims/$claimId/validate", fix.billingToken, null)
        assertStatus(200, validated)
        assertTrue(validated.body.path("valid").asBoolean()) { validated.text }

        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/ready", fix.billingToken, null))
        val submitted = env.doJson("POST", "/api/claims/$claimId/submit", fix.billingToken, null)
        assertStatus(200, submitted)

        assertEquals("REJECTED", submitted.body.path("claim").path("status").asText())
        assertEquals(
            "MEMBER_NOT_ELIGIBLE",
            submitted.body.path("claim").path("rejectionCode").asText(),
        )
        assertTrue(
            submitted.body.path("claim").path("rejectionMessage").asText().contains("2025-12-31"),
        ) { submitted.text }

        // Not adjudicated: the payer refused it, so nothing was priced and there is
        // no record of a decision about money.
        assertTrue(submitted.body.path("adjudication").isNull) { submitted.text }
        val line = submitted.body.path("lines").get(0)
        assertEquals("PENDING", line.path("status").asText())
        assertTrue(line.path("allowedAmount").isNull) { submitted.text }
    }

    @Test
    fun aRejectedClaimIsCorrectedAndGoesBackOut() {
        val fix = fixture(coverageTerminationDate = "2025-12-31")
        val claimId = createClaim(fix.billingToken, fix.data)
        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/ready", fix.billingToken, null))
        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/submit", fix.billingToken, null))

        // Editing a rejected claim is the correction: REJECTED to CORRECTED.
        val edited = env.doJson(
            "PUT",
            "/api/claims/$claimId",
            fix.billingToken,
            claimBody(fix.data, serviceDate = "2025-06-02"),
        )
        assertStatus(200, edited)
        assertEquals("CORRECTED", edited.body.path("claim").path("status").asText())

        // Back out, and this time the member was covered.
        val resubmitted = env.doJson("POST", "/api/claims/$claimId/submit", fix.billingToken, null)
        assertStatus(200, resubmitted)
        assertEquals("ADJUDICATED", resubmitted.body.path("claim").path("status").asText())
        assertEquals(2, resubmitted.body.path("claim").path("submissionVersion").asInt())

        // The payer's earlier reason is gone, because the claim is no longer rejected.
        assertTrue(resubmitted.body.path("claim").path("rejectionCode").isNull) {
            resubmitted.text
        }
        assertEquals(110.0, resubmitted.body.path("adjudication").path("totalAllowed").asDouble())
    }

    @Test
    fun aResubmissionNeedsTheResubmitPermissionNotTheSubmitOne() {
        val fix = fixture(coverageTerminationDate = "2025-12-31")
        val claimId = createClaim(fix.billingToken, fix.data)
        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/ready", fix.billingToken, null))
        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/submit", fix.billingToken, null))

        // PROVIDER holds CLAIM_CREATE and CLAIM_EDIT but neither CLAIM_SUBMIT nor
        // CLAIM_RESUBMIT, so the claim's state does not widen what it may do.
        val provider = signIn(RoleCodes.PROVIDER, practiceA.id)
        assertStatus(403, env.doJson("POST", "/api/claims/$claimId/submit", provider.token, null))

        // A practice admin holds CLAIM_RESUBMIT. Nothing about the coverage has
        // changed, so the payer refuses it again — with a fresh answer, which is
        // what makes a resubmission worth asking for.
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val resubmitted = env.doJson("POST", "/api/claims/$claimId/submit", admin.token, null)
        assertStatus(200, resubmitted)
        assertEquals("REJECTED", resubmitted.body.path("claim").path("status").asText())
        assertEquals(2, resubmitted.body.path("claim").path("submissionVersion").asInt())
    }

    @Test
    fun aReadOnlyUserCanReadButNotSubmit() {
        val fix = fixture()
        val claimId = createClaim(fix.billingToken, fix.data)
        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/ready", fix.billingToken, null))

        val reader = signIn(RoleCodes.READ_ONLY, practiceA.id)
        // READ_ONLY holds CLAIM_VIEW, so the claim is visible and the refusal is a
        // plain 403 rather than a 404.
        assertStatus(200, env.doJson("GET", "/api/claims/$claimId", reader.token, null))
        assertStatus(403, env.doJson("POST", "/api/claims/$claimId/submit", reader.token, null))
    }

    /** A patient, a provider and a primary coverage, plus tokens for two roles. */
    private data class Fixture(val data: Practice, val billingToken: String, val adminToken: String)

    private fun fixture(
        organizationId: Int = practiceA.id,
        coverageTerminationDate: String? = null,
    ): Fixture {
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, organizationId)
        return Fixture(
            data = seedPracticeData(admin.token, organizationId, coverageTerminationDate),
            billingToken = signIn(RoleCodes.BILLING_MANAGER, organizationId).token,
            adminToken = admin.token,
        )
    }

    private data class Practice(val patientId: Int, val providerId: Int, val coverageId: Int)

    private fun seedPracticeData(
        token: String,
        organizationId: Int,
        coverageTerminationDate: String?,
    ): Practice {
        val patient = env.doJson(
            "POST",
            "/api/patients",
            token,
            mapOf("firstName" to "Jane", "lastName" to "Smith", "dateOfBirth" to "1979-03-14"),
        )
        assertStatus(201, patient)
        val patientId = patient.body.path("patient").path("id").asInt()

        val provider = env.doJson(
            "POST",
            "/api/providers",
            token,
            mapOf(
                "organizationId" to organizationId,
                "firstName" to "Dana",
                "lastName" to "Reyes",
                "npi" to "1245319599",
            ),
        )
        assertStatus(201, provider)
        val providerId = provider.body.path("provider").path("id").asInt()

        val coverage = env.doJson(
            "POST",
            "/api/patients/$patientId/coverages",
            token,
            mapOf(
                "payerId" to payerId(),
                "memberId" to "M123456",
                "groupNumber" to "GRP-77",
                "subscriberName" to "Jane Smith",
                "relationshipToSubscriber" to "SELF",
                "effectiveDate" to "2024-01-01",
                "terminationDate" to coverageTerminationDate,
                "priority" to 1,
            ),
        )
        assertStatus(201, coverage)
        val coverageId = coverage.body.path("coverage").path("id").asInt()

        return Practice(patientId, providerId, coverageId)
    }

    private fun createClaim(
        token: String,
        data: Practice,
        body: Map<String, Any?> = claimBody(data),
    ): Int {
        val created = env.doJson("POST", "/api/claims", token, body)
        assertStatus(201, created)
        return created.body.path("claim").path("id").asInt()
    }

    private fun claimBody(
        data: Practice,
        diagnoses: List<String> = listOf("J06.9"),
        procedureCode: String = "99213",
        charge: String = "150.00",
        serviceDate: String = "2026-03-02",
    ): Map<String, Any?> = mapOf(
        "patientId" to data.patientId,
        "providerId" to data.providerId,
        "coverageId" to data.coverageId,
        "serviceDate" to serviceDate,
        "diagnoses" to diagnoses,
        "lines" to listOf(
            mapOf("procedureCode" to procedureCode, "quantity" to 1, "chargeAmount" to charge),
        ),
    )

    private fun payerId(): Int = jdbc
        .sql("SELECT id FROM payers WHERE payer_code = 'SYN001'")
        .query(Int::class.javaObjectType)
        .single()

    private companion object {
        /** Deliberately absent from the seeded fee schedule. */
        const val UNPRICED_CODE = "99999"
    }
}
