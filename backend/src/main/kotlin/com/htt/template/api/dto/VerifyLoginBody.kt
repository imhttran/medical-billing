package com.htt.template.api.dto

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

data class VerifyLoginBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var token: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var code: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var deviceId: String = "",
)
