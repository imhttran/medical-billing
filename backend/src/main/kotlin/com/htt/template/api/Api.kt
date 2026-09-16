package com.htt.template.api

import com.htt.template.service.AuthUser
import com.htt.template.service.Roles
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * Response shapes and request decoding — the `msg`/`fail` helpers and the
 * lenient body decoder, both pinned by the API contract.
 *
 * Two body shapes are in the contract and they are not interchangeable: a bare
 * `{"message": …}` (used by the auth gates, the profile form and the
 * user-management routes) and `{"success": false, "message": …}` (used by
 * signup, login, password reset and admin user creation). [msg] and [fail]
 * build each.
 */
object Api {

    /**
     * Missing and unparsable bodies decode as an empty request object.
     * Route-level validation is what produces the 400s. Unknown fields are
     * ignored. The Kotlin module is what lets the body classes be Kotlin
     * classes at all — without it Jackson can't use their defaults.
     */
    private val decoder: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun msg(message: String?): Map<String, Any?> = mapOf("message" to message)

    fun fail(message: String?): Map<String, Any?> = mapOf("success" to false, "message" to message)

    fun respond(status: HttpStatus, body: Map<String, Any?>): ResponseEntity<Any> =
        ResponseEntity.status(status).body<Any>(body)

    /** Decodes a request body, falling back to an empty request object. */
    fun <T : Any> decode(raw: ByteArray?, type: Class<T>): T {
        if (raw == null || raw.isEmpty()) {
            return empty(type)
        }
        return try {
            decoder.readValue(raw, type)
        } catch (unparsable: IOException) {
            empty(type)
        } catch (unparsable: RuntimeException) {
            empty(type)
        }
    }

    /** A loosely decoded body for handlers that only need to sniff one field. */
    fun decodeNode(raw: ByteArray?): JsonNode {
        if (raw == null || raw.isEmpty()) {
            return MissingNode.getInstance()
        }
        return try {
            decoder.readTree(raw)
        } catch (unparsable: IOException) {
            MissingNode.getInstance()
        }
    }

    /**
     * Role gate for the staff/admin routes: 403s unless the user's role is
     * [minimumRole] or higher.
     */
    fun requireRole(user: AuthUser, minimumRole: String) {
        if (!Roles.hasRole(user.role, minimumRole)) {
            throw ApiRejection(HttpStatus.FORBIDDEN, msg("Insufficient permissions"))
        }
    }

    /** Path ids arrive as text, so an unparsable one is our 400, not Spring's. */
    fun parseId(raw: String): Int =
        raw.toIntOrNull() ?: throw ApiRejection(HttpStatus.BAD_REQUEST, msg("Invalid user id"))

    private fun <T : Any> empty(type: Class<T>): T = try {
        // Body classes default every field, which is what gives them the
        // no-arg constructor this needs.
        type.getDeclaredConstructor().newInstance()
    } catch (noDefaultConstructor: ReflectiveOperationException) {
        throw IllegalArgumentException(
            "${type.name} needs a no-arg constructor",
            noDefaultConstructor,
        )
    }
}
