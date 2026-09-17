package com.htt.billing.service.fhir

import com.htt.billing.claim.ClaimStatus
import com.htt.billing.common.error.NotFoundException
import com.htt.billing.fhir.FhirMapping
import com.htt.billing.fhir.FhirResources
import com.htt.billing.repository.adjudication.AdjudicationRepository
import com.htt.billing.repository.claim.ClaimRepository
import com.htt.billing.repository.coverage.CoverageRepository
import com.htt.billing.repository.coverage.PayerRepository
import com.htt.billing.repository.patient.PatientRepository
import com.htt.billing.repository.payment.PaymentRepository
import com.htt.billing.repository.practice.ProviderRepository
import com.htt.billing.security.Permissions
import com.htt.billing.service.security.AuthorizationService
import java.math.BigDecimal
import java.time.LocalDate
import org.hl7.fhir.r4.model.Claim
import org.hl7.fhir.r4.model.ClaimResponse
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DomainResource
import org.hl7.fhir.r4.model.ExplanationOfBenefit
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Organization
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Service

/**
 * FHIR export: a claim as the payer would receive it, and what the payer made of
 * it — as an ExplanationOfBenefit for the practice to read, and as the
 * ClaimResponse a payer sends back.
 *
 * The three resources are the same facts read different ways. The Claim is what the
 * practice billed; the other two are what the payer answered, and they only exist
 * once the payer has answered — a rejected claim has nothing to explain. The
 * ClaimResponse carries the same figures as the ExplanationOfBenefit from one list
 * of adjudications, so the two cannot say different things.
 *
 * The payer is exported as a contained Organization rather than a reference to
 * something on a server we do not have, so the resource stands on its own.
 */
@Service
class FhirExportService(
    private val fhir: FhirResources,
    private val claims: ClaimRepository,
    private val patients: PatientRepository,
    private val providers: ProviderRepository,
    private val coverages: CoverageRepository,
    private val payers: PayerRepository,
    private val adjudications: AdjudicationRepository,
    private val payments: PaymentRepository,
    private val authorization: AuthorizationService,
) {

    /** Everything one export needs, read once. */
    private data class Facts(
        val claim: ClaimRepository.Claim,
        val patient: PatientRepository.Patient,
        val provider: ProviderRepository.Provider,
        val coverage: CoverageRepository.Coverage,
        val payer: PayerRepository.Payer,
        val diagnoses: List<ClaimRepository.Diagnosis>,
        val lines: List<ClaimRepository.Line>,
        val adjudication: AdjudicationRepository.Adjudication?,
        val insurancePayments: List<PaymentRepository.InsurancePayment>,
    )

    /** @return the claim as FHIR R4 JSON. */
    fun claim(userId: Int, claimId: Int): String =
        fhir.parser().encodeResourceToString(claimResource(factsFor(userId, claimId)))

    /** @return the payer's answer as FHIR R4 JSON, which needs one to exist. */
    fun explanationOfBenefit(userId: Int, claimId: Int): String {
        val facts = factsFor(userId, claimId)
        val adjudication = adjudicationFor(facts)
        return fhir.parser().encodeResourceToString(eobResource(facts, adjudication))
    }

    /**
     * @return the payer's answer as the ClaimResponse a payer sends back, which is
     *         the same answer the ExplanationOfBenefit carries. Both exist because
     *         a payer's reply is read as one by a clearinghouse and as the other by
     *         the practice, and neither is a translation of the first.
     */
    fun claimResponse(userId: Int, claimId: Int): String {
        val facts = factsFor(userId, claimId)
        val adjudication = adjudicationFor(facts)
        return fhir.parser().encodeResourceToString(claimResponseResource(facts, adjudication))
    }

    private fun adjudicationFor(facts: Facts): AdjudicationRepository.Adjudication = facts.adjudication
        ?: throw NotFoundException("This claim has not been adjudicated, so there is nothing to explain")

    /**
     * Visibility first, then the export permission: a claim outside the caller's
     * practices is "not found", one they can see but may not export is a 403.
     */
    private fun factsFor(userId: Int, claimId: Int): Facts {
        val claim = claims.findById(claimId) ?: throw NotFoundException("Claim not found")
        authorization.requireVisible(userId, Permissions.CLAIM_VIEW, claim.organizationId, "Claim not found")
        authorization.require(userId, Permissions.FHIR_EXPORT, claim.organizationId)

        val patient = patients.findById(claim.patientId)
            ?: throw NotFoundException("The patient on this claim no longer exists")
        val provider = providers.findById(claim.providerId)
            ?: throw NotFoundException("The provider on this claim no longer exists")
        val coverage = coverages.findById(claim.coverageId)
            ?: throw NotFoundException("The coverage on this claim no longer exists")
        val payer = payers.findActiveById(claim.payerId)
            ?: throw NotFoundException("The payer on this claim is no longer active")

        return Facts(
            claim = claim,
            patient = patient,
            provider = provider,
            coverage = coverage,
            payer = payer,
            diagnoses = claims.findDiagnoses(claimId),
            lines = claims.findLines(claimId),
            adjudication = adjudications.findLatestForClaim(claimId),
            insurancePayments = payments.findInsuranceForClaim(claimId),
        )
    }

    private fun claimResource(facts: Facts): Claim {
        val claim = Claim()
        claim.id = facts.claim.claimNumber
        claim.addIdentifier(claimNumber(facts.claim.claimNumber))
        // Only a claim that has been written but not sent is a draft to FHIR; one
        // that was sent and refused is still an active claim, refused.
        claim.status =
            if (facts.claim.status == ClaimStatus.DRAFT) Claim.ClaimStatus.DRAFT
            else Claim.ClaimStatus.ACTIVE
        claim.use = Claim.Use.CLAIM
        claim.type = professional()
        claim.patient = patientReference(facts)
        claim.provider = providerReference(facts)
        claim.insurer = payerReference(facts, claim)
        claim.createdElement = FhirMapping.instant(facts.claim.createdAt)
        claim.priority = CodeableConcept(Coding(PRIORITY_SYSTEM, "normal", "Normal"))
        facts.claim.serviceDate?.let {
            claim.billablePeriod = Period().apply { startElement = FhirMapping.dateOnly(it) }
        }
        claim.addInsurance().apply {
            sequence = 1
            focal = true
            coverage = Reference("Coverage/${facts.coverage.id}")
        }

        facts.diagnoses.forEach { diagnosis ->
            claim.addDiagnosis().apply {
                sequence = diagnosis.sequence
                this.diagnosis = FhirMapping.icd10(diagnosis.diagnosisCode)
            }
        }

        facts.lines.forEach { line ->
            claim.addItem().apply {
                sequence = line.lineNumber
                productOrService = FhirMapping.procedure(line.procedureCode, line.procedureCodeSystem)
                quantity = FhirMapping.quantity(line.quantity)
                net = FhirMapping.money(line.chargeAmount)
                // The charge is the line's total, so it is a unit price only when
                // there is one unit of it. Anything else would be arithmetic we made up.
                if (line.quantity == 1) {
                    unitPrice = FhirMapping.money(line.chargeAmount)
                }
            }
        }

        claim.total = FhirMapping.money(facts.lines.fold(BigDecimal.ZERO) { total, line -> total + line.chargeAmount })
        return claim
    }

    private fun eobResource(
        facts: Facts,
        adjudication: AdjudicationRepository.Adjudication
    ): ExplanationOfBenefit {
        val eob = ExplanationOfBenefit()
        eob.id = facts.claim.claimNumber
        eob.addIdentifier(claimNumber(facts.claim.claimNumber))
        eob.status = ExplanationOfBenefit.ExplanationOfBenefitStatus.ACTIVE
        eob.use = ExplanationOfBenefit.Use.CLAIM
        eob.type = professional()
        // A denial is a complete adjudication, not an incomplete one.
        eob.outcome = ExplanationOfBenefit.RemittanceOutcome.COMPLETE
        eob.patient = patientReference(facts)
        eob.provider = providerReference(facts)
        eob.insurer = payerReference(facts, eob)
        eob.createdElement = FhirMapping.instant(adjudication.adjudicatedAt)
        facts.claim.serviceDate?.let {
            eob.billablePeriod = Period().apply { startElement = FhirMapping.dateOnly(it) }
        }
        eob.addInsurance().apply {
            focal = true
            coverage = Reference("Coverage/${facts.coverage.id}")
        }

        totals(adjudication).forEach { (category, amount) ->
            eob.addTotal().apply {
                this.category = FhirMapping.coded(FhirMapping.Systems.ADJUDICATION, category)
                this.amount = FhirMapping.money(amount)
            }
        }

        facts.lines.forEach { line ->
            eob.addItem().apply {
                sequence = line.lineNumber
                productOrService = FhirMapping.procedure(line.procedureCode, line.procedureCodeSystem)
                adjudications(line).forEach { (category, amount) ->
                    addAdjudication().apply {
                        this.category = FhirMapping.coded(FhirMapping.Systems.ADJUDICATION, category)
                        this.amount = FhirMapping.money(amount)
                    }
                }
            }
        }

        // What the payer actually sent, which is what makes the benefit a payment
        // rather than an intention.
        remittance(facts)?.let { (paid, date) ->
            eob.payment = ExplanationOfBenefit.PaymentComponent().apply {
                amount = FhirMapping.money(paid)
                date?.let { dateElement = FhirMapping.dateOnlyElement(it) }
            }
        }
        return eob
    }

    /**
     * The payer's answer in the shape a payer sends it: a ClaimResponse points at
     * the Claim it answers and prices it by line sequence, because the payer is
     * answering someone else's claim rather than restating it.
     */
    private fun claimResponseResource(
        facts: Facts,
        adjudication: AdjudicationRepository.Adjudication
    ): ClaimResponse {
        val response = ClaimResponse()
        response.id = facts.claim.claimNumber
        response.addIdentifier(claimNumber(facts.claim.claimNumber))
        response.status = ClaimResponse.ClaimResponseStatus.ACTIVE
        response.use = ClaimResponse.Use.CLAIM
        response.type = professional()
        // A denial is a complete adjudication, not an incomplete one.
        response.outcome = ClaimResponse.RemittanceOutcome.COMPLETE
        // Our claim export writes the claim number as the Claim's id, so this is the
        // same claim inside a Bundle of the two.
        response.request = Reference("Claim/${facts.claim.claimNumber}")
        response.patient = patientReference(facts)
        response.insurer = payerReference(facts, response)
        response.createdElement = FhirMapping.instant(adjudication.adjudicatedAt)
        response.addInsurance().apply {
            sequence = 1
            focal = true
            coverage = Reference("Coverage/${facts.coverage.id}")
        }

        totals(adjudication).forEach { (category, amount) ->
            response.addTotal().apply {
                this.category = FhirMapping.coded(FhirMapping.Systems.ADJUDICATION, category)
                this.amount = FhirMapping.money(amount)
            }
        }

        facts.lines.forEach { line ->
            response.addItem().apply {
                itemSequence = line.lineNumber
                adjudications(line).forEach { (category, amount) ->
                    addAdjudication().apply {
                        this.category = FhirMapping.coded(FhirMapping.Systems.ADJUDICATION, category)
                        this.amount = FhirMapping.money(amount)
                    }
                }
            }
        }

        // What the payer actually sent, which is what makes the benefit a payment
        // rather than an intention.
        remittance(facts)?.let { (paid, date) ->
            response.payment = ClaimResponse.PaymentComponent().apply {
                amount = FhirMapping.money(paid)
                date?.let { dateElement = FhirMapping.dateOnlyElement(it) }
            }
        }
        return response
    }

    /**
     * The figures a priced line is reported as, wherever a payer's answer is
     * written out. One list, so the ExplanationOfBenefit and the ClaimResponse
     * cannot disagree about what the payer said. A line the payer has not priced
     * has nothing to report.
     */
    private fun adjudications(line: ClaimRepository.Line): List<Pair<String, BigDecimal>> {
        val allowed = line.allowedAmount ?: return emptyList()
        return listOfNotNull(
            FhirMapping.Adjudication.SUBMITTED to line.chargeAmount,
            FhirMapping.Adjudication.ALLOWED to allowed,
            line.adjustmentAmount?.let { FhirMapping.Adjudication.DEDUCTION to it },
            line.patientResponsibility?.let { FhirMapping.Adjudication.COPAY to it },
            line.payerAmount?.let { FhirMapping.Adjudication.BENEFIT to it },
        )
    }

    /** The same figures at the claim level, on the same reasoning. */
    private fun totals(adjudication: AdjudicationRepository.Adjudication): List<Pair<String, BigDecimal>> = listOf(
        FhirMapping.Adjudication.SUBMITTED to adjudication.totalCharge,
        FhirMapping.Adjudication.ALLOWED to adjudication.totalAllowed,
        FhirMapping.Adjudication.DEDUCTION to adjudication.totalAdjustment,
        FhirMapping.Adjudication.COPAY to adjudication.patientResponsibility,
        FhirMapping.Adjudication.BENEFIT to adjudication.payerResponsibility,
    )

    /**
     * What the payer has already sent, and when, or null when it has sent nothing:
     * the remittance is what makes a benefit a payment rather than an intention.
     */
    private fun remittance(facts: Facts): Pair<BigDecimal, LocalDate?>? {
        val paid = facts.insurancePayments.fold(BigDecimal.ZERO) { total, payment -> total + payment.amount }
        if (paid.signum() <= 0) {
            return null
        }
        return paid to facts.insurancePayments.lastOrNull()?.paymentDate
    }

    private fun claimNumber(claimNumber: String): Identifier =
        Identifier().apply {
            system = CLAIM_NUMBER_SYSTEM
            value = claimNumber
        }

    private fun professional(): CodeableConcept =
        CodeableConcept(Coding(FhirMapping.Systems.CLAIM_TYPE, "professional", "Professional"))

    private fun patientReference(facts: Facts): Reference = FhirMapping.reference(
        resourceType = "Patient",
        id = facts.patient.id,
        identifier = facts.patient.externalId,
        display = "${facts.patient.firstName} ${facts.patient.lastName}",
    )

    /** The NPI is what an external system means by a provider; the export says so. */
    private fun providerReference(facts: Facts): Reference = FhirMapping.reference(
        resourceType = "Practitioner",
        id = facts.provider.id,
        identifier = facts.provider.npi
            ?.let { FhirMapping.externalIdentifier(FhirMapping.Systems.NPI, it) }
            ?: facts.provider.externalId,
        display = "Dr ${facts.provider.firstName} ${facts.provider.lastName}",
    )

    /**
     * The payer, as a contained Organization: the practice's payers are reference
     * data rather than resources on a server, and a contained copy keeps the export
     * readable without inventing a URL to point at.
     */
    private fun payerReference(facts: Facts, containing: DomainResource): Reference {
        val organization = Organization()
        organization.id = CONTAINED_PAYER_ID
        organization.name = facts.payer.name
        organization.addIdentifier(
            Identifier().apply {
                system = PAYER_CODE_SYSTEM
                value = facts.payer.payerCode
            },
        )
        containing.addContained(organization)
        return Reference("#$CONTAINED_PAYER_ID").apply { display = facts.payer.name }
    }

    companion object {
        /** Our own identifiers, named so a consumer can tell them apart. */
        const val CLAIM_NUMBER_SYSTEM = "urn:htt:billing:claim-number"
        const val PAYER_CODE_SYSTEM = "urn:htt:billing:payer-code"

        const val PRIORITY_SYSTEM = "http://terminology.hl7.org/CodeSystem/processpriority"
        const val CONTAINED_PAYER_ID = "payer"
    }
}
