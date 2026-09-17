package com.htt.billing.demo

import java.sql.Types
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

internal fun claimsByStatus(jdbc: JdbcClient, organizationId: Int): Map<String, Int> = jdbc
    .sql("SELECT status, count(*) AS total FROM claims WHERE organization_id = :organizationId GROUP BY status")
    .param("organizationId", organizationId)
    .query { row, _ -> row.getString("status") to row.getInt("total") }
    .list()
    .toMap()

internal fun claimIdWithStatus(jdbc: JdbcClient, organizationId: Int, status: String): Int? = jdbc
    .sql("SELECT id FROM claims WHERE organization_id = :organizationId AND status = :status ORDER BY id LIMIT 1")
    .param("organizationId", organizationId)
    .param("status", status)
    .query(Int::class.javaObjectType)
    .optional()
    .orElse(null)

internal fun openWorkItems(jdbc: JdbcClient, organizationId: Int): Int = jdbc
    .sql("SELECT count(*) FROM work_items WHERE organization_id = :organizationId AND status = 'OPEN'")
    .param("organizationId", organizationId)
    .query(Int::class.javaObjectType)
    .single()

/**
 * Audit events for one action, optionally for one entity. Only events with no
 * actor are counted, which is what the seed writes: it has no signed-in user, and
 * the tests pin that rather than a made-up actor.
 */
internal fun seededAuditEventCount(
    jdbc: JdbcClient,
    organizationId: Int,
    action: String,
    entityId: String? = null,
): Int = jdbc
    .sql(
        """
        SELECT count(*) FROM audit_events
        WHERE organization_id = :organizationId AND action = :action AND user_id IS NULL
          AND (:entityId IS NULL OR entity_id = :entityId)
        """,
    )
    .param("organizationId", organizationId)
    .param("action", action)
    .param("entityId", entityId, Types.VARCHAR)
    .query(Int::class.javaObjectType)
    .single()

/** Everything recorded against one claim, payer and patient together. */
internal fun paymentsForClaim(jdbc: JdbcClient, claimId: Int): Int = jdbc
    .sql(
        """
        SELECT (SELECT count(*) FROM patient_payments WHERE claim_id = :claimId)
             + (SELECT count(*) FROM insurance_payments WHERE claim_id = :claimId)
        """,
    )
    .param("claimId", claimId)
    .query(Int::class.javaObjectType)
    .single()

/** The walkthrough claim's money, which is what the plan's milestone states. */
internal data class WalkthroughClaim(
    val claimId: Int,
    val status: String,
    val totalCharge: String,
    val totalAllowed: String,
    val totalAdjustment: String,
    val payerResponsibility: String,
    val patientResponsibility: String,
)

internal fun walkthroughAdjudication(
    jdbc: JdbcClient,
    organizationId: Int,
    patientId: Int,
    serviceDate: String,
): WalkthroughClaim? = jdbc
    .sql(
        """
        SELECT c.id, c.status, a.total_charge, a.total_allowed, a.total_adjustment,
               a.payer_responsibility, a.patient_responsibility
        FROM claims c
        JOIN adjudications a ON a.claim_id = c.id
        WHERE c.organization_id = :organizationId AND c.patient_id = :patientId
          AND c.service_date = CAST(:serviceDate AS DATE)
        """,
    )
    .param("organizationId", organizationId)
    .param("patientId", patientId)
    .param("serviceDate", serviceDate)
    .query { row, _ ->
        WalkthroughClaim(
            claimId = row.getInt("id"),
            status = row.getString("status"),
            totalCharge = row.getBigDecimal("total_charge").toPlainString(),
            totalAllowed = row.getBigDecimal("total_allowed").toPlainString(),
            totalAdjustment = row.getBigDecimal("total_adjustment").toPlainString(),
            payerResponsibility = row.getBigDecimal("payer_responsibility").toPlainString(),
            patientResponsibility = row.getBigDecimal("patient_responsibility").toPlainString(),
        )
    }
    .optional()
    .orElse(null)

/** The id of the seeded patient, so a test can prove it survived a reset. */
internal fun demoPatientId(jdbc: JdbcClient, organizationId: Int): Int? = jdbc
    .sql(
        """
        SELECT id FROM patients
        WHERE organization_id = :organizationId AND external_id = :externalId
        """,
    )
    .param("organizationId", organizationId)
    .param("externalId", DemoDataset.JANE.externalId)
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
