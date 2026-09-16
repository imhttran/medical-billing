package com.htt.template.cli

import com.htt.template.config.DatabaseUrl
import com.htt.template.service.Roles
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

/**
 * `set-role <email> <role>`: roles are granted out-of-band, so there is no HTTP
 * endpoint for this. [run] deliberately does not load .env files and does not
 * start Spring — it reads DATABASE_URL directly, like the CLI it replaces.
 * [apply] is the role change itself, split out so it can be driven against any
 * connection.
 */
object SetRoleCommand {

    /** What happened, independent of how the change was reached. */
    enum class Outcome { OK, NO_SUCH_USER, INVALID_ROLE }

    /** @return the process exit code. */
    fun run(args: Array<String>): Int {
        val usage = "Usage: set-role <email> <${Roles.ROLES.joinToString("|")}>"
        if (args.size != 2 || !Roles.isRole(args[1])) {
            System.err.println(usage)
            return 1
        }
        val email = args[0]
        val role = args[1]

        val dsn = System.getenv("DATABASE_URL").takeUnless { it.isNullOrEmpty() } ?: DEFAULT_DATABASE_URL
        val url = try {
            DatabaseUrl.parse(dsn)
        } catch (malformed: IllegalStateException) {
            System.err.println("Failed to set role: ${malformed.message}")
            return 1
        }

        return try {
            open(url).use { connection ->
                when (apply(connection, email, role)) {
                    Outcome.OK -> {
                        println("$email is now $role")
                        0
                    }

                    Outcome.NO_SUCH_USER -> {
                        System.err.println("No user found with email $email")
                        1
                    }

                    Outcome.INVALID_ROLE -> {
                        System.err.println(usage)
                        1
                    }
                }
            }
        } catch (failed: SQLException) {
            System.err.println("Failed to set role: ${failed.message}")
            1
        }
    }

    /**
     * Sets an existing user's role — the same code path the CLI runs, against
     * whatever connection the caller supplies.
     */
    fun apply(connection: Connection, email: String, role: String): Outcome {
        if (!Roles.isRole(role)) {
            return Outcome.INVALID_ROLE
        }
        if (!userExists(connection, email)) {
            return Outcome.NO_SUCH_USER
        }
        connection.prepareStatement("UPDATE users SET role = ? WHERE email = ?").use { statement ->
            statement.setString(1, role)
            statement.setString(2, email)
            statement.executeUpdate()
        }
        return Outcome.OK
    }

    private fun open(url: DatabaseUrl): Connection {
        val credentials = Properties()
        if (url.username != null) {
            credentials.setProperty("user", url.username)
        }
        if (url.password != null) {
            credentials.setProperty("password", url.password)
        }
        return DriverManager.getConnection(url.jdbcUrl, credentials)
    }

    private fun userExists(connection: Connection, email: String): Boolean =
        connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM users WHERE email = ?)").use { statement ->
            statement.setString(1, email)
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    private const val DEFAULT_DATABASE_URL =
        "postgres://postgres:postgres@localhost:5432/template-db?sslmode=disable"
}
