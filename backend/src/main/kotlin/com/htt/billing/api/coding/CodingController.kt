package com.htt.billing.api.coding

import com.htt.billing.common.Api
import com.htt.billing.identity.AuthUser
import com.htt.billing.service.coding.CodingService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Terminology lookups for the claim screen — ICD-10-CM diagnoses and
 * CPT/HCPCS procedures. These are the plan's
 * `GET /api/codes/diagnoses` and `GET /api/codes/procedures`, unversioned to
 * match the rest of the API.
 */
@RestController
@RequestMapping("/api/codes")
class CodingController(private val codes: CodingService) {

    @GetMapping("/diagnoses")
    fun searchDiagnoses(
        user: AuthUser,
        @RequestParam(name = "query", required = false) query: String?,
        @RequestParam(name = "limit", required = false) limit: Int?,
    ): ResponseEntity<Any> = Api.respond(
        HttpStatus.OK,
        mapOf("diagnoses" to codes.searchDiagnoses(query, limit)),
    )

    @GetMapping("/procedures")
    fun searchProcedures(
        user: AuthUser,
        @RequestParam(name = "query", required = false) query: String?,
        @RequestParam(name = "limit", required = false) limit: Int?,
    ): ResponseEntity<Any> = Api.respond(
        HttpStatus.OK,
        mapOf("procedures" to codes.searchProcedures(query, limit)),
    )
}
