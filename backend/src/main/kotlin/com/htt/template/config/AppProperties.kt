package com.htt.template.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Runtime configuration, bound from environment variables (see
 * application.yml). Normalisation mirrors Go's `envOr` / `intOr` helpers: an
 * empty value counts as unset, and a non-positive number falls back to its
 * default.
 *
 * Every property has a default and a setter, so binding works whether Spring
 * treats this as a JavaBean or as a constructor-bound type. The defaults are
 * filled in again by [normalise] because application.yml passes an empty string
 * through for an unset variable, and binding happens after construction.
 */
@ConfigurationProperties(prefix = "app")
class AppProperties(
    var env: String = DEFAULT_ENV,
    var databaseUrl: String = DEFAULT_DATABASE_URL,
    var frontendUrl: String = DEFAULT_FRONTEND_URL,
    var jwtSecret: String = "",
    var smtpHost: String = "",
    var smtpPort: Int = DEFAULT_SMTP_PORT,
    var smtpUser: String = "",
    var smtpPass: String = "",
    var mailFrom: String = DEFAULT_MAIL_FROM,
    var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    var emailVerificationRequired: Boolean = true,
) {

    @PostConstruct
    fun normalise() {
        if (env.isBlank()) {
            env = DEFAULT_ENV
        }
        if (databaseUrl.isBlank()) {
            databaseUrl = DEFAULT_DATABASE_URL
        }
        if (frontendUrl.isBlank()) {
            frontendUrl = DEFAULT_FRONTEND_URL
        }
        if (mailFrom.isBlank()) {
            mailFrom = DEFAULT_MAIL_FROM
        }
        if (smtpPort <= 0) {
            smtpPort = DEFAULT_SMTP_PORT
        }
        if (maxAttempts <= 0) {
            maxAttempts = DEFAULT_MAX_ATTEMPTS
        }
        if (jwtSecret.isBlank()) {
            if (isProduction()) {
                throw IllegalStateException("JWT_SECRET must be set in production")
            }
            log.warn("[config] JWT_SECRET not set — using insecure dev fallback")
            jwtSecret = INSECURE_DEV_JWT_SECRET
        }
    }

    fun isProduction(): Boolean = env == "production"

    fun isDevelopment(): Boolean = env == "development"

    companion object {
        private val log = LoggerFactory.getLogger(AppProperties::class.java)

        /**
         * Fallback for local development only. At least 32 bytes, because
         * JwtService refuses a shorter HMAC key. Only affects sessions issued
         * while running on the fallback — set JWT_SECRET for anything shared.
         */
        private const val INSECURE_DEV_JWT_SECRET = "dev-insecure-jwt-secret-not-for-production"

        private const val DEFAULT_ENV = "development"
        private const val DEFAULT_DATABASE_URL =
            "postgres://postgres:postgres@localhost:5432/template-db?sslmode=disable"
        private const val DEFAULT_FRONTEND_URL = "http://localhost:3000"
        private const val DEFAULT_MAIL_FROM = "tom.tran@email.com"
        private const val DEFAULT_SMTP_PORT = 587
        private const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
