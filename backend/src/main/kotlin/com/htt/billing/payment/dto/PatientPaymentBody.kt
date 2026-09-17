package com.htt.billing.payment.dto

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * A manual patient payment.
 *
 * `amount` arrives as text and is parsed with BigDecimal, the same as a claim's
 * line charge: a JSON number coerces as written, so 30.00 stays 30.00 instead of
 * becoming a double. Every field defaults to `""`, so a blank reads as absent.
 */
data class PatientPaymentBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var amount: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var paymentMethod: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var paymentDate: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var referenceNumber: String = "",
)
