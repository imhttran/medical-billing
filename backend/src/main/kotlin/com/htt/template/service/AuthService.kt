package com.htt.template.service

import com.htt.template.config.AppProperties
import com.htt.template.repository.DeviceRepository
import com.htt.template.repository.EmailQueueRepository
import com.htt.template.repository.LoginCodeRepository
import com.htt.template.repository.UserRepository
import com.htt.template.service.EmailQueueService.ResetKey
import com.htt.template.service.error.ConflictException
import com.htt.template.service.error.ForbiddenException
import com.htt.template.service.error.NotFoundException
import com.htt.template.service.error.ServerErrorException
import com.htt.template.service.error.TooManyRequestsException
import com.htt.template.service.error.UnauthenticatedException
import com.htt.template.service.error.ValidationException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/** Signup, verification, password reset, login (with 2FA), and self-service password change. */
@Service
class AuthService(
    private val users: UserRepository,
    private val devices: DeviceRepository,
    private val loginCodes: LoginCodeRepository,
    private val emailQueue: EmailQueueRepository,
    private val queuedEmails: EmailQueueService,
    private val hasher: PasswordHasher,
    private val tokens: Tokens,
    private val jwt: JwtService,
    private val properties: AppProperties,
    private val transactions: TransactionTemplate,
) {

    /** Both shapes a successful login can take. */
    sealed interface LoginResult {

        data class Authenticated(val email: String, val token: String) : LoginResult

        /** A 2FA code was emailed; the real token comes from /api/login/verify. */
        data class TwoFactorRequired(val pendingToken: String) : LoginResult
    }

    data class PasswordReset(val email: String, val token: String)

    /**
     * Token verification, user lookup and the email-verification gate — what
     * declaring an [AuthUser] parameter requires. The onboarding gates are
     * applied later, by the resolver, because they depend on the route being
     * called.
     */
    fun authenticate(token: String): AuthUser {
        val email = jwt.verify(token) ?: throw ForbiddenException("Invalid or expired token")
        val account = try {
            users.findAccountByEmail(email) ?: throw NotFoundException("User not found")
        } catch (failed: DataAccessException) {
            // The user lookup sits in the same try/catch as the JWT check, so
            // any failure here reads as a bad token.
            throw ForbiddenException("Invalid or expired token")
        }
        if (properties.emailVerificationRequired && !account.emailVerified) {
            throw ForbiddenException("Please verify your email")
        }
        return AuthUser(
            account.id,
            account.email,
            account.role,
            account.emailVerified,
            account.mustChangePassword,
            account.hasProfile,
            account.password,
        )
    }

    fun signup(email: String, password: String) {
        if (!Validators.isEmail(email)) {
            throw ValidationException("Invalid email address")
        }
        Validators.validatePassword(password)?.let { throw ValidationException(it) }

        // Atomic: user + welcome-email row + verification-email row together, so
        // a failed email never leaves an orphaned account and a rolled-back
        // signup leaves no queued email. The send itself is deferred to the
        // worker — signup is never blocked on mail delivery.
        val verificationToken = tokens.randomToken()
        val passwordHash = hasher.hash(password)
        try {
            transactions.executeWithoutResult {
                users.insertUser(email, passwordHash, verificationToken)
                enqueue(EmailTemplates.welcome(email))
                enqueue(
                    EmailTemplates.verification(
                        email,
                        EmailTemplates.tokenLink(properties.frontendUrl, "verify", verificationToken),
                    ),
                )
            }
        } catch (alreadyRegistered: DuplicateKeyException) {
            // Keep the user-facing message generic so the API doesn't reveal
            // whether an email is already registered (prevents user
            // enumeration). The real reason is logged server-side.
            log.warn("[signup] rejected: email already registered (email={})", email)
            throw ConflictException("Unable to sign up. Please try again later.")
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Signup Error", failed, true)
        }
    }

    fun verify(token: String) {
        try {
            if (token.isEmpty()) {
                throw ValidationException("Missing verification token")
            }
            val id = users.findIdByVerificationToken(token)
                ?: throw ValidationException("Invalid or expired verification link")
            users.markEmailVerified(id)
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Verify Error", failed, true)
        }
    }

    fun resendVerification(email: String) {
        if (!Validators.isEmail(email)) {
            throw ValidationException("Invalid email address")
        }
        val row = try {
            users.findIdAndVerifiedByEmail(email)
        } catch (failed: DataAccessException) {
            null
        }
        if (row != null && !row.emailVerified) {
            try {
                queuedEmails.queueVerificationEmail(row.id, email)
            } catch (failed: DataAccessException) {
                throw ServerErrorException("Resend Verification Error", failed, true)
            }
        }
        // Same response whether or not the account exists or is verified, so
        // this endpoint can't be used to enumerate registered emails.
    }

    fun forgotPassword(email: String) {
        if (!Validators.isEmail(email)) {
            throw ValidationException("Invalid email address")
        }
        try {
            queuedEmails.queuePasswordReset(ResetKey.ByEmail(email))
        } catch (noSuchUser: NotFoundException) {
            // Fall through to the generic response.
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Forgot Password Error", failed, true)
        }
    }

    fun resetPassword(token: String, password: String): PasswordReset {
        if (token.isEmpty()) {
            throw ValidationException("Missing reset token")
        }
        Validators.validatePassword(password)?.let { throw ValidationException(it) }
        val row = try {
            users.findResetRowByToken(token)
        } catch (failed: DataAccessException) {
            // Any failure to find a usable unexpired token reads the same.
            null
        }
        if (row == null || row.resetTokenExpiry == null || row.resetTokenExpiry.toInstant().isBefore(Instant.now())) {
            throw ValidationException("Invalid or expired reset link")
        }
        try {
            users.applyPasswordReset(row.id, hasher.hash(password))
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Reset Password Error", failed, true)
        }
        return PasswordReset(row.email, jwt.issue(row.email))
    }

    fun login(email: String, password: String, deviceId: String): LoginResult {
        val row = try {
            users.findLoginRowByEmail(email)
        } catch (failed: DataAccessException) {
            // Bad credentials and lookup failures read the same.
            null
        }
        if (row == null || !hasher.verify(password, row.password)) {
            throw UnauthenticatedException("Invalid email or password")
        }
        if (properties.emailVerificationRequired && !row.emailVerified) {
            throw ForbiddenException("Please verify your email before logging in.")
        }
        if (deviceId.isNotEmpty() && isTrustedDevice(row.id, deviceId)) {
            return LoginResult.Authenticated(email, jwt.issue(email))
        }

        // New device — require 2FA: queue an emailed code and hand back a
        // pending token. The real JWT is only issued by /api/login/verify.
        val pending = tokens.randomToken()
        val code = tokens.randomCode()
        try {
            loginCodes.insert(row.id, pending, code)
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Login Error", failed, false)
        }
        sendLoginCode(email, code)
        return LoginResult.TwoFactorRequired(pending)
    }

    fun verifyLogin(token: String, code: String, deviceId: String): LoginResult {
        val row = try {
            loginCodes.findByToken(token) ?: throw ValidationException("Invalid or expired code")
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Verify Login Error", failed, false)
        }
        // Lock the code after a handful of failed tries so a 4-digit code can't
        // be brute-forced within its 10-minute window.
        if (
            row.used ||
            Instant.now().isAfter(row.expiresAt.toInstant()) ||
            row.attempts >= MAX_CODE_ATTEMPTS
        ) {
            throw ValidationException("Invalid or expired code")
        }
        if (!constantTimeEquals(row.code, code)) {
            try {
                loginCodes.incrementAttempts(token)
            } catch (ignored: DataAccessException) {
                // A failed attempt counter is not worth failing the request over.
            }
            throw ValidationException("Invalid or expired code")
        }
        try {
            loginCodes.markUsed(token)
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Verify Login Error", failed, false)
        }
        if (deviceId.isNotEmpty()) {
            try {
                devices.trust(row.userId, deviceId)
            } catch (ignored: DataAccessException) {
                // Registering the device is best effort.
            }
        }
        return LoginResult.Authenticated(row.email, jwt.issue(row.email))
    }

    fun resendLoginCode(token: String) {
        val row = try {
            loginCodes.findResendByToken(token)
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Resend Code Error", failed, false)
        }
        if (row == null || row.used || Instant.now().isAfter(row.expiresAt.toInstant())) {
            throw ValidationException("Invalid or expired code")
        }
        if (row.resends >= MAX_CODE_RESENDS) {
            throw TooManyRequestsException("Too many resend attempts")
        }
        val code = tokens.randomCode()
        try {
            loginCodes.refreshCode(token, code)
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Resend Code Error", failed, false)
        }
        sendLoginCode(row.email, code)
    }

    /**
     * Authenticated self-service password change. Used both for the general
     * "change my password" case and to clear the forced-change flag an
     * admin-created account starts with.
     */
    fun changePassword(userId: Int, storedHash: String, currentPassword: String, newPassword: String) {
        if (!hasher.verify(currentPassword, storedHash)) {
            throw UnauthenticatedException("Current password is incorrect")
        }
        Validators.validatePassword(newPassword)?.let { throw ValidationException(it) }
        try {
            users.updatePassword(userId, hasher.hash(newPassword))
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Change Password Error", failed, false)
        }
    }

    private fun isTrustedDevice(userId: Int, deviceId: String): Boolean = try {
        devices.isTrusted(userId, deviceId)
    } catch (failed: DataAccessException) {
        false
    }

    /** Emails the 2FA code through the shared queue; the worker delivers it. */
    private fun sendLoginCode(email: String, code: String) {
        try {
            enqueue(EmailTemplates.loginCode(email, code))
        } catch (ignored: DataAccessException) {
            // The pending login still works; the code is logged by the mailer in dev.
        }
    }

    private fun enqueue(message: EmailTemplates.Email) {
        emailQueue.enqueue(message.to, message.subject, message.body)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AuthService::class.java)

        /** Wrong guesses on a 4-digit code before the pending login is locked. */
        private const val MAX_CODE_ATTEMPTS = 5

        /** Resends allowed per pending login. */
        private const val MAX_CODE_RESENDS = 3

        private fun constantTimeEquals(expected: String, submitted: String): Boolean =
            MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.UTF_8),
                submitted.toByteArray(StandardCharsets.UTF_8),
            )
    }
}
