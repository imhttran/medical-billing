package com.htt.billing.api.identity

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

data class PatchRoleBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var role: String = "",
)
