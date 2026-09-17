package com.htt.billing.service.patient

import com.htt.billing.api.patient.PatientBody
import com.htt.billing.common.Inputs
import com.htt.billing.common.error.ForbiddenException
import com.htt.billing.common.error.NotFoundException
import com.htt.billing.common.error.ValidationException
import com.htt.billing.repository.patient.PatientRepository
import com.htt.billing.repository.patient.PatientRepository.Patient
import com.htt.billing.security.Permissions
import com.htt.billing.service.security.AuthorizationService
import java.time.LocalDate
import org.springframework.stereotype.Service

/**
 * Patients, scoped to the caller's practice.
 *
 * Reads that fall outside the caller's organizations answer "not found" rather
 * than "forbidden" — a 403 would confirm that the record exists in another
 * practice, which is exactly what tenant isolation is meant to hide.
 */
@Service
class PatientService(
    private val patients: PatientRepository,
    private val authorization: AuthorizationService,
) {

    fun list(userId: Int, query: String?): List<Patient> {
        val organizationIds = authorization.permittedOrganizationIds(userId, Permissions.PATIENT_VIEW)
        if (organizationIds.isEmpty()) {
            return emptyList()
        }
        val pattern = Inputs.optionalText(query)?.let { Inputs.containsPattern(it) }
        return patients.findIn(organizationIds, pattern)
    }

    fun get(userId: Int, patientId: Int): Patient {
        val patient = requireExisting(patientId)
        authorization.requireVisible(userId, Permissions.PATIENT_VIEW, patient.organizationId, "Patient not found")
        return patient
    }

    fun create(userId: Int, body: PatientBody): Patient {
        val organizationId =
            authorization.resolveWriteOrganization(userId, Permissions.PATIENT_CREATE, body.organizationId)
        return patients.insert(
            organizationId = organizationId,
            externalId = Inputs.optionalText(body.externalId),
            firstName = Inputs.requiredText(body.firstName, "firstName"),
            lastName = Inputs.requiredText(body.lastName, "lastName"),
            dateOfBirth = requirePastDate(Inputs.requiredDate(body.dateOfBirth, "dateOfBirth")),
            sex = normaliseSex(body.sex),
            addressLine1 = Inputs.optionalText(body.addressLine1),
            addressLine2 = Inputs.optionalText(body.addressLine2),
            city = Inputs.optionalText(body.city),
            state = Inputs.optionalText(body.state),
            postalCode = Inputs.optionalText(body.postalCode),
            phone = Inputs.optionalText(body.phone),
        )
    }

    fun update(userId: Int, patientId: Int, body: PatientBody): Patient {
        val existing = requireExisting(patientId)
        authorization.requireVisible(userId, Permissions.PATIENT_VIEW, existing.organizationId, "Patient not found")
        authorization.require(userId, Permissions.PATIENT_EDIT, existing.organizationId)
        return patients.update(
            id = patientId,
            firstName = Inputs.requiredText(body.firstName, "firstName"),
            lastName = Inputs.requiredText(body.lastName, "lastName"),
            dateOfBirth = requirePastDate(Inputs.requiredDate(body.dateOfBirth, "dateOfBirth")),
            sex = normaliseSex(body.sex),
            addressLine1 = Inputs.optionalText(body.addressLine1),
            addressLine2 = Inputs.optionalText(body.addressLine2),
            city = Inputs.optionalText(body.city),
            state = Inputs.optionalText(body.state),
            postalCode = Inputs.optionalText(body.postalCode),
            phone = Inputs.optionalText(body.phone),
        ) ?: throw NotFoundException("Patient not found")
    }

    /**
     * The practice a new patient belongs to is the tenant rule shared with every
     * other tenant-owned record; see [AuthorizationService.resolveWriteOrganization].
     */
    private fun requireExisting(patientId: Int): Patient =
        patients.findById(patientId) ?: throw NotFoundException("Patient not found")

    private fun requirePastDate(dateOfBirth: LocalDate): LocalDate {
        if (dateOfBirth.isAfter(LocalDate.now())) {
            throw ValidationException("dateOfBirth cannot be in the future")
        }
        return dateOfBirth
    }

    /**
     * A small closed set rather than free text, so the field stays usable for
     * payer and FHIR mapping later. Provisional; revisit when the import lands.
     */
    private fun normaliseSex(raw: String?): String? {
        val value = Inputs.optionalText(raw)?.uppercase() ?: return null
        if (value !in SEXES) {
            throw ValidationException("sex must be one of: ${SEXES.joinToString(", ")}")
        }
        return value
    }

    private companion object {
        val SEXES = setOf("MALE", "FEMALE", "OTHER", "UNKNOWN")
    }
}
