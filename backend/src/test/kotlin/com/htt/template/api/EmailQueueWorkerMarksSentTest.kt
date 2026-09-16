package com.htt.template.api

import com.htt.template.service.EmailQueueService
import com.htt.template.support.IntegrationTest
import com.htt.template.support.NoScheduledEmailWorker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired

/**
 * The queue worker drains pending rows — signup queues welcome + verification,
 * login queues the 2FA code; with SMTP_HOST unset the log transport succeeds, so
 * everything should flip to 'sent'.
 *
 * This drives [EmailQueueService.process] directly rather than leaving it to the
 * scheduler — see [NoScheduledEmailWorker].
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class EmailQueueWorkerMarksSentTest : IntegrationTest() {

    @Autowired
    private lateinit var queue: EmailQueueService

    @Test
    fun email_queue_worker_marks_sent() {
        env.signup()
        env.loginAs(env.email, env.password)

        // Drain in rounds — other tests are enqueueing too, so one LIMIT 10
        // batch may not cover this user's rows yet.
        var processed = 0
        for (round in 0 until 10) {
            val taken = queue.process(10)
            processed += taken
            if (taken == 0) {
                break
            }
        }
        val drained = processed
        assertTrue(drained >= 3) {
            "expected at least welcome+verification+login-code, got " +
                drained
        }

        val unsent = jdbc
            .sql(
                "SELECT count(*) FROM email_queue WHERE \"to\" = :email AND status <> 'sent'",
            )
            .param("email", env.email)
            .query(Long::class.javaObjectType)
            .single()
        assertEquals(0L, unsent, "worker left unsent rows behind")
    }
}
