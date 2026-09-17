package com.htt.billing.fhir

import com.htt.billing.claim.ClaimStatus
import com.htt.billing.security.RoleCodes
import com.htt.billing.service.fhir.FhirExportService
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.TestEnv
import com.htt.billing.support.assertStatus
import java.sql.Date
import org.hl7.fhir.r4.model.Claim
import org.hl7.fhir.r4.model.ClaimResponse
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.ExplanationOfBenefit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * FHIR export over HTTP: what the practice billed, and what the payer answered.
 *
 * The responses are read back with HAPI rather than matched as text, so these
 * assertions are about the resource a FHIR client would see — a text match would
 * pass on JSON no client could parse.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class FhirExportApiTest : BillingApiTest() {

    @Test
    fun exportsTheClaimThatWasBilled() {
        val claimId = submittedClaim()

        val response = export("claims", claimId, exporter.token)
        assertStatus(200, response)
        assertEquals("application/fhir+json", response.contentType) { "a FHIR client expects its own media type" }

        val claim = parse(response) as Claim
        assertEquals(Claim.ClaimStatus.ACTIVE, claim.status)
        assertEquals(Claim.Use.CLAIM, claim.use)
        assertEquals("professional", claim.type.codingFirstRep.code)
        // HAPI holds an id qualified by its type (`Claim/CLM-000002`) once parsed, so
        // the bare id is read off the id element.
        assertTrue(claim.idElement.idPart.startsWith("CLM-")) { claim.idElement.idPart }
        assertEquals(FhirExportService.CLAIM_NUMBER_SYSTEM, claim.identifierFirstRep.system)

        // The patient and provider are referenced by their own identifiers, so a
        // consumer can match them without knowing our row ids.
        assertEquals("Patient", claim.patient.reference.substringBefore('/'))
        assertEquals("FHIR-EXP-1", claim.patient.identifier.value)
        assertEquals("Imported Export", claim.patient.display)
        assertEquals(FhirMapping.Systems.NPI, claim.provider.identifier.system)
        assertEquals("1245319599", claim.provider.identifier.value)

        // The payer is contained, so the resource stands on its own.
        val payer = claim.contained.single() as org.hl7.fhir.r4.model.Organization
        assertEquals("Synthetic Health Plan", payer.name)
        assertEquals("SYN001", payer.identifierFirstRep.value)
        assertEquals("#payer", claim.insurer.reference)

        // Diagnoses and services carry their code systems.
        val diagnosis = claim.diagnosisFirstRep.diagnosis as CodeableConcept
        assertEquals("J06.9", diagnosis.codingFirstRep.code)
        assertEquals(FhirMapping.Systems.ICD_10_CM, diagnosis.codingFirstRep.system)
        val item = claim.item.single()
        assertEquals("99213", item.productOrService.codingFirstRep.code)
        assertEquals(FhirMapping.Systems.CPT, item.productOrService.codingFirstRep.system)
        assertEquals(1, item.quantity.value.toInt())
        assertEquals(150.0, item.net.value.toDouble())
        assertEquals(150.0, claim.total.value.toDouble())
        assertEquals("USD", claim.total.currency)
        assertEquals("2026-03-02", claim.billablePeriod.startElement.valueAsString.substring(0, 10))
    }

    @Test
    fun exportsWhatThePayerMadeOfIt() {
        val claimId = submittedClaim()

        val response = export("eob", claimId, exporter.token)
        assertStatus(200, response)

        val eob = parse(response) as ExplanationOfBenefit
        assertEquals(ExplanationOfBenefit.ExplanationOfBenefitStatus.ACTIVE, eob.status)
        assertEquals(ExplanationOfBenefit.RemittanceOutcome.COMPLETE, eob.outcome)
        assertEquals("Imported Export", eob.patient.display)

        // The plan's worked example, as adjudication categories.
        assertEquals(150.0, total(eob, FhirMapping.Adjudication.SUBMITTED))
        assertEquals(110.0, total(eob, FhirMapping.Adjudication.ALLOWED))
        assertEquals(40.0, total(eob, FhirMapping.Adjudication.DEDUCTION))
        assertEquals(30.0, total(eob, FhirMapping.Adjudication.COPAY))
        assertEquals(80.0, total(eob, FhirMapping.Adjudication.BENEFIT))

        val item = eob.item.single()
        assertEquals("99213", item.productOrService.codingFirstRep.code)
        assertEquals(
            110.0,
            item.adjudication.first { it.category.codingFirstRep.code == FhirMapping.Adjudication.ALLOWED }
                .amount.value.toDouble(),
        )

        // The payer's remittance, which is what makes the benefit a payment.
        assertEquals(80.0, eob.payment.amount.value.toDouble())
        assertEquals("USD", eob.payment.amount.currency)
    }

    @Test
    fun exportsThePayersAnswerAsTheClaimResponseAPayerSends() {
        val claimId = submittedClaim()

        val response = export("claim-response", claimId, exporter.token)
        assertStatus(200, response)
        assertEquals("application/fhir+json", response.contentType) { "a FHIR client expects its own media type" }

        val answer = parse(response) as ClaimResponse
        assertEquals(ClaimResponse.ClaimResponseStatus.ACTIVE, answer.status)
        assertEquals(ClaimResponse.RemittanceOutcome.COMPLETE, answer.outcome)
        // It answers the claim, so it points at the Claim our own export writes.
        assertTrue(answer.request.reference.startsWith("Claim/CLM-")) { answer.request.reference }
        assertEquals("Imported Export", answer.patient.display)
        assertEquals("#payer", answer.insurer.reference)

        // The same figures the ExplanationOfBenefit carries, from one list.
        assertEquals(150.0, total(answer, FhirMapping.Adjudication.SUBMITTED))
        assertEquals(110.0, total(answer, FhirMapping.Adjudication.ALLOWED))
        assertEquals(40.0, total(answer, FhirMapping.Adjudication.DEDUCTION))
        assertEquals(30.0, total(answer, FhirMapping.Adjudication.COPAY))
        assertEquals(80.0, total(answer, FhirMapping.Adjudication.BENEFIT))

        // Priced by the line the claim numbered, because the payer is answering a
        // claim it did not write.
        val item = answer.item.single()
        assertEquals(1, item.itemSequence)
        assertEquals(
            110.0,
            item.adjudication.first { it.category.codingFirstRep.code == FhirMapping.Adjudication.ALLOWED }
                .amount.value.toDouble(),
        )

        assertEquals(80.0, answer.payment.amount.value.toDouble())
        assertEquals("USD", answer.payment.amount.currency)
    }

    @Test
    fun aDraftClaimExportsAndHasNothingToExplainYet() {
        val claimId = createClaim()

        val claim = parse(export("claims", claimId, exporter.token)) as Claim
        assertEquals(Claim.ClaimStatus.DRAFT, claim.status)
        assertTrue(claim.item.isNotEmpty()) { "a draft still carries what it was written with" }

        val eob = export("eob", claimId, exporter.token)
        assertStatus(404, eob)
        assertTrue(eob.body.path("message").asText().contains("has not been adjudicated")) { eob.text }
        assertStatus(404, export("claim-response", claimId, exporter.token))
    }

    @Test
    fun aRejectedClaimExportsAsAnActiveClaimAndHasNothingToExplain() {
        // A service date before the coverage begins: the payer refuses it.
        val claimId = createClaim(serviceDate = "2020-01-05")
        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/ready", exporter.token, null))
        val submitted = env.doJson("POST", "/api/claims/$claimId/submit", exporter.token, null)
        assertEquals(ClaimStatus.REJECTED.name, submitted.body.path("claim").path("status").asText())

        // It is still a claim the practice billed, so it exports as one, with the
        // charge it was written with...
        val claim = parse(export("claims", claimId, exporter.token)) as Claim
        assertEquals(Claim.ClaimStatus.ACTIVE, claim.status)
        assertEquals(150.0, claim.total.value.toDouble())

        // ...and there is no benefit to explain, because nothing was adjudicated.
        assertStatus(404, export("eob", claimId, exporter.token))
    }

    @Test
    fun exportingNeedsThePermissionAndStopsAtThePracticeBoundary() {
        val claimId = submittedClaim()

        // BILLER works claims but holds no FHIR_EXPORT.
        val biller = signIn(RoleCodes.BILLER, practiceA.id)
        assertStatus(403, export("claims", claimId, biller.token))
        assertStatus(403, export("eob", claimId, biller.token))
        assertStatus(403, export("claim-response", claimId, biller.token))

        // Another practice's token cannot see the claim at all, which is "not found"
        // rather than a refusal.
        val outsider = signIn(RoleCodes.PRACTICE_ADMIN, practiceB.id)
        assertStatus(404, export("claims", claimId, outsider.token))
        assertStatus(404, export("eob", claimId, outsider.token))
        assertStatus(404, export("claim-response", claimId, outsider.token))
    }

    private fun total(eob: ExplanationOfBenefit, category: String): Double = eob.total
        .first { it.category.codingFirstRep.code == category }
        .amount.value.toDouble()

    private fun total(response: ClaimResponse, category: String): Double = response.total
        .first { it.category.codingFirstRep.code == category }
        .amount.value.toDouble()

    private fun export(kind: String, claimId: Int, token: String): TestEnv.Response =
        env.doJson("GET", "/api/integrations/fhir/$kind/$claimId", token, null)

    private fun parse(response: TestEnv.Response) = fhir.parser().parseResource(response.text)

    private val fhir = FhirResources()

    /** A claim in the plan's own shape: 99213, 150.00, primary coverage, submitted. */
    private fun submittedClaim(): Int {
        val claimId = createClaim()
        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/ready", exporter.token, null))
        val submitted = env.doJson("POST", "/api/claims/$claimId/submit", exporter.token, null)
        assertStatus(200, submitted)
        assertEquals(ClaimStatus.ADJUDICATED.name, submitted.body.path("claim").path("status").asText())
        return claimId
    }

    private fun createClaim(serviceDate: String = "2026-03-02"): Int {
        val created = env.doJson(
            "POST",
            "/api/claims",
            exporter.token,
            mapOf(
                "patientId" to patientId,
                "providerId" to providerId,
                "coverageId" to coverageId,
                "serviceDate" to serviceDate,
                "diagnoses" to listOf("J06.9"),
                "lines" to listOf(
                    mapOf("procedureCode" to "99213", "quantity" to 1, "chargeAmount" to "150.00"),
                ),
            ),
        )
        assertStatus(201, created)
        return created.body.path("claim").path("id").asInt()
    }

    /** PRACTICE_ADMIN holds FHIR_EXPORT, CLAIM_CREATE and CLAIM_SUBMIT. */
    private val exporter by lazy { signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id) }

    private val patientId: Int by lazy {
        jdbc.sql(
            """
            INSERT INTO patients
                (organization_id, external_id, first_name, last_name, date_of_birth)
            VALUES (:organizationId, :externalId, 'Imported', 'Export', '1980-01-01')
            RETURNING id
            """,
        )
            .param("organizationId", practiceA.id)
            .param("externalId", "http://hospital.example/mrn|FHIR-EXP-1")
            .query(Int::class.javaObjectType)
            .single()
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

    /** Begins after the service dates these tests use, apart from the rejection case. */
    private val coverageId: Int by lazy {
        jdbc.sql(
            """
            INSERT INTO coverages
                (organization_id, patient_id, payer_id, member_id, group_number,
                 effective_date, priority)
            VALUES
                (:organizationId, :patientId, :payerId, 'EXP123456', 'GRP-EXP',
                 :effectiveDate, 1)
            RETURNING id
            """,
        )
            .param("organizationId", practiceA.id)
            .param("patientId", patientId)
            .param("payerId", payerId())
            .param("effectiveDate", Date.valueOf("2024-01-01"))
            .query(Int::class.javaObjectType)
            .single()
    }

}
