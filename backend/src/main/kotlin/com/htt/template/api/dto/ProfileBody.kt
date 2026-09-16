package com.htt.template.api.dto

import com.htt.template.service.ProfileCommand
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * The registration form. Required fields default to empty strings; the optional
 * ones stay null when absent.
 */
data class ProfileBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var firstName: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var lastName: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var address: String = "",
    var address2: String? = null,
    @field:JsonSetter(nulls = Nulls.SKIP) var state: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var zip: String = "",
    /** Blank falls back to US. */
    var country: String? = null,
    @field:JsonSetter(nulls = Nulls.SKIP) var phone: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var communicationPreference: String = "",
    var linkedin: String? = null,
    var github: String? = null,
    var altEmail: String? = null,
) {

    fun toCommand(): ProfileCommand = ProfileCommand(
        firstName,
        lastName,
        address,
        address2,
        state,
        zip,
        country,
        phone,
        communicationPreference,
        linkedin,
        github,
        altEmail,
    )
}
