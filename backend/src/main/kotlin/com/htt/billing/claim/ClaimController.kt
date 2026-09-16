package com.htt.billing.claim

import com.htt.billing.claim.dto.ClaimBody
import com.htt.billing.common.Api
import com.htt.billing.identity.AuthUser
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Claims. There is no endpoint that sets a status: the two transitions this
 * exposes are named actions the domain allows, and everything else about a claim
 * goes through create and update while it is still editable.
 */
@RestController
@RequestMapping("/api/claims")
class ClaimController(private val claims: ClaimService) {

    @GetMapping
    fun listClaims(
        user: AuthUser,
        @RequestParam(name = "patientId", required = false) patientId: Int?,
    ): ResponseEntity<Any> = Api.respond(
        HttpStatus.OK,
        mapOf(
            "claims" to claims.list(user.id, patientId).map {
                mapOf("claim" to it.claim, "totalCharge" to it.totalCharge)
            },
        ),
    )

    @GetMapping("/{id}")
    fun getClaim(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> =
        Api.respond(HttpStatus.OK, detail(claims.detail(user.id, Api.parseId(id, "claim id"))))

    @PostMapping
    fun createClaim(
        user: AuthUser,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        val created = claims.create(user.id, Api.decode(body, ClaimBody::class.java))
        return Api.respond(
            HttpStatus.CREATED,
            detail(created) + mapOf("success" to true, "message" to "Claim created"),
        )
    }

    @PutMapping("/{id}")
    fun updateClaim(
        user: AuthUser,
        @PathVariable("id") id: String,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        val updated = claims.update(
            user.id,
            Api.parseId(id, "claim id"),
            Api.decode(body, ClaimBody::class.java),
        )
        return Api.respond(
            HttpStatus.OK,
            detail(updated) + mapOf("success" to true, "message" to "Claim saved"),
        )
    }

    /** Reports every problem rather than refusing, so a form can list them. */
    @PostMapping("/{id}/validate")
    fun validateClaim(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> {
        val issues = claims.validate(user.id, Api.parseId(id, "claim id"))
        return Api.respond(
            HttpStatus.OK,
            mapOf(
                "valid" to issues.isEmpty(),
                "issues" to issues.map { mapOf("code" to it.code, "message" to it.message) },
            ),
        )
    }

    @PostMapping("/{id}/ready")
    fun markReady(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> {
        val ready = claims.markReady(user.id, Api.parseId(id, "claim id"))
        return Api.respond(
            HttpStatus.OK,
            detail(ready) + mapOf("success" to true, "message" to "Claim marked ready"),
        )
    }

    @PostMapping("/{id}/submit")
    fun submitClaim(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> {
        val submitted = claims.submit(user.id, Api.parseId(id, "claim id"))
        return Api.respond(
            HttpStatus.OK,
            detail(submitted) + mapOf("success" to true, "message" to "Claim submitted"),
        )
    }

    /** Every mutation answers with the whole claim, so a caller never has to refetch. */
    private fun detail(detail: ClaimService.Detail): Map<String, Any?> = mapOf(
        "claim" to detail.claim,
        "diagnoses" to detail.diagnoses,
        "lines" to detail.lines,
        "adjudication" to detail.adjudication,
    )
}
