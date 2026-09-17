package com.htt.billing.api.identity

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * A role grant. `organizationId` is optional for the same reason it is on
 * [CreateUserBody] — a caller who can assign in exactly one practice does not
 * have to name it.
 */
data class AssignRoleBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var roleCode: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var organizationId: Int = 0,
)
