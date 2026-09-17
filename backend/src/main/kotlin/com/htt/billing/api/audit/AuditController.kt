package com.htt.billing.api.audit

import com.htt.billing.common.Api
import com.htt.billing.identity.AuthUser
import com.htt.billing.service.audit.AuditService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The audit trail. Read-only by design: there is no endpoint that writes or
 * erases one, because a trail someone can edit is not evidence of anything.
 */
@RestController
@RequestMapping("/api/audit-events")
class AuditController(private val audit: AuditService) {

    @GetMapping
    fun history(
        user: AuthUser,
        @RequestParam(name = "organizationId", required = false) organizationId: Int?,
        @RequestParam(name = "action", required = false) action: String?,
        @RequestParam(name = "limit", required = false) limit: Int?,
    ): ResponseEntity<Any> = Api.respond(
        HttpStatus.OK,
        mapOf("auditEvents" to audit.history(user.id, organizationId, action, limit)),
    )
}
