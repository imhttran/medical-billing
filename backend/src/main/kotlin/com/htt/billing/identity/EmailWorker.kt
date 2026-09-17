package com.htt.billing.identity

import com.htt.billing.service.identity.EmailQueueService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Polls the email queue and sends (or logs) whatever is pending. */
@Component
class EmailWorker(private val emailQueue: EmailQueueService) {

    @Scheduled(fixedDelay = 3000)
    open fun drainQueue() {
        emailQueue.process(BATCH_SIZE)
    }

    companion object {
        private const val BATCH_SIZE = 10
    }
}
