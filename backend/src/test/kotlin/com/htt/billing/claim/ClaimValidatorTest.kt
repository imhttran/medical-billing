package com.htt.billing.claim

import com.htt.billing.claim.ClaimValidator.Codes
import com.htt.billing.claim.ClaimValidator.Input
import com.htt.billing.claim.ClaimValidator.LineInput
import com.htt.billing.common.error.Issue
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The claim validation rules, one at a time, with no database. Each case varies
 * a single field of an otherwise valid claim, so a failure names the rule that
 * broke rather than the whole fixture.
 */
class ClaimValidatorTest {

    @Test
    fun aCompleteClaimHasNoIssues() {
        assertEquals(emptyList<Issue>(), ClaimValidator.validate(input()))
    }

    @Test
    fun aMissingPatientIsAnIssue() {
        assertCodes(setOf(Codes.PATIENT_MISSING), input(patientOrganizationId = null))
    }

    @Test
    fun aPatientFromAnotherPracticeIsAnIssue() {
        assertCodes(setOf(Codes.ORGANIZATION_MISMATCH), input(patientOrganizationId = OTHER_PRACTICE))
    }

    @Test
    fun aMissingProviderIsAnIssue() {
        assertCodes(setOf(Codes.PROVIDER_MISSING), input(providerOrganizationId = null))
    }

    @Test
    fun aProviderFromAnotherPracticeIsAnIssue() {
        assertCodes(setOf(Codes.ORGANIZATION_MISMATCH), input(providerOrganizationId = OTHER_PRACTICE))
    }

    @Test
    fun missingCoverageIsAnIssue() {
        assertCodes(setOf(Codes.COVERAGE_MISSING), input(coverageOrganizationId = null))
    }

    @Test
    fun coverageForADifferentPatientIsAnIssue() {
        assertCodes(setOf(Codes.COVERAGE_NOT_FOR_PATIENT), input(coveragePatientId = OTHER_PATIENT))
    }

    @Test
    fun inactiveCoverageIsAnIssue() {
        assertCodes(setOf(Codes.COVERAGE_INACTIVE), input(coverageActive = false))
    }

    @Test
    fun coverageWithoutAMemberIdIsAnIssue() {
        assertCodes(setOf(Codes.MEMBER_ID_MISSING), input(coverageMemberIdMissing = true))
    }

    @Test
    fun anInactivePayerIsAnIssue() {
        assertCodes(setOf(Codes.PAYER_INACTIVE), input(payerActive = false))
    }

    @Test
    fun aMissingServiceDateIsAnIssue() {
        assertCodes(setOf(Codes.SERVICE_DATE_MISSING), input(serviceDate = null))
    }

    @Test
    fun aClaimWithNoDiagnosisIsAnIssue() {
        assertCodes(setOf(Codes.NO_DIAGNOSES), input(diagnosisCodes = emptyList()))
    }

    @Test
    fun aClaimWithNoLinesIsAnIssue() {
        assertCodes(setOf(Codes.NO_LINES), input(lines = emptyList()))
    }

    @Test
    fun anUnknownDiagnosisCodeIsAnIssue() {
        assertCodes(setOf(Codes.UNKNOWN_DIAGNOSIS), input(unknownDiagnosisCodes = setOf("ZZZ")))
    }

    @Test
    fun anUnknownProcedureCodeIsAnIssue() {
        assertCodes(
            setOf(Codes.UNKNOWN_PROCEDURE),
            input(
                lines = listOf(LineInput(1, "00000", 1, BigDecimal("150.00"))),
                unknownProcedureCodes = setOf("00000"),
            ),
        )
    }

    @Test
    fun aLineWithoutAProcedureCodeIsAnIssue() {
        assertCodes(
            setOf(Codes.LINE_PROCEDURE_MISSING),
            input(lines = listOf(LineInput(1, "  ", 1, BigDecimal("150.00")))),
        )
    }

    @Test
    fun aLineWithNoQuantityIsAnIssue() {
        assertCodes(
            setOf(Codes.LINE_QUANTITY_INVALID),
            input(lines = listOf(LineInput(1, "99213", 0, BigDecimal("150.00")))),
        )
    }

    @Test
    fun aLineWithoutAPositiveChargeIsAnIssue() {
        assertCodes(
            setOf(Codes.LINE_CHARGE_INVALID),
            input(lines = listOf(LineInput(1, "99213", 1, BigDecimal.ZERO))),
        )
        assertCodes(
            setOf(Codes.LINE_CHARGE_INVALID),
            input(lines = listOf(LineInput(1, "99213", 1, null))),
        )
    }

    @Test
    fun everyProblemIsReportedAtOnce() {
        val issues = ClaimValidator.validate(
            input(
                diagnosisCodes = emptyList(),
                serviceDate = null,
                lines = listOf(LineInput(1, "", 0, null)),
            ),
        )
        val codes = issues.map { it.code }.toSet()
        assertTrue(codes.containsAll(setOf(Codes.NO_DIAGNOSES, Codes.SERVICE_DATE_MISSING))) {
            "got $codes"
        }
        assertTrue(
            codes.containsAll(
                setOf(Codes.LINE_PROCEDURE_MISSING, Codes.LINE_QUANTITY_INVALID, Codes.LINE_CHARGE_INVALID),
            ),
        ) { "got $codes" }
    }

    private fun assertCodes(expected: Set<String>, input: Input) {
        assertEquals(expected, ClaimValidator.validate(input).map { it.code }.toSet())
    }

    /** A valid claim, with one field varied per test. */
    private fun input(
        organizationId: Int = PRACTICE,
        patientId: Int = PATIENT,
        patientOrganizationId: Int? = PRACTICE,
        providerOrganizationId: Int? = PRACTICE,
        coverageOrganizationId: Int? = PRACTICE,
        coveragePatientId: Int? = PATIENT,
        coverageActive: Boolean = true,
        coverageMemberIdMissing: Boolean = false,
        payerActive: Boolean = true,
        serviceDate: LocalDate? = LocalDate.parse("2026-03-02"),
        diagnosisCodes: List<String> = listOf("J06.9"),
        unknownDiagnosisCodes: Set<String> = emptySet(),
        lines: List<LineInput> = listOf(LineInput(1, "99213", 1, BigDecimal("150.00"))),
        unknownProcedureCodes: Set<String> = emptySet(),
    ) = Input(
        organizationId = organizationId,
        patientId = patientId,
        patientOrganizationId = patientOrganizationId,
        providerOrganizationId = providerOrganizationId,
        coverageOrganizationId = coverageOrganizationId,
        coveragePatientId = coveragePatientId,
        coverageActive = coverageActive,
        coverageMemberIdMissing = coverageMemberIdMissing,
        payerActive = payerActive,
        serviceDate = serviceDate,
        diagnosisCodes = diagnosisCodes,
        unknownDiagnosisCodes = unknownDiagnosisCodes,
        lines = lines,
        unknownProcedureCodes = unknownProcedureCodes,
    )

    private companion object {
        const val PRACTICE = 1
        const val OTHER_PRACTICE = 2
        const val PATIENT = 10
        const val OTHER_PATIENT = 11
    }
}
