package com.htt.billing.service.identity

import com.htt.billing.common.error.ConflictException
import com.htt.billing.common.error.NotFoundException
import com.htt.billing.common.error.ServerErrorException
import com.htt.billing.common.error.ValidationException
import com.htt.billing.identity.EmailTemplates.Email
import com.htt.billing.identity.PasswordHasher
import com.htt.billing.identity.Validators
import com.htt.billing.repository.identity.LoginCodeRepository.Resend
import com.htt.billing.repository.identity.UserRepository
import com.htt.billing.security.Permissions
import com.htt.billing.service.identity.EmailQueueService.ResetKey
import com.htt.billing.service.security.AuthorizationService
import com.htt.billing.service.security.RoleAdminService
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Staff/admin user management.
 *
 * The permission gate itself is applied by the controller before anything here
 * runs, because the original checks the role before it parses the path id — a
 * client asking to delete `/api/users/abc` gets 403, not 400. Keeping that order
 * visible in one place beats spreading it across layers.
 *
 * What this layer adds is the tenant half, which a permission check alone cannot
 * answer. A caller has to hold `USER_VIEW` *and* have it reach the account they
 * are naming, so an administrator of one practice cannot read, verify, reset or
 * delete an account that belongs to another.
 */
@Service
class UserAdminService(
    private val users: UserRepository,
    private val queuedEmails: EmailQueueService,
    private val hasher: PasswordHasher,
    private val authorization: AuthorizationService,
    private val roles: RoleAdminService,
    private val transactions: TransactionTemplate,
) {

    /** The accounts the caller's grants reach. */
    fun listUsers(actorId: Int): List<UserRepository.ListItem> = try {
        users.list(
            authorization.permittedOrganizationIds(actorId, Permissions.USER_VIEW),
            // A platform-scoped USER_VIEW reaches every practice, and the accounts
            // that hold no practice at all.
            platformScope = authorization.can(actorId, Permissions.USER_VIEW),
        )
    } catch (failed: DataAccessException) {
        throw ServerErrorException("List Users Error", failed, false)
    }

    /**
     * Admin-only: creates a user with an admin-chosen password, already
     * verified (the admin vouches for the email) and flagged to force a
     * password change on first login.
     *
     * The role comes in the same act, because an account with no assignment
     * belongs to no practice, which would leave it invisible to the administrator
     * who just created it. Both writes share one transaction.
     */
    fun createUser(
        actorId: Int,
        email: String,
        password: String,
        roleCode: String,
        requestedOrganizationId: Int,
    ): UserRepository.UserWithRole {
        if (!Validators.isEmail(email)) {
            throw ValidationException("Invalid email address")
        }
        Validators.validatePassword(password)?.let { throw ValidationException(it) }
        val organizationId = authorization.resolveWriteOrganization(
            actorId,
            Permissions.ROLE_ASSIGN,
            requestedOrganizationId,
        )
        return try {
            var created: UserRepository.UserWithRole? = null
            transactions.executeWithoutResult {
                created = users.insertAdminCreatedUser(email, hasher.hash(password))
                roles.assign(actorId, created!!.id, roleCode, organizationId)
            }
            checkNotNull(created)
        } catch (alreadyRegistered: DuplicateKeyException) {
            throw ConflictException("Email is already registered")
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Admin Create User Error", failed, true)
        }
    }

    /** Staff can nudge a not-yet-verified user's verification email along. */
    fun resendVerification(actorId: Int, id: Int) {
        val row = try {
            visible(actorId, id)
            users.findVerificationRowById(id)
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Resend Verification Error", failed, false)
        }
        if (row == null) {
            throw NotFoundException("User not found")
        }
        if (row.emailVerified) {
            throw ValidationException("User is already verified")
        }
        try {
            queuedEmails.queueVerificationEmail(row.id, row.email)
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Resend Verification Error", failed, false)
        }
    }

    /** Admin-only: flip a user's verified flag directly, no email round-trip. */
    fun setVerification(actorId: Int, id: Int, verified: Boolean): UserRepository.VerificationRow = try {
        visible(actorId, id)
        users.updateVerification(id, verified) ?: throw NotFoundException("User not found")
    } catch (failed: DataAccessException) {
        throw ServerErrorException("Update Verification Error", failed, false)
    }

    /**
     * Admin-only: sends the same reset-password email a user would trigger
     * themselves, so an admin never has to see or set anyone's password.
     */
    fun resetPassword(actorId: Int, id: Int) {
        try {
            visible(actorId, id)
            queuedEmails.queuePasswordReset(ResetKey.ById(id))
        } catch (noSuchUser: NotFoundException) {
            throw NotFoundException("User not found")
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Admin Reset Password Error", failed, false)
        }
    }

    fun deleteUser(actorId: Int, id: Int) {
        if (id == actorId) {
            throw ValidationException("Cannot delete your own account")
        }
        try {
            visible(actorId, id)
            if (users.deleteById(id) == 0) {
                throw NotFoundException("User not found")
            }
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Delete User Error", failed, false)
        }
    }

    /**
     * The tenant half of every per-user route. Answers "not found" rather than
     * "forbidden", so an administrator cannot use these routes to discover which
     * accounts exist in a practice that is not theirs.
     */
    private fun visible(actorId: Int, targetUserId: Int) {
        authorization.requireUserVisible(actorId, targetUserId, "User not found")
    }
}
