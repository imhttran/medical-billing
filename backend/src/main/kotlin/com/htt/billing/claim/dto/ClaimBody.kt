package com.htt.billing.claim.dto

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * A claim create or update.
 *
 * There is no `payerId`: the payer is read from the coverage, so the two cannot
 * disagree. There is no `totalCharge` either — it is summed from the lines.
 *
 * Money arrives as text and is parsed with BigDecimal. A JSON number coerces to
 * a string as written, so 150.00 stays 150.00 instead of becoming a double.
 */
data class ClaimBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var organizationId: Int = 0,
    @field:JsonSetter(nulls = Nulls.SKIP) var patientId: Int = 0,
    @field:JsonSetter(nulls = Nulls.SKIP) var providerId: Int = 0,
    @field:JsonSetter(nulls = Nulls.SKIP) var coverageId: Int = 0,
    @field:JsonSetter(nulls = Nulls.SKIP) var serviceDate: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var diagnoses: List<String> = emptyList(),
    @field:JsonSetter(nulls = Nulls.SKIP) var lines: List<LineBody> = emptyList(),
)

data class LineBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var procedureCode: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var quantity: Int = 1,
    @field:JsonSetter(nulls = Nulls.SKIP) var chargeAmount: String = "",
)
