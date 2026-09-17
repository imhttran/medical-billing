package com.htt.billing.demo

import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Queries the demo reset tests share. The demo practice is real state in the
 * database rather than a per-test fixture, so every one of these tests has to
 * both read it and clean up after itself.
 */

internal fun demoOrganizationId(jdbc: JdbcClient): Int? = jdbc
    .sql("SELECT id FROM organizations WHERE name = :name")
    .param("name", DemoDataset.ORGANIZATION_NAME)
    .query(Int::class.javaObjectType)
    .optional()
    .orElse(null)

internal fun countPatients(jdbc: JdbcClient, organizationId: Int): Int =
    countOf(jdbc, "patients", organizationId)

internal fun countProviders(jdbc: JdbcClient, organizationId: Int): Int =
    countOf(jdbc, "providers", organizationId)

internal fun countCoverages(jdbc: JdbcClient, organizationId: Int): Int =
    countOf(jdbc, "coverages", organizationId)

internal fun countClaims(jdbc: JdbcClient, organizationId: Int): Int =
    countOf(jdbc, "claims", organizationId)

internal fun countPayments(jdbc: JdbcClient, organizationId: Int): Int =
    countOf(jdbc, "patient_payments", organizationId) + countOf(jdbc, "insurance_payments", organizationId)

/** The id of the seeded patient, so a test can prove it survived a reset. */
internal fun demoPatientId(jdbc: JdbcClient, organizationId: Int): Int? = jdbc
    .sql(
        """
        SELECT id FROM patients
        WHERE organization_id = :organizationId AND external_id = :externalId
        """,
    )
    .param("organizationId", organizationId)
    .param("externalId", DemoDataset.PATIENT_EXTERNAL_ID)
    .query(Int::class.javaObjectType)
    .optional()
    .orElse(null)

internal fun demoCoverageMemberId(jdbc: JdbcClient, patientId: Int): String? = jdbc
    .sql("SELECT member_id FROM coverages WHERE patient_id = :patientId ORDER BY priority ASC LIMIT 1")
    .param("patientId", patientId)
    .query(String::class.java)
    .optional()
    .orElse(null)

internal fun auditCountFor(jdbc: JdbcClient, action: String, userId: Int): Int = jdbc
    .sql("SELECT count(*) FROM audit_events WHERE action = :action AND user_id = :userId")
    .param("action", action)
    .param("userId", userId)
    .query(Int::class.javaObjectType)
    .single()

/**
 * A patient the reset did not put there. Stands in for the record a developer
 * created by hand and would not want a restart to remove.
 */
internal fun insertStrayPatient(jdbc: JdbcClient, organizationId: Int, lastName: String): Int = jdbc
    .sql(
        """
        INSERT INTO patients (organization_id, first_name, last_name, date_of_birth)
        VALUES (:organizationId, 'Stray', :lastName, DATE '1990-01-01')
        RETURNING id
        """,
    )
    .param("organizationId", organizationId)
    .param("lastName", lastName)
    .query(Int::class.javaObjectType)
    .single()

/**
 * A claim against the seeded patient, and a payment on it, as a developer's own
 * session would leave behind. A reset has to clear the claim before it can delete
 * the patient and coverage the claim points at.
 */
internal fun insertStrayClaim(jdbc: JdbcClient, organizationId: Int, patientId: Int): Int = jdbc
    .sql(
        """
        INSERT INTO claims
            (organization_id, claim_number, patient_id, provider_id, coverage_id, payer_id, service_date)
        SELECT :organizationId, 'CLM-STRAY', :patientId,
               (SELECT id FROM providers WHERE organization_id = :organizationId ORDER BY id LIMIT 1),
               (SELECT id FROM coverages WHERE patient_id = :patientId ORDER BY priority ASC LIMIT 1),
               (SELECT id FROM payers ORDER BY id LIMIT 1),
               DATE '2026-03-02'
        RETURNING id
        """,
    )
    .param("organizationId", organizationId)
    .param("patientId", patientId)
    .query(Int::class.javaObjectType)
    .single()

internal fun insertStrayPatientPayment(
    jdbc: JdbcClient,
    organizationId: Int,
    patientId: Int,
    claimId: Int,
): Int = jdbc
    .sql(
        """
        INSERT INTO patient_payments
            (organization_id, patient_id, claim_id, amount, payment_method, payment_date)
        VALUES (:organizationId, :patientId, :claimId, 10.00, 'CASH', DATE '2026-03-10')
        RETURNING id
        """,
    )
    .param("organizationId", organizationId)
    .param("patientId", patientId)
    .param("claimId", claimId)
    .query(Int::class.javaObjectType)
    .single()

/** Removes the demo practice and everything under it, leaving no trace. */
internal fun clearDemoData(jdbc: JdbcClient) {
    val organizationId = demoOrganizationId(jdbc) ?: return
    jdbc.sql("DELETE FROM claims WHERE organization_id = :id").param("id", organizationId).update()
    jdbc.sql("DELETE FROM coverages WHERE organization_id = :id").param("id", organizationId).update()
    jdbc.sql("DELETE FROM patients WHERE organization_id = :id").param("id", organizationId).update()
    jdbc.sql("DELETE FROM providers WHERE organization_id = :id").param("id", organizationId).update()
    jdbc.sql("DELETE FROM user_role_assignments WHERE organization_id = :id").param("id", organizationId).update()
    jdbc.sql("DELETE FROM audit_events WHERE organization_id = :id").param("id", organizationId).update()
    jdbc.sql("DELETE FROM organizations WHERE id = :id").param("id", organizationId).update()
}

/**
 * Only ever called with the three literals above. The table name cannot come
 * from a request, and [countOf] is private to this file.
 */
private fun countOf(jdbc: JdbcClient, table: String, organizationId: Int): Int = jdbc
    .sql("SELECT count(*) FROM $table WHERE organization_id = :organizationId")
    .param("organizationId", organizationId)
    .query(Int::class.javaObjectType)
    .single()
