package com.htt.billing.demo

import java.time.LocalDate

/**
 * The synthetic dataset, in one place so "deterministic" is a property of the
 * code and not a hope about the database.
 *
 * Nothing here is random and nothing is derived from the current time, so the
 * golden path can be exercised, reset, and exercised again with the same
 * numbers. The plan's walkthrough is Jane Smith, primary coverage, ICD-10-CM
 * J06.9 and CPT 99213 at a $150 charge.
 */
object DemoDataset {

    const val ORGANIZATION_NAME = "Demo Primary Care"
    const val ORGANIZATION_NPI = "1234567893"
    const val ORGANIZATION_TAX_ID = "12-3456789"

    data class DemoProvider(
        val firstName: String,
        val lastName: String,
        val npi: String,
        val taxonomyCode: String,
    )

    val PROVIDERS: List<DemoProvider> = listOf(
        DemoProvider("Dana", "Reyes", "1245319599", "207Q00000X"),
    )

    /**
     * What the local login is granted in the demo practice.
     *
     * Both roles, because one demo account has to be able to do everything the
     * screens offer: the practice admin manages users, and the billing manager
     * works claims, assigns and resolves queue items, takes payments and voids. A
     * real deployment gives the two to different people.
     */
    val DEV_ADMIN_ROLES: List<String> = listOf("PRACTICE_ADMIN", "BILLING_MANAGER")

    const val PATIENT_EXTERNAL_ID = "DEMO-JANE-SMITH"
    const val PATIENT_FIRST_NAME = "Jane"
    const val PATIENT_LAST_NAME = "Smith"
    val PATIENT_DATE_OF_BIRTH: LocalDate = LocalDate.parse("1979-03-14")
    const val PATIENT_SEX = "FEMALE"
    const val PATIENT_ADDRESS = "400 Congress Ave"
    const val PATIENT_CITY = "Austin"
    const val PATIENT_STATE = "TX"
    const val PATIENT_POSTAL_CODE = "78701"
    const val PATIENT_PHONE = "512-555-0142"

    /** Seeded globally by V3__patients_providers_coverage_codes.sql. */
    const val PAYER_CODE = "SYN001"

    const val COVERAGE_MEMBER_ID = "JSM123456"
    const val COVERAGE_GROUP_NUMBER = "DEMO-GRP"
    const val COVERAGE_RELATIONSHIP = "SELF"
    val COVERAGE_EFFECTIVE_DATE: LocalDate = LocalDate.parse("2024-01-01")
}
