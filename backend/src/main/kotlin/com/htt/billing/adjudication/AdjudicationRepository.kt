package com.htt.billing.adjudication

import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * The adjudication record: one row per submission, holding what the payer said.
 * Read-mostly — an adjudication is never updated, because it is the record of a
 * decision that was already taken.
 */
@Repository
class AdjudicationRepository(private val jdbc: JdbcClient) {

    data class Adjudication(
        val id: Int,
        val claimId: Int,
        val organizationId: Int,
        val adjudicatedAt: Instant,
        val outcome: String,
        val totalCharge: BigDecimal,
        val totalAllowed: BigDecimal,
        val totalAdjustment: BigDecimal,
        val payerResponsibility: BigDecimal,
        val patientResponsibility: BigDecimal,
    )

    fun insert(
        claimId: Int,
        organizationId: Int,
        outcome: String,
        totalCharge: BigDecimal,
        totalAllowed: BigDecimal,
        totalAdjustment: BigDecimal,
        payerResponsibility: BigDecimal,
        patientResponsibility: BigDecimal,
    ): Adjudication = jdbc
        .sql(
            """
            INSERT INTO adjudications
                (claim_id, organization_id, outcome, total_charge, total_allowed,
                 total_adjustment, payer_responsibility, patient_responsibility)
            VALUES
                (:claimId, :organizationId, :outcome, :totalCharge, :totalAllowed,
                 :totalAdjustment, :payerResponsibility, :patientResponsibility)
            RETURNING $COLUMNS
            """,
        )
        .param("claimId", claimId)
        .param("organizationId", organizationId)
        .param("outcome", outcome)
        .param("totalCharge", totalCharge)
        .param("totalAllowed", totalAllowed)
        .param("totalAdjustment", totalAdjustment)
        .param("payerResponsibility", payerResponsibility)
        .param("patientResponsibility", patientResponsibility)
        .query(MAPPER)
        .single()

    /** The latest decision on the claim, which is the one the UI shows. */
    fun findLatestForClaim(claimId: Int): Adjudication? = jdbc
        .sql("$SELECT WHERE claim_id = :claimId ORDER BY id DESC LIMIT 1")
        .param("claimId", claimId)
        .query(MAPPER)
        .optional()
        .orElse(null)

    private companion object {

        const val COLUMNS = """
            id, claim_id AS "claimId", organization_id AS "organizationId",
            adjudicated_at AS "adjudicatedAt", outcome,
            total_charge AS "totalCharge", total_allowed AS "totalAllowed",
            total_adjustment AS "totalAdjustment",
            payer_responsibility AS "payerResponsibility",
            patient_responsibility AS "patientResponsibility"
        """

        const val SELECT = "SELECT $COLUMNS FROM adjudications"

        val MAPPER = RowMapper { rs, _ ->
            Adjudication(
                rs.getInt("id"),
                rs.getInt("claimId"),
                rs.getInt("organizationId"),
                rs.getObject("adjudicatedAt", OffsetDateTime::class.java).toInstant(),
                rs.getString("outcome"),
                rs.getBigDecimal("totalCharge"),
                rs.getBigDecimal("totalAllowed"),
                rs.getBigDecimal("totalAdjustment"),
                rs.getBigDecimal("payerResponsibility"),
                rs.getBigDecimal("patientResponsibility"),
            )
        }
    }
}
