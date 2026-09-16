package com.htt.template.service

import com.htt.template.repository.UserRepository
import com.htt.template.service.EmailQueueService.ResetKey
import com.htt.template.service.error.ConflictException
import com.htt.template.service.error.NotFoundException
import com.htt.template.service.error.ServerErrorException
import com.htt.template.service.error.ValidationException
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service

/**
 * Staff/admin user management.
 *
 * The role gate itself (staff-or-higher, admin-only) is applied by the
 * controller before anything here runs, because the original checks the role
 * before it parses the path id — a client asking to delete `/api/users/abc`
 * gets 403, not 400. Keeping that order visible in one place beats spreading it
 * across layers.
 */
@Service
class UserAdminService(
    private val users: UserRepository,
    private val queuedEmails: EmailQueueService,
    private val hasher: PasswordHasher,
) {

    /** Staff see clients and other staff; admin sees everyone. */
    fun listUsers(includeAdminAccounts: Boolean): List<UserRepository.ListItem> = try {
        users.list(includeAdminAccounts)
    } catch (failed: DataAccessException) {
        throw ServerErrorException("List Users Error", failed, false)
    }

    /**
     * Admin-only: creates a user with an admin-chosen password, already
     * verified (the admin vouches for the email) and flagged to force a
     * password change on first login.
     */
    fun createUser(email: String, password: String): UserRepository.UserWithRole {
        if (!Validators.isEmail(email)) {
            throw ValidationException("Invalid email address")
        }
        Validators.validatePassword(password)?.let { throw ValidationException(it) }
        return try {
            users.insertAdminCreatedUser(email, hasher.hash(password))
        } catch (alreadyRegistered: DuplicateKeyException) {
            throw ConflictException("Email is already registered")
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Admin Create User Error", failed, true)
        }
    }

    /** Staff can nudge a not-yet-verified user's verification email along. */
    fun resendVerification(id: Int) {
        val row = try {
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
    fun setVerification(id: Int, verified: Boolean): UserRepository.VerificationRow = try {
        users.updateVerification(id, verified) ?: throw NotFoundException("User not found")
    } catch (failed: DataAccessException) {
        throw ServerErrorException("Update Verification Error", failed, false)
    }

    /**
     * Admin-only: changes a user's role. Blocks self-demotion so an admin can't
     * lock themselves (and potentially every other admin) out of admin routes.
     */
    fun setRole(requesterId: Int, id: Int, role: String): UserRepository.RoleRow {
        if (!Roles.isRole(role)) {
            throw ValidationException("role must be one of: " + Roles.ROLES.joinToString(", "))
        }
        if (id == requesterId) {
            throw ValidationException("Cannot change your own role")
        }
        return try {
            users.updateRole(id, role) ?: throw NotFoundException("User not found")
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Update Role Error", failed, false)
        }
    }

    /**
     * Admin-only: sends the same reset-password email a user would trigger
     * themselves, so an admin never has to see or set anyone's password.
     */
    fun resetPassword(id: Int) {
        try {
            queuedEmails.queuePasswordReset(ResetKey.ById(id))
        } catch (noSuchUser: NotFoundException) {
            throw NotFoundException("User not found")
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Admin Reset Password Error", failed, false)
        }
    }

    fun deleteUser(requesterId: Int, id: Int) {
        if (id == requesterId) {
            throw ValidationException("Cannot delete your own account")
        }
        try {
            if (users.deleteById(id) == 0) {
                throw NotFoundException("User not found")
            }
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Delete User Error", failed, false)
        }
    }
}
