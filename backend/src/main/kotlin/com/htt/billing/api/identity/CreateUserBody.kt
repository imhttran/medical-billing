package com.htt.billing.api.identity

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * Admin-created user: the admin picks the first password, and the role, because
 * an account with no assignment belongs to no practice and would be invisible to
 * the administrator who just made it.
 *
 * `organizationId` is optional. A caller who holds `ROLE_ASSIGN` in exactly one
 * practice does not have to name it, so the browser never sends the tenant it
 * writes to.
 */
data class CreateUserBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var email: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var password: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var roleCode: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var organizationId: Int = 0,
)
