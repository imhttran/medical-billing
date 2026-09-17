package com.htt.billing.fhir

import ca.uhn.fhir.parser.DataFormatException
import com.htt.billing.common.error.Issue
import com.htt.billing.common.error.ValidationException
import com.htt.billing.common.error.ValidationIssuesException
import com.htt.billing.coverage.CoverageRepository
import com.htt.billing.coverage.PayerRepository
import com.htt.billing.patient.PatientRepository
import com.htt.billing.practice.ProviderRepository
import com.htt.billing.security.AuthorizationService
import com.htt.billing.security.Permissions
import java.time.LocalDate
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.ContactPoint
import org.hl7.fhir.r4.model.Coverage
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Practitioner
import org.hl7.fhir.r4.model.Resource
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * FHIR import: a Bundle of Patients, Practitioners and Coverages from another
 * system, reconciled onto the practice's own records.
 *
 * The whole bundle is one transaction and one answer. Half an import is worse than
 * none — a practice that has the patient but not their coverage cannot bill — so a
 * bundle with a problem in it is refused with every problem listed, and nothing is
 * written. Resource types this does not handle (an Encounter, an Organization) are
 * reported as skipped rather than refused: an export carries more than we need.
 *
 * Reconciliation is by the identifier the source system used, which is why
 * `external_id` exists on patients and providers; a Coverage has no identifier of
 * its own, so it reconciles on the member it names. A record the practice already
 * holds is updated rather than duplicated.
 */
@Service
class FhirImportService(
    private val fhir: FhirResources,
    private val patients: PatientRepository,
    private val providers: ProviderRepository,
    private val coverages: CoverageRepository,
    private val payers: PayerRepository,
    private val authorization: AuthorizationService,
    private val transactions: TransactionTemplate,
) {

    /** What happened to one resource, for the caller to log or show. */
    data class Outcome(val resourceType: String, val action: String, val id: Int, val identifier: String?)

    data class Summary(val organizationId: Int, val imported: List<Outcome>, val skipped: List<String>)

    fun importBundle(userId: Int, requestedOrganizationId: Int, body: ByteArray): Summary {
        val organizationId = authorization.resolveWriteOrganization(
            userId,
            Permissions.FHIR_IMPORT,
            requestedOrganizationId,
        )
        if (body.isEmpty()) {
            throw ValidationException("A FHIR Bundle is required")
        }
        val bundle = try {
            fhir.parser().parseResource(String(body, Charsets.UTF_8))
        } catch (notFhir: DataFormatException) {
            throw ValidationException("The body is not a FHIR resource: ${notFhir.message}")
        }
        if (bundle !is Bundle) {
            throw ValidationException("The body must be a Bundle, not a ${bundle.fhirType()}")
        }

        val entries = bundle.entry.mapNotNull { it.resource }
        val issues = mutableListOf<Issue>()
        val outcomes = mutableListOf<Outcome>()
        val skipped = entries.map { it.resourceType.name }.filter { it !in HANDLED }.distinct()

        transactions.executeWithoutResult {
            // Order matters: a coverage names a patient, and both name a practice.
            val patientIds = mutableMapOf<String, Int>()
            entries.filterIsInstance<Patient>().forEach { resource ->
                upsertPatient(organizationId, resource, patientIds, issues)?.let { outcomes += it }
            }
            entries.filterIsInstance<Practitioner>().forEach { resource ->
                upsertPractitioner(organizationId, resource, issues)?.let { outcomes += it }
            }
            entries.filterIsInstance<Coverage>().forEach { resource ->
                upsertCoverage(organizationId, resource, patientIds, issues)?.let { outcomes += it }
            }
            if (issues.isNotEmpty()) {
                // Rolls the transaction back, so nothing half-applied survives.
                throw ValidationIssuesException(issues)
            }
        }

        return Summary(organizationId, outcomes, skipped)
    }

    /**
     * A patient, reconciled on the identifier the source system used.
     *
     * @return what happened, or null when the resource was refused — the issues
     *         collected alongside say why.
     */
    private fun upsertPatient(
        organizationId: Int,
        resource: Patient,
        patientIds: MutableMap<String, Int>,
        issues: MutableList<Issue>,
    ): Outcome? {
        val identifier = resource.identifier.firstOrNull { !it.value.isNullOrBlank() }
        if (identifier == null) {
            issues += Issue(PATIENT_NO_IDENTIFIER, "A Patient needs an identifier to be reconciled on")
            return null
        }
        val externalId = FhirMapping.externalIdentifier(identifier.system, identifier.value)
        val name = resource.nameFirstRep
        if (name.family.isNullOrBlank()) {
            issues += Issue(PATIENT_NO_NAME, "Patient $externalId has no family name")
            return null
        }
        val birthDate = resource.birthDateElement.value?.let { LocalDate.ofInstant(it.toInstant(), ZONE) }
        if (birthDate == null) {
            issues += Issue(PATIENT_NO_BIRTH_DATE, "Patient $externalId has no date of birth")
            return null
        }

        val address = resource.addressFirstRep
        val existing = patients.findByExternalId(organizationId, externalId)
        val saved = if (existing == null) {
            patients.insert(
                organizationId = organizationId,
                externalId = externalId,
                firstName = name.given.firstOrNull()?.value.orEmpty(),
                lastName = name.family,
                dateOfBirth = birthDate,
                sex = FhirMapping.sex(resource.gender),
                addressLine1 = address.line.firstOrNull()?.value,
                addressLine2 = address.line.getOrNull(1)?.value,
                city = address.city,
                state = address.state,
                postalCode = address.postalCode,
                phone = resource.telecom.firstOrNull { it.system == ContactPoint.ContactPointSystem.PHONE }?.value,
            )
        } else {
            patients.update(
                id = existing.id,
                firstName = name.given.firstOrNull()?.value.orEmpty(),
                lastName = name.family,
                dateOfBirth = birthDate,
                sex = FhirMapping.sex(resource.gender),
                addressLine1 = address.line.firstOrNull()?.value,
                addressLine2 = address.line.getOrNull(1)?.value,
                city = address.city,
                state = address.state,
                postalCode = address.postalCode,
                phone = resource.telecom.firstOrNull { it.system == ContactPoint.ContactPointSystem.PHONE }?.value,
            )
        }
        if (saved == null) {
            issues += Issue(PATIENT_NO_NAME, "Patient $externalId could not be written")
            return null
        }

        // So a coverage in the same bundle can find its patient. A reference and the
        // resource it points at are matched on the last segment of the reference —
        // `Patient/p1` and `p1` are the same resource, and HAPI rewrites the entry's
        // own id to the first form.
        patientIds[externalId] = saved.id
        referenceKey(resource.id)?.let { patientIds[it] = saved.id }
        referenceKey(resource.idElement?.value)?.let { patientIds[it] = saved.id }
        return Outcome(PATIENT, if (existing == null) ACTION_CREATED else ACTION_UPDATED, saved.id, externalId)
    }

    /**
     * A practitioner. Reconciled on the source system's identifier when there is one,
     * and on the NPI otherwise: a Practitioner with neither cannot be told apart from
     * a stranger, so it is taken to be a new provider.
     */
    private fun upsertPractitioner(
        organizationId: Int,
        resource: Practitioner,
        issues: MutableList<Issue>,
    ): Outcome? {
        val identifier = resource.identifier.firstOrNull { !it.value.isNullOrBlank() }
        val externalId = identifier?.let { FhirMapping.externalIdentifier(it.system, it.value) }
        val npi = resource.identifier.firstOrNull { it.system == FhirMapping.Systems.NPI }?.value
        val name = resource.nameFirstRep
        if (name.family.isNullOrBlank()) {
            issues += Issue(PRACTITIONER_NO_NAME, "A Practitioner needs a family name")
            return null
        }

        val existing = when {
            externalId != null -> providers.findByExternalId(organizationId, externalId)
            npi != null -> providers.findByNpi(organizationId, npi)
            else -> null
        }
        val taxonomy = resource.qualification.firstOrNull()?.code?.codingFirstRep?.code
        val saved = if (existing == null) {
            providers.insert(
                organizationId = organizationId,
                firstName = name.given.firstOrNull()?.value.orEmpty(),
                lastName = name.family,
                npi = npi,
                taxonomyCode = taxonomy,
                externalId = externalId,
            )
        } else {
            providers.update(
                id = existing.id,
                firstName = name.given.firstOrNull()?.value.orEmpty(),
                lastName = name.family,
                npi = npi,
                taxonomyCode = taxonomy,
                externalId = externalId,
            )
        }
        if (saved == null) {
            issues += Issue(PRACTITIONER_NO_NAME, "Practitioner ${externalId ?: name.family} could not be written")
            return null
        }
        return Outcome(PRACTITIONER, if (existing == null) ACTION_CREATED else ACTION_UPDATED, saved.id, externalId)
    }

    /**
     * A coverage, reconciled on the member and payer it names — it has no identifier
     * of its own, and those two are what identify it.
     *
     * Its patient is found by the identifier on the beneficiary reference, and
     * failing that by a reference to a Patient in this same bundle. Its payer is
     * found by the payor's identifier (the payer code) or its display name, because
     * payers are reference data the practice cannot invent from an import.
     */
    private fun upsertCoverage(
        organizationId: Int,
        resource: Coverage,
        patientIds: Map<String, Int>,
        issues: MutableList<Issue>,
    ): Outcome? {
        val memberId = resource.identifier.firstOrNull { !it.value.isNullOrBlank() }?.value
        if (memberId.isNullOrBlank()) {
            issues += Issue(COVERAGE_NO_MEMBER_ID, "A Coverage needs a member identifier")
            return null
        }

        val beneficiary = resource.beneficiary
        // The reference's identifier is the same identifier the Patient was imported
        // with, so it is joined the same way before it is looked up; failing that, the
        // reference may point straight at a Patient entry in this bundle.
        val beneficiaryIdentifier = beneficiary.identifier?.value?.let {
            FhirMapping.externalIdentifier(beneficiary.identifier.system, it)
        }
        val referredPatientId = beneficiaryIdentifier
            ?.let { patients.findByExternalId(organizationId, it)?.id }
            ?: referenceKey(beneficiary.reference)?.let { patientIds[it] }
        val patient = referredPatientId?.let { patients.findById(it) }
        if (patient == null) {
            issues += Issue(
                COVERAGE_NO_PATIENT,
                "Coverage $memberId does not name a patient in this import or in this practice",
            )
            return null
        }

        val payor = resource.payor.firstOrNull()
        val payer = payor?.identifier?.value?.let { payers.findActiveByCode(it) }
            ?: payor?.display?.let { payers.findActiveByName(it) }
        if (payor == null || payer == null) {
            issues += Issue(
                COVERAGE_NO_PAYER,
                "Coverage $memberId names a payor this practice does not know: " +
                        (payor?.identifier?.value ?: payor?.display ?: "none"),
            )
            return null
        }

        val groupNumber = resource.getGroupNumber()
        val effectiveDate = resource.period?.start?.let { LocalDate.ofInstant(it.toInstant(), ZONE) }
        val terminationDate = resource.period?.end?.let { LocalDate.ofInstant(it.toInstant(), ZONE) }

        val existing = coverages.findByMember(patient.id, payer.id, memberId)
        val saved = if (existing == null) {
            coverages.insert(
                organizationId = organizationId,
                patientId = patient.id,
                payerId = payer.id,
                memberId = memberId,
                groupNumber = groupNumber,
                subscriberName = resource.subscriber?.display,
                relationshipToSubscriber = resource.relationship?.codingFirstRep?.code,
                effectiveDate = effectiveDate,
                terminationDate = terminationDate,
                // R4's `order` is zero-based; our priority starts at one.
                priority = if (resource.hasOrder()) resource.order + 1 else 1,
            )
        } else {
            coverages.update(
                id = existing.id,
                payerId = payer.id,
                memberId = memberId,
                groupNumber = groupNumber,
                subscriberName = resource.subscriber?.display ?: existing.subscriberName,
                relationshipToSubscriber = resource.relationship?.codingFirstRep?.code
                    ?: existing.relationshipToSubscriber,
                effectiveDate = effectiveDate ?: existing.effectiveDate,
                terminationDate = terminationDate ?: existing.terminationDate,
                priority = existing.priority,
                active = existing.active,
            )
        }
        if (saved == null) {
            issues += Issue(COVERAGE_NO_MEMBER_ID, "Coverage $memberId could not be written")
            return null
        }
        return Outcome(COVERAGE, if (existing == null) ACTION_CREATED else ACTION_UPDATED, saved.id, memberId)
    }

    /** The id a reference and a resource agree on, whatever shape each was written in. */
    private fun referenceKey(reference: String?): String? =
        reference?.trim()?.takeIf { it.isNotEmpty() }?.substringAfterLast('/')

    /** The group number, which R4 carries as a `class` entry of type `group`. */
    private fun Coverage.getGroupNumber(): String? = getClass_()
        .firstOrNull { it.type?.codingFirstRep?.code == "group" }
        ?.value

    companion object {
        const val PATIENT = "Patient"
        const val PRACTITIONER = "Practitioner"
        const val COVERAGE = "Coverage"

        const val ACTION_CREATED = "created"
        const val ACTION_UPDATED = "updated"

        const val PATIENT_NO_IDENTIFIER = "FHIR_PATIENT_NO_IDENTIFIER"
        const val PATIENT_NO_NAME = "FHIR_PATIENT_NO_NAME"
        const val PATIENT_NO_BIRTH_DATE = "FHIR_PATIENT_NO_BIRTH_DATE"
        const val PRACTITIONER_NO_NAME = "FHIR_PRACTITIONER_NO_NAME"
        const val COVERAGE_NO_MEMBER_ID = "FHIR_COVERAGE_NO_MEMBER_ID"
        const val COVERAGE_NO_PATIENT = "FHIR_COVERAGE_NO_PATIENT"
        const val COVERAGE_NO_PAYER = "FHIR_COVERAGE_NO_PAYER"

        private val HANDLED = setOf(PATIENT, PRACTITIONER, COVERAGE)
        private val ZONE = java.time.ZoneOffset.UTC
    }
}
