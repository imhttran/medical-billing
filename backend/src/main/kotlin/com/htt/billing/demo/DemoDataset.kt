package com.htt.billing.demo

import java.time.LocalDate

/**
 * The synthetic dataset, in one place so "deterministic" is a property of the
 * code and not a hope about the database.
 *
 * Nothing here is random and nothing is derived from the current time, so the
 * golden path can be exercised, reset, and exercised again with the same
 * numbers. The plan's walkthrough is Jane Smith, primary coverage, ICD-10-CM
 * J06.9 and CPT 99213 at a $150 charge, and that claim is in [CLAIMS] landing on
 * the plan's 110.00 allowed, 40.00 adjustment, 80.00 insurance and 30.00
 * patient.
 *
 * Ten patients and three clinicians, because a demo is read from the screens
 * rather than from a test assertion. The claims cover every state the screens
 * show, including the two answers from the payer that leave work on the queue
 * and the drafts an operator submits by hand.
 */
object DemoDataset {

    const val ORGANIZATION_NAME = "Demo Primary Care"
    const val ORGANIZATION_NPI = "1234567893"
    const val ORGANIZATION_TAX_ID = "12-3456789"

    /** Seeded globally by V3__patients_providers_coverage_codes.sql. */
    const val PAYER_CODE = "SYN001"

    /** A payer with no fee schedule, so everything billed to it is uncovered. */
    const val SECONDARY_PAYER_CODE = "EXC001"

    /**
     * What the local login is granted in the demo practice.
     *
     * Both roles, because one demo account has to be able to do everything the
     * screens offer: the practice admin manages users, and the billing manager
     * works claims, assigns and resolves queue items, takes payments and voids. A
     * real deployment gives the two to different people.
     */
    val DEV_ADMIN_ROLES: List<String> = listOf("PRACTICE_ADMIN", "BILLING_MANAGER")

    data class DemoProvider(
        /** What a claim in [CLAIMS] names this clinician by. */
        val externalId: String,
        val firstName: String,
        val lastName: String,
        val npi: String,
        val taxonomyCode: String,
    )

    val PROVIDERS: List<DemoProvider> = listOf(
        DemoProvider("DEMO-REYES", "Dana", "Reyes", "1245319599", "207Q00000X"),
        DemoProvider("DEMO-BELL", "Marcus", "Bell", "1497758544", "207R00000X"),
        DemoProvider("DEMO-RAMAN", "Priya", "Raman", "1922043397", "208000000X"),
    )

    data class DemoCoverage(
        val payerCode: String,
        val memberId: String,
        val groupNumber: String?,
        val relationship: String,
        val effectiveDate: LocalDate?,
        /** Set when the plan ended, which is what makes the payer reject a claim. */
        val terminationDate: LocalDate? = null,
        val priority: Int = 1,
        /** Left null for the member themselves; named for a dependent. */
        val subscriberName: String? = null,
    )

    data class DemoPatient(
        /** What a claim in [CLAIMS] names this patient by. */
        val externalId: String,
        val firstName: String,
        val lastName: String,
        val dateOfBirth: LocalDate,
        val sex: String,
        val addressLine1: String,
        val city: String,
        val state: String,
        val postalCode: String,
        val phone: String,
        val coverages: List<DemoCoverage>,
    )

    val PATIENTS: List<DemoPatient> = listOf(
        DemoPatient(
            "DEMO-JANE-SMITH", "Jane", "Smith", LocalDate.parse("1979-03-14"), "FEMALE",
            "400 Congress Ave", "Austin", "TX", "78701", "512-555-0142",
            listOf(
                DemoCoverage(PAYER_CODE, "JSM123456", "DEMO-GRP", "SELF", LocalDate.parse("2024-01-01")),
            ),
        ),
        DemoPatient(
            "DEMO-CHEN", "Robert", "Chen", LocalDate.parse("1968-07-22"), "MALE",
            "1801 Far West Blvd", "Austin", "TX", "78731", "512-555-0117",
            listOf(
                DemoCoverage(PAYER_CODE, "RCH204518", "DEMO-GRP", "SELF", LocalDate.parse("2023-06-01")),
            ),
        ),
        DemoPatient(
            "DEMO-ALVAREZ", "Maria", "Alvarez", LocalDate.parse("1985-11-02"), "FEMALE",
            "900 Rutland Dr", "Austin", "TX", "78758", "512-555-0188",
            listOf(
                DemoCoverage(PAYER_CODE, "MAL331902", "DEMO-GRP", "SELF", LocalDate.parse("2025-01-01")),
            ),
        ),
        // The rejected claim's patient: the plan ended before the visit, which is
        // the one refusal the simulated payer makes.
        DemoPatient(
            "DEMO-OKAFOR", "David", "Okafor", LocalDate.parse("1954-02-19"), "MALE",
            "1103 Raider Way", "Cedar Park", "TX", "78613", "512-555-0166",
            listOf(
                DemoCoverage(
                    PAYER_CODE, "DOK778113", "DEMO-GRP", "SELF",
                    LocalDate.parse("2022-04-01"), terminationDate = LocalDate.parse("2025-12-31"),
                ),
            ),
        ),
        // Two coverages, primary first, which is what the patient screen lists and
        // what makes the coverage order worth looking at.
        DemoPatient(
            "DEMO-NAKAMURA", "Emily", "Nakamura", LocalDate.parse("1996-06-30"), "FEMALE",
            "2400 Windsor Rd", "Austin", "TX", "78703", "512-555-0121",
            listOf(
                DemoCoverage(PAYER_CODE, "ENK559340", "DEMO-GRP", "SELF", LocalDate.parse("2024-09-01")),
                DemoCoverage(
                    SECONDARY_PAYER_CODE, "EXC884120", "EXC-GRP", "SELF",
                    LocalDate.parse("2024-09-01"), priority = 2,
                ),
            ),
        ),
        DemoPatient(
            "DEMO-WHITFIELD", "Samuel", "Whitfield", LocalDate.parse("1972-09-11"), "MALE",
            "1500 Grand Avenue Pkwy", "Pflugerville", "TX", "78660", "512-555-0193",
            listOf(
                DemoCoverage(PAYER_CODE, "SWH612874", "DEMO-GRP", "SELF", LocalDate.parse("2021-01-01")),
            ),
        ),
        DemoPatient(
            "DEMO-RAHMAN", "Aisha", "Rahman", LocalDate.parse("1990-04-25"), "FEMALE",
            "5010 Manchaca Rd", "Austin", "TX", "78745", "512-555-0139",
            listOf(
                DemoCoverage(PAYER_CODE, "ARH447201", "DEMO-GRP", "SELF", LocalDate.parse("2025-03-01")),
            ),
        ),
        DemoPatient(
            "DEMO-NOWAK", "Thomas", "Nowak", LocalDate.parse("1961-12-08"), "MALE",
            "2300 Austin Ave", "Georgetown", "TX", "78626", "512-555-0104",
            listOf(
                DemoCoverage(PAYER_CODE, "TNW903556", "DEMO-GRP", "SELF", LocalDate.parse("2020-10-01")),
            ),
        ),
        DemoPatient(
            "DEMO-LINDQVIST", "Grace", "Lindqvist", LocalDate.parse("2003-01-17"), "FEMALE",
            "3105 Duval St", "Austin", "TX", "78705", "512-555-0158",
            listOf(
                DemoCoverage(PAYER_CODE, "GLI228470", "DEMO-GRP", "SELF", LocalDate.parse("2024-02-01")),
            ),
        ),
        // A minor on someone else's plan, so the coverage screen has a
        // relationship that is not SELF and a subscriber who is not the patient.
        DemoPatient(
            "DEMO-CASTELLANO", "Henry", "Castellano", LocalDate.parse("2015-05-04"), "MALE",
            "6701 Airport Blvd", "Austin", "TX", "78752", "512-555-0175",
            listOf(
                DemoCoverage(
                    PAYER_CODE, "HCA115938", "DEMO-GRP", "CHILD",
                    LocalDate.parse("2024-01-01"), subscriberName = "Rosa Castellano",
                ),
            ),
        ),
    )

    /** The walkthrough patient, named in the plan. */
    val JANE: DemoPatient get() = PATIENTS.first()

    data class DemoClaimLine(
        val procedureCode: String,
        val chargeAmount: String,
        val quantity: Int = 1,
    )

    /** How far the seed takes a claim. Everything past this is the operator's work. */
    enum class Path { DRAFT, READY, SUBMITTED }

    data class DemoClaim(
        /** A [DemoPatient.externalId]. */
        val patient: String,
        /** A [DemoProvider.externalId]. */
        val provider: String,
        val serviceDate: LocalDate,
        val diagnoses: List<String>,
        val lines: List<DemoClaimLine>,
        val path: Path = Path.SUBMITTED,
        /** Recorded after the payer answers, leaving the claim part paid. */
        val patientPayment: String? = null,
    )

    val CLAIMS: List<DemoClaim> = listOf(
        // The plan's walkthrough. 150.00 charged, 110.00 allowed, 40.00 written
        // off, 80.00 from the payer and 30.00 from the patient.
        DemoClaim(
            "DEMO-JANE-SMITH", "DEMO-REYES", LocalDate.parse("2026-09-08"),
            listOf("J06.9"), listOf(DemoClaimLine("99213", "150.00")),
        ),
        // Two lines, the payer's share in, and part of the patient's — so the
        // payment screen has a balance rather than a settled claim.
        DemoClaim(
            "DEMO-JANE-SMITH", "DEMO-REYES", LocalDate.parse("2026-08-11"),
            listOf("I10"),
            listOf(DemoClaimLine("99214", "210.00"), DemoClaimLine("36415", "12.00")),
            patientPayment = "15.00",
        ),
        DemoClaim(
            "DEMO-ALVAREZ", "DEMO-BELL", LocalDate.parse("2026-07-15"),
            listOf("E11.9"),
            listOf(DemoClaimLine("99213", "150.00"), DemoClaimLine("81002", "15.00")),
        ),
        // A paid visit and a refused one on the same claim: adjudicated, with the
        // uncovered service left on the queue.
        DemoClaim(
            "DEMO-CHEN", "DEMO-BELL", LocalDate.parse("2026-08-19"),
            listOf("R05"),
            listOf(DemoClaimLine("99213", "150.00"), DemoClaimLine("17110", "95.00")),
        ),
        // Preventive care the plan covers in full, so nothing is owed at all.
        DemoClaim(
            "DEMO-WHITFIELD", "DEMO-REYES", LocalDate.parse("2026-09-01"),
            listOf("Z00.00"), listOf(DemoClaimLine("99396", "220.00")),
        ),
        DemoClaim(
            "DEMO-RAHMAN", "DEMO-BELL", LocalDate.parse("2026-06-20"),
            listOf("K21.9"),
            listOf(DemoClaimLine("99214", "210.00"), DemoClaimLine("96127", "30.00")),
        ),
        // Nothing on this claim is covered, which is a denial rather than a
        // rejection, and the queue item that follows it.
        DemoClaim(
            "DEMO-LINDQVIST", "DEMO-RAMAN", LocalDate.parse("2026-08-05"),
            listOf("L03.90"), listOf(DemoClaimLine("17110", "95.00")),
        ),
        // Seen after the plan ended, so the payer refuses to price it at all.
        DemoClaim(
            "DEMO-OKAFOR", "DEMO-BELL", LocalDate.parse("2026-03-10"),
            listOf("I10"), listOf(DemoClaimLine("99214", "210.00")),
        ),
        DemoClaim(
            "DEMO-NAKAMURA", "DEMO-REYES", LocalDate.parse("2026-09-04"),
            listOf("F41.1"),
            listOf(DemoClaimLine("99214", "210.00"), DemoClaimLine("96127", "30.00")),
        ),
        DemoClaim(
            "DEMO-CASTELLANO", "DEMO-RAMAN", LocalDate.parse("2026-09-10"),
            listOf("J02.9"), listOf(DemoClaimLine("99213", "150.00")),
        ),
        DemoClaim(
            "DEMO-NOWAK", "DEMO-REYES", LocalDate.parse("2026-05-28"),
            listOf("E11.9"),
            listOf(DemoClaimLine("99212", "110.00"), DemoClaimLine("81002", "15.00")),
        ),
        // Left for the operator: validated and waiting to go out.
        DemoClaim(
            "DEMO-RAHMAN", "DEMO-REYES", LocalDate.parse("2026-09-15"),
            listOf("I10"), listOf(DemoClaimLine("99213", "150.00")), path = Path.DRAFT,
        ),
        DemoClaim(
            "DEMO-ALVAREZ", "DEMO-RAMAN", LocalDate.parse("2026-09-14"),
            listOf("N39.0"),
            listOf(DemoClaimLine("99213", "150.00"), DemoClaimLine("81002", "15.00")),
            path = Path.READY,
        ),
        // Started and abandoned before anyone named a service, so the validation
        // screen has something to report.
        DemoClaim(
            "DEMO-NOWAK", "DEMO-REYES", LocalDate.parse("2026-09-16"),
            listOf("E11.9"), emptyList(), path = Path.DRAFT,
        ),
    )

    /** The rows the master-data part of the seed writes, for the reset's report. */
    val COVERAGE_COUNT: Int get() = PATIENTS.sumOf { it.coverages.size }
}
