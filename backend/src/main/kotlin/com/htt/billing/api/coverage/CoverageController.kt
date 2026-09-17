package com.htt.billing.api.coverage

import com.htt.billing.common.Api
import com.htt.billing.identity.AuthUser
import com.htt.billing.repository.coverage.CoverageRepository.Coverage
import com.htt.billing.service.coverage.CoverageService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Coverage routes. The create and list paths hang off the patient because that
 * is where the practice comes from; the update is addressed by coverage id.
 */
@RestController
class CoverageController(private val coverages: CoverageService) {

    @GetMapping("/api/patients/{patientId}/coverages")
    fun listCoverages(
        user: AuthUser,
        @PathVariable("patientId") patientId: String,
    ): ResponseEntity<Any> = Api.respond(
        HttpStatus.OK,
        mapOf("coverages" to coverages.list(user.id, Api.parseId(patientId, "patient id"))),
    )

    @PostMapping("/api/patients/{patientId}/coverages")
    fun createCoverage(
        user: AuthUser,
        @PathVariable("patientId") patientId: String,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        val created = coverages.create(
            user.id,
            Api.parseId(patientId, "patient id"),
            Api.decode(body, CoverageBody::class.java),
        )
        return Api.respond(
            HttpStatus.CREATED,
            mapOf("success" to true, "message" to "Coverage created", "coverage" to created),
        )
    }

    @PutMapping("/api/coverages/{id}")
    fun updateCoverage(
        user: AuthUser,
        @PathVariable("id") id: String,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        val updated = coverages.update(
            user.id,
            Api.parseId(id, "coverage id"),
            Api.decode(body, CoverageBody::class.java),
        )
        return Api.respond(
            HttpStatus.OK,
            mapOf("success" to true, "message" to "Coverage updated", "coverage" to updated),
        )
    }
}
