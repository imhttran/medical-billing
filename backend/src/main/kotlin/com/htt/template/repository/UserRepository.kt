package com.htt.template.repository

import java.time.Instant
import java.time.OffsetDateTime
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Every `users` query in the app. Raw SQL with [JdbcClient] — column aliases
 * spell out the camelCase shape the API returns.
 */
@Repository
class UserRepository(private val jdbc: JdbcClient) {

    /** A logged-in user: everything the auth gates and /api/change-password need. */
    data class Account(
        val id: Int,
        val email: String,
        val role: String,
        val emailVerified: Boolean,
        val mustChangePassword: Boolean,
        val password: String,
        val hasProfile: Boolean,
    )

    data class LoginRow(val id: Int, val password: String, val emailVerified: Boolean)

    data class IdVerified(val id: Int, val emailVerified: Boolean)

    data class ResetRow(
        val id: Int,
        val email: String,
        val resetTokenExpiry: OffsetDateTime?,
    )

    data class VerificationRow(
        val id: Int,
        val email: String,
        val emailVerified: Boolean,
    )

    data class UserWithRole(
        val id: Int,
        val email: String,
        val role: String,
        val emailVerified: Boolean,
    )

    data class RoleRow(val id: Int, val email: String, val role: String)

    data class ListItem(
        val id: Int,
        val email: String,
        val role: String,
        val emailVerified: Boolean,
        val createdAt: Instant,
    )

    fun findAccountByEmail(email: String): Account? = jdbc
        .sql(
            """
            SELECT id, email, role,
                   email_verified       AS "emailVerified",
                   must_change_password AS "mustChangePassword",
                   password,
                   EXISTS (SELECT 1 FROM user_profiles WHERE user_id = users.id) AS "hasProfile"
            FROM users WHERE email = :email
            """,
        )
        .param("email", email)
        .query(Account::class.java)
        .optional()
        .orElse(null)

    fun findLoginRowByEmail(email: String): LoginRow? = jdbc
        .sql(
            """
            SELECT id, password, email_verified AS "emailVerified"
            FROM users WHERE email = :email
            """,
        )
        .param("email", email)
        .query(LoginRow::class.java)
        .optional()
        .orElse(null)

    fun findIdAndVerifiedByEmail(email: String): IdVerified? = jdbc
        .sql(
            """
            SELECT id, email_verified AS "emailVerified"
            FROM users WHERE email = :email
            """,
        )
        .param("email", email)
        .query(IdVerified::class.java)
        .optional()
        .orElse(null)

    fun findIdByEmail(email: String): Int? = jdbc
        .sql("SELECT id FROM users WHERE email = :email")
        .param("email", email)
        .query(Int::class.javaObjectType)
        .optional()
        .orElse(null)

    fun findIdByVerificationToken(token: String): Int? = jdbc
        .sql("SELECT id FROM users WHERE verification_token = :token")
        .param("token", token)
        .query(Int::class.javaObjectType)
        .optional()
        .orElse(null)

    fun findResetRowByToken(token: String): ResetRow? = jdbc
        .sql(
            """
            SELECT id, email, reset_token_expiry AS "resetTokenExpiry"
            FROM users WHERE reset_token = :token
            """,
        )
        .param("token", token)
        .query(ResetRow::class.java)
        .optional()
        .orElse(null)

    fun findVerificationRowById(id: Int): VerificationRow? = jdbc
        .sql(
            """
            SELECT id, email, email_verified AS "emailVerified"
            FROM users WHERE id = :id
            """,
        )
        .param("id", id)
        .query(VerificationRow::class.java)
        .optional()
        .orElse(null)

    /** Signup: unverified, carrying the verification token the email links to. */
    fun insertUser(email: String, passwordHash: String, verificationToken: String): Int = jdbc
        .sql(
            """
            INSERT INTO users (email, password, email_verified, verification_token)
            VALUES (:email, :password, false, :token)
            RETURNING id
            """,
        )
        .param("email", email)
        .param("password", passwordHash)
        .param("token", verificationToken)
        .query(Int::class.javaObjectType)
        .single()

    /** Dev seed: keeps whatever is already there rather than overwriting it. */
    fun insertUserIfAbsent(
        email: String,
        passwordHash: String,
        role: String,
        emailVerified: Boolean,
    ): Int? = jdbc
        .sql(
            """
            INSERT INTO users (email, password, role, email_verified)
            VALUES (:email, :password, :role, :verified)
            ON CONFLICT (email) DO NOTHING
            RETURNING id
            """,
        )
        .param("email", email)
        .param("password", passwordHash)
        .param("role", role)
        .param("verified", emailVerified)
        .query(Int::class.javaObjectType)
        .optional()
        .orElse(null)

    /**
     * Admin-created accounts arrive already verified (the admin vouches for the
     * email) and flagged to force a password change on first login.
     */
    fun insertAdminCreatedUser(email: String, passwordHash: String): UserWithRole = jdbc
        .sql(
            """
            INSERT INTO users (email, password, email_verified, must_change_password)
            VALUES (:email, :password, true, true)
            RETURNING id, email, role, email_verified AS "emailVerified"
            """,
        )
        .param("email", email)
        .param("password", passwordHash)
        .query(UserWithRole::class.java)
        .single()

    fun markEmailVerified(id: Int) {
        jdbc.sql(
            "UPDATE users SET email_verified = true, verification_token = NULL WHERE id = :id",
        )
            .param("id", id)
            .update()
    }

    fun updatePassword(id: Int, passwordHash: String) {
        jdbc.sql(
            """
            UPDATE users
            SET password = :password, must_change_password = false
            WHERE id = :id
            """,
        )
            .param("password", passwordHash)
            .param("id", id)
            .update()
    }

    fun applyPasswordReset(id: Int, passwordHash: String) {
        jdbc.sql(
            """
            UPDATE users
            SET password = :password, reset_token = NULL, reset_token_expiry = NULL,
                must_change_password = false
            WHERE id = :id
            """,
        )
            .param("password", passwordHash)
            .param("id", id)
            .update()
    }

    fun setVerificationToken(id: Int, token: String) {
        jdbc.sql("UPDATE users SET verification_token = :token WHERE id = :id")
            .param("token", token)
            .param("id", id)
            .update()
    }

    /** Returns the email the reset token was set on, or null when nothing matched. */
    fun setResetTokenByEmail(email: String, token: String, expiry: OffsetDateTime): String? = jdbc
        .sql(
            """
            UPDATE users SET reset_token = :token, reset_token_expiry = :expiry
            WHERE email = :email
            RETURNING email
            """,
        )
        .param("token", token)
        .param("expiry", expiry)
        .param("email", email)
        .query(String::class.java)
        .optional()
        .orElse(null)

    fun setResetTokenById(id: Int, token: String, expiry: OffsetDateTime): String? = jdbc
        .sql(
            """
            UPDATE users SET reset_token = :token, reset_token_expiry = :expiry
            WHERE id = :id
            RETURNING email
            """,
        )
        .param("token", token)
        .param("expiry", expiry)
        .param("id", id)
        .query(String::class.java)
        .optional()
        .orElse(null)

    fun updateVerification(id: Int, verified: Boolean): VerificationRow? = jdbc
        .sql(
            """
            UPDATE users SET email_verified = :verified, verification_token = NULL
            WHERE id = :id
            RETURNING id, email, email_verified AS "emailVerified"
            """,
        )
        .param("verified", verified)
        .param("id", id)
        .query(VerificationRow::class.java)
        .optional()
        .orElse(null)

    fun updateRole(id: Int, role: String): RoleRow? = jdbc
        .sql(
            """
            UPDATE users SET role = :role WHERE id = :id
            RETURNING id, email, role
            """,
        )
        .param("role", role)
        .param("id", id)
        .query(RoleRow::class.java)
        .optional()
        .orElse(null)

    /** @return rows deleted (0 → no such user). */
    fun deleteById(id: Int): Int = jdbc
        .sql("DELETE FROM users WHERE id = :id")
        .param("id", id)
        .update()

    /**
     * Staff see clients and other staff — admin accounts aren't theirs to
     * manage. Admin sees everyone.
     */
    fun list(includeAdminAccounts: Boolean): List<ListItem> {
        val filter = if (includeAdminAccounts) "" else " WHERE role IN ('client', 'staff')"
        // Explicit row mapper so created_at is normalised to UTC before it is
        // serialised, whatever offset the driver hands back.
        return jdbc
            .sql(
                """
                SELECT id, email, role,
                       email_verified AS "emailVerified",
                       created_at     AS "createdAt"
                FROM users$filter
                ORDER BY created_at ASC
                """,
            )
            .query(
                RowMapper { rs, _ ->
                    ListItem(
                        rs.getInt("id"),
                        rs.getString("email"),
                        rs.getString("role"),
                        rs.getBoolean("emailVerified"),
                        rs.getObject("createdAt", OffsetDateTime::class.java).toInstant(),
                    )
                },
            )
            .list()
    }
}
