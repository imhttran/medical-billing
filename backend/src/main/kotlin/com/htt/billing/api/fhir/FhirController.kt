package com.htt.billing.api.fhir

import com.htt.billing.common.Api
import com.htt.billing.common.error.ValidationException
import com.htt.billing.identity.AuthUser
import com.htt.billing.service.fhir.FhirExportService
import com.htt.billing.service.fhir.FhirImportService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * FHIR is import/export rather than the normal UI API: the browser talks to
 * `/api/claims` and friends, and an external system exchanges resources here.
 *
 * Exports answer with `application/fhir+json` — the media type a FHIR client
 * expects — and the import takes a Bundle in that same form.
 */
@RestController
@RequestMapping("/api/integrations/fhir")
class FhirController(
    private val imports: FhirImportService,
    private val exports: FhirExportService,
) {

    /**
     * A Bundle of Patients, Practitioners, Coverages and Claims.
     *
     * The practice is named rather than assumed — an import has no single row to
     * derive it from — and the name is checked against the caller's grants, so the
     * browser still cannot point an import at a practice the caller cannot write.
     */
    @PostMapping("/import", consumes = ["application/fhir+json", MediaType.APPLICATION_JSON_VALUE])
    fun importBundle(
        user: AuthUser,
        @RequestParam(name = "organizationId", required = false) organizationId: Int?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        val summary = imports.importBundle(
            user.id,
            organizationId ?: 0,
            body ?: throw ValidationException("A FHIR Bundle is required"),
        )
        return Api.respond(
            HttpStatus.OK,
            mapOf(
                "success" to true,
                "message" to "Bundle imported",
                "organizationId" to summary.organizationId,
                "imported" to summary.imported,
                "skipped" to summary.skipped,
            ),
        )
    }

    @GetMapping("/claims/{id}", produces = [FHIR_JSON])
    fun exportClaim(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<String> =
        fhirResponse(exports.claim(user.id, Api.parseId(id, "claim id")))

    @GetMapping("/eob/{id}", produces = [FHIR_JSON])
    fun exportExplanationOfBenefit(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<String> =
        fhirResponse(exports.explanationOfBenefit(user.id, Api.parseId(id, "claim id")))

    @GetMapping("/claim-response/{id}", produces = [FHIR_JSON])
    fun exportClaimResponse(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<String> =
        fhirResponse(exports.claimResponse(user.id, Api.parseId(id, "claim id")))

    /** The resource itself, not wrapped: a FHIR client parses the body as one. */
    private fun fhirResponse(body: String): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.OK)
            .contentType(MediaType.parseMediaType(FHIR_JSON))
            .body(body)

    private companion object {
        /** No charset parameter: FHIR's media type is defined without one. */
        const val FHIR_JSON = "application/fhir+json"
    }
}
