package com.htt.billing.api.identity

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/** Admin-created user: the admin picks the first password. */
data class CreateUserBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var email: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var password: String = "",
)
