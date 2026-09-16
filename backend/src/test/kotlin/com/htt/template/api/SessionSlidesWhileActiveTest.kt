package com.htt.template.api

import com.htt.template.service.JwtService
import com.htt.template.support.IntegrationTest
import com.htt.template.support.assertStatus
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired

/**
 * A token deep
 * into its life is renewed on successful use (X-Renewed-Token, produced by
 * [SessionRenewalFilter]), the renewed token is a normal bearer, a
 * full-life token is not renewed, and a hard-expired one is rejected.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SessionSlidesWhileActiveTest : IntegrationTest() {

    @Autowired
    private lateinit var jwt: JwtService

    @Test
    fun session_slides_while_active() {
        env.signup()

        // 60 seconds left of the 10-minute window → renewed on use.
        val aging = jwt.issueWithTtl(env.email, 60L)
        val sliding = env.doJson("GET", "/api/me", aging, null)
        assertStatus(200, sliding)
        val renewed = sliding.renewedToken
        assertNotNull(renewed, "aging token should be renewed")
        assertNotEquals(aging, renewed, "renewal must be a new token")

        // The renewed token is a normal bearer.
        assertStatus(200, env.doJson("GET", "/api/me", renewed, null))

        // A fresh (full-life) token is not renewed.
        val fresh = jwt.issue(env.email)
        val full = env.doJson("GET", "/api/me", fresh, null)
        assertStatus(200, full)
        assertNull(full.renewedToken, "full-life token should not renew")

        // Hard expiry: past the window, the token is rejected outright.
        val expired = jwt.issueWithTtl(env.email, -60L)
        assertStatus(403, env.doJson("GET", "/api/me", expired, null))
    }
}
