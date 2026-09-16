package com.htt.template.repository

/**
 * A row of `user_profiles`. Field names match the Postgres columns; the
 * property names produce the wire format. Nullable columns are nullable
 * references.
 */
data class Profile(
    val id: Int,
    val userId: Int,
    val firstName: String,
    val lastName: String,
    val address: String,
    val address2: String?,
    val state: String,
    val zip: String,
    val country: String,
    val phone: String,
    val communicationPreference: String,
    val linkedin: String?,
    val github: String?,
    val altEmail: String?,
)
