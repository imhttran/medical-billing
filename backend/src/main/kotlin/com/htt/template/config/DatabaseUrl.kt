package com.htt.template.config

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * The backend's one database variable is DATABASE_URL, in the libpq form this
 * project has always used (`postgres://user:pass@host:port/db?params`). The JDBC
 * driver wants a `jdbc:postgresql://` URL with credentials supplied separately,
 * so it is translated here. A URL that is already JDBC-shaped is passed through
 * untouched.
 */
data class DatabaseUrl(val jdbcUrl: String, val username: String?, val password: String?) {

    companion object {

        fun parse(raw: String): DatabaseUrl {
            val value = raw.trim()
            if (value.startsWith("jdbc:")) {
                return DatabaseUrl(value, null, null)
            }
            check(value.startsWith("postgres://") || value.startsWith("postgresql://")) {
                "DATABASE_URL must be a postgres:// or jdbc:postgresql:// URL, got: $raw"
            }
            val uri = URI.create(value)
            val port = if (uri.port == -1) 5432 else uri.port
            val jdbc = StringBuilder("jdbc:postgresql://")
                .append(uri.host)
                .append(':')
                .append(port)
                .append(uri.path)
            if (uri.query != null) {
                // libpq parameters such as sslmode are understood by the JDBC
                // driver too, so the query string is carried over verbatim.
                jdbc.append('?').append(uri.query)
            }

            var username: String? = null
            var password: String? = null
            val userInfo = uri.userInfo
            if (userInfo != null) {
                val credentials = userInfo.split(":", limit = 2)
                username = decode(credentials[0])
                password = if (credentials.size > 1) decode(credentials[1]) else null
            }
            return DatabaseUrl(jdbc.toString(), username, password)
        }

        private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)
    }
}
