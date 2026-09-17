package com.htt.billing.payment

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The money arithmetic of a balance, on its own so it is testable without a
 * database and the same figure is reached the same way wherever it is asked for.
 *
 * A balance is never stored. It is what was owed minus what has been paid, and
 * this is the only place that subtraction happens.
 */
object Balances {

    /**
     * What is still owed.
     *
     * Never negative: a payment history that exceeds what was adjudicated reads as
     * settled rather than as a credit. The simulator cannot produce one, because a
     * payment larger than the balance is refused where it is recorded, but the
     * arithmetic should not depend on that having worked.
     */
    fun outstanding(owed: BigDecimal, paid: BigDecimal): BigDecimal =
        (owed - paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)

    /** Nothing is being asked of anybody: no money is owed, or all of it is in. */
    fun isSettled(owed: BigDecimal, paid: BigDecimal): Boolean =
        outstanding(owed, paid).signum() == 0
}
