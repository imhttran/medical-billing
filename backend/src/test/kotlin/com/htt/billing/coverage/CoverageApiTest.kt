package com.htt.billing.coverage

import com.htt.billing.repository.coverage.CoverageRepository.Coverage
import com.htt.billing.security.RoleCodes
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Coverage hangs off the patient, so its practice is the patient's practice —
 * the browser never names it. These cover that boundary and the validation that
 * keeps a coverage billable.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CoverageApiTest : BillingApiTest() {

    @Test
    fun createsAndListsCoverageForAPatient() {
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val patient = createPatient(admin.token, practiceA.id)

        val created = env.doJson("POST", createPath(patient), admin.token, coverageBody())
        assertStatus(201, created)
        assertEquals("M123456", created.body.path("coverage").path("memberId").asText())
        assertEquals(patient, created.body.path("coverage").path("patientId").asInt())
        // Taken from the patient, not from the request.
        assertEquals(practiceA.id, created.body.path("coverage").path("organizationId").asInt())

        val listed = env.doJson("GET", "/api/patients/$patient/coverages", admin.token, null)
        assertStatus(200, listed)
        assertEquals(1, listed.body.path("coverages").size())
        assertEquals("2024-01-01", listed.body.path("coverages").get(0).path("effectiveDate").asText())
    }

    @Test
    fun retiresCoverageOnUpdate() {
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val patient = createPatient(admin.token, practiceA.id)
        val coverage = createCoverage(admin.token, patient)

        val updated = env.doJson(
            "PUT",
            "/api/coverages/$coverage",
            admin.token,
            coverageBody() + mapOf("active" to false),
        )
        assertStatus(200, updated)
        assertFalse(updated.body.path("coverage").path("active").asBoolean())
    }

    @Test
    fun cannotAddCoverageToAnotherPracticesPatient() {
        val adminB = signIn(RoleCodes.PRACTICE_ADMIN, practiceB.id)
        val adminA = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val patientInB = createPatient(adminB.token, practiceB.id)

        assertStatus(404, env.doJson("POST", createPath(patientInB), adminA.token, coverageBody()))
        assertStatus(404, env.doJson("GET", "/api/patients/$patientInB/coverages", adminA.token, null))
    }

    @Test
    fun editingCoverageNeedsMoreThanView() {
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val biller = signIn(RoleCodes.BILLER, practiceA.id)
        val patient = createPatient(admin.token, practiceA.id)
        val coverage = createCoverage(admin.token, patient)

        // BILLER holds COVERAGE_VIEW but not COVERAGE_EDIT.
        assertStatus(200, env.doJson("GET", "/api/patients/$patient/coverages", biller.token, null))
        assertStatus(403, env.doJson("POST", createPath(patient), biller.token, coverageBody()))
        assertStatus(403, env.doJson("PUT", "/api/coverages/$coverage", biller.token, coverageBody()))
    }

    @Test
    fun rejectsCoverageThatCouldNotBeBilled() {
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val patient = createPatient(admin.token, practiceA.id)
        val path = createPath(patient)

        assertStatus(400, env.doJson("POST", path, admin.token, coverageBody() + mapOf("memberId" to "")))
        assertStatus(400, env.doJson("POST", path, admin.token, coverageBody() + mapOf("payerId" to 0)))
        assertStatus(400, env.doJson("POST", path, admin.token, coverageBody() + mapOf("payerId" to 99999999)))
        assertStatus(
            400,
            env.doJson(
                "POST",
                path,
                admin.token,
                coverageBody() + mapOf("effectiveDate" to "2025-06-01", "terminationDate" to "2025-01-01"),
            ),
        )
        assertStatus(400, env.doJson("POST", path, admin.token, coverageBody() + mapOf("priority" to 0)))
    }

    @Test
    fun payerListIsAvailableForThePicker() {
        val biller = signIn(RoleCodes.BILLER, practiceA.id)

        val payers = env.doJson("GET", "/api/payers", biller.token, null)
        assertStatus(200, payers)
        val codes = payers.body.path("payers").map { it.path("payerCode").asText() }
        assertTrue(codes.contains("SYN001")) { "seeded payers missing: ${payers.text}" }
    }

    private fun createCoverage(token: String, patientId: Int): Int {
        val created = env.doJson("POST", createPath(patientId), token, coverageBody())
        assertStatus(201, created)
        return created.body.path("coverage").path("id").asInt()
    }

    private fun createPath(patientId: Int): String = "/api/patients/$patientId/coverages"

    private fun createPatient(token: String, organizationId: Int): Int {
        val created = env.doJson(
            "POST",
            "/api/patients",
            token,
            mapOf(
                "organizationId" to organizationId,
                "firstName" to "Jane",
                "lastName" to "Smith",
                "dateOfBirth" to "1980-04-12",
            ),
        )
        assertStatus(201, created)
        return created.body.path("patient").path("id").asInt()
    }

    private fun coverageBody(): Map<String, Any?> = mapOf(
        "payerId" to payerId(),
        "memberId" to "M123456",
        "groupNumber" to "GRP-77",
        "subscriberName" to "Jane Smith",
        "relationshipToSubscriber" to "SELF",
        "effectiveDate" to "2024-01-01",
        "priority" to 1,
    )

    /** The seeded payer, looked up directly so the test does not depend on ordering. */
}
