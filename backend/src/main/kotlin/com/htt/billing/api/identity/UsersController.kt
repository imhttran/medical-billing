package com.htt.billing.api.identity

import com.htt.billing.common.Api
import com.htt.billing.common.ApiRejection
import com.htt.billing.common.error.ValidationException
import com.htt.billing.identity.AuthUser
import com.htt.billing.security.Permissions
import com.htt.billing.service.identity.UserAdminService
import com.htt.billing.service.security.AuthorizationService
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
 * Staff/admin user management. The permission gate runs before the path id is
 * parsed, because that is the order the original checked them in.
 */
@RestController
@RequestMapping("/api/users")
class UsersController(
    private val admin: UserAdminService,
    private val authorization: AuthorizationService,
) {

    @GetMapping
    fun listUsers(user: AuthUser): ResponseEntity<Any> {
        require(user, Permissions.USER_VIEW)
        return Api.respond(
            HttpStatus.OK,
            mapOf("users" to admin.listUsers()),
        )
    }

    @PostMapping
    fun createUser(
        user: AuthUser,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        require(user, Permissions.USER_CREATE)
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
                    "emailVerified" to created.emailVerified,
                ),
            ),
        )
    }

    @DeleteMapping("/{id}")
    fun deleteUser(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> {
        require(user, Permissions.USER_DISABLE)
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
        require(user, Permissions.USER_EDIT)
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

    @PostMapping("/{id}/resend-verification")
    fun resendVerification(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> {
        require(user, Permissions.USER_EDIT)
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
        require(user, Permissions.USER_RESET_PASSWORD)
        admin.resetPassword(Api.parseId(id))
        return Api.respond(
            HttpStatus.OK,
            mapOf(
                "success" to true,
                "message" to "Password reset email sent",
            ),
        )
    }

    /**
     * The same refusal [AuthorizationService.require] would give, spelled here
     * because these checks answer before the path id is parsed.
     */
    private fun require(user: AuthUser, permission: String) {
        if (!authorization.holds(user.id, permission)) {
            throw ApiRejection(
                HttpStatus.FORBIDDEN,
                Api.msg(AuthorizationService.missingPermission(permission)),
            )
        }
    }
}
