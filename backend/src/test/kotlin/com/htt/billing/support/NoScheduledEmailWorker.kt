package com.htt.billing.support

import com.htt.billing.identity.EmailWorker
import com.htt.billing.service.identity.EmailQueueService
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Spring starts the `@Scheduled` drain with the context (and keeps it running
 * while the context stays cached), which would race the queue test and drain
 * rows it is counting.
 *
 * Importing this replaces that bean with a silent one, so tests decide when
 * [EmailQueueService.process] runs.
 */
@TestConfiguration
class NoScheduledEmailWorker {

    @Bean
    fun emailWorker(queue: EmailQueueService): EmailWorker = object : EmailWorker(queue) {
        override fun drainQueue() {
            // Silenced on purpose; see this class's javadoc.
        }
    }
}
