package com.htt.billing.service.demo

import com.htt.billing.adjudication.PayerSimulator
import com.htt.billing.claim.ClaimStatus
import com.htt.billing.common.error.ServerErrorException
import com.htt.billing.demo.DemoDataset
import com.htt.billing.identity.DevAdminSeeder
import com.htt.billing.repository.audit.AuditRepository
import com.htt.billing.repository.claim.ClaimRepository
import com.htt.billing.repository.claim.ClaimRepository.Claim
import com.htt.billing.repository.claim.ClaimRepository.LineInput
import com.htt.billing.repository.coverage.CoverageRepository
import com.htt.billing.repository.coverage.PayerRepository
import com.htt.billing.repository.identity.UserRepository
import com.htt.billing.repository.patient.PatientRepository
import com.htt.billing.repository.payment.PaymentRepository
import com.htt.billing.repository.practice.OrganizationRepository
import com.htt.billing.repository.practice.ProviderRepository
import com.htt.billing.repository.security.RbacRepository
import com.htt.billing.security.Permissions
import com.htt.billing.service.adjudication.AdjudicationService
import com.htt.billing.service.claim.ClaimService
import com.htt.billing.service.payment.PaymentService
import com.htt.billing.service.security.AuthorizationService
import com.htt.billing.service.workflow.WorkQueueService
import java.math.BigDecimal
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Rebuilding the synthetic dataset, so the golden path and the error scenarios
 * can be exercised repeatedly from the same starting point.
 *
 * The reset is scoped to the demo practice's billing records. It deliberately
 * does not touch users, role assignments, other practices, the terminology
 * tables, or the audit trail — those are configured, not synthetic, and erasing
 * them would turn a data reset into an environment wipe. The practice row itself
 * survives for the same reason: audit events point at it.
 *
 * Claims go first. Everything a claim points at — its lines, its diagnoses, its
 * adjudications, its payments and its work items — cascades from the claim row,
 * while patients and coverages are referenced by claims and cannot be deleted
 * before them.
 */
@Service
class DemoResetService(
    private val organizations: OrganizationRepository,
    private val patients: PatientRepository,
    private val providers: ProviderRepository,
    private val coverages: CoverageRepository,
    private val payers: PayerRepository,
    private val claims: ClaimRepository,
    /** The payer's remittance, which the service records with its own reference. */
    private val payments: PaymentService,
    /** The patient's share, which is a row entered by hand somewhere in the app. */
    private val paymentRecords: PaymentRepository,
    private val adjudication: AdjudicationService,
    private val queue: WorkQueueService,
    private val users: UserRepository,
    private val rbac: RbacRepository,
    private val authorization: AuthorizationService,
    private val audit: AuditRepository,
    private val transactions: TransactionTemplate,
) {

    /** What the dataset holds once a reset has finished. */
    data class Dataset(
        val organizationId: Int,
        val providers: Int,
        val patients: Int,
        val coverages: Int,
        val claims: Int,
    )

    /**
     * The operator path — a platform user asking for a reset through the API.
     *
     * `SYSTEM_RESET` is a platform-scoped permission, so an organization-scoped
     * grant can never satisfy it, and the practice roles do not hold it at all.
     */
    fun reset(actorId: Int): Dataset {
        authorization.require(actorId, Permissions.SYSTEM_RESET)
        var dataset: Dataset? = null
        transactions.executeWithoutResult { dataset = apply(actorId) }
        return checkNotNull(dataset) { "the reset transaction returned no dataset" }
    }

    /**
     * The boot path — seed only when the demo practice is empty, so restarting
     * the backend never destroys whatever a developer has been working on.
     *
     * @return the dataset built, or null when there was already data to keep.
     */
    fun seedIfAbsent(): Dataset? {
        val existing = organizations.findByName(DemoDataset.ORGANIZATION_NAME)
        if (existing != null && patients.countInOrganization(existing.id) > 0) {
            // The data stays, but the grant is still ensured: it was added after
            // some local databases had already been seeded, and it is idempotent.
            grantDevAdminAccess(existing.id)
            return null
        }
        var seeded: Dataset? = null
        transactions.executeWithoutResult { seeded = apply(null) }
        return seeded
    }

    /**
     * The whole reset, in one transaction: a failure part way through leaves the
     * previous dataset rather than half of each.
     */
    private fun apply(actorId: Int?): Dataset {
        val organizationId = organizations.findByName(DemoDataset.ORGANIZATION_NAME)?.id
            ?: organizations.insert(
                DemoDataset.ORGANIZATION_NAME,
                DemoDataset.ORGANIZATION_NPI,
                DemoDataset.ORGANIZATION_TAX_ID,
            ).id

        claims.deleteInOrganization(organizationId)
        coverages.deleteInOrganization(organizationId)
        patients.deleteInOrganization(organizationId)
        providers.deleteInOrganization(organizationId)

        // Payers are seeded by migration, so a missing one is a broken database,
        // not bad input. The reset cannot invent one without guessing its id.
        val payerIds = DemoDataset.PATIENTS
            .flatMap { it.coverages.map { coverage -> coverage.payerCode } }
            .distinct()
            .associateWith { requirePayer(it) }

        val providerIds = DemoDataset.PROVIDERS.associate { provider ->
            provider.externalId to providers.insert(
                organizationId = organizationId,
                externalId = provider.externalId,
                firstName = provider.firstName,
                lastName = provider.lastName,
                npi = provider.npi,
                taxonomyCode = provider.taxonomyCode,
            ).id
        }

        // A claim names one coverage, and the screens treat priority 1 as the
        // primary one, so that is the coverage the seeded claims go out on.
        val primaryCoverageIds = mutableMapOf<String, Int>()
        val patientIds = DemoDataset.PATIENTS.associate { patient ->
            val patientId = patients.insert(
                organizationId = organizationId,
                externalId = patient.externalId,
                firstName = patient.firstName,
                lastName = patient.lastName,
                dateOfBirth = patient.dateOfBirth,
                sex = patient.sex,
                addressLine1 = patient.addressLine1,
                addressLine2 = null,
                city = patient.city,
                state = patient.state,
                postalCode = patient.postalCode,
                phone = patient.phone,
            ).id
            patient.coverages.forEach { coverage ->
                val inserted = coverages.insert(
                    organizationId = organizationId,
                    patientId = patientId,
                    payerId = payerIds.getValue(coverage.payerCode),
                    memberId = coverage.memberId,
                    groupNumber = coverage.groupNumber,
                    subscriberName = coverage.subscriberName
                        ?: "${patient.firstName} ${patient.lastName}",
                    relationshipToSubscriber = coverage.relationship,
                    effectiveDate = coverage.effectiveDate,
                    terminationDate = coverage.terminationDate,
                    priority = coverage.priority,
                )
                if (inserted.priority == PRIMARY_PRIORITY) {
                    primaryCoverageIds[patient.externalId] = inserted.id
                }
            }
            patient.externalId to patientId
        }

        DemoDataset.CLAIMS.forEach {
            seedClaim(organizationId, it, patientIds, providerIds, primaryCoverageIds)
        }

        // Boot seeding has no actor to attribute it to, so it is not audited; a
        // reset that someone asked for is.
        if (actorId != null) {
            audit.record(
                userId = actorId,
                organizationId = organizationId,
                action = ACTION_DEMO_RESET,
                entityType = ENTITY_ORGANIZATION,
                entityId = organizationId.toString(),
                metadataJson = """{"providers":${DemoDataset.PROVIDERS.size},""" +
                        """"patients":${DemoDataset.PATIENTS.size},""" +
                        """"coverages":${DemoDataset.COVERAGE_COUNT},""" +
                        """"claims":${DemoDataset.CLAIMS.size}}""",
            )
        }

        grantDevAdminAccess(organizationId)

        return Dataset(
            organizationId = organizationId,
            providers = DemoDataset.PROVIDERS.size,
            patients = DemoDataset.PATIENTS.size,
            coverages = DemoDataset.COVERAGE_COUNT,
            claims = DemoDataset.CLAIMS.size,
        )
    }

    /**
     * One seeded claim, taken as far as its [DemoDataset.Path] says.
     *
     * ClaimService is what writes a claim in this app, and it needs a signed-in
     * user to authorize and to author the audit trail — which a boot seed has
     * none of. So the same moves are made here from the same pieces: the state
     * machine still gates every transition, the payer still prices the lines, and
     * the money still lands in the payment tables. Only the choice of where a
     * priced claim ends up is repeated, and
     * [com.htt.billing.service.claim.ClaimService] owns that rule.
     */
    private fun seedClaim(
        organizationId: Int,
        spec: DemoDataset.DemoClaim,
        patientIds: Map<String, Int>,
        providerIds: Map<String, Int>,
        primaryCoverageIds: Map<String, Int>,
    ) {
        val coverageId = primaryCoverageIds.getValue(spec.patient)
        val coverage = coverages.findById(coverageId)
            ?: throw missing("Coverage $coverageId vanished while seeding the demo")
        val claim = claims.insert(
            organizationId = organizationId,
            patientId = patientIds.getValue(spec.patient),
            providerId = providerIds.getValue(spec.provider),
            coverageId = coverageId,
            payerId = coverage.payerId,
            serviceDate = spec.serviceDate,
        )
        claims.replaceDiagnoses(claim.id, spec.diagnoses)
        claims.replaceLines(
            claim.id,
            spec.lines.mapIndexed { index, line ->
                LineInput(
                    lineNumber = index + 1,
                    procedureCode = line.procedureCode,
                    quantity = line.quantity,
                    chargeAmount = BigDecimal(line.chargeAmount),
                )
            },
        )

        when (spec.path) {
            DemoDataset.Path.DRAFT -> Unit
            DemoDataset.Path.READY -> move(claim, ClaimStatus.READY, submittedAt = null)
            DemoDataset.Path.SUBMITTED -> submit(claim, coverage, spec)
        }
    }

    /**
     * What the payer answers, applied as the moves it implies.
     *
     * Claims go out the way a biller's claim does, through READY and then
     * SUBMITTED, because those are the moves the state machine has. After that the
     * payer either refuses the claim outright, which is a rejection and leaves
     * nothing priced, or accepts it and it is priced line by line.
     *
     * Sending a claim to the payer is the billing act the trail exists for, so it
     * is recorded here too, in the shape [ClaimService] records it in. The seed has
     * no signed-in user to attribute it to, so the event carries none rather than a
     * made-up one.
     */
    private fun submit(
        claim: Claim,
        coverage: CoverageRepository.Coverage,
        spec: DemoDataset.DemoClaim,
    ) {
        var current = move(claim, ClaimStatus.READY, submittedAt = null)
        current = move(current, ClaimStatus.SUBMITTED, submittedAt = Instant.now())

        val rejection = PayerSimulator.rejectionFor(
            coveredFrom = coverage.effectiveDate,
            coveredTo = coverage.terminationDate,
            serviceDate = claim.serviceDate,
        )
        val settled = if (rejection == null) {
            acceptAndPrice(current)
        } else {
            queue.openForRejection(claim, rejection)
            move(
                current,
                ClaimStatus.REJECTED,
                submittedAt = null,
                rejectionCode = rejection.code,
                rejectionMessage = rejection.message,
            )
        }

        audit.record(
            userId = null,
            organizationId = claim.organizationId,
            action = ClaimService.ACTION_CLAIM_SUBMITTED,
            entityType = ClaimService.ENTITY_CLAIM,
            entityId = claim.id.toString(),
            metadataJson = """{"claimNumber":"${claim.claimNumber}",""" +
                    """"status":"${settled.status}",""" +
                    """"submissionVersion":${settled.submissionVersion}}""",
        )

        // A payment on the part paid claim. It is left partial on purpose, so the
        // payment screen has a balance to work with, and it writes no audit event:
        // whether a payment settles a claim is PaymentService's rule, and the seed
        // would be guessing at it here.
        val paid = spec.patientPayment ?: return
        paymentRecords.insertPatientPayment(
            claimId = settled.id,
            patientId = settled.patientId,
            organizationId = settled.organizationId,
            amount = BigDecimal(paid),
            paymentMethod = SEEDED_PAYMENT_METHOD,
            paymentDate = spec.serviceDate.plusDays(30),
            referenceNumber = null,
        )
        move(settled, ClaimStatus.PARTIALLY_PAID, submittedAt = null)
    }

    /**
     * The payer accepted the claim, so it is priced and settled against what the
     * answer says is outstanding. A service it would not cover is follow-up work,
     * so the queue gets an item whether or not the claim was denied outright.
     */
    private fun acceptAndPrice(accepted: Claim): Claim {
        val claim = move(accepted, ClaimStatus.ACCEPTED, submittedAt = null)
        val answer = adjudication.adjudicate(claim, claims.findLines(claim.id))
        val decision = answer.adjudication
        payments.recordInsurancePayment(
            claimId = claim.id,
            organizationId = claim.organizationId,
            claimNumber = claim.claimNumber,
            submissionVersion = claim.submissionVersion,
            amount = decision.payerResponsibility,
        )
        if (answer.deniedProcedures.isNotEmpty()) {
            queue.openForDenial(claim, answer.deniedProcedures)
        }

        val outcome = when {
            decision.outcome == AdjudicationService.OUTCOME_DENIED -> ClaimStatus.DENIED
            decision.patientResponsibility.signum() > 0 -> ClaimStatus.ADJUDICATED
            else -> ClaimStatus.PAID
        }
        return move(claim, outcome, submittedAt = null)
    }

    /**
     * A status change the state machine allows, or the seed refuses to write it.
     * The claim the move produced comes back, because the next move is checked
     * against it.
     */
    private fun move(
        claim: Claim,
        to: ClaimStatus,
        submittedAt: Instant?,
        rejectionCode: String? = null,
        rejectionMessage: String? = null,
    ): Claim {
        ClaimStatus.requireMove(claim.status, to)
        return claims.updateStatus(claim.id, to, submittedAt, rejectionCode, rejectionMessage)
            ?: throw missing("Claim ${claim.id} vanished while seeding the demo")
    }

    private fun requirePayer(code: String): Int = payers.findActiveByCode(code)?.id
        ?: throw missing("Demo payer $code is missing")

    private fun missing(message: String) = ServerErrorException(
        message,
        IllegalStateException(message),
        false,
    )

    /**
     * Makes the seeded practice usable by the local login. The dev admin is a
     * platform account with no billing role, and the billing screens show nothing
     * without one — every query is scoped to the caller's grants. Granted here
     * rather than in the identity seeder so the two stay independent, and skipped
     * when the account is absent (tests, qa, production).
     */
    private fun grantDevAdminAccess(organizationId: Int) {
        val devAdminId = users.findIdByEmail(DevAdminSeeder.DEV_ADMIN_EMAIL) ?: return
        DemoDataset.DEV_ADMIN_ROLES.forEach { roleCode ->
            val role = rbac.findRoleByCode(roleCode) ?: return@forEach
            rbac.insertAssignmentIfAbsent(devAdminId, role.id, organizationId, createdBy = null)
        }
    }

    companion object {
        const val ACTION_DEMO_RESET = "DEMO_DATA_RESET"
        const val ENTITY_ORGANIZATION = "Organization"

        /** The coverage a seeded claim goes out on. */
        private const val PRIMARY_PRIORITY = 1

        /** Matches `patient_payments.payment_method`. */
        private const val SEEDED_PAYMENT_METHOD = "CARD"
    }
}
