package com.htt.template.repository

import java.time.OffsetDateTime
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Pending 2FA logins (`login_codes`), keyed by the token handed to the client. */
@Repository
class LoginCodeRepository(private val jdbc: JdbcClient) {

    data class Code(
        val userId: Int,
        val code: String,
        val expiresAt: OffsetDateTime,
        val used: Boolean,
        val attempts: Int,
        val email: String,
    )

    data class Resend(
        val resends: Int,
        val expiresAt: OffsetDateTime,
        val used: Boolean,
        val email: String,
    )

    fun insert(userId: Int, token: String, code: String) {
        jdbc.sql(
            """
            INSERT INTO login_codes (user_id, token, code, expires_at)
            VALUES (:userId, :token, :code, now() + interval '10 minutes')
            """,
        )
            .param("userId", userId)
            .param("token", token)
            .param("code", code)
            .update()
    }

    fun findByToken(token: String): Code? = jdbc
        .sql(
            """
            SELECT lc.user_id AS "userId", lc.code, lc.expires_at AS "expiresAt",
                   lc.used, lc.attempts, u.email
            FROM login_codes lc
            JOIN users u ON u.id = lc.user_id
            WHERE lc.token = :token
            """,
        )
        .param("token", token)
        .query(Code::class.java)
        .optional()
        .orElse(null)

    fun findResendByToken(token: String): Resend? = jdbc
        .sql(
            """
            SELECT lc.resends, lc.expires_at AS "expiresAt", lc.used, u.email
            FROM login_codes lc
            JOIN users u ON u.id = lc.user_id
            WHERE lc.token = :token
            """,
        )
        .param("token", token)
        .query(Resend::class.java)
        .optional()
        .orElse(null)

    /** Counts a wrong guess so a 4-digit code can't be brute-forced in its window. */
    fun incrementAttempts(token: String) {
        jdbc.sql("UPDATE login_codes SET attempts = attempts + 1 WHERE token = :token")
            .param("token", token)
            .update()
    }

    fun markUsed(token: String) {
        jdbc.sql("UPDATE login_codes SET used = true WHERE token = :token")
            .param("token", token)
            .update()
    }

    fun refreshCode(token: String, code: String) {
        jdbc.sql(
            """
            UPDATE login_codes
            SET code = :code, resends = resends + 1, expires_at = now() + interval '10 minutes'
            WHERE token = :token
            """,
        )
            .param("code", code)
            .param("token", token)
            .update()
    }
}
