package com.htt.template.service

import com.htt.template.config.AppProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JwtServiceTest {

    private val jwt = JwtService(properties(SECRET))

    @Test
    fun jwtRoundTrip() {
        val token = jwt.issue("a@b.c")
        assertEquals("a@b.c", jwt.verify(token))
    }

    @Test
    fun rejectsAnotherSecret() {
        val token = jwt.issue("a@b.c")
        assertNull(
            JwtService(properties("a-different-secret-that-is-long-enough"))
                .verify(token),
        )
    }

    @Test
    fun rejectsGarbage() {
        assertNull(jwt.verify("not-a-jwt"))
        assertNull(jwt.verify(""))
    }

    @Test
    fun rejectsExpiredTokens() {
        assertNull(jwt.verify(jwt.issueWithTtl("a@b.c", -60L)))
    }

    @Test
    fun ignoresTokensWithoutAnEmailClaim() {
        assertNull(jwt.verify(jwt.issueWithTtl("", 600L)))
    }

    @Test
    fun renewalOnlyPastHalfLife() {
        // A fresh (full-life) token is not renewed...
        assertNull(jwt.renewIfDue(jwt.issue("a@b.c")))
        // ...one deep into its life is...
        val renewed = jwt.renewIfDue(jwt.issueWithTtl("a@b.c", 60L))
        assertNotNull(renewed)
        assertEquals("a@b.c", jwt.verify(renewed))
        // ...and an expired one is dead, not renewable.
        assertNull(jwt.renewIfDue(jwt.issueWithTtl("a@b.c", -60L)))
        assertNull(jwt.renewIfDue("not-a-jwt"))
    }

    /** Short HMAC secrets are brute-forceable, so they're refused up front. */
    @Test
    fun refusesSecretsShorterThanTheHmacKeySize() {
        val failure = assertThrows<IllegalStateException> { JwtService(properties("short")) }
        assertTrue(failure.message!!.contains("JWT_SECRET"))
    }

    companion object {

        private const val SECRET = "test-secret-long-enough-for-hs256"

        private fun properties(secret: String): AppProperties = AppProperties(jwtSecret = secret)
    }
}
