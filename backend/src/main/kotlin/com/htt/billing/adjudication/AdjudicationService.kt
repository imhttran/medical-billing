package com.htt.billing.adjudication

import com.htt.billing.claim.ClaimRepository
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
     * @return the payer's decision, priced line by line and recorded. The caller
     *         reads the outcome and the patient responsibility off it to decide
     *         where the claim goes next, and records the remittance that follows.
     */
    fun adjudicate(
        claim: ClaimRepository.Claim,
        lines: List<ClaimRepository.Line>,
    ): AdjudicationRepository.Adjudication {
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

        return adjudications.insert(
            claimId = claim.id,
            organizationId = claim.organizationId,
            outcome = if (result.anyCovered) OUTCOME_ADJUDICATED else OUTCOME_DENIED,
            totalCharge = result.totalCharge,
            totalAllowed = result.totalAllowed,
            totalAdjustment = result.totalAdjustment,
            payerResponsibility = result.payerResponsibility,
            patientResponsibility = result.patientResponsibility,
        )
    }

    fun latestForClaim(claimId: Int): AdjudicationRepository.Adjudication? =
        adjudications.findLatestForClaim(claimId)

    companion object {
        const val OUTCOME_ADJUDICATED = "ADJUDICATED"
        const val OUTCOME_DENIED = "DENIED"
    }
}
