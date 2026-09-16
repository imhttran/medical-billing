package com.htt.template.service

import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Input validation. Patterns use UNICODE_CHARACTER_CLASS so `\d` and `\s` are
 * Unicode-aware (the JVM would otherwise treat them as ASCII-only).
 */
object Validators {

    private val EMAIL = pattern("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+\$")

    // US phone numbers only, digits with optional standard formatting
    // (spaces/dots/dashes/parens) and an optional leading +1/1.
    private val PHONE = pattern("^\\+?1?[-.\\s]?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}\$")

    // US zip codes only — 5 digits, matching the frontend's pattern="[0-9]{5}".
    private val ZIP = pattern("^\\d{5}\$")

    private val PASSWORD_SPECIAL = pattern("[!@#\$%^&*(),.?\":{}|<>]")

    private val US_STATE_CODES = listOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI",
        "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN",
        "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH",
        "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA",
        "WV", "WI", "WY",
    )

    private fun pattern(regex: String): Pattern =
        Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS)

    fun isEmail(email: String): Boolean = EMAIL.matcher(email).matches()

    fun isPhone(phone: String): Boolean = PHONE.matcher(phone).matches()

    fun isZip(zip: String): Boolean = ZIP.matcher(zip).matches()

    /** http(s) only — good enough for LinkedIn/GitHub profile links. */
    fun isUrl(rawUrl: String): Boolean {
        val uri = try {
            URI(rawUrl)
        } catch (malformed: URISyntaxException) {
            return false
        }
        val scheme = uri.scheme
        val httpScheme = scheme != null &&
            (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true))
        return httpScheme && uri.host != null
    }

    fun isUsState(state: String): Boolean = US_STATE_CODES.contains(state)

    // One country today; a second one turns this into a set lookup again.
    fun isCountry(country: String): Boolean = country == "US"

    /**
     * @return a message describing the first unmet rule, or null when the
     *         password is fine.
     */
    fun validatePassword(password: String): String? {
        // Byte length, not character count: a multi-byte character is worth
        // more than one.
        if (password.toByteArray(StandardCharsets.UTF_8).size < 8) {
            return "Password must be at least 8 characters long"
        }
        // ASCII only, deliberately: a non-ASCII uppercase letter or digit does
        // not satisfy these rules.
        if (password.none { it in 'A'..'Z' }) {
            return "Password must contain at least one uppercase letter"
        }
        if (password.none { it in '0'..'9' }) {
            return "Password must contain at least one number"
        }
        if (!PASSWORD_SPECIAL.matcher(password).find()) {
            return "Password must contain at least one special character"
        }
        return null
    }
}
