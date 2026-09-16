package com.htt.template.service

import com.htt.template.repository.Profile
import com.htt.template.repository.ProfileRepository
import com.htt.template.service.error.ConflictException
import com.htt.template.service.error.ServerErrorException
import com.htt.template.service.error.ValidationException
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service

/**
 * The one-time registration form: submitted once per user, and a missing row
 * (not a boolean flag) is what gates a user into the completion form.
 */
@Service
class ProfileService(private val profiles: ProfileRepository) {

    /** A missing profile is a null result, not an error — the absence is the gate. */
    fun getProfile(userId: Int): Profile? = try {
        profiles.findByUserId(userId)
    } catch (failed: DataAccessException) {
        throw ServerErrorException("Get Profile Error", failed, false)
    }

    fun saveProfile(userId: Int, command: ProfileCommand): Profile {
        validate(command)?.let { throw ValidationException(it) }
        // A blank country falls back to US.
        val country = command.country?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_COUNTRY
        val row = Profile(
            0,
            userId,
            command.firstName.trim(),
            command.lastName.trim(),
            command.address.trim(),
            optionalTrimmed(command.address2),
            command.state.trim(),
            command.zip.trim(),
            country,
            command.phone.trim(),
            command.communicationPreference,
            optionalTrimmed(command.linkedin),
            optionalTrimmed(command.github),
            optionalTrimmed(command.altEmail),
        )
        return try {
            profiles.insert(row)
        } catch (alreadyExists: DuplicateKeyException) {
            throw ConflictException("Profile already exists")
        } catch (failed: DataAccessException) {
            throw ServerErrorException("Save Profile Error", failed, false)
        }
    }

    companion object {
        private val COMMUNICATION_PREFERENCES = listOf("email", "text", "phone")

        private const val DEFAULT_COUNTRY = "US"

        /**
         * @return a message describing the first unmet rule, or null when the
         *         form is valid.
         */
        fun validate(command: ProfileCommand): String? {
            val missing = mutableListOf<String>()
            collectMissing(missing, "firstName", command.firstName)
            collectMissing(missing, "lastName", command.lastName)
            collectMissing(missing, "address", command.address)
            collectMissing(missing, "state", command.state)
            collectMissing(missing, "zip", command.zip)
            collectMissing(missing, "phone", command.phone)
            collectMissing(missing, "communicationPreference", command.communicationPreference)
            if (missing.isNotEmpty()) {
                return "Missing required field(s): " + missing.joinToString(", ")
            }
            if (command.communicationPreference !in COMMUNICATION_PREFERENCES) {
                return "communicationPreference must be one of: " +
                    COMMUNICATION_PREFERENCES.joinToString(", ")
            }
            if (!Validators.isPhone(command.phone)) {
                return "Phone number is invalid"
            }
            if (!Validators.isZip(command.zip)) {
                return "Zip code is invalid"
            }
            if (!Validators.isUsState(command.state)) {
                return "State is invalid"
            }
            // The dropdown only offers what's in the country list, but a direct
            // API call could still send something else.
            val country = command.country
            if (!country.isNullOrEmpty() && !Validators.isCountry(country)) {
                return "Country is invalid"
            }
            val altEmail = command.altEmail
            if (!altEmail.isNullOrEmpty() && !Validators.isEmail(altEmail)) {
                return "Additional email address is invalid"
            }
            val linkedin = command.linkedin
            if (!linkedin.isNullOrEmpty() && !Validators.isUrl(linkedin)) {
                return "LinkedIn URL is invalid"
            }
            val github = command.github
            if (!github.isNullOrEmpty() && !Validators.isUrl(github)) {
                return "GitHub URL is invalid"
            }
            return null
        }

        private fun collectMissing(missing: MutableList<String>, field: String, value: String) {
            if (value.isBlank()) {
                missing.add(field)
            }
        }

        /** `body.x?.trim() || null` — blank optionals are stored as NULL. */
        private fun optionalTrimmed(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }
    }
}
