package com.htt.template.api.dto

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * Request bodies carry real defaults: a missing field keeps its default, and an
 * explicit `null` is skipped rather than wiping it (treating null as "the field
 * was absent" is what the contract expects). Validation is what rejects bad
 * input, not decoding.
 */
data class SignupBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var email: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var password: String = "",
)
