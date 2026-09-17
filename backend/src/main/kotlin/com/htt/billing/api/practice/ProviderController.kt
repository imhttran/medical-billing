package com.htt.billing.api.practice

import com.htt.billing.common.Api
import com.htt.billing.identity.AuthUser
import com.htt.billing.repository.practice.ProviderRepository.Provider
import com.htt.billing.service.practice.ProviderService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/providers")
class ProviderController(private val providers: ProviderService) {

    @GetMapping
    fun listProviders(user: AuthUser): ResponseEntity<Any> = Api.respond(
        HttpStatus.OK,
        mapOf("providers" to providers.list(user.id)),
    )

    @GetMapping("/{id}")
    fun getProvider(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> = Api.respond(
        HttpStatus.OK,
        mapOf("provider" to providers.get(user.id, Api.parseId(id, "provider id"))),
    )

    @PostMapping
    fun createProvider(
        user: AuthUser,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        val created = providers.create(user.id, Api.decode(body, ProviderBody::class.java))
        return Api.respond(
            HttpStatus.CREATED,
            mapOf("success" to true, "message" to "Provider created", "provider" to created),
        )
    }
}
