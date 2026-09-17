package com.htt.billing.claim

import com.htt.billing.adjudication.AdjudicationRepository
import com.htt.billing.adjudication.AdjudicationService
import com.htt.billing.adjudication.PayerSimulator
import com.htt.billing.audit.AuditRepository
import com.htt.billing.claim.ClaimRepository.Claim
import com.htt.billing.claim.ClaimRepository.ClaimSummary
import com.htt.billing.claim.ClaimRepository.LineInput
import com.htt.billing.claim.dto.ClaimBody
import com.htt.billing.common.Inputs
import com.htt.billing.common.error.Issue
import com.htt.billing.common.error.NotFoundException
import com.htt.billing.common.error.ValidationException
import com.htt.billing.common.error.ValidationIssuesException
import com.htt.billing.payment.PaymentService
import com.htt.billing.security.AuthorizationService
import com.htt.billing.security.Permissions
import com.htt.billing.workflow.WorkQueueService
import java.math.BigDecimal
import java.time.Instant
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Claims: creating and editing the draft, validating it, and the transitions the
 * state machine allows.
 *
 * Status is never taken from a request. Each action here performs the named moves
 * the domain allows for it — and submission performs several, because the
 * simulated payer accepts, prices and answers immediately where a real one would
 * reply later. Submitting is also how a rejected claim goes back out.
 */
@Service
class ClaimService(
    private val claims: ClaimRepository,
    private val facts: ClaimFacts,
    private val authorization: AuthorizationService,
    private val adjudication: AdjudicationService,
    private val payments: PaymentService,
    private val queue: WorkQueueService,
    private val audit: AuditRepository,
    private val transactions: TransactionTemplate,
) {

    data class Detail(
        val claim: Claim,
        val diagnoses: List<ClaimRepository.Diagnosis>,
        val lines: List<ClaimRepository.Line>,
        val adjudication: AdjudicationRepository.Adjudication?,
    )

    fun list(userId: Int, patientId: Int?): List<ClaimSummary> {
        val organizationIds = authorization.permittedOrganizationIds(userId, Permissions.CLAIM_VIEW)
        if (organizationIds.isEmpty()) {
            return emptyList()
        }
        return claims.findIn(organizationIds, patientId)
    }

    fun detail(userId: Int, claimId: Int): Detail = detailOf(requireVisible(userId, claimId))

    fun create(userId: Int, body: ClaimBody): Detail {
        val organizationId = authorization.resolveWriteOrganization(
            userId,
            Permissions.CLAIM_CREATE,
            body.organizationId,
        )
        // The payer is read from the coverage, so the two can never disagree. It is
        // a snapshot: changing the coverage afterwards does not rewrite a claim
        // that was already written against the old payer.
        val coverage = facts.coverageFor(body.coverageId)
            ?: throw ValidationException("coverageId must name an existing coverage")
        requireReference(body.patientId, "patientId")
        requireReference(body.providerId, "providerId")
        facts.requireInPractice(organizationId, body.patientId, body.providerId, coverage)

        var created: Claim? = null
        try {
            transactions.executeWithoutResult {
                val claim = claims.insert(
                    organizationId = organizationId,
                    patientId = body.patientId,
                    providerId = body.providerId,
                    coverageId = coverage.id,
                    payerId = coverage.payerId,
                    serviceDate = Inputs.optionalDate(body.serviceDate, "serviceDate"),
                )
                claims.replaceDiagnoses(claim.id, cleanDiagnoses(body.diagnoses))
                claims.replaceLines(claim.id, parseLines(body.lines))
                created = claim
            }
        } catch (badReference: DataIntegrityViolationException) {
            // The foreign keys on patient, provider and coverage.
            throw ValidationException("Unknown patient or provider")
        }
        return detailOf(checkNotNull(created))
    }

    fun update(userId: Int, claimId: Int, body: ClaimBody): Detail {
        val existing = requireVisible(userId, claimId)
        authorization.require(userId, Permissions.CLAIM_EDIT, existing.organizationId)
        if (!ClaimStatus.isEditable(existing.status)) {
            throw ValidationException("A claim in ${existing.status} can no longer be edited")
        }
        val coverage = facts.coverageFor(body.coverageId)
            ?: throw ValidationException("coverageId must name an existing coverage")
        requireReference(body.patientId, "patientId")
        requireReference(body.providerId, "providerId")
        // The references come from the body here too, so an edit cannot re-point a
        // claim at another practice's rows either.
        facts.requireInPractice(existing.organizationId, body.patientId, body.providerId, coverage)

        var updated: Claim? = null
        transactions.executeWithoutResult {
            val claim = claims.updateHeader(
                id = claimId,
                patientId = body.patientId,
                providerId = body.providerId,
                coverageId = coverage.id,
                payerId = coverage.payerId,
                serviceDate = Inputs.optionalDate(body.serviceDate, "serviceDate"),
            ) ?: throw NotFoundException("Claim not found")
            claims.replaceDiagnoses(claimId, cleanDiagnoses(body.diagnoses))
            claims.replaceLines(claimId, parseLines(body.lines))
            // Editing a rejected claim is the correction itself — the move the
            // state machine calls REJECTED to CORRECTED. Without it a rejection
            // would be a dead end, since nothing else may touch a rejected claim.
            updated = if (claim.status == ClaimStatus.REJECTED) {
                claims.updateStatus(claimId, ClaimStatus.CORRECTED, submittedAt = null)
                    ?: throw NotFoundException("Claim not found")
            } else {
                claim
            }
        }
        return detailOf(checkNotNull(updated))
    }

    /**
     * Validates without changing anything, so the UI can show every problem at
     * once. The same rules gate the two transitions below.
     */
    fun validate(userId: Int, claimId: Int): List<Issue> = issuesFor(requireVisible(userId, claimId))

    fun markReady(userId: Int, claimId: Int): Detail {
        val claim = requireVisible(userId, claimId)
        authorization.require(userId, Permissions.CLAIM_EDIT, claim.organizationId)
        requireNoIssues(claim)
        ClaimStatus.requireMove(claim.status, ClaimStatus.READY)
        return detailOf(
            claims.updateStatus(claimId, ClaimStatus.READY, submittedAt = null)
                ?: throw NotFoundException("Claim not found"),
        )
    }

    /**
     * Submits the claim and takes the payer's answer.
     *
     * Two ways in: a READY claim goes out for the first time, and a rejected or
     * corrected one goes back out, which the matrix gates on CLAIM_RESUBMIT rather
     * than CLAIM_SUBMIT. The claim has to be READY first, because DRAFT to
     * SUBMITTED is not a move the state machine has — validating and submitting
     * are separate steps on purpose.
     *
     * The payer answers one of three ways. It accepts the claim and prices it, and
     * the money it says it owes is recorded with the answer, because the simulated
     * payer pays as it decides. Or it accepts and prices everything as not covered,
     * which is a denial. Or it refuses to process the claim at all, which is a
     * rejection: nothing is priced, so no adjudication and no payment are written.
     */
    fun submit(userId: Int, claimId: Int): Detail {
        val claim = requireVisible(userId, claimId)
        val resubmission =
            claim.status == ClaimStatus.REJECTED || claim.status == ClaimStatus.CORRECTED
        authorization.require(
            userId,
            if (resubmission) Permissions.CLAIM_RESUBMIT else Permissions.CLAIM_SUBMIT,
            claim.organizationId,
        )
        requireNoIssues(claim)

        var result: Claim? = null
        transactions.executeWithoutResult {
            // A rejected claim is corrected on its way back out, so the moves the
            // state machine names are taken in order.
            if (claim.status == ClaimStatus.REJECTED) {
                ClaimStatus.requireMove(ClaimStatus.REJECTED, ClaimStatus.CORRECTED)
                claims.updateStatus(claimId, ClaimStatus.CORRECTED, submittedAt = null)
                    ?: throw NotFoundException("Claim not found")
            }
            val from = if (claim.status == ClaimStatus.REJECTED) {
                ClaimStatus.CORRECTED
            } else {
                claim.status
            }
            val outgoing =
                if (resubmission) ClaimStatus.RESUBMITTED else ClaimStatus.SUBMITTED
            ClaimStatus.requireMove(from, outgoing)
            claims.updateStatus(claimId, outgoing, Instant.now())
                ?: throw NotFoundException("Claim not found")
            if (resubmission) {
                // Going back to the payer is the follow-up the queue was waiting on.
                queue.resolveForClaim(claimId, userId)
            }

            val rejection = eligibilityRejection(claim)
            result = if (rejection == null) {
                takeThePayersAnswer(claimId, outgoing, claimant = claim)
            } else {
                ClaimStatus.requireMove(outgoing, ClaimStatus.REJECTED)
                queue.openForRejection(claim, rejection)
                claims.updateStatus(
                    id = claimId,
                    status = ClaimStatus.REJECTED,
                    submittedAt = null,
                    rejectionCode = rejection.code,
                    rejectionMessage = rejection.message,
                ) ?: throw NotFoundException("Claim not found")
            }
            // Sending a claim to the payer is the billing act this trail exists
            // for, so it is recorded in the same transaction as the move — and a
            // submission the payer refused is recorded like any other.
            val settled = checkNotNull(result)
            audit.record(
                userId = userId,
                organizationId = claim.organizationId,
                action = if (resubmission) ACTION_CLAIM_RESUBMITTED else ACTION_CLAIM_SUBMITTED,
                entityType = ENTITY_CLAIM,
                entityId = claimId.toString(),
                metadataJson = """{"claimNumber":"${claim.claimNumber}",""" +
                        """"status":"${settled.status}","submissionVersion":${settled.submissionVersion}}""",
            )
        }
        return detailOf(checkNotNull(result))
    }

    /**
     * Accept, price, and settle the claim's status against what the answer says is
     * outstanding.
     *
     * A denial is where the payer allowed nothing. Otherwise the payer paid its
     * share as it answered, so the only money still owed is the patient's: a claim
     * with a patient responsibility is ADJUDICATED and awaiting it, and one without
     * is PAID because nothing is outstanding at all.
     *
     * A service the payer would not cover is follow-up work, so it goes on the
     * queue either way — a claim can be adjudicated and still need someone to look
     * at a refused line.
     */
    private fun takeThePayersAnswer(
        claimId: Int,
        outgoing: ClaimStatus,
        claimant: Claim,
    ): Claim {
        ClaimStatus.requireMove(outgoing, ClaimStatus.ACCEPTED)
        val accepted = claims.updateStatus(claimId, ClaimStatus.ACCEPTED, submittedAt = null)
            ?: throw NotFoundException("Claim not found")

        val answer = adjudication.adjudicate(accepted, claims.findLines(claimId))
        val decision = answer.adjudication
        payments.recordInsurancePayment(
            claimId = claimId,
            organizationId = accepted.organizationId,
            claimNumber = accepted.claimNumber,
            submissionVersion = accepted.submissionVersion,
            amount = decision.payerResponsibility,
        )
        if (answer.deniedProcedures.isNotEmpty()) {
            queue.openForDenial(claimant, answer.deniedProcedures)
        }

        val outcome = when {
            decision.outcome == AdjudicationService.OUTCOME_DENIED -> ClaimStatus.DENIED
            decision.patientResponsibility.signum() > 0 -> ClaimStatus.ADJUDICATED
            else -> ClaimStatus.PAID
        }
        ClaimStatus.requireMove(ClaimStatus.ACCEPTED, outcome)
        return claims.updateStatus(claimId, outcome, submittedAt = null)
            ?: throw NotFoundException("Claim not found")
    }

    /** The payer's eligibility check, which validation deliberately does not pre-empt. */
    private fun eligibilityRejection(claim: Claim): PayerSimulator.Rejection? {
        val coverage = facts.coverageFor(claim.coverageId)
        return PayerSimulator.rejectionFor(
            coveredFrom = coverage?.effectiveDate,
            coveredTo = coverage?.terminationDate,
            serviceDate = claim.serviceDate,
        )
    }

    private fun issuesFor(claim: Claim): List<Issue> = ClaimValidator.validate(
        facts.forClaim(
            claim,
            claims.findDiagnoses(claim.id).map { it.diagnosisCode },
            claims.findLines(claim.id).map {
                LineInput(it.lineNumber, it.procedureCode, it.quantity, it.chargeAmount)
            },
        ),
    )

    private fun requireNoIssues(claim: Claim) {
        val issues = issuesFor(claim)
        if (issues.isNotEmpty()) {
            throw ValidationIssuesException(issues)
        }
    }

    /**
     * Visibility first, then the action: a claim outside the caller's practices
     * answers "not found" (404) so its existence is not confirmed, while one they
     * can see but not act on answers 403.
     */
    private fun requireVisible(userId: Int, claimId: Int): Claim {
        val claim = claims.findById(claimId) ?: throw NotFoundException("Claim not found")
        authorization.requireVisible(userId, Permissions.CLAIM_VIEW, claim.organizationId, "Claim not found")
        return claim
    }

    private fun detailOf(claim: Claim): Detail = Detail(
        claim = claim,
        diagnoses = claims.findDiagnoses(claim.id),
        lines = claims.findLines(claim.id),
        adjudication = adjudication.latestForClaim(claim.id),
    )

    private fun requireReference(id: Int, field: String) {
        if (id <= 0) {
            throw ValidationException("$field is required")
        }
    }

    private fun cleanDiagnoses(codes: List<String>): List<String> =
        codes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun parseLines(rows: List<com.htt.billing.claim.dto.LineBody>): List<LineInput> =
        rows.mapIndexed { index, row ->
            LineInput(
                lineNumber = index + 1,
                procedureCode = row.procedureCode.trim(),
                quantity = row.quantity,
                chargeAmount = parseCharge(row.chargeAmount),
            )
        }

    /** Text in, BigDecimal out: a JSON number coerces as written, so 150.00 stays exact. */
    private fun parseCharge(raw: String): BigDecimal = Inputs.requiredMoney(raw, "chargeAmount")

    companion object {
        const val ACTION_CLAIM_SUBMITTED = "CLAIM_SUBMITTED"
        const val ACTION_CLAIM_RESUBMITTED = "CLAIM_RESUBMITTED"
        const val ENTITY_CLAIM = "Claim"
    }
}
