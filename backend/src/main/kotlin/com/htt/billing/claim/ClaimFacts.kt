package com.htt.billing.claim

import com.htt.billing.coding.CodingRepository
import com.htt.billing.coverage.CoverageRepository
import com.htt.billing.coverage.PayerRepository
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
