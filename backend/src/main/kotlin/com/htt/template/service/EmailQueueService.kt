package com.htt.template.service

import com.htt.template.config.AppProperties
import com.htt.template.repository.EmailQueueRepository
import com.htt.template.repository.UserRepository
import com.htt.template.service.error.NotFoundException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.slf4j.LoggerFactory
import org.springframework.mail.MailException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Writes to the outbound mail queue. Each enqueue happens in the same
 * transaction as the token change that caused it, so a rolled-back request
 * leaves no queued email — and no request ever waits for a mail server.
 */
@Service
class EmailQueueService(
    private val users: UserRepository,
    private val queue: EmailQueueRepository,
    private val mailer: Mailer,
    private val tokens: Tokens,
    private val properties: AppProperties,
    private val transactions: TransactionTemplate,
) {

    /** Which user a password reset is for — the sentinel the callers differ on. */
    sealed interface ResetKey {

        data class ByEmail(val email: String) : ResetKey

        data class ById(val id: Int) : ResetKey
    }

    /**
     * Shared by /api/forgot-password and the admin-triggered reset route. The
     * update itself both finds the user and sets the token, so callers don't
     * need their own lookup.
     *
     * @throws NotFoundException when no user matches — self-service
     *         forgot-password ignores it, the admin route turns it into a 404.
     */
    fun queuePasswordReset(key: ResetKey) {
        val token = tokens.randomToken()
        val expiry = OffsetDateTime.now(ZoneOffset.UTC).plusHours(RESET_TOKEN_TTL_HOURS)
        transactions.executeWithoutResult {
            val email = when (key) {
                is ResetKey.ByEmail -> users.setResetTokenByEmail(key.email, token, expiry)
                is ResetKey.ById -> users.setResetTokenById(key.id, token, expiry)
            } ?: throw NotFoundException("user not found")
            val message = EmailTemplates.passwordReset(
                email,
                EmailTemplates.tokenLink(properties.frontendUrl, "reset-password", token),
            )
            queue.enqueue(message.to, message.subject, message.body)
        }
    }

    /** Shared by /api/resend-verification and the staff-triggered resend route. */
    fun queueVerificationEmail(userId: Int, email: String) {
        val token = tokens.randomToken()
        transactions.executeWithoutResult {
            users.setVerificationToken(userId, token)
            val message = EmailTemplates.verification(
                email,
                EmailTemplates.tokenLink(properties.frontendUrl, "verify", token),
            )
            queue.enqueue(message.to, message.subject, message.body)
        }
    }

    /**
     * Pick up pending emails, send them, mark sent / retry with a bounded cap.
     *
     * @return the number of jobs processed (used by tests).
     */
    fun process(take: Int): Int {
        val jobs = try {
            queue.findPending(properties.maxAttempts, take)
        } catch (failed: Exception) {
            log.error("[emailQueue] worker error: {}", failed.message)
            return 0
        }
        for (job in jobs) {
            try {
                mailer.send(job.to, job.subject, job.body)
                queue.markSent(job.id)
            } catch (failed: MailException) {
                val attempts = job.attempts + 1
                val status = if (attempts >= properties.maxAttempts) "failed" else "pending"
                // ponytail: no backoff; fixed-interval poll is the retry. Add
                // exponential backoff if a slow mailer causes stampedes.
                queue.markAttempted(job.id, attempts, failed.message, status)
            }
        }
        return jobs.size
    }

    private companion object {
        private val log = LoggerFactory.getLogger(EmailQueueService::class.java)

        private const val RESET_TOKEN_TTL_HOURS = 1L
    }
}
