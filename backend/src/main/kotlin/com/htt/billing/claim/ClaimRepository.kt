package com.htt.billing.claim

import java.math.BigDecimal
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Every `claims` query, plus the diagnoses and lines that belong to the claim —
 * one repository because the claim is the aggregate root and nothing reaches a
 * line except through it.
 *
 * There is no `total_charge` column: a stored total is a second copy of the line
 * charges that a bug can desync. It is summed on read instead.
 */
@Repository
class ClaimRepository(private val jdbc: JdbcClient) {

    data class Claim(
        val id: Int,
        val organizationId: Int,
        val claimNumber: String,
        val patientId: Int,
        val providerId: Int,
        val coverageId: Int,
        val payerId: Int,
        val serviceDate: LocalDate?,
        val status: ClaimStatus,
        val submissionVersion: Int,
        val submittedAt: Instant?,
        /*
         * The payer's reason for rejecting the claim, and null on any claim it
         * did not reject. It describes the status, so it is replaced by the next
         * payer answer rather than kept as a history.
         */
        val rejectionCode: String?,
        val rejectionMessage: String?,
        val rejectedAt: Instant?,
    )

    /** A claim plus the figure the list needs, which comes from its lines. */
    data class ClaimSummary(val claim: Claim, val totalCharge: BigDecimal)

    data class Diagnosis(val id: Int, val claimId: Int, val diagnosisCode: String, val sequence: Int)

    data class Line(
        val id: Int,
        val claimId: Int,
        val lineNumber: Int,
        val procedureCode: String,
        val quantity: Int,
        val chargeAmount: BigDecimal,
        val allowedAmount: BigDecimal?,
        val adjustmentAmount: BigDecimal?,
        val payerAmount: BigDecimal?,
        val patientResponsibility: BigDecimal?,
        val status: String,
    )

    /** A line as submitted, before any of the payer's figures exist. */
    data class LineInput(
        val lineNumber: Int,
        val procedureCode: String,
        val quantity: Int,
        val chargeAmount: BigDecimal
    )

    data class PricedLine(
        val allowedAmount: BigDecimal?,
        val adjustmentAmount: BigDecimal?,
        val payerAmount: BigDecimal?,
        val patientResponsibility: BigDecimal?,
        val status: String,
    )

    fun findById(id: Int): Claim? = jdbc
        .sql("$SELECT WHERE id = :id")
        .param("id", id)
        .query(MAPPER)
        .optional()
        .orElse(null)

    fun findIn(organizationIds: List<Int>, patientId: Int?): List<ClaimSummary> {
        val patientFilter = if (patientId == null) "" else " AND patient_id = :patientId"
        return jdbc
            .sql(
                """
                SELECT $COLUMNS,
                       COALESCE((SELECT sum(l.charge_amount) FROM claim_lines l WHERE l.claim_id = claims.id), 0)
                           AS "totalCharge"
                FROM claims
                WHERE organization_id IN (:organizationIds)$patientFilter
                ORDER BY created_at DESC, id DESC
                """,
            )
            .param("organizationIds", organizationIds)
            .apply { if (patientId != null) param("patientId", patientId) }
            .query(
                RowMapper { rs, _ ->
                    ClaimSummary(MAPPER.mapRow(rs, 0)!!, rs.getBigDecimal("totalCharge"))
                },
            )
            .list()
    }

    /**
     * The claim number comes from a sequence here rather than from the id, so it
     * is allocated in the same statement that inserts the row.
     */
    fun insert(
        organizationId: Int,
        patientId: Int,
        providerId: Int,
        coverageId: Int,
        payerId: Int,
        serviceDate: LocalDate?,
    ): Claim = jdbc
        .sql(
            """
            INSERT INTO claims
                (organization_id, claim_number, patient_id, provider_id, coverage_id,
                 payer_id, service_date)
            VALUES
                (:organizationId, 'CLM-' || lpad(nextval('claim_numbers')::text, 6, '0'),
                 :patientId, :providerId, :coverageId, :payerId, :serviceDate)
            RETURNING $COLUMNS
            """,
        )
        .param("organizationId", organizationId)
        .param("patientId", patientId)
        .param("providerId", providerId)
        .param("coverageId", coverageId)
        .param("payerId", payerId)
        .param("serviceDate", serviceDate, Types.DATE)
        .query(MAPPER)
        .single()

    fun updateHeader(
        id: Int,
        patientId: Int,
        providerId: Int,
        coverageId: Int,
        payerId: Int,
        serviceDate: LocalDate?,
    ): Claim? = jdbc
        .sql(
            """
            UPDATE claims
            SET patient_id = :patientId, provider_id = :providerId,
                coverage_id = :coverageId, payer_id = :payerId,
                service_date = :serviceDate, updated_at = now()
            WHERE id = :id
            RETURNING $COLUMNS
            """,
        )
        .param("id", id)
        .param("patientId", patientId)
        .param("providerId", providerId)
        .param("coverageId", coverageId)
        .param("payerId", payerId)
        .param("serviceDate", serviceDate, Types.DATE)
        .query(MAPPER)
        .optional()
        .orElse(null)

    /**
     * Moves the claim, and records the payer's rejection in the same statement:
     * every call passes the rejection it has, so a claim that is not being
     * rejected has the previous one cleared. `submission_version` counts a move
     * into RESUBMITTED, because that move is what a resubmission is.
     */
    fun updateStatus(
        id: Int,
        status: ClaimStatus,
        submittedAt: Instant?,
        rejectionCode: String? = null,
        rejectionMessage: String? = null,
    ): Claim? = jdbc
        .sql(
            """
            UPDATE claims
            SET status = :status,
                submitted_at = COALESCE(:submittedAt, submitted_at),
                submission_version = submission_version
                    + CASE WHEN :status = 'RESUBMITTED' THEN 1 ELSE 0 END,
                rejection_code = :rejectionCode,
                rejection_message = :rejectionMessage,
                rejected_at = :rejectedAt,
                updated_at = now()
            WHERE id = :id
            RETURNING $COLUMNS
            """,
        )
        .param("id", id)
        .param("status", status.name)
        .param("submittedAt", submittedAt?.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("rejectionCode", rejectionCode, Types.VARCHAR)
        .param("rejectionMessage", rejectionMessage, Types.VARCHAR)
        .param(
            "rejectedAt",
            if (rejectionCode == null) null else Instant.now().atOffset(ZoneOffset.UTC),
            Types.TIMESTAMP_WITH_TIMEZONE,
        )
        .query(MAPPER)
        .optional()
        .orElse(null)

    fun findDiagnoses(claimId: Int): List<Diagnosis> = jdbc
        .sql(
            """
            SELECT id, claim_id AS "claimId", diagnosis_code AS "diagnosisCode", sequence
            FROM claim_diagnoses WHERE claim_id = :claimId ORDER BY sequence ASC
            """,
        )
        .param("claimId", claimId)
        .query(Diagnosis::class.java)
        .list()

    /** Replaces the diagnosis list wholesale; the claim form sends what it holds. */
    fun replaceDiagnoses(claimId: Int, diagnosisCodes: List<String>) {
        jdbc.sql("DELETE FROM claim_diagnoses WHERE claim_id = :claimId").param("claimId", claimId).update()
        diagnosisCodes.forEachIndexed { index, code ->
            jdbc.sql(
                """
                INSERT INTO claim_diagnoses (claim_id, diagnosis_code, sequence)
                VALUES (:claimId, :code, :sequence)
                """,
            )
                .param("claimId", claimId)
                .param("code", code)
                .param("sequence", index + 1)
                .update()
        }
    }

    fun findLines(claimId: Int): List<Line> = jdbc
        .sql("$SELECT_LINE WHERE claim_id = :claimId ORDER BY line_number ASC")
        .param("claimId", claimId)
        .query(LINE_MAPPER)
        .list()

    fun replaceLines(claimId: Int, lines: List<LineInput>) {
        jdbc.sql("DELETE FROM claim_lines WHERE claim_id = :claimId").param("claimId", claimId).update()
        lines.forEach { line ->
            jdbc.sql(
                """
                INSERT INTO claim_lines (claim_id, line_number, procedure_code, quantity, charge_amount)
                VALUES (:claimId, :lineNumber, :procedureCode, :quantity, :chargeAmount)
                """,
            )
                .param("claimId", claimId)
                .param("lineNumber", line.lineNumber)
                .param("procedureCode", line.procedureCode)
                .param("quantity", line.quantity)
                .param("chargeAmount", line.chargeAmount)
                .update()
        }
    }

    fun priceLine(lineId: Int, priced: PricedLine) {
        jdbc.sql(
            """
            UPDATE claim_lines
            SET allowed_amount = :allowed, adjustment_amount = :adjustment,
                payer_amount = :payer, patient_responsibility = :patient, status = :status
            WHERE id = :id
            """,
        )
            .param("id", lineId)
            .param("allowed", priced.allowedAmount, Types.NUMERIC)
            .param("adjustment", priced.adjustmentAmount, Types.NUMERIC)
            .param("payer", priced.payerAmount, Types.NUMERIC)
            .param("patient", priced.patientResponsibility, Types.NUMERIC)
            .param("status", priced.status)
            .update()
    }

    private companion object {

        const val COLUMNS = """
            id, organization_id AS "organizationId", claim_number AS "claimNumber",
            patient_id AS "patientId", provider_id AS "providerId",
            coverage_id AS "coverageId", payer_id AS "payerId",
            service_date AS "serviceDate", status,
            submission_version AS "submissionVersion", submitted_at AS "submittedAt",
            rejection_code AS "rejectionCode",
            rejection_message AS "rejectionMessage", rejected_at AS "rejectedAt"
        """

        // Unqualified on purpose: RETURNING cannot reference a table alias, so one
        // column list has to serve both the selects and the returning clauses.
        const val SELECT = "SELECT $COLUMNS FROM claims"

        const val LINE_COLUMNS = """
            id, claim_id AS "claimId", line_number AS "lineNumber",
            procedure_code AS "procedureCode", quantity,
            charge_amount AS "chargeAmount", allowed_amount AS "allowedAmount",
            adjustment_amount AS "adjustmentAmount", payer_amount AS "payerAmount",
            patient_responsibility AS "patientResponsibility", status
        """

        const val SELECT_LINE = "SELECT $LINE_COLUMNS FROM claim_lines"

        val MAPPER = RowMapper { rs, _ ->
            Claim(
                rs.getInt("id"),
                rs.getInt("organizationId"),
                rs.getString("claimNumber"),
                rs.getInt("patientId"),
                rs.getInt("providerId"),
                rs.getInt("coverageId"),
                rs.getInt("payerId"),
                rs.getObject("serviceDate", LocalDate::class.java),
                ClaimStatus.valueOf(rs.getString("status")),
                rs.getInt("submissionVersion"),
                rs.getObject("submittedAt", OffsetDateTime::class.java)?.toInstant(),
                rs.getString("rejectionCode"),
                rs.getString("rejectionMessage"),
                rs.getObject("rejectedAt", OffsetDateTime::class.java)?.toInstant(),
            )
        }

        val LINE_MAPPER = RowMapper { rs, _ ->
            Line(
                rs.getInt("id"),
                rs.getInt("claimId"),
                rs.getInt("lineNumber"),
                rs.getString("procedureCode"),
                rs.getInt("quantity"),
                rs.getBigDecimal("chargeAmount"),
                rs.getBigDecimal("allowedAmount"),
                rs.getBigDecimal("adjustmentAmount"),
                rs.getBigDecimal("payerAmount"),
                rs.getBigDecimal("patientResponsibility"),
                rs.getString("status"),
            )
        }
    }
}
