package com.htt.billing.api.patient

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * A patient create or update. [organizationId] is required on create and ignored
 * on update — a patient never moves between practices.
 */
data class PatientBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var organizationId: Int = 0,
    @field:JsonSetter(nulls = Nulls.SKIP) var externalId: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var firstName: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var lastName: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var dateOfBirth: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var sex: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var addressLine1: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var addressLine2: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var city: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var state: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var postalCode: String = "",
    @field:JsonSetter(nulls = Nulls.SKIP) var phone: String = "",
)
