package com.htt.billing.api.practice

import com.htt.billing.common.Api
import com.htt.billing.identity.AuthUser
import com.htt.billing.repository.practice.OrganizationRepository.Organization
import com.htt.billing.security.Permissions
import com.htt.billing.service.security.AuthorizationService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Organization reads. There is no "which organization am I acting as" parameter
 * and no client-supplied organization id — the list is derived from the caller's
 * own grants, so one practice can never see another's row.
 */
@RestController
@RequestMapping("/api/organizations")
class OrganizationsController(private val authorization: AuthorizationService) {

    @GetMapping
    fun listOrganizations(user: AuthUser): ResponseEntity<Any> {
        val permitted = authorization.permittedOrganizations(user.id, Permissions.ORGANIZATION_VIEW)
        return Api.respond(
            HttpStatus.OK,
            mapOf(
                "organizations" to permitted.map {
                    mapOf("id" to it.id, "name" to it.name)
                },
            ),
        )
    }
}
