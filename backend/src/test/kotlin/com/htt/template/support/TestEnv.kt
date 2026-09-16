package com.htt.template.support

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * One uniquely-addressed fixture per test, driven through the real app with
 * MockMvc, plus the shared helpers — login (including the 2FA round trip), the
 * emailed login code, profile setup, direct role updates and fixture cleanup.
 *
 * Every fixture email is unique per run, so reruns against a dirty database
 * still pass.
 */
class TestEnv(private val mvc: MockMvc, private val jdbc: JdbcClient) {

    val email: String =
        "kotlintest-" + RUN.incrementAndGet() + "-" + (System.nanoTime() % 100_000) + "@mail.com"

    val password: String = PASSWORD

    /** A response: the status, the session-renewal header, and the parsed body. */
    data class Response(val status: Int, val renewedToken: String?, val body: JsonNode) {

        /** The body as text, for assertion messages. */
        val text: String get() = body.toString()
    }

    /**
     * The request carries `content-type: application/json` and a bearer token
     * when one is given, and an unparsable body reads as JSON null.
     */
    fun doJson(method: String, path: String, token: String?, body: Map<String, Any?>?): Response {
        val request: MockHttpServletRequestBuilder = when (method) {
            "GET" -> get(path)
            "POST" -> post(path)
            "PATCH" -> patch(path)
            "DELETE" -> delete(path)
            else -> throw IllegalArgumentException("unsupported method $method")
        }
        request.contentType(MediaType.APPLICATION_JSON)
        if (!token.isNullOrEmpty()) {
            request.header("Authorization", "Bearer $token")
        }
        request.content(if (body == null) ByteArray(0) else raw(body))
        return try {
            val response = mvc.perform(request).andReturn().response
            Response(
                response.status,
                response.getHeader(RENEWED_TOKEN_HEADER),
                parse(response.contentAsByteArray),
            )
        } catch (failed: Exception) {
            throw IllegalStateException("request failed: $method $path", failed)
        }
    }

    /** A bare signup call — asserting the outcome is the test's job. */
    fun signup(): Response =
        doJson("POST", "/api/signup", "", mapOf("email" to email, "password" to PASSWORD))

    /** Logs in, answering a 2FA challenge with the code out of the queued email. */
    fun loginAs(email: String, password: String): String {
        val login = doJson("POST", "/api/login", "", mapOf("email" to email, "password" to password))
        assertStatus(200, login)
        var token = login.body.path("token").asText("")
        assertFalse(token.isEmpty()) { "login for $email returned no token: ${login.text}" }
        if (login.body.path("twoFactorRequired").asBoolean(false)) {
            val code = fetchLoginCode(email)
            val verified = doJson(
                "POST",
                "/api/login/verify",
                "",
                mapOf("token" to token, "code" to code, "deviceId" to "test-device"),
            )
            assertStatus(200, verified)
            token = verified.body.path("token").asText("")
            assertFalse(token.isEmpty()) { "2FA verify for $email returned no token: ${verified.text}" }
        }
        return token
    }

    /**
     * The newest queued email for the address, with the 4-digit login code
     * pulled out of its body (the worker isn't what delivers it in tests).
     */
    fun fetchLoginCode(email: String): String {
        val body = jdbc
            .sql("SELECT body FROM email_queue WHERE \"to\" = :email ORDER BY id DESC LIMIT 1")
            .param("email", email)
            .query(String::class.java)
            .optional()
            .orElseThrow { IllegalStateException("no queued email for $email") }
        val match = LOGIN_CODE.matcher(body)
        if (!match.find()) {
            throw IllegalStateException("no 4-digit code in queued email: $body")
        }
        return match.group()
    }

    /** Fills in the profile so onboarding gates don't mask the behaviour under test. */
    fun fillProfile(token: String) {
        assertStatus(201, doJson("POST", "/api/profile", token, profileBody()))
    }

    /** Sets a user's role directly in the store. */
    fun setRole(email: String, role: String) {
        jdbc.sql("UPDATE users SET role = :role WHERE email = :email")
            .param("role", role)
            .param("email", email)
            .update()
    }

    fun roleOf(email: String): String? = jdbc
        .sql("SELECT role FROM users WHERE email = :email")
        .param("email", email)
        .query(String::class.java)
        .optional()
        .orElse(null)

    fun ownUserId(token: String): Int {
        val me = doJson("GET", "/api/me", token, null)
        assertStatus(200, me)
        val id = me.body.path("user").path("id")
        assertTrue(id.isNumber) { "/api/me returned no user id: ${me.text}" }
        return id.asInt()
    }

    /**
     * Deletes the fixture's rows: the user (which cascades to the profile) and
     * the queued emails addressed to it.
     */
    fun cleanup() {
        jdbc.sql("DELETE FROM users WHERE email = :email")
            .param("email", email)
            .update()
        jdbc.sql("DELETE FROM email_queue WHERE \"to\" = :email")
            .param("email", email)
            .update()
    }

    companion object {

        /** Runs this JVM has started; keeps fixture emails apart. */
        private val RUN = AtomicLong()

        private val JSON = ObjectMapper()

        /** Pulls the first 4-digit run out of a queued email body. */
        private val LOGIN_CODE = Pattern.compile("\\b\\d{4}\\b")

        /** Produced by `SessionRenewalFilter` for sessions past their half-life. */
        const val RENEWED_TOKEN_HEADER = "X-Renewed-Token"

        const val PASSWORD = "Valid123!"

        /**
         * app.env is deliberately not `development`: login codes stay random
         * (loginAs pulls them out of the queued email) and the dev-admin seeder
         * stays off. Only the seed test asks for development.
         */
        const val APP_ENV_TEST = "app.env=test"

        const val APP_ENV_DEVELOPMENT = "app.env=development"

        /** These contexts do not require email verification. */
        const val EMAIL_VERIFICATION_NOT_REQUIRED = "app.email-verification-required=false"

        const val FRONTEND_URL = "app.frontend-url=http://localhost:3000"

        /**
         * At least 32 bytes: JwtService refuses a shorter HMAC key, so a shorter
         * secret would fail the context at startup.
         */
        const val JWT_SECRET = "app.jwt-secret=test-secret-for-integration-tests-32b"

        /** Lets this suite swap the scheduled queue drain for a silent one. */
        const val ALLOW_BEAN_OVERRIDE = "spring.main.allow-bean-definition-overriding=true"

        /** The database the test context runs against, or null when unset. */
        fun databaseUrl(): String? = System.getenv("TEST_DATABASE_URL")

        /** The registration form the profile tests post. */
        fun profileBody(): Map<String, Any?> = mapOf(
            "firstName" to "Test",
            "lastName" to "User",
            "address" to "1 Test St",
            "state" to "CA",
            "zip" to "94043",
            "phone" to "555-123-4567",
            "communicationPreference" to "email",
        )

        private fun raw(body: Map<String, Any?>): ByteArray = try {
            JSON.writeValueAsBytes(body)
        } catch (unexpected: JsonProcessingException) {
            throw IllegalArgumentException("body is not serialisable: $body", unexpected)
        }

        /** Missing or unparsable bodies read as JSON null. */
        private fun parse(bytes: ByteArray?): JsonNode {
            if (bytes == null || bytes.isEmpty()) {
                return NullNode.getInstance()
            }
            return try {
                JSON.readTree(bytes)
            } catch (unparsable: IOException) {
                NullNode.getInstance()
            }
        }
    }
}

/**
 * The shared assertions live at the top level so a test can import them by
 * name: `import com.htt.template.support.assertStatus`.
 */

/** Status assertion, with the body included in the failure message. */
fun assertStatus(want: Int, response: TestEnv.Response) {
    assertEquals(want, response.status) { "body ${response.text}" }
}

/** `assert_eq!(body[key], json!(true))` — boolean true, not just truthy. */
fun assertJsonTrue(key: String, response: TestEnv.Response) {
    val value = response.body.path(key)
    assertTrue(value.isBoolean && value.booleanValue()) { "$key = ${response.text}" }
}

/** `assert_ne!(body[key], json!(true))`. */
fun assertJsonNotTrue(key: String, response: TestEnv.Response) {
    val value = response.body.path(key)
    assertFalse(value.isBoolean && value.booleanValue()) { "$key = ${response.text}" }
}

/** `assert_eq!(body["message"], json!(…))`, with the body in the message. */
fun assertMessage(want: String, response: TestEnv.Response) {
    assertEquals(want, response.body.path("message").asText()) { "body ${response.text}" }
}
