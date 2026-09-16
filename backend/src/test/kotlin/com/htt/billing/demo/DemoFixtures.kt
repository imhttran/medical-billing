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

/** Removes the demo practice and everything under it, leaving no trace. */
internal fun clearDemoData(jdbc: JdbcClient) {
    val organizationId = demoOrganizationId(jdbc) ?: return
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
