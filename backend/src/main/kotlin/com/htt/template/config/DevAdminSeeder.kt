package com.htt.template.config

import com.htt.template.repository.Profile
import com.htt.template.repository.ProfileRepository
import com.htt.template.repository.UserRepository
import com.htt.template.service.PasswordHasher
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Dev-only convenience: guarantees a known admin login exists locally, so
 * there's no manual set-role step for local dev. Gated on NODE_ENV so these
 * credentials can never appear in a qa/prod database.
 */
@Component
class DevAdminSeeder(
    private val properties: AppProperties,
    private val users: UserRepository,
    private val profiles: ProfileRepository,
    private val hasher: PasswordHasher,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        if (!properties.isDevelopment()) {
            return
        }
        try {
            // Conflict (no row) and DB errors both fall through to the lookup.
            var id = users.insertUserIfAbsent(DEV_ADMIN_EMAIL, hasher.hash(DEV_ADMIN_PASSWORD), "admin", true)
            if (id == null) {
                id = users.findIdByEmail(DEV_ADMIN_EMAIL)
            }
            if (id == null) {
                log.error("[seed] failed: dev admin missing after insert")
                return
            }
            // Pre-fill the profile too, so the dev admin isn't stopped by its
            // own onboarding gate.
            profiles.insertIfAbsent(
                Profile(0, id, "Dev", "Admin", "N/A", null, "N/A", "00000", "US", "N/A", "email", null, null, null),
            )
            log.info("[seed] dev admin ready: {}", DEV_ADMIN_EMAIL)
        } catch (failed: Exception) {
            log.error("[seed] failed: {}", failed.message)
        }
    }

    companion object {
        private const val DEV_ADMIN_EMAIL = "admin@mail.com"
        private const val DEV_ADMIN_PASSWORD = "Password1234!"

        private val log = LoggerFactory.getLogger(DevAdminSeeder::class.java)
    }
}
