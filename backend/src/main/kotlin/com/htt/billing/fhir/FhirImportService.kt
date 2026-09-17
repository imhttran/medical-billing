package com.htt.billing.fhir

import ca.uhn.fhir.parser.DataFormatException
import com.htt.billing.claim.ClaimRepository
import com.htt.billing.claim.ClaimStatus
import com.htt.billing.common.error.Issue
import com.htt.billing.common.error.ValidationException
import com.htt.billing.common.error.ValidationIssuesException
import com.htt.billing.coverage.CoverageRepository
import com.htt.billing.coverage.PayerRepository
import com.htt.billing.patient.PatientRepository
import com.htt.billing.practice.ProviderRepository
import com.htt.billing.security.AuthorizationService
import com.htt.billing.security.Permissions
import java.math.BigDecimal
import java.time.LocalDate
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Claim
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.ContactPoint
import org.hl7.fhir.r4.model.Coverage
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Practitioner
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * FHIR import: a Bundle of Patients, Practitioners, Coverages and Claims from
 * another system, reconciled onto the practice's own records.
 *
 * The whole bundle is one transaction and one answer. Half an import is worse than
 * none — a practice that has the patient but not their coverage cannot bill — so a
 * bundle with a problem in it is refused with every problem listed, and nothing is
 * written. Resource types this does not handle (an Encounter, an Organization) are
 * reported as skipped rather than refused: an export carries more than we need.
 *
 * Reconciliation is by the identifier the source system used, which is why
 * `external_id` exists on patients and providers; a Coverage has no identifier of
 * its own, so it reconciles on the member it names, and a Claim on its claim
 * number. A record the practice already holds is updated rather than duplicated.
 *
 * A resource may also be referred to by a reference into the same bundle, which is
 * how a sender links a claim to a patient it is sending in the same breath.
 */
@Service
class FhirImportService(
    private val fhir: FhirResources,
    private val patients: PatientRepository,
    private val providers: ProviderRepository,
    private val coverages: CoverageRepository,
    private val payers: PayerRepository,
    private val claims: ClaimRepository,
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
            fhir.parser().parseResource(hapiSpelling(String(body, Charsets.UTF_8)))
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
            // Order matters: a coverage names a patient, both name a practice, and a
            // claim names all three. Each resource that has been written records the
            // ids it can be referred to by, so a later entry in the bundle can point
            // at it.
            val patientIds = mutableMapOf<String, Int>()
            val providerIds = mutableMapOf<String, Int>()
            val coverageIds = mutableMapOf<String, Int>()
            entries.filterIsInstance<Patient>().forEach { resource ->
                upsertPatient(organizationId, resource, patientIds, issues)?.let { outcomes += it }
            }
            entries.filterIsInstance<Practitioner>().forEach { resource ->
                upsertPractitioner(organizationId, resource, providerIds, issues)?.let { outcomes += it }
            }
            entries.filterIsInstance<Coverage>().forEach { resource ->
                upsertCoverage(organizationId, resource, patientIds, coverageIds, issues)?.let { outcomes += it }
            }
            entries.filterIsInstance<Claim>().forEach { resource ->
                upsertClaim(organizationId, resource, patientIds, providerIds, coverageIds, issues)
                    ?.let { outcomes += it }
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
        providerIds: MutableMap<String, Int>,
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
        externalId?.let { providerIds[it] = saved.id }
        npi?.let { providerIds[it] = saved.id }
        referenceKey(resource.id)?.let { providerIds[it] = saved.id }
        referenceKey(resource.idElement?.value)?.let { providerIds[it] = saved.id }
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
        coverageIds: MutableMap<String, Int>,
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
        coverageIds[memberId] = saved.id
        referenceKey(resource.id)?.let { coverageIds[it] = saved.id }
        referenceKey(resource.idElement?.value)?.let { coverageIds[it] = saved.id }
        return Outcome(COVERAGE, if (existing == null) ACTION_CREATED else ACTION_UPDATED, saved.id, memberId)
    }

    /**
     * A claim, reconciled on its claim number: the identifier a claim carries
     * between systems, and the only thing about it another system can know.
     *
     * The status is not taken from the resource. An imported claim arrives as a
     * draft for the practice to check, validate and send — the state machine in
     * [ClaimStatus] is the only writer of a status, and an incoming resource does
     * not get to put a claim into a state it cannot be in. The payer comes from the
     * coverage, as it does when a claim is written on the claim screen, so the two
     * can never disagree.
     */
    private fun upsertClaim(
        organizationId: Int,
        resource: Claim,
        patientIds: Map<String, Int>,
        providerIds: Map<String, Int>,
        coverageIds: Map<String, Int>,
        issues: MutableList<Issue>,
    ): Outcome? {
        val claimNumber = resource.identifier.firstOrNull { !it.value.isNullOrBlank() }?.value
            ?: referenceKey(resource.idElement?.idPart)
        if (claimNumber.isNullOrBlank()) {
            issues += Issue(CLAIM_NO_NUMBER, "A Claim needs a claim number to be reconciled on")
            return null
        }

        val existing = claims.findByClaimNumber(claimNumber)
        if (existing != null && existing.organizationId != organizationId) {
            // Claim numbers are unique across practices, so this is a number this
            // practice cannot use rather than a claim it can update.
            issues += Issue(CLAIM_NUMBER_TAKEN, "Claim $claimNumber is already used by another practice")
            return null
        }
        if (existing != null && !ClaimStatus.isEditable(existing.status)) {
            issues += Issue(
                CLAIM_NOT_EDITABLE,
                "Claim $claimNumber is ${existing.status}, so it can no longer be imported onto",
            )
            return null
        }

        val patientId = referredRow(
            reference = resource.patient,
            bundleIds = patientIds,
            byIdentifier = { patients.findByExternalId(organizationId, it)?.id },
            byRowId = { patients.findById(it)?.takeIf { row -> row.organizationId == organizationId }?.id },
        )
        if (patientId == null) {
            issues += Issue(
                CLAIM_NO_PATIENT,
                "Claim $claimNumber does not name a patient in this import or in this practice",
            )
            return null
        }

        val providerId = referredRow(
            reference = resource.provider,
            bundleIds = providerIds,
            byIdentifier = { identifier ->
                val npi = FhirMapping.splitIdentifier(identifier)
                    ?.takeIf { (system, _) -> system == FhirMapping.Systems.NPI }
                // The NPI is what a provider is named by between systems; failing
                // that, the source system's own identifier.
                npi?.let { providers.findByNpi(organizationId, it.second)?.id }
                    ?: providers.findByExternalId(organizationId, identifier)?.id
            },
            byRowId = { providers.findById(it)?.takeIf { row -> row.organizationId == organizationId }?.id },
        )
        if (providerId == null) {
            issues += Issue(
                CLAIM_NO_PROVIDER,
                "Claim $claimNumber does not name a provider in this import or in this practice",
            )
            return null
        }

        // The focal insurance is the primary one, whatever order the sender listed
        // them in; a claim here is billed against one coverage.
        val primary = resource.insurance.firstOrNull { it.focal } ?: resource.insurance.firstOrNull()
        val coverageId = primary?.coverage?.let { reference ->
            referredRow(
                reference = reference,
                bundleIds = coverageIds,
                byIdentifier = { identifier ->
                    // A coverage has no identifier of its own: the member is what it
                    // reconciles on, and only within this patient's coverages.
                    val memberId = FhirMapping.splitIdentifier(identifier)?.second
                    coverages.findByPatient(patientId).firstOrNull { it.memberId == memberId }?.id
                },
                byRowId = { coverages.findById(it)?.takeIf { row -> row.organizationId == organizationId }?.id },
            )
        }
        val coverage = coverageId?.let { coverages.findById(it) }
        if (coverage == null) {
            // Without a coverage there is no payer, and the payer is not taken from
            // the resource: the claim and its coverage could then disagree.
            issues += Issue(
                CLAIM_NO_COVERAGE,
                "Claim $claimNumber does not name a coverage in this import or in this practice",
            )
            return null
        }

        val lines = resource.item.mapIndexedNotNull { index, item ->
            lineOf(index, item, claimNumber, issues)
        }
        if (lines.size != resource.item.size) {
            // The issues name every line that could not be written.
            return null
        }
        val diagnoses = resource.diagnosis
            // A CodeableConcept, not a reference to a Condition: only the code is
            // imported, and the cast is how HAPI's choice element is read.
            .mapNotNull { (it.diagnosis as? CodeableConcept)?.codingFirstRep?.code }
            .filter { it.isNotBlank() }
            .distinct()
        val serviceDate = resource.billablePeriod?.start?.let { LocalDate.ofInstant(it.toInstant(), ZONE) }

        val saved = if (existing == null) {
            claims.insert(
                organizationId = organizationId,
                patientId = patientId,
                providerId = providerId,
                coverageId = coverage.id,
                payerId = coverage.payerId,
                serviceDate = serviceDate,
                claimNumber = claimNumber,
            )
        } else {
            claims.updateHeader(
                id = existing.id,
                patientId = patientId,
                providerId = providerId,
                coverageId = coverage.id,
                payerId = coverage.payerId,
                serviceDate = serviceDate,
            )
        }
        if (saved == null) {
            issues += Issue(CLAIM_NOT_EDITABLE, "Claim $claimNumber could not be written")
            return null
        }
        claims.replaceDiagnoses(saved.id, diagnoses)
        claims.replaceLines(saved.id, lines)
        // Editing a rejected claim is the correction itself, exactly as it is on
        // the claim screen; nothing else may touch a rejected claim.
        if (saved.status == ClaimStatus.REJECTED) {
            claims.updateStatus(saved.id, ClaimStatus.CORRECTED, submittedAt = null)
        }
        return Outcome(CLAIM, if (existing == null) ACTION_CREATED else ACTION_UPDATED, saved.id, claimNumber)
    }

    /**
     * One service line, or null when it cannot be written — the issue says why.
     *
     * `net` is the line's total when the sender wrote one; a sender that priced by
     * the unit instead gets its arithmetic done for it rather than a charge guessed
     * from the unit price alone.
     */
    private fun lineOf(
        index: Int,
        item: Claim.ItemComponent,
        claimNumber: String,
        issues: MutableList<Issue>,
    ): ClaimRepository.LineInput? {
        val number = index + 1
        val procedureCode = item.productOrService?.codingFirstRep?.code
        if (procedureCode.isNullOrBlank()) {
            issues += Issue(CLAIM_LINE_NO_PROCEDURE, "Line $number of claim $claimNumber has no procedure code")
            return null
        }
        val quantity = item.quantity?.value?.toInt() ?: 1
        val charge = item.net?.value ?: item.unitPrice?.value?.multiply(BigDecimal(quantity))
        if (charge == null) {
            issues += Issue(CLAIM_LINE_NO_CHARGE, "Line $number of claim $claimNumber has no charge")
            return null
        }
        return ClaimRepository.LineInput(number, procedureCode, quantity, charge)
    }

    /**
     * Who a reference names, tried most specific first: an entry in this bundle, an
     * identifier the practice already holds, then one of the practice's own rows.
     * Null when none of the three knows it, which is a refusal rather than a guess.
     */
    private fun referredRow(
        reference: Reference,
        bundleIds: Map<String, Int>,
        byIdentifier: (String) -> Int?,
        byRowId: (Int) -> Int?,
    ): Int? = referenceKey(reference.reference)?.let { bundleIds[it] }
        ?: joinedIdentifier(reference)?.let(byIdentifier)
        ?: referenceKey(reference.reference)?.toIntOrNull()?.let(byRowId)

    /** The identifier on a reference, joined the way our own columns hold one. */
    private fun joinedIdentifier(reference: Reference): String? =
        reference.identifier?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { FhirMapping.externalIdentifier(reference.identifier.system, it) }

    /**
     * HAPI's R4 model types `Claim.diagnosis.diagnosis` as a choice and spells the
     * element `diagnosisCodeableConcept` in JSON, where R4 spells it `diagnosis`.
     * HAPI throws the name it does not recognise away — with a warning on the log and
     * nothing else — so a claim from a conformant sender would arrive with no
     * diagnoses at all. The element is renamed to HAPI's spelling before the body is
     * parsed, which is the one place the two vocabularies differ. The nested
     * diagnosis of an ExplanationOfBenefit is the same element and is renamed with it.
     */
    private fun hapiSpelling(body: String): String =
        DIAGNOSIS_ELEMENT.replace(body) { match -> "\"diagnosisCodeableConcept\"${match.groupValues[1]}{" }

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
        const val CLAIM = "Claim"

        const val ACTION_CREATED = "created"
        const val ACTION_UPDATED = "updated"

        const val PATIENT_NO_IDENTIFIER = "FHIR_PATIENT_NO_IDENTIFIER"
        const val PATIENT_NO_NAME = "FHIR_PATIENT_NO_NAME"
        const val PATIENT_NO_BIRTH_DATE = "FHIR_PATIENT_NO_BIRTH_DATE"
        const val PRACTITIONER_NO_NAME = "FHIR_PRACTITIONER_NO_NAME"
        const val COVERAGE_NO_MEMBER_ID = "FHIR_COVERAGE_NO_MEMBER_ID"
        const val COVERAGE_NO_PATIENT = "FHIR_COVERAGE_NO_PATIENT"
        const val COVERAGE_NO_PAYER = "FHIR_COVERAGE_NO_PAYER"
        const val CLAIM_NO_NUMBER = "FHIR_CLAIM_NO_NUMBER"
        const val CLAIM_NUMBER_TAKEN = "FHIR_CLAIM_NUMBER_TAKEN"
        const val CLAIM_NOT_EDITABLE = "FHIR_CLAIM_NOT_EDITABLE"
        const val CLAIM_NO_PATIENT = "FHIR_CLAIM_NO_PATIENT"
        const val CLAIM_NO_PROVIDER = "FHIR_CLAIM_NO_PROVIDER"
        const val CLAIM_NO_COVERAGE = "FHIR_CLAIM_NO_COVERAGE"
        const val CLAIM_LINE_NO_PROCEDURE = "FHIR_CLAIM_LINE_NO_PROCEDURE"
        const val CLAIM_LINE_NO_CHARGE = "FHIR_CLAIM_LINE_NO_CHARGE"

        /** `"diagnosis": {` — the element itself, never the array it sits in. */
        private val DIAGNOSIS_ELEMENT = Regex("\"diagnosis\"(\\s*:\\s*)\\{")

        private val HANDLED = setOf(PATIENT, PRACTITIONER, COVERAGE, CLAIM)
        private val ZONE = java.time.ZoneOffset.UTC
    }
}
