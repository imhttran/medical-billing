package com.htt.template.api

import jakarta.servlet.http.HttpServletRequest

/** Reads the JWT out of `Authorization: Bearer <token>`. */
internal object BearerToken {

    /** The second space-separated part, or empty when there isn't one. */
    fun from(request: HttpServletRequest): String {
        val header = request.getHeader("Authorization") ?: return ""
        val parts = header.split(" ")
        return if (parts.size > 1) parts[1] else ""
    }
}
