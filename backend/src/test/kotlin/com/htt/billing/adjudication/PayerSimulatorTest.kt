package com.htt.billing.adjudication

import com.htt.billing.adjudication.PayerSimulator.LineInput
import com.htt.billing.adjudication.PayerSimulator.Rate
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The simulated payer on its own: the rejection rule, then the arithmetic. The
 * first pricing case is the plan's own worked example, which Milestone 1's
 * acceptance criteria assert.
 */
class PayerSimulatorTest {

    @Test
    fun aMemberCoveredOnTheServiceDateIsNotRejected() {
        val serviceDate = LocalDate.parse("2026-03-02")

        assertNull(PayerSimulator.rejectionFor(LocalDate.parse("2024-01-01"), null, serviceDate))
        assertNull(PayerSimulator.rejectionFor(null, null, serviceDate))
        assertNull(PayerSimulator.rejectionFor(null, LocalDate.parse("2026-03-03"), serviceDate))
    }

    @Test
    fun bothCoverageDatesAreInclusive() {
        val serviceDate = LocalDate.parse("2026-03-02")

        assertNull(PayerSimulator.rejectionFor(serviceDate, serviceDate, serviceDate))
    }

    @Test
    fun aServiceAfterTheCoverageEndedIsRejected() {
        val rejection = PayerSimulator.rejectionFor(
            LocalDate.parse("2024-01-01"),
            LocalDate.parse("2025-12-31"),
            LocalDate.parse("2026-03-02"),
        )

        assertEquals(PayerSimulator.REJECTION_MEMBER_NOT_ELIGIBLE, rejection?.code)
        // The reason names the date it turned on, so a biller does not have to
        // open the coverage to find out which end is wrong.
        assertTrue(rejection!!.message.contains("2026-03-02")) { rejection.message }
        assertTrue(rejection.message.contains("2025-12-31")) { rejection.message }
    }

    @Test
    fun aServiceBeforeTheCoverageBeganIsRejected() {
        val rejection = PayerSimulator.rejectionFor(
            LocalDate.parse("2026-04-01"),
            null,
            LocalDate.parse("2026-03-02"),
        )

        assertEquals(PayerSimulator.REJECTION_MEMBER_NOT_ELIGIBLE, rejection?.code)
    }

    @Test
    fun aMissingServiceDateIsNotThePayersObjection() {
        // Validation refuses a claim without a service date; the payer never sees it.
        assertNull(PayerSimulator.rejectionFor(LocalDate.parse("2024-01-01"), null, null))
    }

    @Test
    fun thePlanWorkedExamplePricesExactly() {
        val result = PayerSimulator.price(
            listOf(LineInput(1, "99213", BigDecimal("150.00"))),
            mapOf("99213" to Rate(BigDecimal("110.00"), BigDecimal("30.00"))),
        )

        val line = result.lines.single()
        assertTrue(line.covered)
        assertEquals(BigDecimal("110.00"), line.allowedAmount)
        assertEquals(BigDecimal("40.00"), line.adjustmentAmount)
        assertEquals(BigDecimal("80.00"), line.payerAmount)
        assertEquals(BigDecimal("30.00"), line.patientResponsibility)
        assertEquals(PayerSimulator.LINE_PAID, line.status)

        // The claim's totals follow from the lines, and reconcile by construction.
        assertEquals(BigDecimal("150.00"), result.totalCharge)
        assertEquals(BigDecimal("110.00"), result.totalAllowed)
        assertEquals(BigDecimal("40.00"), result.totalAdjustment)
        assertEquals(BigDecimal("80.00"), result.payerResponsibility)
        assertEquals(BigDecimal("30.00"), result.patientResponsibility)
    }

    @Test
    fun aProcedureWithNoRateIsNotCovered() {
        val result = PayerSimulator.price(
            listOf(LineInput(1, "00000", BigDecimal("150.00"))),
            mapOf("99213" to Rate(BigDecimal("110.00"), BigDecimal("30.00"))),
        )

        val line = result.lines.single()
        assertFalse(line.covered)
        assertEquals(BigDecimal("0.00"), line.allowedAmount)
        assertEquals(BigDecimal("0.00"), line.payerAmount)
        assertEquals(BigDecimal("0.00"), line.patientResponsibility)
        assertEquals(PayerSimulator.LINE_DENIED, line.status)
        assertFalse(result.anyCovered)
    }

    @Test
    fun aChargeBelowTheAllowedAmountIsPaidInFull() {
        // The payer never allows more than it was billed.
        val result = PayerSimulator.price(
            listOf(LineInput(1, "99213", BigDecimal("90.00"))),
            mapOf("99213" to Rate(BigDecimal("110.00"), BigDecimal("30.00"))),
        )

        val line = result.lines.single()
        assertEquals(BigDecimal("90.00"), line.allowedAmount)
        assertEquals(BigDecimal("0.00"), line.adjustmentAmount)
        assertEquals(BigDecimal("60.00"), line.payerAmount)
        assertEquals(BigDecimal("30.00"), line.patientResponsibility)
    }

    @Test
    fun aCopayLargerThanTheAllowedAmountLeavesThePayerOwingNothing() {
        val result = PayerSimulator.price(
            listOf(LineInput(1, "81002", BigDecimal("25.00"))),
            mapOf("81002" to Rate(BigDecimal("12.00"), BigDecimal("50.00"))),
        )

        val line = result.lines.single()
        assertEquals(BigDecimal("12.00"), line.allowedAmount)
        assertEquals(BigDecimal("12.00"), line.patientResponsibility)
        assertEquals(BigDecimal("0.00"), line.payerAmount)
    }

    @Test
    fun aPartlyCoveredClaimStillAdjudicatesAndReportsBothLines() {
        val result = PayerSimulator.price(
            listOf(
                LineInput(1, "99213", BigDecimal("150.00")),
                LineInput(2, "00000", BigDecimal("40.00")),
            ),
            mapOf("99213" to Rate(BigDecimal("110.00"), BigDecimal("30.00"))),
        )

        assertTrue(result.anyCovered)
        assertEquals(BigDecimal("190.00"), result.totalCharge)
        // Only the covered line writes anything off; the denied line contributes
        // nothing to allowed, adjustment or either responsibility.
        assertEquals(BigDecimal("40.00"), result.totalAdjustment)
        assertEquals(BigDecimal("110.00"), result.totalAllowed)
        assertEquals(BigDecimal("110.00"), result.payerResponsibility + result.patientResponsibility)
    }

    @Test
    fun everyFigureIsRoundedToCents() {
        val result = PayerSimulator.price(
            listOf(LineInput(1, "99213", BigDecimal("150.005"))),
            mapOf("99213" to Rate(BigDecimal("110.004"), BigDecimal("30.006"))),
        )

        val line = result.lines.single()
        assertEquals(2, line.allowedAmount.scale())
        assertEquals(2, line.adjustmentAmount.scale())
        assertEquals(2, line.payerAmount.scale())
        assertEquals(BigDecimal("150.01"), line.chargeAmount)
        assertEquals(BigDecimal("110.00"), line.allowedAmount)
        assertEquals(BigDecimal("40.01"), line.adjustmentAmount)
        assertEquals(BigDecimal("30.01"), line.patientResponsibility)
    }
}
