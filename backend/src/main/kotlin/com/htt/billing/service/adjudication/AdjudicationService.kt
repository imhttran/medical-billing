package com.htt.billing.service.adjudication

import com.htt.billing.adjudication.PayerSimulator
import com.htt.billing.repository.adjudication.AdjudicationRepository
import com.htt.billing.repository.adjudication.FeeScheduleRepository
import com.htt.billing.repository.claim.ClaimRepository
import org.springframework.stereotype.Service

/**
 * Runs a submitted claim past the simulated payer: prices every line, writes the
 * result onto the lines, and records one adjudication for the submission.
 *
 * The caller owns the transaction and the claim's status — this decides the
 * outcome and reports which status it implies.
 */
@Service
class AdjudicationService(
    private val feeSchedule: FeeScheduleRepository,
    private val adjudications: AdjudicationRepository,
    private val claims: ClaimRepository,
) {

    /**
     * The payer's answer: its decision, and the services it would not cover.
     *
     * The adjudication is the record of what it said about money. The refused
     * services are not in that record — a line's status holds them — but they are
     * what the follow-up is about, so they come back with the answer rather than
     * being read again by whoever opens the work item.
     */
    data class Answer(
        val adjudication: AdjudicationRepository.Adjudication,
        val deniedProcedures: List<String>,
    )

    /**
     * @return the payer's answer, priced line by line and recorded. The caller
     *         decides where the claim goes next, records the remittance that
     *         follows it, and opens whatever follow-up it leaves behind.
     */
    fun adjudicate(
        claim: ClaimRepository.Claim,
        lines: List<ClaimRepository.Line>,
    ): Answer {
        val rates = feeSchedule.ratesFor(claim.payerId)
        val result = PayerSimulator.price(
            lines.map { PayerSimulator.LineInput(it.lineNumber, it.procedureCode, it.chargeAmount) },
            rates,
        )

        val pricedByLineNumber = result.lines.associateBy { it.lineNumber }
        lines.forEach { line ->
            val priced = pricedByLineNumber.getValue(line.lineNumber)
            claims.priceLine(
                line.id,
                ClaimRepository.PricedLine(
                    allowedAmount = priced.allowedAmount,
                    adjustmentAmount = priced.adjustmentAmount,
                    payerAmount = priced.payerAmount,
                    patientResponsibility = priced.patientResponsibility,
                    status = priced.status,
                ),
            )
        }

        return Answer(
            adjudication = adjudications.insert(
                claimId = claim.id,
                organizationId = claim.organizationId,
                outcome = if (result.anyCovered) OUTCOME_ADJUDICATED else OUTCOME_DENIED,
                totalCharge = result.totalCharge,
                totalAllowed = result.totalAllowed,
                totalAdjustment = result.totalAdjustment,
                payerResponsibility = result.payerResponsibility,
                patientResponsibility = result.patientResponsibility,
            ),
            deniedProcedures = result.deniedProcedures,
        )
    }

    fun latestForClaim(claimId: Int): AdjudicationRepository.Adjudication? =
        adjudications.findLatestForClaim(claimId)

    companion object {
        const val OUTCOME_ADJUDICATED = "ADJUDICATED"
        const val OUTCOME_DENIED = "DENIED"
    }
}
