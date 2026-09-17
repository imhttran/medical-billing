package com.htt.billing.audit

import com.htt.billing.audit.AuditRepository.Event

import com.htt.billing.security.AuthorizationService
import com.htt.billing.security.Permissions
import org.springframework.stereotype.Service

/**
 * Reading the audit trail, which is the other half of keeping one.
 *
 * Who sees what follows the same rule as every other tenant-owned read: the
 * caller's `AUDIT_VIEW` grants decide the practices, and a named practice is
 * checked against them rather than trusted. Platform-scoped events — the ones
 * that belong to no practice — are shown only to a caller whose `AUDIT_VIEW` is
 * itself platform-scoped, because a practice has no business reading platform
 * operations.
 */
@Service
class AuditService(
    private val audit: AuditRepository,
    private val authorization: AuthorizationService,
) {

    fun history(
        userId: Int,
        organizationId: Int?,
        action: String?,
        limit: Int?,
    ): List<Event> {
        // Asking for a practice the caller cannot audit is a refusal, not silently
        // their own history — the same answer a named tenant gets everywhere else,
        // and it is answered before the derived list so a caller with no permission
        // at all still gets the 403 they named.
        organizationId?.let { authorization.require(userId, Permissions.AUDIT_VIEW, it) }

        val grants = authorization.grantsFor(userId).filter { it.permission == Permissions.AUDIT_VIEW }
        if (grants.isEmpty()) {
            return emptyList()
        }
        val platformWide = grants.any { it.organizationId == null }
        val organizationIds = authorization.permittedOrganizationIds(userId, Permissions.AUDIT_VIEW)
        return audit.find(
            organizationIds = organizationIds,
            includePlatformEvents = platformWide,
            organizationId = organizationId,
            action = action?.takeIf { it.isNotBlank() },
            limit = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
        )
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 500
    }
}
