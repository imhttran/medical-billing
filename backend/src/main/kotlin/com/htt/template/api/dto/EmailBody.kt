package com.htt.template.api.dto

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/** An email-only body: signup, resend-verification, forgot-password. */
data class EmailBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var email: String = "",
)
