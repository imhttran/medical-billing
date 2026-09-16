package com.htt.billing.coverage

import com.htt.billing.common.Api
import com.htt.billing.identity.AuthUser
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The payer list, so the coverage editor can offer a picker. Payers are shared
 * reference data, so any signed-in user may read them; there is no
 * create/update endpoint because V1 configures payers by migration.
 */
@RestController
class PayerController(private val payers: PayerRepository) {

    @GetMapping("/api/payers")
    fun listPayers(user: AuthUser): ResponseEntity<Any> = Api.respond(
        HttpStatus.OK,
        mapOf("payers" to payers.findAllActive()),
    )
}
