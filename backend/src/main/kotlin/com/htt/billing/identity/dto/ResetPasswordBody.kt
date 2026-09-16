package com.htt.billing.identity.dto

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

data class ResetPasswordBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var token: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var password: String = "",
)
