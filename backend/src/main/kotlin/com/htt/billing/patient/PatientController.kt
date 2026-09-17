package com.htt.billing.patient

import com.htt.billing.common.Api
import com.htt.billing.identity.AuthUser
import com.htt.billing.patient.dto.PatientBody
import com.htt.billing.payment.PaymentService
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
 * Patients. No endpoint takes "which practice" from the browser — the list is
 * derived from the caller's grants, and a single patient is only readable if the
 * caller holds PATIENT_VIEW in the organization that owns it.
 *
 * Routes are unversioned to match the endpoints that already exist; the note in
 * the README's API section says why.
 */
@RestController
@RequestMapping("/api/patients")
class PatientController(private val patients: PatientService, private val payments: PaymentService) {

    @GetMapping
    fun listPatients(
        user: AuthUser,
        @RequestParam(name = "query", required = false) query: String?,
    ): ResponseEntity<Any> = Api.respond(
        HttpStatus.OK,
        mapOf("patients" to patients.list(user.id, query)),
    )

    @GetMapping("/{id}")
    fun getPatient(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> = Api.respond(
        HttpStatus.OK,
        mapOf("patient" to patients.get(user.id, Api.parseId(id, "patient id"))),
    )

    /**
     * What the patient still owes, across every claim, with the claims that make it
     * up. Computed from the adjudications and the payments, never stored.
     */
    @GetMapping("/{id}/balance")
    fun getBalance(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> = Api.respond(
        HttpStatus.OK,
        mapOf("balance" to payments.patientBalance(user.id, Api.parseId(id, "patient id"))),
    )

    @PostMapping
    fun createPatient(
        user: AuthUser,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        val created = patients.create(user.id, Api.decode(body, PatientBody::class.java))
        return Api.respond(
            HttpStatus.CREATED,
            mapOf("success" to true, "message" to "Patient created", "patient" to created),
        )
    }

    @PutMapping("/{id}")
    fun updatePatient(
        user: AuthUser,
        @PathVariable("id") id: String,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        val updated = patients.update(
            user.id,
            Api.parseId(id, "patient id"),
            Api.decode(body, PatientBody::class.java),
        )
        return Api.respond(
            HttpStatus.OK,
            mapOf("success" to true, "message" to "Patient updated", "patient" to updated),
        )
    }
}
