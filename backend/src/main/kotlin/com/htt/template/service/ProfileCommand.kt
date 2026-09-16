package com.htt.template.service

/**
 * The registration form as it arrived, before validation and trimming. Blank
 * optionals are stored as NULL.
 */
data class ProfileCommand(
    val firstName: String,
    val lastName: String,
    val address: String,
    val address2: String?,
    val state: String,
    val zip: String,
    val country: String?,
    val phone: String,
    val communicationPreference: String,
    val linkedin: String?,
    val github: String?,
    val altEmail: String?,
)
