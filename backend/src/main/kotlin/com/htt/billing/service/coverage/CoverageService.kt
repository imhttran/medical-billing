package com.htt.billing.service.coverage

import com.htt.billing.api.coverage.CoverageBody
import com.htt.billing.common.Inputs
import com.htt.billing.common.error.NotFoundException
import com.htt.billing.common.error.ValidationException
import com.htt.billing.repository.coverage.CoverageRepository
import com.htt.billing.repository.coverage.CoverageRepository.Coverage
import com.htt.billing.repository.coverage.PayerRepository
import com.htt.billing.repository.coverage.PayerRepository.Payer
import com.htt.billing.repository.patient.PatientRepository
import com.htt.billing.repository.patient.PatientRepository.Patient
import com.htt.billing.security.Permissions
import com.htt.billing.service.security.AuthorizationService
import java.time.LocalDate
import org.springframework.stereotype.Service

/**
 * Patient coverage. A coverage's practice is its patient's practice, read from
 * the patient row — the browser never supplies it.
 *
 * Out-of-scope reads answer "not found", the same as in [com.htt.billing.service.patient.PatientService].
 */
@Service
class CoverageService(
    private val coverages: CoverageRepository,
    private val patients: PatientRepository,
    private val payers: PayerRepository,
    private val authorization: AuthorizationService,
) {

    fun list(userId: Int, patientId: Int): List<Coverage> {
        val patient = requirePatient(patientId)
        authorization.requireVisible(userId, Permissions.COVERAGE_VIEW, patient.organizationId, "Patient not found")
        return coverages.findByPatient(patient.id)
    }

    fun create(userId: Int, patientId: Int, body: CoverageBody): Coverage {
        val patient = requirePatient(patientId)
        requireCoverageAccess(userId, patient.organizationId)
        val payer = requireActivePayer(body.payerId)
        val (effectiveDate, terminationDate) = validatedDates(body)
        return coverages.insert(
            organizationId = patient.organizationId,
            patientId = patient.id,
            payerId = payer.id,
            memberId = Inputs.requiredText(body.memberId, "memberId"),
            groupNumber = Inputs.optionalText(body.groupNumber),
            subscriberName = Inputs.optionalText(body.subscriberName),
            relationshipToSubscriber = Inputs.optionalText(body.relationshipToSubscriber),
            effectiveDate = effectiveDate,
            terminationDate = terminationDate,
            priority = requirePriority(body.priority),
        )
    }

    fun update(userId: Int, coverageId: Int, body: CoverageBody): Coverage {
        val existing = coverages.findById(coverageId) ?: throw NotFoundException("Coverage not found")
        requireCoverageAccess(userId, existing.organizationId)
        val payer = requireActivePayer(body.payerId)
        val (effectiveDate, terminationDate) = validatedDates(body)
        return coverages.update(
            id = coverageId,
            payerId = payer.id,
            memberId = Inputs.requiredText(body.memberId, "memberId"),
            groupNumber = Inputs.optionalText(body.groupNumber),
            subscriberName = Inputs.optionalText(body.subscriberName),
            relationshipToSubscriber = Inputs.optionalText(body.relationshipToSubscriber),
            effectiveDate = effectiveDate,
            terminationDate = terminationDate,
            priority = requirePriority(body.priority),
            active = body.active,
        ) ?: throw NotFoundException("Coverage not found")
    }

    private fun requirePatient(patientId: Int): Patient =
        patients.findById(patientId) ?: throw NotFoundException("Patient not found")

    /**
     * Visibility first, then the action. Seeing the patient is not permission to
     * change its coverage, so the two are answered differently — "not found" and
     * "forbidden" respectively.
     */
    private fun requireCoverageAccess(userId: Int, organizationId: Int) {
        authorization.requireVisible(userId, Permissions.COVERAGE_VIEW, organizationId, "Patient not found")
        authorization.require(userId, Permissions.COVERAGE_EDIT, organizationId)
    }

    private fun requireActivePayer(payerId: Int): Payer {
        if (payerId <= 0) {
            throw ValidationException("payerId is required")
        }
        return payers.findActiveById(payerId)
            ?: throw ValidationException("Unknown or inactive payer")
    }

    private fun requirePriority(priority: Int): Int {
        if (priority < 1) {
            throw ValidationException("priority must be 1 or greater")
        }
        return priority
    }

    private fun validatedDates(body: CoverageBody): Pair<LocalDate?, LocalDate?> {
        val effective = Inputs.optionalDate(body.effectiveDate, "effectiveDate")
        val termination = Inputs.optionalDate(body.terminationDate, "terminationDate")
        if (effective != null && termination != null && termination.isBefore(effective)) {
            throw ValidationException("terminationDate cannot be before effectiveDate")
        }
        return effective to termination
    }
}
