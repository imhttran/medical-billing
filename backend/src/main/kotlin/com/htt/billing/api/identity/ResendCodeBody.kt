package com.htt.billing.api.identity

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/** The pending-login token, for resending a 2FA code. */
data class ResendCodeBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var token: String = "",
)
