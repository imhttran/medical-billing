package com.htt.billing.adjudication

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The deterministic simulated payer.
 *
 * Pure: the caller supplies the rates, so the arithmetic is testable without a
 * database and the same claim always prices the same way. Money is BigDecimal
 * throughout and every figure is rounded to cents on the way out.
 *
 * Three rules, and no attempt to model a real contract:
 *
 * - a procedure the payer has no rate for is not covered;
 * - otherwise the payer allows the lower of its rate and the charge, and the
 *   difference is the contractual adjustment a biller writes off;
 * - the patient owes a fixed copay, capped at what the payer allows, and the
 *   payer owes the rest of the allowed amount.
 *
 * A copay rather than coinsurance, because the plan's own worked example is not
 * a percentage: 20% of 110.00 would be 22.00, not the 30.00 it states.
 */
object PayerSimulator {

    const val LINE_PAID = "PAID"
    const val LINE_DENIED = "DENIED"

    data class Rate(val allowedAmount: BigDecimal, val patientCopay: BigDecimal)

    data class LineInput(val lineNumber: Int, val procedureCode: String, val chargeAmount: BigDecimal)

    data class LineResult(
        val lineNumber: Int,
        /** What was billed, rounded to cents like everything else here. */
        val chargeAmount: BigDecimal,
        val covered: Boolean,
        val allowedAmount: BigDecimal,
        val adjustmentAmount: BigDecimal,
        val payerAmount: BigDecimal,
        val patientResponsibility: BigDecimal,
    ) {
        val status: String get() = if (covered) LINE_PAID else LINE_DENIED
    }

    data class Result(val lines: List<LineResult>) {

        // The billed total is the sum of the charges, not of the parts: a denied
        // line has no allowed amount and no adjustment, so the parts would lose it.
        val totalCharge: BigDecimal = lines.sumOf { it.chargeAmount }
        val totalAllowed: BigDecimal = lines.sumOf { it.allowedAmount }
        val totalAdjustment: BigDecimal = lines.sumOf { it.adjustmentAmount }
        val payerResponsibility: BigDecimal = lines.sumOf { it.payerAmount }
        val patientResponsibility: BigDecimal = lines.sumOf { it.patientResponsibility }

        /** Any line paid makes the claim adjudicated; none paid makes it denied. */
        val anyCovered: Boolean get() = lines.any { it.covered }
    }

    fun price(lines: List<LineInput>, rates: Map<String, Rate>): Result =
        Result(lines.map { priceLine(it, rates[it.procedureCode]) })

    private fun priceLine(line: LineInput, rate: Rate?): LineResult {
        val zero = cents(BigDecimal.ZERO)
        val charge = cents(line.chargeAmount)
        if (rate == null) {
            // Not covered: nothing is allowed and nothing is owed. The charge stays
            // on the line as what was billed.
            return LineResult(line.lineNumber, charge, false, zero, zero, zero, zero)
        }
        val allowed = cents(minOf(rate.allowedAmount, charge))
        val adjustment = cents(charge - allowed)
        val patient = cents(minOf(rate.patientCopay, allowed))
        val payer = cents(allowed - patient)
        return LineResult(line.lineNumber, charge, true, allowed, adjustment, payer, patient)
    }

    private fun minOf(first: BigDecimal, second: BigDecimal): BigDecimal =
        if (first < second) first else second

    /** Cents, so no total carries a fraction of a cent around. */
    private fun cents(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)
}
