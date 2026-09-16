package com.htt.template.api

import com.htt.template.api.dto.ProfileBody
import com.htt.template.service.AuthUser
import com.htt.template.service.ProfileService
import com.htt.template.service.error.ConflictException
import com.htt.template.service.error.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/profile")
class ProfileController(private val profiles: ProfileService) {

    @GetMapping
    fun getProfile(user: AuthUser): ResponseEntity<Any> =
        // A missing profile is a 200 with null, not a 404 — the absence is the
        // gate, not an error.
        Api.respond(HttpStatus.OK, mapOf("profile" to profiles.getProfile(user.id)))

    @PostMapping
    fun saveProfile(
        user: AuthUser,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        val request = Api.decode(body, ProfileBody::class.java)
        val saved = try {
            profiles.saveProfile(user.id, request.toCommand())
        } catch (rejected: ValidationException) {
            // This route's 400s are the bare-message shape, not the
            // success-false one, so the shared defaults don't apply.
            return Api.respond(HttpStatus.BAD_REQUEST, Api.msg(rejected.message))
        } catch (rejected: ConflictException) {
            return Api.respond(HttpStatus.BAD_REQUEST, Api.msg(rejected.message))
        }
        return Api.respond(
            HttpStatus.CREATED,
            mapOf(
                "success" to true,
                "message" to "Profile saved!",
                "profile" to saved,
            ),
        )
    }
}
