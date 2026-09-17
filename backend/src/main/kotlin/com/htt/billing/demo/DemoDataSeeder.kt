package com.htt.billing.demo

import com.htt.billing.service.demo.DemoResetService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Conditional
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Puts the synthetic dataset in place on a dev/demo boot, so a fresh database —
 * or one just rebuilt by `./manage.sh db:reseed` — has Jane Smith waiting
 * instead of requiring a manual call before the golden path can be walked.
 *
 * Only seeds when the demo practice is empty. A restart therefore never
 * destroys what a developer has been working on; the destructive reset is the
 * endpoint, and it has to be asked for.
 *
 * After [com.htt.billing.identity.DevUserSeeder], which it needs: the grants that
 * make the practice visible to the dev logins go to those accounts.
 */
@Component
@Order(1)
@Conditional(DemoEnvironment::class)
class DemoDataSeeder(private val demo: DemoResetService) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val seeded = demo.seedIfAbsent()
        if (seeded == null) {
            log.info("[demo] practice already holds patients — leaving it alone")
            return
        }
        log.info(
            "[demo] seeded practice {} with {} patient(s), {} provider(s), {} coverage(s), {} claim(s)",
            seeded.organizationId,
            seeded.patients,
            seeded.providers,
            seeded.coverages,
            seeded.claims,
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(DemoDataSeeder::class.java)
    }
}
