package com.htt.template.api.dto

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

data class ChangePasswordBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var currentPassword: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var newPassword: String = "",
)
