package com.htt.template.api

import com.htt.template.cli.SetRoleCommand
import com.htt.template.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

/**
 * The out-of-band role grant: valid roles update, unknown emails and invalid
 * roles are rejected.
 *
 * The CLI wrapper and the role change itself are separate
 * ([SetRoleCommand.run] vs [SetRoleCommand.apply]), so the grant is
 * exercised against this suite's database rather than a dev one.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SetRoleSubcommandTest : IntegrationTest() {

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun set_role_subcommand() {
        env.signup()

        // Argument handling, before any database work: unknown role, and the
        // wrong number of arguments.
        assertEquals(
            1,
            SetRoleCommand.run(arrayOf(env.email, "wizard")),
        )
        assertEquals(1, SetRoleCommand.run(arrayOf(env.email)))

        // No such user, through the CLI wrapper's own DSN resolution.
        assertEquals(
            1,
            SetRoleCommand.run(arrayOf("nobody@mail.com", "admin")),
        )

        // The grant itself, against this suite's database.
        dataSource.connection.use { connection ->
            assertEquals(
                SetRoleCommand.Outcome.OK,
                SetRoleCommand.apply(connection, env.email, "staff"),
            )
            assertEquals(
                SetRoleCommand.Outcome.NO_SUCH_USER,
                SetRoleCommand.apply(connection, "nobody@mail.com", "staff"),
            )
            assertEquals(
                SetRoleCommand.Outcome.INVALID_ROLE,
                SetRoleCommand.apply(connection, env.email, "wizard"),
            )
        }
        assertEquals("staff", env.roleOf(env.email))
    }
}
