package com.htt.billing.demo

import com.htt.billing.common.error.ForbiddenException
import com.htt.billing.claim.ClaimStatus
import com.htt.billing.repository.adjudication.AdjudicationRepository
import com.htt.billing.repository.claim.ClaimRepository
import com.htt.billing.security.RoleCodes
import com.htt.billing.service.claim.ClaimService
import com.htt.billing.service.demo.DemoResetService
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired

/**
 * The demo reset, in a context with no dev or demo profile — which is the
 * production shape. The service is exercised directly here; the endpoint's
 * absence is itself the assertion, and [DemoResetEndpointTest] covers the HTTP
 * path where the profile is active.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class DemoResetTest : BillingApiTest() {

    @Autowired
    private lateinit var demo: DemoResetService

    @Autowired
    private lateinit var claimRepository: ClaimRepository

    @Autowired
    private lateinit var adjudications: AdjudicationRepository

    @Autowired
    private lateinit var claimService: ClaimService

    @AfterEach
    fun removeDemoPractice() {
        clearDemoData(jdbc)
    }

    @Test
    fun resetEndpointDoesNotExistWithoutADevOrDemoProfile() {
        val platformAdmin = signIn(RoleCodes.PLATFORM_ADMIN, null)

        // The controller bean is not registered, so there is no route to reach —
        // not a route that refuses. That is the difference the plan asks for.
        assertStatus(404, env.doJson("POST", "/api/system/reset", platformAdmin.token, null))
    }

    @Test
    fun resetRebuildsTheSameDataset() {
        val platformAdminId = insertUser("platform-admin")
        assign(platformAdminId, RoleCodes.PLATFORM_ADMIN, null)

        val first = demo.reset(platformAdminId)
        val organizationId = demoOrganizationId(jdbc)
        assertNotNull(organizationId)
        assertEquals(DemoDataset.PROVIDERS.size, first.providers)
        assertEquals(DemoDataset.PATIENTS.size, countPatients(jdbc, organizationId!!))
        assertEquals(DemoDataset.COVERAGE_COUNT, countCoverages(jdbc, organizationId))
        assertEquals(DemoDataset.CLAIMS.size, countClaims(jdbc, organizationId))

        // Something the reset did not put there, which it must therefore remove.
        insertStrayPatient(jdbc, organizationId, "Stray")
        assertEquals(DemoDataset.PATIENTS.size + 1, countPatients(jdbc, organizationId))

        val second = demo.reset(platformAdminId)
        assertEquals(first.organizationId, second.organizationId)
        assertEquals(DemoDataset.PATIENTS.size, countPatients(jdbc, organizationId))
        assertEquals(DemoDataset.PROVIDERS.size, countProviders(jdbc, organizationId))
        assertEquals(DemoDataset.CLAIMS.size, countClaims(jdbc, organizationId))

        // The fixed values, not just "a patient exists".
        val patientId = demoPatientId(jdbc, organizationId)
        assertNotNull(patientId)
        assertEquals(
            DemoDataset.JANE.coverages.first().memberId,
            demoCoverageMemberId(jdbc, patientId!!),
        )
    }

    @Test
    fun theWalkthroughClaimLandsOnThePlansNumbers() {
        val platformAdminId = insertUser("platform-admin")
        assign(platformAdminId, RoleCodes.PLATFORM_ADMIN, null)
        demo.reset(platformAdminId)

        val organizationId = demoOrganizationId(jdbc)!!
        val claim = walkthroughClaim(
            claimRepository,
            organizationId,
            demoPatientId(jdbc, organizationId)!!,
        )
        val adjudication = adjudications.findLatestForClaim(claim.id)

        // The milestone's numbers, read through the app rather than recomputed. A
        // seeded claim that stopped matching them would be a demo that contradicts
        // the plan.
        assertEquals(ClaimStatus.ADJUDICATED, claim.status)
        assertNotNull(adjudication) { "the walkthrough claim was not adjudicated" }
        assertEquals("150.00", adjudication!!.totalCharge.toPlainString())
        assertEquals("110.00", adjudication.totalAllowed.toPlainString())
        assertEquals("40.00", adjudication.totalAdjustment.toPlainString())
        assertEquals("80.00", adjudication.payerResponsibility.toPlainString())
        assertEquals("30.00", adjudication.patientResponsibility.toPlainString())
    }

    @Test
    fun theSeededClaimsCoverTheStatesTheScreensShow() {
        val platformAdminId = insertUser("platform-admin")
        assign(platformAdminId, RoleCodes.PLATFORM_ADMIN, null)
        demo.reset(platformAdminId)

        val organizationId = demoOrganizationId(jdbc)!!
        val byStatus = claimsByStatus(jdbc, organizationId)

        // The drafts the operator finishes, and a ready one to send.
        assertEquals(
            DemoDataset.CLAIMS.count { it.path == DemoDataset.Path.DRAFT },
            byStatus["DRAFT"] ?: 0,
        )
        assertEquals(
            DemoDataset.CLAIMS.count { it.path == DemoDataset.Path.READY },
            byStatus["READY"] ?: 0,
        )
        // The three endings the payer can give, which is what the screens are
        // read for: nothing owed, part owed, and the two refusals.
        assertTrue((byStatus["PAID"] ?: 0) >= 1) { "no fully paid claim" }
        assertTrue((byStatus["DENIED"] ?: 0) >= 1) { "no denied claim" }
        assertEquals(1, byStatus["REJECTED"] ?: 0) { "no rejected claim" }

        // The part paid one owes its state to two payments rather than to a status
        // anyone typed, so both have to be there.
        val partPaid = claimIdWithStatus(jdbc, organizationId, "PARTIALLY_PAID")
        assertNotNull(partPaid) { "no part paid claim" }
        assertEquals(2, paymentsForClaim(jdbc, partPaid!!)) {
            "a part paid claim needs the payer's share and the patient's"
        }

        // A refusal and two uncovered services are the work the queue is for.
        assertEquals(3, openWorkItems(jdbc, organizationId))
    }

    @Test
    fun everySeededSubmissionIsInTheAuditTrail() {
        val platformAdminId = insertUser("platform-admin")
        assign(platformAdminId, RoleCodes.PLATFORM_ADMIN, null)
        demo.reset(platformAdminId)

        val organizationId = demoOrganizationId(jdbc)!!
        // Claim history is the audit trail, so a demo with fourteen claims and an
        // empty trail would be a demo of the wrong thing. Every claim the seed sent
        // to the payer is in it, and Jane's is among them carrying the payer's
        // answer.
        assertEquals(
            DemoDataset.CLAIMS.count { it.path == DemoDataset.Path.SUBMITTED },
            seededAuditEventCount(jdbc, organizationId, ClaimService.ACTION_CLAIM_SUBMITTED),
        )

        val walkthroughClaimId = walkthroughClaim(
            claimRepository,
            organizationId,
            demoPatientId(jdbc, organizationId)!!,
        ).id
        assertEquals(
            1,
            seededAuditEventCount(
                jdbc,
                organizationId,
                ClaimService.ACTION_CLAIM_SUBMITTED,
                walkthroughClaimId.toString(),
            ),
        )
    }

    @Test
    fun everySeededClaimWouldHaveSurvivedTheAppsOwnValidation() {
        val platformAdminId = insertUser("platform-admin")
        assign(platformAdminId, RoleCodes.PLATFORM_ADMIN, null)
        demo.reset(platformAdminId)

        val organizationId = demoOrganizationId(jdbc)!!
        // The app's own validation is asked through the service, which is what a
        // biller's screen asks, so the seed is held to the same rule rather than to
        // a second reading of it. A billing manager is the account that can see the
        // practice's claims.
        val billerId = insertUser("demo-biller")
        assign(billerId, RoleCodes.BILLING_MANAGER, organizationId)

        val seeded = claimRepository.findIn(listOf(organizationId), null).map { it.claim }
        assertEquals(DemoDataset.CLAIMS.size, seeded.size)

        // Anything past DRAFT is a claim the app would have validated before moving
        // it. The drafts are left out on purpose: one of them is incomplete so the
        // validation screen has something to report.
        seeded.filter { it.status != ClaimStatus.DRAFT }.forEach { claim ->
            val issues = claimService.validate(billerId, claim.id)
            assertTrue(issues.isEmpty()) {
                "${claim.claimNumber} is not a claim the app would have accepted: " +
                    issues.joinToString(", ") { it.code }
            }
        }
    }

    @Test
    fun resetClearsTheClaimsAndPaymentsUnderThePractice() {
        val platformAdminId = insertUser("platform-admin")
        assign(platformAdminId, RoleCodes.PLATFORM_ADMIN, null)
        demo.reset(platformAdminId)
        val organizationId = demoOrganizationId(jdbc)!!
        val patientId = demoPatientId(jdbc, organizationId)!!
        val seeded = countClaims(jdbc, organizationId)

        // A claim and a payment on it: the rows a developing session leaves behind.
        // The claim references the seeded patient and coverage, so a reset that does
        // not clear claims first cannot delete either of them.
        val claimId = insertStrayClaim(jdbc, organizationId, patientId)
        assertNotNull(insertStrayPatientPayment(jdbc, organizationId, patientId, claimId))
        assertEquals(seeded + 1, countClaims(jdbc, organizationId))

        demo.reset(platformAdminId)

        assertEquals(seeded, countClaims(jdbc, organizationId)) { "the dataset did not come back" }
        assertEquals(0, paymentsForClaim(jdbc, claimId)) { "a payment survived the reset" }
        assertEquals(DemoDataset.PATIENTS.size, countPatients(jdbc, organizationId))
        assertEquals(DemoDataset.COVERAGE_COUNT, countCoverages(jdbc, organizationId))
    }

    @Test
    fun resetIsAudited() {
        val platformAdminId = insertUser("platform-admin")
        assign(platformAdminId, RoleCodes.PLATFORM_ADMIN, null)

        assertEquals(0, auditCountFor(jdbc, DemoResetService.ACTION_DEMO_RESET, platformAdminId))
        demo.reset(platformAdminId)
        assertEquals(1, auditCountFor(jdbc, DemoResetService.ACTION_DEMO_RESET, platformAdminId))
    }

    @Test
    fun seedIfAbsentSeedsAnEmptyPracticeAndThenLeavesItAlone() {
        val platformAdminId = insertUser("platform-admin")
        assign(platformAdminId, RoleCodes.PLATFORM_ADMIN, null)
        // Another context (app.env=development) may have seeded already, so start
        // from a known empty practice rather than assuming one.
        clearDemoData(jdbc)

        val seeded = demo.seedIfAbsent()
        assertNotNull(seeded)

        // The restart case: data is present, so nothing is destroyed.
        val organizationId = demoOrganizationId(jdbc)!!
        val stray = insertStrayPatient(jdbc, organizationId, "Keep")
        assertNull(demo.seedIfAbsent())
        assertTrue(countPatients(jdbc, organizationId) == DemoDataset.PATIENTS.size + 1) {
            "a boot wiped existing work"
        }
        assertNotNull(stray)
    }

    @Test
    fun resetRequiresThePlatformScopedPermission() {
        val platformAdminId = insertUser("platform-admin")
        assign(platformAdminId, RoleCodes.PLATFORM_ADMIN, null)
        val practiceAdminId = insertUser("practice-admin")
        assign(practiceAdminId, RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val billerId = insertUser("biller")
        assign(billerId, RoleCodes.BILLER, practiceA.id)

        // A practice-scoped grant cannot satisfy a platform-scoped check, and
        // none of the practice roles hold SYSTEM_RESET at all.
        val before = demoOrganizationId(jdbc)?.let { countPatients(jdbc, it) } ?: 0
        assertThrows(ForbiddenException::class.java) { demo.reset(practiceAdminId) }
        assertThrows(ForbiddenException::class.java) { demo.reset(billerId) }

        // Refused means nothing changed, whatever was there beforehand.
        val after = demoOrganizationId(jdbc)?.let { countPatients(jdbc, it) } ?: 0
        assertEquals(before, after)
    }
}
