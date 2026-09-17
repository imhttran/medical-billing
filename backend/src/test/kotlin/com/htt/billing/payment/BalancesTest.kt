package com.htt.billing.payment

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The balance arithmetic on its own. A balance is never stored, so this is the
 * only definition of one.
 */
class BalancesTest {

    @Test
    fun balanceIsWhatIsOwedMinusWhatIsPaid() {
        assertEquals(BigDecimal("30.00"), Balances.outstanding(BigDecimal("30.00"), BigDecimal.ZERO))
        assertEquals(BigDecimal("20.00"), Balances.outstanding(BigDecimal("30.00"), BigDecimal("10.00")))
        assertEquals(BigDecimal("0.00"), Balances.outstanding(BigDecimal("30.00"), BigDecimal("30.00")))
    }

    @Test
    fun morePaidThanOwedReadsAsSettledRatherThanAsACredit() {
        val over = Balances.outstanding(BigDecimal("30.00"), BigDecimal("40.00"))

        assertEquals(BigDecimal("0.00"), over)
        assertTrue(over.signum() >= 0) { "a negative balance would be a credit, which V1 does not model" }
    }

    @Test
    fun nothingOwedIsSettled() {
        assertTrue(Balances.isSettled(BigDecimal.ZERO, BigDecimal.ZERO))
        assertTrue(Balances.isSettled(BigDecimal("30.00"), BigDecimal("30.00")))
        assertFalse(Balances.isSettled(BigDecimal("30.00"), BigDecimal("29.99")))
    }

    @Test
    fun theBalanceIsAlwaysCents() {
        // Half up on the cent, the same rounding the charges and allowed amounts use.
        val balance = Balances.outstanding(BigDecimal("30.005"), BigDecimal.ZERO)

        assertEquals(2, balance.scale())
        assertEquals(BigDecimal("30.01"), balance)
        assertEquals(BigDecimal("30.00"), Balances.outstanding(BigDecimal("30.004"), BigDecimal.ZERO))
    }
}
