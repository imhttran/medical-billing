package com.htt.billing.coverage.dto

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * A coverage create or update. There is no `organizationId`: a coverage belongs
 * to the same practice as its patient, and that is read from the patient row
 * rather than sent by the browser.
 */
data class CoverageBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var payerId: Int = 0,
    @field:JsonSetter(nulls = Nulls.SKIP) var memberId: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var groupNumber: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var subscriberName: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var relationshipToSubscriber: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var effectiveDate: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var terminationDate: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var priority: Int = 1,
    // Present so an update can retire a coverage; that is what makes the payer
    // simulator's inactive-coverage path reachable.
    @field:JsonSetter(nulls = Nulls.SKIP) var active: Boolean = true,
)
