package com.htt.billing.service.security

import com.htt.billing.common.error.ForbiddenException
import com.htt.billing.common.error.NotFoundException
import com.htt.billing.common.error.ValidationException
import com.htt.billing.repository.practice.OrganizationRepository
import com.htt.billing.repository.practice.OrganizationRepository.Organization
import com.htt.billing.repository.security.RbacRepository
import com.htt.billing.repository.security.RbacRepository.Grant
import com.htt.billing.repository.security.RbacRepository.Role
import com.htt.billing.security.Permissions
import com.htt.billing.security.ScopeTypes
import org.springframework.stereotype.Service

/**
 * The billing authorization model, replacing role-name checks for anything
 * organization-scoped. A caller holds permissions through role assignments, and
 * each assignment carries the organization it applies to.
 *
 * Every check loads the caller's grants. That is one query per check, which is
 * the right trade while the app is small — cache in the request or on the user
 * only when a profile shows it matters.
 */
@Service
class AuthorizationService(
    private val rbac: RbacRepository,
    private val organizations: OrganizationRepository,
) {

    /**
     * Throws unless the user holds [permission] at a scope that covers
     * [organizationId]. Pass null for a platform-scoped action (one with no
     * organization behind it, such as a demo reset), or an organization id for
     * anything tenant-owned.
     *
     * This is the gate to call from services and controllers. Never branch on a
     * role name.
     */
    fun require(userId: Int, permission: String, organizationId: Int? = null) {
        if (!can(userId, permission, organizationId)) {
            throw ForbiddenException(missingPermission(permission))
        }
    }

    fun can(userId: Int, permission: String, organizationId: Int? = null): Boolean =
        permits(grantsFor(userId), permission, organizationId)

    /**
     * Whether the user holds [permission] through any active grant, whatever scope
     * that grant carries.
     *
     * For the actions that are not tenant-owned. User administration is the only
     * one, and it is not scoped yet — `users` carries no practice of its own, so
     * the only link to one is a role assignment, which a freshly created account
     * doesn't have. So this answers "holds it somewhere", which is the same
     * question the ranked `client`/`staff`/`admin` column answered before it.
     *
     * Scoping the user list to the caller's practices is its own piece of work,
     * because it means deciding how an account belongs to a practice.
     */
    fun holds(userId: Int, permission: String): Boolean =
        grantsFor(userId).any { it.permission == permission }

    /**
     * The organization a new record belongs to.
     *
     * A caller may name one, and it is checked against their grants either way.
     * When they can write in exactly one — every V1 user — the id is not required
     * at all, so the browser never supplies the tenant it writes to. An account
     * that can write in several has to say which, because the choice is
     * genuinely ambiguous.
     */
    fun resolveWriteOrganization(userId: Int, permission: String, requested: Int): Int {
        if (requested > 0) {
            require(userId, permission, requested)
            return requested
        }
        val permitted = permittedOrganizationIds(userId, permission)
        if (permitted.size == 1) {
            return permitted.single()
        }
        if (permitted.isEmpty()) {
            throw ForbiddenException(missingPermission(permission))
        }
        throw ValidationException(
            "organizationId is required: this account can write in more than one practice",
        )
    }

    /**
     * The visibility half of a tenant check, for a record that has already been
     * loaded and whose owning organization is known.
     *
     * A caller who cannot *see* that organization gets "not found", because a 403
     * would confirm the record exists in another practice. The action half is a
     * plain [require], which answers 403 — they can see it, they just may not do
     * this to it. Both halves are needed on any read or write of a tenant-owned
     * row; asking only the second leaks the first.
     */
    fun requireVisible(userId: Int, viewPermission: String, organizationId: Int, missingMessage: String) {
        if (!can(userId, viewPermission, organizationId)) {
            throw NotFoundException(missingMessage)
        }
    }

    fun grantsFor(userId: Int): List<Grant> = rbac.permissionGrants(userId)

    /** The codes of the roles the user holds, at any scope, for `/api/me`. */
    fun rolesFor(userId: Int): List<String> = rbac.roleCodesFor(userId)

    /**
     * Refuses unless the actor's `USER_VIEW` reaches at least one of the target's
     * assignment scopes.
     *
     * The same shape as [requireVisible], and for the same reason — an
     * administrator of one practice gets "not found" rather than a refusal for an
     * account they hold nothing in, because a 403 would confirm the account
     * exists elsewhere. This is what confines user administration to a practice.
     */
    fun requireUserVisible(actorId: Int, targetUserId: Int, missingMessage: String) {
        val grants = grantsFor(actorId)
        val scopes = rbac.assignmentScopes(targetUserId)
        // An account with no assignment is reachable by nobody but a
        // platform-scoped administrator, who is the one who places it.
        val reachable = scopes.isEmpty() && permits(grants, Permissions.USER_VIEW, null)
        if (!reachable && !scopes.any { permits(grants, Permissions.USER_VIEW, it) }) {
            throw NotFoundException(missingMessage)
        }
    }

    /**
     * The roles the actor may hand out, which is the roles whose scope matches a
     * scope they hold `ROLE_ASSIGN` at. A practice administrator gets the
     * organization-scoped roles and no platform ones, so the picker cannot offer
     * a grant the server would refuse.
     */
    fun assignableRoles(actorId: Int): List<Role> {
        val grants = grantsFor(actorId).filter { it.permission == Permissions.ROLE_ASSIGN }
        if (grants.isEmpty()) {
            return emptyList()
        }
        // A platform-scoped grant covers every practice, so it can hand out the
        // platform roles too. An organization-scoped one covers only its own
        // practice, which is enough for the organization roles and no more.
        val anywhere = permits(grants, Permissions.ROLE_ASSIGN, null)
        val inSomePractice = grants.any { it.organizationId != null }
        return rbac.activeRoles().filter { role ->
            if (role.scopeType == ScopeTypes.PLATFORM) anywhere else anywhere || inSomePractice
        }
    }

    /**
     * The organizations the user may see with [permission], for list endpoints.
     * A platform-scoped grant means every active organization, otherwise only the
     * ones the user is assigned to — so a tenant-scoped caller can never be
     * handed another practice's row, whatever the browser asks for.
     */
    fun permittedOrganizations(userId: Int, permission: String): List<Organization> {
        val granting = grantsFor(userId).filter { it.permission == permission }
        if (granting.isEmpty()) {
            return emptyList()
        }
        if (granting.any { it.organizationId == null }) {
            return organizations.findAllActive()
        }
        return organizations.findActiveByIds(granting.mapNotNull { it.organizationId }.distinct())
    }

    /**
     * The same list as organization ids, for the `organization_id IN (:ids)`
     * filter the tenant-owned list queries use. Empty means the caller may see
     * nothing; that is the answer, not an error.
     */
    fun permittedOrganizationIds(userId: Int, permission: String): List<Int> =
        permittedOrganizations(userId, permission).map { it.id }

    companion object {

        /**
         * Shared so a caller that has to refuse before it can ask says the same
         * thing [require] would, instead of a second spelling of it.
         */
        fun missingPermission(permission: String): String = "Missing permission: $permission"

        /**
         * The whole rule, kept pure so it is unit-testable without a database.
         *
         * A permission says what may be done, a grant's scope says where. So a
         * grant matches when the permission matches and the grant is either
         * platform-scoped (applies anywhere) or scoped to exactly this
         * organization. A grant for Practice A never matches Practice B, and
         * never satisfies a platform-scoped check.
         *
         * PLATFORM_ADMIN needs no special case here — it simply holds no patient
         * or claim permission, so platform administration cannot reach clinical
         * or billing content.
         */
        fun permits(grants: List<Grant>, permission: String, organizationId: Int?): Boolean =
            grants.any {
                it.permission == permission &&
                        (it.organizationId == null || it.organizationId == organizationId)
            }
    }
}
