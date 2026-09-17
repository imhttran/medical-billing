package com.htt.billing.claim

import com.htt.billing.common.error.Issue
import com.htt.billing.repository.claim.ClaimRepository.Line
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The rules a claim must satisfy before it can be marked READY or SUBMITTED.
 *
 * Pure on purpose: the service loads the referenced rows and hands them in, so
 * every rule is unit-testable without a database. It returns issues instead of
 * throwing, so one response can list everything that is wrong rather than making
 * the caller fix them one round trip at a time.
 *
 * A null `*OrganizationId` means that row was not found at all.
 */
object ClaimValidator {

    /** Stable codes, so a UI can point at a field and an integration can act. */
    object Codes {
        const val PATIENT_MISSING = "CLAIM_PATIENT_MISSING"
        const val PROVIDER_MISSING = "CLAIM_PROVIDER_MISSING"
        const val COVERAGE_MISSING = "CLAIM_COVERAGE_MISSING"
        const val COVERAGE_NOT_FOR_PATIENT = "CLAIM_COVERAGE_NOT_FOR_PATIENT"
        const val COVERAGE_INACTIVE = "CLAIM_COVERAGE_INACTIVE"
        const val MEMBER_ID_MISSING = "CLAIM_MEMBER_ID_MISSING"
        const val PAYER_INACTIVE = "CLAIM_PAYER_INACTIVE"
        const val SERVICE_DATE_MISSING = "CLAIM_SERVICE_DATE_MISSING"
        const val NO_DIAGNOSES = "CLAIM_NO_DIAGNOSES"
        const val NO_LINES = "CLAIM_NO_LINES"
        const val UNKNOWN_DIAGNOSIS = "CLAIM_UNKNOWN_DIAGNOSIS"
        const val UNKNOWN_PROCEDURE = "CLAIM_UNKNOWN_PROCEDURE"
        const val LINE_PROCEDURE_MISSING = "CLAIM_LINE_PROCEDURE_MISSING"
        const val LINE_QUANTITY_INVALID = "CLAIM_LINE_QUANTITY_INVALID"
        const val LINE_CHARGE_INVALID = "CLAIM_LINE_CHARGE_INVALID"
        const val ORGANIZATION_MISMATCH = "CLAIM_ORGANIZATION_MISMATCH"
    }

    data class LineInput(
        val lineNumber: Int,
        val procedureCode: String,
        val quantity: Int,
        val chargeAmount: BigDecimal?,
    )

    data class Input(
        val organizationId: Int,
        val patientId: Int,
        val patientOrganizationId: Int?,
        val providerOrganizationId: Int?,
        val coverageOrganizationId: Int?,
        val coveragePatientId: Int?,
        val coverageActive: Boolean,
        val coverageMemberIdMissing: Boolean,
        val payerActive: Boolean,
        val serviceDate: LocalDate?,
        val diagnosisCodes: List<String>,
        val unknownDiagnosisCodes: Set<String>,
        val lines: List<LineInput>,
        val unknownProcedureCodes: Set<String>,
    )

    fun validate(input: Input): List<Issue> {
        val issues = mutableListOf<Issue>()

        checkPatient(input, issues)
        checkProvider(input, issues)
        checkCoverage(input, issues)
        checkServiceDate(input, issues)
        checkDiagnoses(input, issues)
        checkLines(input, issues)

        return issues
    }

    private fun checkPatient(input: Input, issues: MutableList<Issue>) {
        val patientOrganization = input.patientOrganizationId
        if (patientOrganization == null) {
            issues += Issue(Codes.PATIENT_MISSING, "The patient on this claim no longer exists")
            return
        }
        if (patientOrganization != input.organizationId) {
            issues += Issue(Codes.ORGANIZATION_MISMATCH, "The patient belongs to another practice")
        }
    }

    private fun checkProvider(input: Input, issues: MutableList<Issue>) {
        val providerOrganization = input.providerOrganizationId
        if (providerOrganization == null) {
            issues += Issue(Codes.PROVIDER_MISSING, "The provider on this claim no longer exists")
            return
        }
        if (providerOrganization != input.organizationId) {
            issues += Issue(Codes.ORGANIZATION_MISMATCH, "The provider belongs to another practice")
        }
    }

    private fun checkCoverage(input: Input, issues: MutableList<Issue>) {
        val coverageOrganization = input.coverageOrganizationId
        if (coverageOrganization == null) {
            issues += Issue(Codes.COVERAGE_MISSING, "This claim has no primary coverage")
            return
        }
        if (coverageOrganization != input.organizationId) {
            issues += Issue(Codes.ORGANIZATION_MISMATCH, "The coverage belongs to another practice")
        }
        if (input.coveragePatientId != input.patientId) {
            issues += Issue(Codes.COVERAGE_NOT_FOR_PATIENT, "The coverage belongs to a different patient")
        }
        if (!input.coverageActive) {
            issues += Issue(Codes.COVERAGE_INACTIVE, "The coverage is not active")
        }
        if (input.coverageMemberIdMissing) {
            issues += Issue(Codes.MEMBER_ID_MISSING, "The coverage has no member ID")
        }
        if (!input.payerActive) {
            issues += Issue(Codes.PAYER_INACTIVE, "The payer is not active")
        }
    }

    private fun checkServiceDate(input: Input, issues: MutableList<Issue>) {
        if (input.serviceDate == null) {
            issues += Issue(Codes.SERVICE_DATE_MISSING, "The claim has no service date")
        }
    }

    private fun checkDiagnoses(input: Input, issues: MutableList<Issue>) {
        if (input.diagnosisCodes.isEmpty()) {
            issues += Issue(Codes.NO_DIAGNOSES, "At least one diagnosis is required")
        }
        input.unknownDiagnosisCodes.sorted().forEach {
            issues += Issue(Codes.UNKNOWN_DIAGNOSIS, "Unknown diagnosis code: $it")
        }
    }

    private fun checkLines(input: Input, issues: MutableList<Issue>) {
        if (input.lines.isEmpty()) {
            issues += Issue(Codes.NO_LINES, "At least one service line is required")
            return
        }
        input.lines.forEach { line ->
            if (line.procedureCode.isBlank()) {
                issues += Issue(
                    Codes.LINE_PROCEDURE_MISSING,
                    "Line ${line.lineNumber} has no procedure code",
                )
            } else if (line.procedureCode in input.unknownProcedureCodes) {
                issues += Issue(
                    Codes.UNKNOWN_PROCEDURE,
                    "Line ${line.lineNumber} has an unknown procedure code: ${line.procedureCode}",
                )
            }
            if (line.quantity < 1) {
                issues += Issue(
                    Codes.LINE_QUANTITY_INVALID,
                    "Line ${line.lineNumber} needs a quantity of at least 1",
                )
            }
            val charge = line.chargeAmount
            if (charge == null || charge <= BigDecimal.ZERO) {
                issues += Issue(
                    Codes.LINE_CHARGE_INVALID,
                    "Line ${line.lineNumber} needs a charge greater than zero",
                )
            }
        }
    }
}
