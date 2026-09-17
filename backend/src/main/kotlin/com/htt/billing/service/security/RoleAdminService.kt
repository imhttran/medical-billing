package com.htt.billing.service.security

import com.htt.billing.common.error.ConflictException
import com.htt.billing.common.error.ValidationException
import com.htt.billing.repository.audit.AuditRepository
import com.htt.billing.repository.security.RbacRepository
import com.htt.billing.repository.security.RbacRepository.Role
import com.htt.billing.security.Permissions
import com.htt.billing.security.ScopeTypes
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Granting roles to users. Every change is authorized against the assigning
 * user's `ROLE_ASSIGN` permission *at the target scope*, and audited in the same
 * transaction, so an assignment that happened always has a record of who made
 * it.
 */
@Service
class RoleAdminService(
    private val rbac: RbacRepository,
    private val authorization: AuthorizationService,
    private val audit: AuditRepository,
    private val transactions: TransactionTemplate,
) {

    data class Assignment(
        val id: Int,
        val userId: Int,
        val roleCode: String,
        val organizationId: Int?,
    )

    /**
     * Assigns [roleCode] to [targetUserId] at [organizationId], or platform-wide
     * when that is null.
     *
     * The actor must hold `ROLE_ASSIGN` at the same scope, which is what stops a
     * practice administrator from granting anything outside their own practice
     * and keeps organization-scoped grants from satisfying platform checks.
     */
    fun assign(actorId: Int, targetUserId: Int, roleCode: String, organizationId: Int?): Assignment {
        val role = rbac.findRoleByCode(roleCode)
            ?: throw ValidationException("Unknown role: $roleCode")
        requireMatchingScope(role, organizationId)
        authorization.require(actorId, Permissions.ROLE_ASSIGN, organizationId)

        var assignmentId = 0
        try {
            transactions.executeWithoutResult {
                assignmentId = rbac.insertAssignment(targetUserId, role.id, organizationId, actorId)
                // Ids and codes only, per the data-handling baseline.
                audit.record(
                    userId = actorId,
                    organizationId = organizationId,
                    action = ACTION_ROLE_ASSIGNED,
                    entityType = ENTITY_USER_ROLE_ASSIGNMENT,
                    entityId = assignmentId.toString(),
                    metadataJson = """{"roleCode":"$roleCode","targetUserId":$targetUserId}""",
                )
            }
        } catch (alreadyAssigned: DuplicateKeyException) {
            throw ConflictException("That role is already assigned at this scope")
        } catch (badReference: DataIntegrityViolationException) {
            // Foreign key on user_id or organization_id — the caller named
            // something that does not exist.
            throw ValidationException("Unknown user or organization")
        }
        return Assignment(assignmentId, targetUserId, roleCode, organizationId)
    }

    /**
     * [assign] for a request that may not name a scope. An organization role lands
     * in whichever practice the actor can assign in, so a browser never supplies
     * the tenant it writes to, and a platform role has to arrive without one.
     */
    fun assignResolvingScope(
        actorId: Int,
        targetUserId: Int,
        roleCode: String,
        requestedOrganizationId: Int,
    ): Assignment {
        val role = rbac.findRoleByCode(roleCode)
            ?: throw ValidationException("Unknown role: $roleCode")
        val scope = if (role.scopeType == ScopeTypes.ORGANIZATION) {
            authorization.resolveWriteOrganization(actorId, Permissions.ROLE_ASSIGN, requestedOrganizationId)
        } else {
            if (requestedOrganizationId > 0) {
                throw ValidationException("${role.code} is platform-scoped and takes no organization")
            }
            null
        }
        return assign(actorId, targetUserId, roleCode, scope)
    }

    /**
     * A role's scope and the assignment's scope have to agree, or the boundary
     * is meaningless — an organization-scoped role with no organization would
     * match every practice.
     */
    private fun requireMatchingScope(role: Role, organizationId: Int?) {
        val organizationScoped = role.scopeType == ScopeTypes.ORGANIZATION
        if (organizationScoped && organizationId == null) {
            throw ValidationException("${role.code} must be assigned within an organization")
        }
        if (!organizationScoped && organizationId != null) {
            throw ValidationException("${role.code} is platform-scoped and takes no organization")
        }
    }

    companion object {
        const val ACTION_ROLE_ASSIGNED = "ROLE_ASSIGNED"
        const val ENTITY_USER_ROLE_ASSIGNMENT = "UserRoleAssignment"
    }
}
