package com.htt.billing.coverage

import java.sql.Types
import java.time.LocalDate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Every `coverages` query. A coverage carries its own organization_id so the
 * tenant check never has to walk back through the patient.
 */
@Repository
class CoverageRepository(private val jdbc: JdbcClient) {

    data class Coverage(
        val id: Int,
        val organizationId: Int,
        val patientId: Int,
        val payerId: Int,
        val memberId: String,
        val groupNumber: String?,
        val subscriberName: String?,
        val relationshipToSubscriber: String?,
        val effectiveDate: LocalDate?,
        val terminationDate: LocalDate?,
        val priority: Int,
        val active: Boolean,
    )

    fun findById(id: Int): Coverage? = jdbc
        .sql("$SELECT WHERE id = :id")
        .param("id", id)
        .query(MAPPER)
        .optional()
        .orElse(null)

    /** Primary first, which is the order the claim screen offers them in. */
    fun findByPatient(patientId: Int): List<Coverage> = jdbc
        .sql("$SELECT WHERE patient_id = :patientId ORDER BY priority ASC, id ASC")
        .param("patientId", patientId)
        .query(MAPPER)
        .list()

    /**
     * @return rows deleted. Explicit rather than relying on the cascade from
     *         patients, because the demo reset reports what it removed.
     */
    fun deleteInOrganization(organizationId: Int): Int = jdbc
        .sql("DELETE FROM coverages WHERE organization_id = :organizationId")
        .param("organizationId", organizationId)
        .update()

    fun insert(
        organizationId: Int,
        patientId: Int,
        payerId: Int,
        memberId: String,
        groupNumber: String?,
        subscriberName: String?,
        relationshipToSubscriber: String?,
        effectiveDate: LocalDate?,
        terminationDate: LocalDate?,
        priority: Int,
    ): Coverage = jdbc
        .sql(
            """
            INSERT INTO coverages
                (organization_id, patient_id, payer_id, member_id, group_number,
                 subscriber_name, relationship_to_subscriber, effective_date,
                 termination_date, priority)
            VALUES
                (:organizationId, :patientId, :payerId, :memberId, :groupNumber,
                 :subscriberName, :relationshipToSubscriber, :effectiveDate,
                 :terminationDate, :priority)
            RETURNING $COLUMNS
            """,
        )
        .param("organizationId", organizationId)
        .param("patientId", patientId)
        .param("payerId", payerId)
        .param("memberId", memberId)
        .param("groupNumber", groupNumber, Types.VARCHAR)
        .param("subscriberName", subscriberName, Types.VARCHAR)
        .param("relationshipToSubscriber", relationshipToSubscriber, Types.VARCHAR)
        .param("effectiveDate", effectiveDate, Types.DATE)
        .param("terminationDate", terminationDate, Types.DATE)
        .param("priority", priority)
        .query(MAPPER)
        .single()

    /** @return the updated row, or null when there is no such coverage. */
    fun update(
        id: Int,
        payerId: Int,
        memberId: String,
        groupNumber: String?,
        subscriberName: String?,
        relationshipToSubscriber: String?,
        effectiveDate: LocalDate?,
        terminationDate: LocalDate?,
        priority: Int,
        active: Boolean,
    ): Coverage? = jdbc
        .sql(
            """
            UPDATE coverages
            SET payer_id = :payerId, member_id = :memberId, group_number = :groupNumber,
                subscriber_name = :subscriberName,
                relationship_to_subscriber = :relationshipToSubscriber,
                effective_date = :effectiveDate, termination_date = :terminationDate,
                priority = :priority, active = :active, updated_at = now()
            WHERE id = :id
            RETURNING $COLUMNS
            """,
        )
        .param("id", id)
        .param("payerId", payerId)
        .param("memberId", memberId)
        .param("groupNumber", groupNumber, Types.VARCHAR)
        .param("subscriberName", subscriberName, Types.VARCHAR)
        .param("relationshipToSubscriber", relationshipToSubscriber, Types.VARCHAR)
        .param("effectiveDate", effectiveDate, Types.DATE)
        .param("terminationDate", terminationDate, Types.DATE)
        .param("priority", priority)
        .param("active", active)
        .query(MAPPER)
        .optional()
        .orElse(null)

    private companion object {

        const val COLUMNS = """
            id, organization_id AS "organizationId", patient_id AS "patientId",
            payer_id AS "payerId", member_id AS "memberId",
            group_number AS "groupNumber", subscriber_name AS "subscriberName",
            relationship_to_subscriber AS "relationshipToSubscriber",
            effective_date AS "effectiveDate", termination_date AS "terminationDate",
            priority, active
        """

        const val SELECT = "SELECT $COLUMNS FROM coverages"

        val MAPPER = RowMapper { rs, _ ->
            Coverage(
                rs.getInt("id"),
                rs.getInt("organizationId"),
                rs.getInt("patientId"),
                rs.getInt("payerId"),
                rs.getString("memberId"),
                rs.getString("groupNumber"),
                rs.getString("subscriberName"),
                rs.getString("relationshipToSubscriber"),
                rs.getObject("effectiveDate", LocalDate::class.java),
                rs.getObject("terminationDate", LocalDate::class.java),
                rs.getInt("priority"),
                rs.getBoolean("active"),
            )
        }
    }
}
