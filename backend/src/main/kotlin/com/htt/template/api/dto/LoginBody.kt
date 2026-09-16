package com.htt.template.api.dto

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

data class LoginBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var email: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var password: String = "",
    /** Empty when the client doesn't send one — then 2FA always applies. */
    @field:JsonSetter(nulls = Nulls.SKIP) var deviceId: String = "",
)
