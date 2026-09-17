package com.htt.billing.payment

import com.htt.billing.claim.ClaimRepository
import com.htt.billing.claim.ClaimStatus
import com.htt.billing.common.Inputs
import com.htt.billing.common.error.NotFoundException
import com.htt.billing.common.error.ValidationException
import com.htt.billing.patient.PatientRepository
import com.htt.billing.payment.PaymentRepository.InsurancePayment
import com.htt.billing.payment.PaymentRepository.PatientPayment
import com.htt.billing.payment.dto.PatientPaymentBody
import com.htt.billing.security.AuthorizationService
import com.htt.billing.security.Permissions
import java.math.BigDecimal
import java.time.LocalDate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Money against claims: what the payer remitted, what the patient paid, and what
 * is still owed.
 *
 * Nothing here stores a balance. Every figure comes from the adjudication and the
 * payment rows through [Balances], so a payment cannot leave a total behind that
 * disagrees with it.
 *
 * Visibility and permission are answered separately, as everywhere else: a claim
 * in another practice is "not found", one the caller may see but not be paid on is
 * a plain 403.
 */
@Service
class PaymentService(
    private val payments: PaymentRepository,
    private val claims: ClaimRepository,
    private val patients: PatientRepository,
    private val authorization: AuthorizationService,
    private val transactions: TransactionTemplate,
) {

    /** One claim's money: what it asks of the patient, what came in, what is left. */
    data class Summary(
        val insurancePayments: List<InsurancePayment>,
        val patientPayments: List<PatientPayment>,
        val patientResponsibility: BigDecimal,
        val patientPaid: BigDecimal,
        val balance: BigDecimal,
    )

    data class ClaimBalance(
        val claimId: Int,
        val claimNumber: String,
        val patientResponsibility: BigDecimal,
        val patientPaid: BigDecimal,
        val balance: BigDecimal,
    )

    data class PatientBalance(val patientId: Int, val balance: BigDecimal, val claims: List<ClaimBalance>)

    fun summary(userId: Int, claimId: Int): Summary {
        val claim = requireVisibleClaim(userId, claimId)
        authorization.require(userId, Permissions.PAYMENT_VIEW, claim.organizationId)
        return summaryOf(claimId)
    }

    /**
     * Records a hand-entered patient payment and moves the claim on: a claim that
     * still owes something is PARTIALLY_PAID, one that owes nothing is PAID.
     *
     * A payment is refused rather than reconciled when it is larger than the
     * balance, so the arithmetic never has to represent a credit.
     */
    fun recordPatientPayment(userId: Int, claimId: Int, body: PatientPaymentBody): PatientPayment {
        val claim = requireVisibleClaim(userId, claimId)
        authorization.require(userId, Permissions.PAYMENT_RECORD, claim.organizationId)

        val owed = payments.owedOn(claimId)
        val paid = payments.patientPaidOn(claimId)
        val balance = Balances.outstanding(owed, paid)
        if (balance.signum() == 0) {
            throw ValidationException("Nothing is owed on this claim")
        }
        val amount = Inputs.requiredMoney(body.amount, "amount")
        if (amount.signum() <= 0) {
            throw ValidationException("amount must be greater than zero")
        }
        if (amount > balance) {
            throw ValidationException("A payment of $amount is more than the $balance still owed")
        }

        var recorded: PatientPayment? = null
        transactions.executeWithoutResult {
            recorded = payments.insertPatientPayment(
                claimId = claimId,
                patientId = claim.patientId,
                organizationId = claim.organizationId,
                amount = amount,
                paymentMethod = requireMethod(body.paymentMethod),
                paymentDate = Inputs.requiredDate(body.paymentDate, "paymentDate"),
                referenceNumber = Inputs.optionalText(body.referenceNumber),
            )

            val settled = Balances.isSettled(owed, paid + amount)
            val next = if (settled) ClaimStatus.PAID else ClaimStatus.PARTIALLY_PAID
            // Staying partially paid is not a move, so only a change is checked
            // against the state machine.
            if (next != claim.status) {
                ClaimStatus.requireMove(claim.status, next)
                claims.updateStatus(claimId, next, submittedAt = null)
                    ?: throw NotFoundException("Claim not found")
            }
        }
        return checkNotNull(recorded)
    }

    /**
     * The payer's remittance, recorded when it adjudicates. There is nothing to
     * enter by hand: the simulated payer answers immediately, so its answer and its
     * payment are the same event, and the reference traces one to the other.
     */
    fun recordInsurancePayment(
        claimId: Int,
        organizationId: Int,
        claimNumber: String,
        submissionVersion: Int,
        amount: BigDecimal,
    ) {
        if (amount.signum() <= 0) {
            return
        }
        payments.insertInsurancePayment(
            claimId = claimId,
            organizationId = organizationId,
            amount = amount,
            paymentDate = LocalDate.now(),
            referenceNumber = "$REFERENCE_PREFIX-$claimNumber-$submissionVersion",
        )
    }

    /** What the patient owes across every claim they have, newest first. */
    fun patientBalance(userId: Int, patientId: Int): PatientBalance {
        val patient = patients.findById(patientId) ?: throw NotFoundException("Patient not found")
        authorization.requireVisible(userId, Permissions.PATIENT_VIEW, patient.organizationId, "Patient not found")
        authorization.require(userId, Permissions.PAYMENT_VIEW, patient.organizationId)

        val rows = payments.owedByClaimForPatient(patient.id).map {
            val balance = Balances.outstanding(it.owed, it.paid)
            ClaimBalance(it.claimId, it.claimNumber, it.owed, it.paid, balance)
        }
        return PatientBalance(
            patientId = patient.id,
            balance = rows.fold(BigDecimal.ZERO) { total, row -> total + row.balance },
            claims = rows,
        )
    }

    private fun summaryOf(claimId: Int): Summary {
        val owed = payments.owedOn(claimId)
        val paid = payments.patientPaidOn(claimId)
        return Summary(
            insurancePayments = payments.findInsuranceForClaim(claimId),
            patientPayments = payments.findPatientForClaim(claimId),
            patientResponsibility = owed,
            patientPaid = paid,
            balance = Balances.outstanding(owed, paid),
        )
    }

    private fun requireVisibleClaim(userId: Int, claimId: Int): ClaimRepository.Claim {
        val claim = claims.findById(claimId) ?: throw NotFoundException("Claim not found")
        authorization.requireVisible(userId, Permissions.CLAIM_VIEW, claim.organizationId, "Claim not found")
        return claim
    }

    private fun requireMethod(raw: String): String {
        val method = Inputs.requiredText(raw, "paymentMethod").uppercase()
        if (method !in METHODS) {
            throw ValidationException("paymentMethod must be one of ${METHODS.joinToString(", ")}")
        }
        return method
    }

    companion object {

        /** Matches the CHECK constraint on `patient_payments.payment_method`. */
        val METHODS = setOf("CASH", "CHECK", "CARD", "TRANSFER", "OTHER")

        const val REFERENCE_PREFIX = "SIM"
    }
}
