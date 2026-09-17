package com.htt.billing.fhir

import com.htt.billing.claim.ClaimStatus
import com.htt.billing.security.RoleCodes
import com.htt.billing.service.fhir.FhirExportService
import com.htt.billing.service.fhir.FhirImportService
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.TestEnv
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * FHIR import over HTTP: a Bundle from another system, reconciled onto the
 * practice's own records.
 *
 * The bundle bodies are built as nested maps rather than raw JSON text — the test
 * harness posts maps — which serialises to exactly the JSON a FHIR client sends.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class FhirImportApiTest : BillingApiTest() {

    @Test
    fun importsAPatientAPractitionerAndACoverage() {
        val response = importBundle(bundleOf(patient(), practitioner(), coverage()))

        assertStatus(200, response)
        assertEquals(practiceA.id, response.body.path("organizationId").asInt())
        val imported = response.body.path("imported")
        assertEquals(3, imported.size()) { response.text }
        assertEquals(listOf("created", "created", "created"), imported.map { it.path("action").asText() })
        assertEquals(
            listOf("Patient", "Practitioner", "Coverage"),
            imported.map { it.path("resourceType").asText() },
        )
        // The identifier is echoed, so a caller can line up what it sent.
        assertEquals("$MRN_SYSTEM|MRN-4471", imported.get(0).path("identifier").asText())

        // They are ordinary records afterwards: the screens read them the same way.
        val patientId = imported.get(0).path("id").asInt()
        val shown = env.doJson("GET", "/api/patients/$patientId", importer.token, null)
        assertStatus(200, shown)
        assertEquals("Imported", shown.body.path("patient").path("lastName").asText())
        assertEquals("1971-02-03", shown.body.path("patient").path("dateOfBirth").asText())
        assertEquals("FEMALE", shown.body.path("patient").path("sex").asText())
        assertEquals("512-555-0199", shown.body.path("patient").path("phone").asText())

        val coverages = env.doJson("GET", "/api/patients/$patientId/coverages", importer.token, null)
        assertStatus(200, coverages)
        assertEquals(1, coverages.body.path("coverages").size()) { coverages.text }
        assertEquals("IMP9001", coverages.body.path("coverages").get(0).path("memberId").asText())
        assertEquals("GRP-IMPORT", coverages.body.path("coverages").get(0).path("groupNumber").asText())
        assertEquals("2024-01-01", coverages.body.path("coverages").get(0).path("effectiveDate").asText())

        val providerId = imported.get(1).path("id").asInt()
        val providers = env.doJson("GET", "/api/providers", importer.token, null)
        assertStatus(200, providers)
        val importedProvider = providers.body.path("providers").first { it.path("id").asInt() == providerId }
        assertEquals("1245319599", importedProvider.path("npi").asText())
        assertEquals("207Q00000X", importedProvider.path("taxonomyCode").asText())
    }

    @Test
    fun aSecondImportOfTheSameResourcesUpdatesRatherThanDuplicates() {
        importBundle(bundleOf(patient(), practitioner(), coverage()))

        // The same identifiers, with the patient having moved: an update, not a
        // second Mrs Imported.
        val again = importBundle(bundleOf(patient(family = "Imported-Updated", city = "Dallas")))
        assertStatus(200, again)
        assertEquals(
            listOf("updated"),
            again.body.path("imported").map { it.path("action").asText() },
        ) { again.text }

        val patients = env.doJson("GET", "/api/patients?query=Imported", importer.token, null)
        assertStatus(200, patients)
        assertEquals(1, patients.body.path("patients").size()) { patients.text }
        assertEquals("Imported-Updated", patients.body.path("patients").get(0).path("lastName").asText())
        assertEquals("Dallas", patients.body.path("patients").get(0).path("city").asText())
    }

    @Test
    fun aCoverageMayNameItsPatientByReferenceIntoTheSameBundle() {
        // No identifier on the beneficiary reference: the Coverage points at the
        // Patient entry, which is how a real export links the two.
        val patient = patient().toMutableMap().apply { this["id"] = "p1" }
        val coverage = coverage(memberId = "IMP-X1").toMutableMap().apply {
            this["beneficiary"] = mapOf("reference" to "Patient/p1")
        }

        val response = importBundle(bundleOf(patient, coverage))

        assertStatus(200, response)
        val patientId = response.body.path("imported").get(0).path("id").asInt()
        val coverageId = response.body.path("imported").get(1).path("id").asInt()
        val listed = env.doJson("GET", "/api/patients/$patientId/coverages", importer.token, null)
        assertStatus(200, listed)
        assertEquals(listOf(coverageId), listed.body.path("coverages").map { it.path("id").asInt() })
    }

    @Test
    fun aPayorNamedByDisplayIsMatchedToThePayer() {
        val coverage = coverage().toMutableMap().apply {
            this["payor"] = listOf(mapOf("display" to "Synthetic Health Plan"))
        }

        val response = importBundle(bundleOf(patient(), coverage))

        assertStatus(200, response)
        assertEquals(2, response.body.path("imported").size()) { response.text }
    }

    @Test
    fun resourceTypesThisDoesNotHandleAreSkippedRatherThanRefused() {
        // An export carries more than we need.
        val encounter = mapOf(
            "resourceType" to "Encounter",
            "status" to "finished",
            "class" to mapOf("code" to "AMB"),
        )

        val response = importBundle(bundleOf(patient(), encounter))

        assertStatus(200, response)
        assertEquals(listOf("Patient"), response.body.path("imported").map { it.path("resourceType").asText() })
        assertEquals(listOf("Encounter"), response.body.path("skipped").map { it.asText() }) { response.text }
    }

    @Test
    fun aBundleWithAProblemInItIsRefusedWholeAndNothingIsWritten() {
        // The first patient is importable, the second has no identifier. Half an
        // import would leave a practice that can bill for one patient and not the
        // other, so neither is written.
        val response = importBundle(
            bundleOf(patient(value = "MRN-1"), patient(identifiers = emptyList<Map<String, Any?>>()), coverage()),
        )

        assertStatus(400, response)
        assertTrue(hasIssue(response, FhirImportService.PATIENT_NO_IDENTIFIER)) { response.text }

        val patients = env.doJson("GET", "/api/patients?query=Imported", importer.token, null)
        assertTrue(patients.body.path("patients").isEmpty) { "a refused import wrote a patient: ${patients.text}" }
    }

    @Test
    fun refusesAPayorThisPracticeDoesNotKnow() {
        val unknownPayor = coverage().toMutableMap().apply {
            this["payor"] = listOf(mapOf("identifier" to mapOf("value" to "NOT-A-PAYER")))
        }

        val response = importBundle(bundleOf(patient(), unknownPayor))

        assertStatus(400, response)
        assertTrue(hasIssue(response, FhirImportService.COVERAGE_NO_PAYER)) { response.text }
    }

    @Test
    fun refusesACoverageWithNoMemberIdentifier() {
        val noMemberId = coverage().toMutableMap().apply { this["identifier"] = emptyList<Map<String, Any?>>() }

        // The member identifier is what makes a coverage billable, so a bundle that
        // cannot say who the member is cannot be imported.
        val response = importBundle(bundleOf(patient(), noMemberId))

        assertStatus(400, response)
        assertTrue(hasIssue(response, FhirImportService.COVERAGE_NO_MEMBER_ID)) { response.text }
    }

    @Test
    fun refusesWhatIsNotAFhirBundle() {
        val notFhir = env.doJson("POST", importPath(), importer.token, mapOf("hello" to true))
        assertStatus(400, notFhir)
        assertTrue(notFhir.body.path("message").asText().contains("not a FHIR resource")) { notFhir.text }

        // A bare resource is FHIR, but an import is a Bundle of them.
        val notABundle = env.doJson("POST", importPath(), importer.token, patient())
        assertStatus(400, notABundle)
        assertTrue(notABundle.body.path("message").asText().contains("must be a Bundle")) { notABundle.text }

        assertStatus(400, env.doJson("POST", importPath(), importer.token, null))
    }

    @Test
    fun importingNeedsThePermissionAndAPracticeTheCallerMayWrite() {
        // BILLER holds no FHIR permission at all.
        val biller = signIn(RoleCodes.BILLER, practiceA.id)
        assertStatus(403, importAs(biller, practiceA.id, bundleOf(patient())))

        // A practice admin imports into their own practice...
        val adminElsewhere = signIn(RoleCodes.PRACTICE_ADMIN, practiceB.id)
        assertStatus(200, importAs(adminElsewhere, practiceB.id, bundleOf(patient(value = "MRN-B7"))))

        // ...and naming one they have no grant in is refused, not quietly moved to
        // the practice they can write.
        assertStatus(403, importAs(adminElsewhere, practiceA.id, bundleOf(patient(value = "MRN-A9"))))

        // Naming nothing is allowed when there is only one practice they could mean,
        // which is what every other create endpoint does too.
        val derived = env.doJson("POST", importPath(null), adminElsewhere.token, bundleOf(patient(value = "MRN-B8")))
        assertStatus(200, derived)
        assertEquals(practiceB.id, derived.body.path("organizationId").asInt())
    }

    @Test
    fun importsAClaimWithTheRecordsItNamesInTheSameBundle() {
        val response = importBundle(
            bundleOf(patient(id = "p1"), practitioner(id = "prac1"), coverage(entryId = "cov1"), claim()),
        )

        assertStatus(200, response)
        val imported = response.body.path("imported")
        assertEquals(
            listOf("Patient", "Practitioner", "Coverage", "Claim"),
            imported.map { it.path("resourceType").asText() },
        ) { response.text }
        assertEquals("IMP-CLM-1", imported.get(3).path("identifier").asText())

        val claimId = imported.get(3).path("id").asInt()
        val shown = env.doJson("GET", "/api/claims/$claimId", importer.token, null)
        assertStatus(200, shown)
        // Not the status the resource carried: an imported claim arrives as a draft
        // for the practice to check, validate and send.
        assertEquals(ClaimStatus.DRAFT.name, shown.body.path("claim").path("status").asText())
        assertEquals(imported.get(0).path("id").asInt(), shown.body.path("claim").path("patientId").asInt())
        assertEquals(imported.get(2).path("id").asInt(), shown.body.path("claim").path("coverageId").asInt()) {
            "the focal coverage is the primary one, wherever the sender listed it"
        }
        assertEquals("2026-03-02", shown.body.path("claim").path("serviceDate").asText())
        assertEquals(listOf("J06.9"), shown.body.path("diagnoses").map { it.path("diagnosisCode").asText() })
        val line = shown.body.path("lines").single()
        assertEquals("99213", line.path("procedureCode").asText())
        assertEquals(1, line.path("quantity").asInt())
        assertEquals(150.0, line.path("chargeAmount").asDouble())
        assertEquals("PENDING", line.path("status").asText()) { "nothing prices an imported claim until it is sent" }
    }

    @Test
    fun aClaimIsReconciledOnIdentifiersThePracticeAlreadyHolds() {
        // The records first, then the claim naming them the way another system
        // would: the patient by the identifier it was reconciled on, the provider by
        // NPI, the coverage by member id.
        importBundle(bundleOf(patient(), practitioner(), coverage()))

        val first = importBundle(bundleOf(claim()))
        assertStatus(200, first)
        assertEquals(listOf("created"), first.body.path("imported").map { it.path("action").asText() }) { first.text }
        val claimId = first.body.path("imported").get(0).path("id").asInt()

        // The same claim number with a different service: the practice's own claim
        // is updated rather than a second one written.
        val again = importBundle(bundleOf(claim(items = listOf(item(code = "99214", charge = 200.0)))))
        assertStatus(200, again)
        assertEquals(listOf("updated"), again.body.path("imported").map { it.path("action").asText() }) { again.text }
        assertEquals(claimId, again.body.path("imported").get(0).path("id").asInt())

        val shown = env.doJson("GET", "/api/claims/$claimId", importer.token, null)
        assertEquals(listOf("99214"), shown.body.path("lines").map { it.path("procedureCode").asText() })
        assertEquals(200.0, shown.body.path("lines").get(0).path("chargeAmount").asDouble())
        val listed = env.doJson("GET", "/api/claims", importer.token, null)
        assertEquals(1, listed.body.path("claims").size()) { listed.text }
    }

    @Test
    fun refusesToImportOntoAClaimThePayerHasAlreadyAnswered() {
        val imported = importBundle(
            bundleOf(patient(id = "p1"), practitioner(id = "prac1"), coverage(entryId = "cov1"), claim()),
        )
        val claimId = imported.body.path("imported").get(3).path("id").asInt()
        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/ready", importer.token, null))
        val submitted = env.doJson("POST", "/api/claims/$claimId/submit", importer.token, null)
        assertEquals(ClaimStatus.ADJUDICATED.name, submitted.body.path("claim").path("status").asText())

        // A claim the payer has priced is not something an import can rewrite.
        val again = importBundle(bundleOf(claim(items = listOf(item(code = "99214", charge = 200.0)))))
        assertStatus(400, again)
        assertTrue(hasIssue(again, FhirImportService.CLAIM_NOT_EDITABLE)) { again.text }

        val shown = env.doJson("GET", "/api/claims/$claimId", importer.token, null)
        assertEquals(ClaimStatus.ADJUDICATED.name, shown.body.path("claim").path("status").asText())
        assertEquals(listOf("99213"), shown.body.path("lines").map { it.path("procedureCode").asText() })
    }

    @Test
    fun aClaimNamingAPatientNobodyKnowsIsRefusedWhole() {
        val response = importBundle(
            bundleOf(
                patient(id = "p1"),
                claim(
                    patient = mapOf("identifier" to mapOf("system" to MRN_SYSTEM, "value" to "MRN-NOBODY")),
                    provider = mapOf("reference" to "Practitioner/prac1"),
                    coverage = mapOf("reference" to "Coverage/cov1"),
                ),
            ),
        )

        assertStatus(400, response)
        assertTrue(hasIssue(response, FhirImportService.CLAIM_NO_PATIENT)) { response.text }
        // The patient it did name in the same bundle went back with it.
        val patients = env.doJson("GET", "/api/patients?query=Imported", importer.token, null)
        assertTrue(patients.body.path("patients").isEmpty) { patients.text }
    }

    @Test
    fun aClaimWithNoClaimNumberOrNoChargeIsRefused() {
        val noNumber = importBundle(bundleOf(claim(identifiers = emptyList<Map<String, Any?>>())))
        assertStatus(400, noNumber)
        assertTrue(hasIssue(noNumber, FhirImportService.CLAIM_NO_NUMBER)) { noNumber.text }

        // A line with no charge is a line nothing can be billed for.
        val noCharge = importBundle(
            bundleWithRecords(claim(items = listOf(item(charge = null)))),
        )
        assertStatus(400, noCharge)
        assertTrue(hasIssue(noCharge, FhirImportService.CLAIM_LINE_NO_CHARGE)) { noCharge.text }
    }

    private fun hasIssue(response: TestEnv.Response, code: String): Boolean =
        response.body.path("issues").any { it.path("code").asText() == code }

    private fun importPath(organizationId: Int? = practiceA.id): String =
        "/api/integrations/fhir/import" + (organizationId?.let { "?organizationId=$it" } ?: "")

    private fun importBundle(bundle: Map<String, Any?>): TestEnv.Response =
        importAs(importer, practiceA.id, bundle)

    /**
     * A bundle that carries the records a claim names — the patient, the provider and
     * the coverage, each reconciled on the identifier the claim refers to it by — so
     * a test about the claim has nothing else in its way.
     */
    private fun bundleWithRecords(vararg more: Map<String, Any?>): Map<String, Any?> =
        bundleOf(patient(), practitioner(), coverage(), *more)

    private fun importAs(
        session: BillingApiTest.Session,
        organizationId: Int,
        bundle: Map<String, Any?>
    ): TestEnv.Response =
        env.doJson("POST", importPath(organizationId), session.token, bundle)

    private fun bundleOf(vararg resources: Map<String, Any?>): Map<String, Any?> = mapOf(
        "resourceType" to "Bundle",
        "type" to "collection",
        "entry" to resources.map { mapOf("resource" to it) },
    )

    private fun patient(
        value: String = "MRN-4471",
        family: String = "Imported",
        city: String? = null,
        identifiers: List<Map<String, Any?>>? = null,
        id: String? = null,
    ): Map<String, Any?> = mapOf(
        "resourceType" to "Patient",
        "identifier" to (identifiers ?: listOf(mapOf("system" to MRN_SYSTEM, "value" to value))),
        "name" to listOf(mapOf("family" to family, "given" to listOf("Ida"))),
        "birthDate" to "1971-02-03",
        "gender" to "female",
        "telecom" to listOf(mapOf("system" to "phone", "value" to "512-555-0199")),
        "address" to mapOf(
            "line" to listOf("1 Import Way"),
            "city" to (city ?: "Austin"),
            "state" to "TX",
            "postalCode" to "78701",
        ),
    ).plus(id?.let { mapOf("id" to it) } ?: emptyMap())

    private fun practitioner(id: String? = null): Map<String, Any?> = mapOf(
        "resourceType" to "Practitioner",
        "identifier" to listOf(
            mapOf("system" to MRN_SYSTEM, "value" to "PRAC-88"),
            mapOf("system" to FhirMapping.Systems.NPI, "value" to "1245319599"),
        ),
        "name" to listOf(mapOf("family" to "Imported", "given" to listOf("Dana"))),
        "qualification" to listOf(
            mapOf(
                "code" to mapOf(
                    "coding" to listOf(
                        mapOf("system" to "http://nucc.org/provider-taxonomy", "code" to "207Q00000X"),
                    ),
                ),
            ),
        ),
    ).plus(id?.let { mapOf("id" to it) } ?: emptyMap())

    private fun coverage(memberId: String = "IMP9001", entryId: String? = null): Map<String, Any?> = mapOf(
        "resourceType" to "Coverage",
        "identifier" to listOf(mapOf("system" to "urn:payer:member", "value" to memberId)),
        "status" to "active",
        "beneficiary" to mapOf(
            "identifier" to mapOf("system" to MRN_SYSTEM, "value" to "MRN-4471"),
            "display" to "Ida Imported",
        ),
        "payor" to listOf(mapOf("identifier" to mapOf("value" to "SYN001"))),
        "subscriber" to mapOf("display" to "Ida Imported"),
        "relationship" to mapOf("coding" to listOf(mapOf("code" to "self"))),
        "period" to mapOf("start" to "2024-01-01"),
        "class" to listOf(
            mapOf(
                "type" to mapOf("coding" to listOf(mapOf("code" to "group"))),
                "value" to "GRP-IMPORT",
            ),
        ),
    ).plus(entryId?.let { mapOf("id" to it) } ?: emptyMap())

    /**
     * A claim as another system sends one.
     *
     * Its references default to the entries the helpers above write when they are
     * given an id, and to the identifiers they are reconciled on when they are not —
     * which is the two ways a claim can name a record it is not carrying.
     */
    private fun claim(
        number: String = "IMP-CLM-1",
        patient: Map<String, Any?> = mapOf(
            "identifier" to mapOf("system" to MRN_SYSTEM, "value" to "MRN-4471"),
        ),
        provider: Map<String, Any?> = mapOf(
            "identifier" to mapOf("system" to FhirMapping.Systems.NPI, "value" to "1245319599"),
        ),
        coverage: Map<String, Any?> = mapOf("identifier" to mapOf("value" to "IMP9001")),
        identifiers: List<Map<String, Any?>>? = null,
        items: List<Map<String, Any?>> = listOf(item()),
    ): Map<String, Any?> = mapOf(
        "resourceType" to "Claim",
        "identifier" to (
                identifiers ?: listOf(mapOf("system" to FhirExportService.CLAIM_NUMBER_SYSTEM, "value" to number))
                ),
        // Whatever the sender called it: an import does not take a status from a
        // resource, so this is not the status the claim is written with.
        "status" to "active",
        "use" to "claim",
        "type" to mapOf(
            "coding" to listOf(mapOf("system" to FhirMapping.Systems.CLAIM_TYPE, "code" to "professional")),
        ),
        "patient" to patient,
        "provider" to provider,
        "created" to "2026-03-01",
        "priority" to mapOf("coding" to listOf(mapOf("code" to "normal"))),
        // Two coverages with the focal one second, which is the order a sender that
        // lists secondary insurance first writes: the focal one is the primary.
        "insurance" to listOf(
            mapOf(
                "sequence" to 2,
                "focal" to false,
                "coverage" to mapOf("identifier" to mapOf("value" to "NOT-THE-ONE"))
            ),
            mapOf("sequence" to 1, "focal" to true, "coverage" to coverage),
        ),
        "billablePeriod" to mapOf("start" to "2026-03-02"),
        // The R4 spelling of the element, which is what a conformant sender writes;
        // HAPI alone would drop it, which is why the import renames it before parsing.
        "diagnosis" to listOf(
            mapOf(
                "sequence" to 1,
                "diagnosis" to mapOf(
                    "coding" to listOf(mapOf("system" to FhirMapping.Systems.ICD_10_CM, "code" to "J06.9")),
                ),
            ),
        ),
        "item" to items,
        "total" to mapOf("value" to 150.0, "currency" to "USD"),
    )

    /** A service line, with either half left out when the case is about it. */
    private fun item(code: String? = "99213", charge: Double? = 150.0): Map<String, Any?> {
        val item = mutableMapOf<String, Any?>(
            "sequence" to 1,
            "quantity" to mapOf("value" to 1),
        )
        code?.let {
            item["productOrService"] = mapOf(
                "coding" to listOf(mapOf("system" to FhirMapping.Systems.CPT, "code" to it)),
            )
        }
        charge?.let { item["net"] = mapOf("value" to it, "currency" to "USD") }
        return item
    }

    /** PRACTICE_ADMIN holds FHIR_IMPORT, and can read back what it imported. */
    private val importer by lazy { signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id) }

    private companion object {
        const val MRN_SYSTEM = "http://hospital.example/mrn"
    }
}
