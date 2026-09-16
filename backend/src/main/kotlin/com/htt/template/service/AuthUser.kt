package com.htt.template.service

/**
 * A logged-in user, as both the auth gates and the route handlers need it.
 * [passwordHash] is the stored hash, checked by /api/change-password.
 */
data class AuthUser(
    val id: Int,
    val email: String,
    val role: String,
    val emailVerified: Boolean,
    val mustChangePassword: Boolean,
    val hasProfile: Boolean,
    val passwordHash: String,
)
