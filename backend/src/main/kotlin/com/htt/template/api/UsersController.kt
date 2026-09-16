package com.htt.template.api

import com.htt.template.api.dto.CreateUserBody
import com.htt.template.api.dto.PatchRoleBody
import com.htt.template.service.AuthUser
import com.htt.template.service.Roles
import com.htt.template.service.UserAdminService
import com.htt.template.service.error.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Staff/admin user management. The role gate runs before the path id is parsed,
 * because that is the order the original checked them in.
 */
@RestController
@RequestMapping("/api/users")
class UsersController(private val admin: UserAdminService) {

    @GetMapping
    fun listUsers(user: AuthUser): ResponseEntity<Any> {
        Api.requireRole(user, "staff")
        return Api.respond(
            HttpStatus.OK,
            mapOf("users" to admin.listUsers(Roles.hasRole(user.role, "admin"))),
        )
    }

    @PostMapping
    fun createUser(
        user: AuthUser,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        Api.requireRole(user, "admin")
        val request = Api.decode(body, CreateUserBody::class.java)
        val created = admin.createUser(request.email, request.password)
        return Api.respond(
            HttpStatus.CREATED,
            mapOf(
                "success" to true,
                "message" to "User created successfully!",
                "user" to mapOf(
                    "id" to created.id,
                    "email" to created.email,
                    "role" to created.role,
                    "emailVerified" to created.emailVerified,
                ),
            ),
        )
    }

    @DeleteMapping("/{id}")
    fun deleteUser(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> {
        Api.requireRole(user, "admin")
        val userId = Api.parseId(id)
        try {
            admin.deleteUser(user.id, userId)
        } catch (rejected: ValidationException) {
            return Api.respond(HttpStatus.BAD_REQUEST, Api.msg(rejected.message))
        }
        return Api.respond(HttpStatus.OK, mapOf("success" to true, "message" to "User deleted"))
    }

    @PatchMapping("/{id}/verification")
    fun patchVerification(
        user: AuthUser,
        @PathVariable("id") id: String,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        Api.requireRole(user, "admin")
        val userId = Api.parseId(id)
        // Decoded loosely: a present-but-non-boolean value (string, number)
        // reads as not-a-boolean.
        val emailVerified = Api.decodeNode(body).path("emailVerified")
        if (!emailVerified.isBoolean) {
            return Api.respond(HttpStatus.BAD_REQUEST, Api.msg("emailVerified must be a boolean"))
        }
        val updated = admin.setVerification(userId, emailVerified.booleanValue())
        return Api.respond(
            HttpStatus.OK,
            mapOf(
                "success" to true,
                "message" to if (updated.emailVerified) "User marked as verified" else "User marked as unverified",
                "user" to mapOf(
                    "id" to updated.id,
                    "email" to updated.email,
                    "emailVerified" to updated.emailVerified,
                ),
            ),
        )
    }

    @PatchMapping("/{id}/role")
    fun patchRole(
        user: AuthUser,
        @PathVariable("id") id: String,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        Api.requireRole(user, "admin")
        val userId = Api.parseId(id)
        val request = Api.decode(body, PatchRoleBody::class.java)
        val updated = try {
            admin.setRole(user.id, userId, request.role)
        } catch (rejected: ValidationException) {
            return Api.respond(HttpStatus.BAD_REQUEST, Api.msg(rejected.message))
        }
        return Api.respond(
            HttpStatus.OK,
            mapOf(
                "success" to true,
                "message" to "User role updated",
                "user" to mapOf(
                    "id" to updated.id,
                    "email" to updated.email,
                    "role" to updated.role,
                ),
            ),
        )
    }

    @PostMapping("/{id}/resend-verification")
    fun resendVerification(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> {
        Api.requireRole(user, "staff")
        val userId = Api.parseId(id)
        try {
            admin.resendVerification(userId)
        } catch (rejected: ValidationException) {
            return Api.respond(HttpStatus.BAD_REQUEST, Api.msg(rejected.message))
        }
        return Api.respond(
            HttpStatus.OK,
            mapOf(
                "success" to true,
                "message" to "Verification email sent",
            ),
        )
    }

    @PostMapping("/{id}/reset-password")
    fun resetPassword(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> {
        Api.requireRole(user, "admin")
        admin.resetPassword(Api.parseId(id))
        return Api.respond(
            HttpStatus.OK,
            mapOf(
                "success" to true,
                "message" to "Password reset email sent",
            ),
        )
    }
}
