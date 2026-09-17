package com.htt.billing.identity

import com.htt.billing.common.config.AppProperties
import com.htt.billing.demo.DemoDataset
import com.htt.billing.repository.identity.ProfileRepository
import com.htt.billing.repository.identity.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Dev-only convenience: guarantees a known login exists locally for each billing
 * role, so a demo can switch accounts to show what the permission matrix means
 * rather than describing it, and so there's no manual set-role step. Gated on
 * `NODE_ENV` so these credentials can never appear in a qa/prod database.
 *
 * Every account gets the same password and a filled profile, so none of them is
 * stopped by the onboarding gates on the way in.
 *
 * Runs before [com.htt.billing.demo.DemoDataSeeder], which grants these accounts
 * their billing roles and so needs them to already exist.
 */
@Component
@Order(0)
class DevUserSeeder(
    private val properties: AppProperties,
    private val users: UserRepository,
    private val profiles: ProfileRepository,
    private val hasher: PasswordHasher,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        if (!properties.isDevelopment()) {
            return
        }
        DemoDataset.DEV_TEAM.forEach(::seed)
        log.info("[seed] {} dev account(s) ready", DemoDataset.DEV_TEAM.size)
    }

    /**
     * Idempotent per account, and one account's failure does not stop the rest —
     * a single bad email should not cost the whole demo team.
     */
    private fun seed(member: DemoDataset.DemoUser) {
        try {
            // Conflict (no row) and DB errors both fall through to the lookup.
            var id = users.insertUserIfAbsent(
                member.email,
                hasher.hash(DEV_PASSWORD),
                member.coarseRole,
                true,
            )
            if (id == null) {
                id = users.findIdByEmail(member.email)
            }
            if (id == null) {
                log.error("[seed] failed: {} missing after insert", member.email)
                return
            }
            // Pre-fill the profile too, so no seeded account is stopped by its
            // own onboarding gate.
            profiles.insertIfAbsent(
                Profile(
                    0,
                    id,
                    member.firstName,
                    member.lastName,
                    "N/A",
                    null,
                    "N/A",
                    "00000",
                    "US",
                    "N/A",
                    "email",
                    null,
                    null,
                    null,
                ),
            )
        } catch (failed: Exception) {
            log.error("[seed] failed for {}: {}", member.email, failed.message)
        }
    }

    companion object {
        const val DEV_ADMIN_EMAIL = "admin@mail.com"

        /** Shared by every seeded account, so the demo can move between them. */
        private const val DEV_PASSWORD = "Password1234!"

        private val log = LoggerFactory.getLogger(DevUserSeeder::class.java)
    }
}
