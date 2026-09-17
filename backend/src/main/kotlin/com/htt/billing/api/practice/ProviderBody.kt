package com.htt.billing.api.practice

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * A provider create. `user_id` is not settable: linking a practitioner to a
 * login is not needed by anything in V1, and the column stays NULL until it is.
 */
data class ProviderBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var organizationId: Int = 0,
    @field:JsonSetter(nulls = Nulls.SKIP) var firstName: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var lastName: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var npi: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var taxonomyCode: String = "",
)
