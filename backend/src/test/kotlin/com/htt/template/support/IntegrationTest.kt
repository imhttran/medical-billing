package com.htt.template.support

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import java.util.function.Supplier

/**
 * The scaffolding every `/api` integration test shares: one test-env context,
 * MockMvc, the JDBC client and the per-test fixture.
 *
 * Spring's annotations carry to subclasses; JUnit's do not, so each subclass
 * still carries its own `@EnabledIfEnvironmentVariable`.
 */
@SpringBootTest(
    properties = [
        TestEnv.APP_ENV_TEST,
        TestEnv.EMAIL_VERIFICATION_NOT_REQUIRED,
        TestEnv.FRONTEND_URL,
        TestEnv.JWT_SECRET,
        TestEnv.ALLOW_BEAN_OVERRIDE,
    ],
)
@AutoConfigureMockMvc
@Import(NoScheduledEmailWorker::class) // these tests drive the queue themselves
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // close the context (and its 10-connection pool) after each class
abstract class IntegrationTest {

    @Autowired
    protected lateinit var mvc: MockMvc

    @Autowired
    protected lateinit var jdbc: JdbcClient

    protected lateinit var env: TestEnv

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun databaseUrl(registry: DynamicPropertyRegistry) {
            registry.add("app.database-url", Supplier { TestEnv.databaseUrl() })
        }
    }

    @BeforeEach
    fun setUp() {
        env = TestEnv(mvc, jdbc)
    }

    @AfterEach
    fun cleanup() {
        env.cleanup()
    }
}
