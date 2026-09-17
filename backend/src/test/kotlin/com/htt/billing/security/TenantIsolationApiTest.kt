package com.htt.billing.security

import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.TestEnv
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The tenant boundary, checked route by route rather than slice by slice.
 *
 * Each slice tested the isolation of the endpoints it added; this is the pass
 * that says the boundary holds across all of them at once, with the same roles on
 * both sides. That is what makes a 404 mean "another practice" rather than "you
 * are not allowed": the caller in practice B holds exactly the permissions the
 * caller in practice A holds, so the only thing separating them is the tenant.
 *
 * A route the boundary forgets is a route that answers something else — 200, or a
 * 403 that would confirm the row exists — so the expected status is part of the
 * case, not an afterthought.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class TenantIsolationApiTest : BillingApiTest() {

    @Test
    fun everyTenantOwnedRouteAnswersAnotherPracticeWithNotFound() {
        val fix = fixtureInA()
        val outsider = signIn(RoleCodes.BILLER, practiceB.id)

        val routes = listOf(
            Route("GET", "/api/patients/${fix.patientId}", null),
            Route("PUT", "/api/patients/${fix.patientId}", patientBody()),
            Route("GET", "/api/patients/${fix.patientId}/balance", null),
            Route("GET", "/api/patients/${fix.patientId}/coverages", null),
            Route("POST", "/api/patients/${fix.patientId}/coverages", coverageBody()),
            Route("PUT", "/api/coverages/${fix.coverageId}", coverageBody()),
            Route("GET", "/api/providers/${fix.providerId}", null),
            Route("GET", "/api/claims/${fix.claimId}", null),
            Route("PUT", "/api/claims/${fix.claimId}", claimBody(fix)),
            Route("POST", "/api/claims/${fix.claimId}/validate", null),
            Route("POST", "/api/claims/${fix.claimId}/ready", null),
            Route("POST", "/api/claims/${fix.claimId}/submit", null),
            Route("GET", "/api/claims/${fix.claimId}/payments", null),
            Route("POST", "/api/claims/${fix.claimId}/patient-payments", patientPaymentBody()),
            Route("GET", "/api/work-items/${fix.workItemId}", null),
            Route("POST", "/api/work-items/${fix.workItemId}/assign", mapOf("userId" to outsider.userId)),
            Route("POST", "/api/work-items/${fix.workItemId}/resolve", null),
        )

        routes.forEach { route ->
            val response = env.doJson(route.method, route.path, outsider.token, route.body)
            assertEquals(404, response.status) { "${route.method} ${route.path} leaked: ${response.text}" }
        }
    }

    @Test
    fun theExportsOfAnotherPracticesClaimAreNotFoundToo() {
        val fix = fixtureInA()
        val outsider = signIn(RoleCodes.PRACTICE_ADMIN, practiceB.id)

        // PRACTICE_ADMIN holds FHIR_EXPORT in both practices, so a 403 here would
        // mean the claim was found and refused rather than never seen.
        listOf("claims", "eob", "claim-response").forEach { kind ->
            val response = env.doJson("GET", "/api/integrations/fhir/$kind/${fix.claimId}", outsider.token, null)
            assertEquals(404, response.status) { "$kind leaked another practice's claim: ${response.text}" }
        }
    }

    @Test
    fun listsReturnNothingFromAnotherPractice() {
        fixtureInA()
        val outsider = signIn(RoleCodes.BILLER, practiceB.id)

        listOf("/api/patients", "/api/claims", "/api/work-items", "/api/providers").forEach { path ->
            val listed = env.doJson("GET", path, outsider.token, null)
            assertStatus(200, listed)
            assertTrue(listedRows(listed).isEmpty()) { "$path showed another practice's rows: ${listed.text}" }
        }
    }

    @Test
    fun aWriteIntoAnotherPracticeIsRefusedRatherThanMoved() {
        val outsider = signIn(RoleCodes.PRACTICE_ADMIN, practiceB.id)

        // Naming practice A is refused...
        assertEquals(403, createPatient(outsider, practiceA.id).status)
        assertEquals(403, createProvider(outsider, practiceA.id).status)

        // ...and naming nothing writes to their own practice, never the one they
        // named before or the one the fixture uses.
        val derived = createPatient(outsider, organizationId = 0)
        assertStatus(201, derived)
        assertEquals(practiceB.id, derived.body.path("patient").path("organizationId").asInt())
    }

    @Test
    fun aClaimCannotNameAnotherPracticesRows() {
        val inA = fixtureIn(practiceA.id)
        val inB = fixtureIn(practiceB.id)
        val biller = signIn(RoleCodes.BILLER, practiceB.id)

        // A claim's references arrive in the body, so naming another practice's
        // patient, provider or coverage has to be refused: the foreign keys would
        // take them, and validation would only report it once someone tried to
        // send the claim.
        val created = env.doJson("POST", "/api/claims", biller.token, claimBody(inA))
        assertEquals(400, created.status) { "another practice's rows were written into a claim: ${created.text}" }

        // The same on an edit, which takes its references from the body too.
        val edited = env.doJson("PUT", "/api/claims/${inB.claimId}", biller.token, claimBody(inA))
        assertEquals(400, edited.status) { "an edit pointed a claim at another practice: ${edited.text}" }

        // And the claim it tried to re-point is untouched.
        val shown = env.doJson("GET", "/api/claims/${inB.claimId}", biller.token, null)
        assertStatus(200, shown)
        assertEquals(inB.coverageId, shown.body.path("claim").path("coverageId").asInt())
    }

    @Test
    fun platformAdministrationCannotReadPatientOrClaimContent() {
        val fix = fixtureInA()
        val platformAdmin = signIn(RoleCodes.PLATFORM_ADMIN, null)

        // Platform administration: the practice list and the trail are theirs.
        assertStatus(200, env.doJson("GET", "/api/organizations", platformAdmin.token, null))
        assertStatus(200, env.doJson("GET", "/api/audit-events", platformAdmin.token, null))

        // Content is not: the lists are empty rather than full, because a list is
        // derived from grants and this account holds none of these.
        listOf("/api/patients", "/api/claims", "/api/work-items", "/api/providers").forEach { path ->
            val listed = env.doJson("GET", path, platformAdmin.token, null)
            assertStatus(200, listed)
            assertTrue(listedRows(listed).isEmpty()) { "$path served platform administration content: ${listed.text}" }
        }

        // And a single row answers "not found" rather than "forbidden", because the
        // account is not a member of the practice at all.
        assertStatus(404, env.doJson("GET", "/api/claims/${fix.claimId}", platformAdmin.token, null))
        assertStatus(404, env.doJson("GET", "/api/patients/${fix.patientId}", platformAdmin.token, null))
        assertStatus(404, env.doJson("POST", "/api/claims/${fix.claimId}/submit", platformAdmin.token, null))
        assertStatus(404, env.doJson("POST", "/api/claims/${fix.claimId}/patient-payments", platformAdmin.token, null))
        assertStatus(403, env.doJson("POST", "/api/providers", platformAdmin.token, providerBody(practiceA.id)))
    }

    private data class Route(val method: String, val path: String, val body: Map<String, Any?>?)

    /** Every row a list answer carries, whatever the endpoint calls its array. */
    private fun listedRows(response: TestEnv.Response): List<Any> =
        response.body.elements().asSequence().flatMap { it.asSequence() }.toList()

    /** A patient, a provider, a coverage, a refused claim and the item it opened. */
    private data class Fixture(
        val patientId: Int,
        val providerId: Int,
        val coverageId: Int,
        val claimId: Int,
        val workItemId: Int,
    )

    private fun fixtureInA(): Fixture = fixtureIn(practiceA.id)

    private fun fixtureIn(organizationId: Int): Fixture {
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, organizationId)
        val patient = env.doJson("POST", "/api/patients", admin.token, patientBody(organizationId))
        assertStatus(201, patient)
        val patientId = patient.body.path("patient").path("id").asInt()

        val provider = env.doJson("POST", "/api/providers", admin.token, providerBody(organizationId))
        assertStatus(201, provider)
        val providerId = provider.body.path("provider").path("id").asInt()

        val coverage = env.doJson("POST", "/api/patients/$patientId/coverages", admin.token, coverageBody())
        assertStatus(201, coverage)
        val coverageId = coverage.body.path("coverage").path("id").asInt()

        val created = env.doJson(
            "POST",
            "/api/claims",
            admin.token,
            claimBody(Fixture(patientId, providerId, coverageId, 0, 0)),
        )
        assertStatus(201, created)
        val claimId = created.body.path("claim").path("id").asInt()
        assertStatus(200, env.doJson("POST", "/api/claims/$claimId/ready", admin.token, null))
        val submitted = env.doJson("POST", "/api/claims/$claimId/submit", admin.token, null)
        assertEquals("REJECTED", submitted.body.path("claim").path("status").asText()) { submitted.text }

        val queue = env.doJson("GET", "/api/work-items", admin.token, null)
        assertStatus(200, queue)
        val workItemId = queue.body.path("workItems").first().path("id").asInt()

        return Fixture(patientId, providerId, coverageId, claimId, workItemId)
    }

    private fun createPatient(session: BillingApiTest.Session, organizationId: Int): TestEnv.Response =
        env.doJson("POST", "/api/patients", session.token, patientBody(organizationId))

    private fun createProvider(session: BillingApiTest.Session, organizationId: Int): TestEnv.Response =
        env.doJson("POST", "/api/providers", session.token, providerBody(organizationId))

    private fun patientBody(organizationId: Int = practiceA.id): Map<String, Any?> = mapOf(
        "organizationId" to organizationId,
        "firstName" to "Jane",
        "lastName" to "Smith",
        "dateOfBirth" to "1979-03-14",
    )

    private fun providerBody(organizationId: Int): Map<String, Any?> = mapOf(
        "organizationId" to organizationId,
        "firstName" to "Dana",
        "lastName" to "Reyes",
        "npi" to "1245319599",
    )

    private fun coverageBody(): Map<String, Any?> = mapOf(
        "payerId" to jdbc
            .sql("SELECT id FROM payers WHERE payer_code = 'SYN001'")
            .query(Int::class.javaObjectType)
            .single(),
        "memberId" to "T1S0LAT10N",
        "groupNumber" to "GRP-77",
        "subscriberName" to "Jane Smith",
        "relationshipToSubscriber" to "SELF",
        "effectiveDate" to "2024-01-01",
        // Ended, so the claim below is refused and the queue has something in it.
        "terminationDate" to "2025-12-31",
        "priority" to 1,
    )

    private fun claimBody(fix: Fixture, serviceDate: String = "2026-03-02"): Map<String, Any?> = mapOf(
        "patientId" to fix.patientId,
        "providerId" to fix.providerId,
        "coverageId" to fix.coverageId,
        "serviceDate" to serviceDate,
        "diagnoses" to listOf("J06.9"),
        "lines" to listOf(mapOf("procedureCode" to "99213", "quantity" to 1, "chargeAmount" to "150.00")),
    )

    private fun patientPaymentBody(): Map<String, Any?> = mapOf(
        "amount" to "10.00",
        "paymentMethod" to "CASH",
        "paymentDate" to "2026-04-01",
    )
}
