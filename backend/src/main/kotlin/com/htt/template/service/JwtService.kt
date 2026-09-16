package com.htt.template.service

import com.htt.template.config.AppProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Service

/**
 * HS256 JWTs carrying the `{email, exp, iat}` claims the API contract pins.
 *
 * Sessions expire [SESSION_TTL_SECONDS] after issue; the filter slides active
 * sessions forward by re-issuing past the half-life.
 */
@Service
class JwtService(properties: AppProperties) {

    private val key: SecretKey

    init {
        val secret = properties.jwtSecret.toByteArray(StandardCharsets.UTF_8)
        // Refuse a weak key up front rather than failing deep inside the
        // signer: a short HMAC secret is brute-forceable.
        check(secret.size >= MINIMUM_SECRET_BYTES) {
            "JWT_SECRET must be at least $MINIMUM_SECRET_BYTES bytes for HS256"
        }
        key = SecretKeySpec(secret, "HmacSHA256")
    }

    fun issue(email: String): String = issueWithTtl(email, SESSION_TTL_SECONDS)

    fun issueWithTtl(email: String, ttlSeconds: Long): String {
        val now = Instant.now().epochSecond
        return Jwts.builder()
            .claim("email", email)
            .issuedAt(Date.from(Instant.ofEpochSecond(now)))
            .expiration(Date.from(Instant.ofEpochSecond(now + ttlSeconds)))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    /**
     * @return the email claim, or null on any failure — bad signature, expired,
     *         malformed, or a token whose email claim is missing/blank.
     */
    fun verify(token: String?): String? =
        claims(token)
            ?.get("email", String::class.java)
            ?.takeIf { it.isNotEmpty() }

    /**
     * A fresh token when the current one is past its half-life, so an active
     * user's session slides forward instead of hard-expiring mid-use. Expired
     * or invalid tokens are never renewed.
     */
    fun renewIfDue(token: String?): String? =
        claims(token)
            ?.takeIf { it.expiration != null }
            ?.takeIf {
                it.expiration.toInstant().epochSecond - Instant.now().epochSecond < RENEW_THRESHOLD_SECONDS
            }
            ?.let { issue(it.get("email", String::class.java)) }

    private fun claims(token: String?): Claims? {
        if (token.isNullOrEmpty()) {
            return null
        }
        return try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        } catch (rejected: JwtException) {
            null
        } catch (rejected: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private const val SESSION_TTL_SECONDS = 600L // 10 minutes
        private const val RENEW_THRESHOLD_SECONDS = 300L // half-life
        private const val MINIMUM_SECRET_BYTES = 32 // 256 bits, HS256's key size
    }
}
