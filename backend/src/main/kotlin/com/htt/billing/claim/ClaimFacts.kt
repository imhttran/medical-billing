package com.htt.billing.claim

import com.htt.billing.coding.CodingRepository
import com.htt.billing.coverage.CoverageRepository
import com.htt.billing.coverage.PayerRepository
import com.htt.billing.common.error.ValidationException
import com.htt.billing.patient.PatientRepository
import com.htt.billing.practice.ProviderRepository
import org.springframework.stereotype.Component

/**
 * Gathers the rows a claim refers to, so the validator can stay pure and the
 * claim service does not have to hold five repositories to ask one question.
 */
@Component
class ClaimFacts(
    private val patients: PatientRepository,
    private val providers: ProviderRepository,
    private val coverages: CoverageRepository,
    private val payers: PayerRepository,
    private val coding: CodingRepository,
) {

    /** The coverage a claim names, which is also where its payer comes from. */
    fun coverageFor(coverageId: Int): CoverageRepository.Coverage? = coverages.findById(coverageId)

    /**
     * Every row a claim names has to belong to the practice it is written in.
     *
     * The references arrive in the request body and the foreign keys would accept a
     * patient, provider or coverage from another practice, so a claim could be
     * written against another tenant's rows if nothing said so. Validation reports
     * the mismatch too, but only when someone tries to send the claim — the row is
     * what has to be right, so the refusal happens where the claim is written.
     */
    fun requireInPractice(
        organizationId: Int,
        patientId: Int,
        providerId: Int,
        coverage: CoverageRepository.Coverage,
    ) {
        if (patients.findById(patientId)?.organizationId != organizationId) {
            throw ValidationException("patientId must name a patient in this practice")
        }
        if (providers.findById(providerId)?.organizationId != organizationId) {
            throw ValidationException("providerId must name a provider in this practice")
        }
        if (coverage.organizationId != organizationId) {
            throw ValidationException("coverageId must name a coverage in this practice")
        }
    }

    /**
     * The validator's view of [claim]'s references. The codes come in from the
     * caller because "what the claim holds" and "what the request proposed" are
     * different questions, and only the caller knows which it is asking.
     */
    fun forClaim(
        claim: ClaimRepository.Claim,
        diagnosisCodes: List<String>,
        lines: List<ClaimRepository.LineInput>,
    ): ClaimValidator.Input {
        val patient = patients.findById(claim.patientId)
        val provider = providers.findById(claim.providerId)
        val coverage = coverages.findById(claim.coverageId)
        return ClaimValidator.Input(
            organizationId = claim.organizationId,
            patientId = claim.patientId,
            patientOrganizationId = patient?.organizationId,
            providerOrganizationId = provider?.organizationId,
            coverageOrganizationId = coverage?.organizationId,
            coveragePatientId = coverage?.patientId,
            coverageActive = coverage?.active == true,
            coverageMemberIdMissing = coverage?.memberId.isNullOrBlank(),
            payerActive = payers.findActiveById(claim.payerId) != null,
            serviceDate = claim.serviceDate,
            diagnosisCodes = diagnosisCodes,
            unknownDiagnosisCodes = coding.unknownDiagnosisCodes(diagnosisCodes),
            lines = lines.map {
                ClaimValidator.LineInput(it.lineNumber, it.procedureCode, it.quantity, it.chargeAmount)
            },
            unknownProcedureCodes = coding.unknownProcedureCodes(lines.map { it.procedureCode }),
        )
    }
}
