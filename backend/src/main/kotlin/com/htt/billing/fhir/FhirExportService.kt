package com.htt.billing.fhir

import com.htt.billing.adjudication.AdjudicationRepository
import com.htt.billing.claim.ClaimRepository
import com.htt.billing.claim.ClaimStatus
import com.htt.billing.common.error.NotFoundException
import com.htt.billing.coverage.CoverageRepository
import com.htt.billing.coverage.PayerRepository
import com.htt.billing.patient.PatientRepository
import com.htt.billing.payment.PaymentRepository
import com.htt.billing.practice.ProviderRepository
import com.htt.billing.security.AuthorizationService
import com.htt.billing.security.Permissions
import java.math.BigDecimal
import org.hl7.fhir.r4.model.Claim
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
 * FHIR export: a claim as the payer would receive it, and what the payer made of it.
 *
 * The two resources are the same facts read two ways. The Claim is what the practice
 * billed; the ExplanationOfBenefit is what the payer answered, and it only exists
 * once the payer has answered — a rejected claim has nothing to explain.
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
        val adjudication = facts.adjudication
            ?: throw NotFoundException("This claim has not been adjudicated, so there is nothing to explain")
        return fhir.parser().encodeResourceToString(eobResource(facts, adjudication))
    }

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

        eob.addTotal(totalOf(FhirMapping.Adjudication.SUBMITTED, adjudication.totalCharge))
        eob.addTotal(totalOf(FhirMapping.Adjudication.ALLOWED, adjudication.totalAllowed))
        eob.addTotal(totalOf(FhirMapping.Adjudication.DEDUCTION, adjudication.totalAdjustment))
        eob.addTotal(totalOf(FhirMapping.Adjudication.COPAY, adjudication.patientResponsibility))
        eob.addTotal(totalOf(FhirMapping.Adjudication.BENEFIT, adjudication.payerResponsibility))

        facts.lines.forEach { line ->
            eob.addItem().apply {
                sequence = line.lineNumber
                productOrService = FhirMapping.procedure(line.procedureCode, line.procedureCodeSystem)
                addAdjudication(line)
            }
        }

        // What the payer actually sent, which is what makes the benefit a payment
        // rather than an intention.
        val paid = facts.insurancePayments.fold(BigDecimal.ZERO) { total, payment -> total + payment.amount }
        if (paid.signum() > 0) {
            eob.payment = ExplanationOfBenefit.PaymentComponent().apply {
                amount = FhirMapping.money(paid)
                facts.insurancePayments.lastOrNull()?.let { dateElement = FhirMapping.dateOnlyElement(it.paymentDate) }
            }
        }
        return eob
    }

    private fun ExplanationOfBenefit.ItemComponent.addAdjudication(line: ClaimRepository.Line) {
        val allowed = line.allowedAmount ?: return
        addAdjudication().apply {
            category = FhirMapping.coded(FhirMapping.Systems.ADJUDICATION, FhirMapping.Adjudication.SUBMITTED)
            amount = FhirMapping.money(line.chargeAmount)
        }
        listOf(
            FhirMapping.Adjudication.ALLOWED to allowed,
            FhirMapping.Adjudication.DEDUCTION to line.adjustmentAmount,
            FhirMapping.Adjudication.COPAY to line.patientResponsibility,
            FhirMapping.Adjudication.BENEFIT to line.payerAmount,
        ).forEach { (category, amount) ->
            if (amount != null) {
                addAdjudication().apply {
                    this.category = FhirMapping.coded(FhirMapping.Systems.ADJUDICATION, category)
                    this.amount = FhirMapping.money(amount)
                }
            }
        }
    }

    private fun totalOf(category: String, amount: BigDecimal): ExplanationOfBenefit.TotalComponent =
        ExplanationOfBenefit.TotalComponent().apply {
            this.category = FhirMapping.coded(FhirMapping.Systems.ADJUDICATION, category)
            this.amount = FhirMapping.money(amount)
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
