package com.htt.billing.repository.workflow

import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Every `work_items` query.
 *
 * The list joins the claim and its patient, because a queue row that does not say
 * which claim and patient it is about is not actionable — and the read is scoped
 * by organization, which the item carries itself.
 */
@Repository
class WorkItemRepository(private val jdbc: JdbcClient) {

    /** A queue row: the item, plus what identifies the claim it is about. */
    data class Row(
        val id: Int,
        val organizationId: Int,
        val claimId: Int,
        val claimNumber: String,
        val claimStatus: String,
        val patientId: Int,
        val patientName: String,
        val type: String,
        val status: String,
        val reasonCode: String,
        val reasonText: String,
        val assignedUserId: Int?,
        val assignedUserEmail: String?,
        val createdAt: Instant,
        val resolvedAt: Instant?,
    )

    fun insert(
        organizationId: Int,
        claimId: Int,
        type: String,
        reasonCode: String,
        reasonText: String,
    ) {
        jdbc.sql(
            """
            INSERT INTO work_items (organization_id, claim_id, type, reason_code, reason_text)
            VALUES (:organizationId, :claimId, :type, :reasonCode, :reasonText)
            ON CONFLICT DO NOTHING
            """,
        )
            .param("organizationId", organizationId)
            .param("claimId", claimId)
            .param("type", type)
            .param("reasonCode", reasonCode)
            .param("reasonText", reasonText)
            .update()
    }

    /**
     * Closes every open item on the claim. Called when the claim goes back to the
     * payer: the follow-up that opened them has been done.
     *
     * @return the ids resolved, so each one can be audited against its own row.
     */
    fun resolveOpenForClaim(claimId: Int, resolvedBy: Int?): List<Int> = jdbc
        .sql(
            """
            UPDATE work_items
            SET status = 'RESOLVED', resolved_at = now(), resolved_by_user_id = :resolvedBy
            WHERE claim_id = :claimId AND status = 'OPEN'
            RETURNING id
            """,
        )
        .param("claimId", claimId)
        .param("resolvedBy", resolvedBy, Types.INTEGER)
        .query(Int::class.javaObjectType)
        .list()

    fun findIn(organizationIds: List<Int>, status: String?): List<Row> {
        val statusFilter = if (status == null) "" else " AND w.status = :status"
        return jdbc
            .sql("$SELECT WHERE w.organization_id IN (:organizationIds)$statusFilter $ORDER")
            .param("organizationIds", organizationIds)
            .apply { if (status != null) param("status", status) }
            .query(MAPPER)
            .list()
    }

    fun findById(id: Int): Row? = jdbc
        .sql("$SELECT WHERE w.id = :id")
        .param("id", id)
        .query(MAPPER)
        .optional()
        .orElse(null)

    fun assign(id: Int, userId: Int): Row? {
        val updated = jdbc
            .sql("UPDATE work_items SET assigned_user_id = :userId WHERE id = :id")
            .param("userId", userId)
            .param("id", id)
            .update()
        return if (updated == 0) null else findById(id)
    }

    fun resolve(id: Int, resolvedBy: Int): Row? {
        val updated = jdbc
            .sql(
                """
                UPDATE work_items
                SET status = 'RESOLVED', resolved_at = now(), resolved_by_user_id = :resolvedBy
                WHERE id = :id AND status = 'OPEN'
                """,
            )
            .param("resolvedBy", resolvedBy)
            .param("id", id)
            .update()
        return if (updated == 0) null else findById(id)
    }

    private companion object {

        const val COLUMNS = """
            w.id, w.organization_id AS "organizationId", w.claim_id AS "claimId",
            c.claim_number AS "claimNumber", c.status AS "claimStatus",
            c.patient_id AS "patientId",
            p.first_name || ' ' || p.last_name AS "patientName",
            w.type, w.status, w.reason_code AS "reasonCode", w.reason_text AS "reasonText",
            w.assigned_user_id AS "assignedUserId", u.email AS "assignedUserEmail",
            w.created_at AS "createdAt", w.resolved_at AS "resolvedAt"
        """

        const val SELECT = """
            SELECT $COLUMNS
            FROM work_items w
            JOIN claims c ON c.id = w.claim_id
            JOIN patients p ON p.id = c.patient_id
            LEFT JOIN users u ON u.id = w.assigned_user_id
        """

        /** Open first, then newest: the queue is what is still to do. */
        const val ORDER = "ORDER BY (w.status = 'OPEN') DESC, w.created_at DESC, w.id DESC"

        val MAPPER = RowMapper { rs, _ ->
            Row(
                rs.getInt("id"),
                rs.getInt("organizationId"),
                rs.getInt("claimId"),
                rs.getString("claimNumber"),
                rs.getString("claimStatus"),
                rs.getInt("patientId"),
                rs.getString("patientName"),
                rs.getString("type"),
                rs.getString("status"),
                rs.getString("reasonCode"),
                rs.getString("reasonText"),
                rs.getObject("assignedUserId", Integer::class.java)?.toInt(),
                rs.getString("assignedUserEmail"),
                rs.getObject("createdAt", OffsetDateTime::class.java).toInstant(),
                rs.getObject("resolvedAt", OffsetDateTime::class.java)?.toInstant(),
            )
        }
    }
}
