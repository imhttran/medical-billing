package com.htt.template.service

/**
 * Ranked lowest to highest: a role satisfies a check for itself or anything
 * below it.
 */
object Roles {

    val ROLES: List<String> = listOf("client", "staff", "admin")

    fun isRole(role: String): Boolean = ROLES.contains(role)

    private fun indexOf(role: String): Int = ROLES.indexOf(role)

    fun hasRole(userRole: String, minimumRole: String): Boolean {
        val user = indexOf(userRole)
        val minimum = indexOf(minimumRole)
        return user >= 0 && minimum >= 0 && user >= minimum
    }
}
