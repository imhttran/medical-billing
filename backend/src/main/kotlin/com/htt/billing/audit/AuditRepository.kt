package com.htt.billing.audit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * The audit trail for billing and access-management actions.
 *
 * [metadataJson] is a JSON object built by the caller. Keep it to ids, codes and
 * counts — no patient information, matching the plan's data-handling baseline.
 */
@Repository
class AuditRepository(private val jdbc: JdbcClient) {

    /**
     * One thing that happened.
     *
     * [organizationId] is null for a platform-scoped act — one that belongs to no
     * single practice, such as a platform-wide role assignment. Those are not in
     * any practice's history, so a practice-scoped reader never sees them.
     */
    data class Event(
        val id: Int,
        val organizationId: Int?,
        val userId: Int?,
        val action: String,
        val entityType: String,
        val entityId: String?,
        val timestamp: Instant,
        val metadata: Map<String, Any?>,
    )

    fun record(
        userId: Int?,
        organizationId: Int?,
        action: String,
        entityType: String,
        entityId: String?,
        metadataJson: String = "{}",
    ) {
        jdbc.sql(
            """
            INSERT INTO audit_events
                (organization_id, user_id, action, entity_type, entity_id, metadata)
            VALUES
                (:organizationId, :userId, :action, :entityType, :entityId, CAST(:metadata AS jsonb))
            """,
        )
            // Nullable columns carry an explicit SQL type: the driver cannot
            // infer one from a bare null.
            .param("organizationId", organizationId, Types.INTEGER)
            .param("userId", userId, Types.INTEGER)
            .param("action", action)
            .param("entityType", entityType)
            .param("entityId", entityId)
            .param("metadata", metadataJson)
            .update()
    }

    /**
     * The history of [organizationIds], newest first, with the platform-scoped
     * events included only for a reader who is entitled to them.
     *
     * The practice filter and the action filter are optional; the limit is not,
     * because an unbounded audit query is a way to take the database down.
     */
    fun find(
        organizationIds: List<Int>,
        includePlatformEvents: Boolean,
        organizationId: Int?,
        action: String?,
        limit: Int,
    ): List<Event> = jdbc
        .sql(
            """
            SELECT id, organization_id AS "organizationId", user_id AS "userId",
                   action, entity_type AS "entityType", entity_id AS "entityId",
                   "timestamp", metadata
            FROM audit_events
            WHERE (organization_id IN (:organizationIds)
                   OR (:includePlatformEvents AND organization_id IS NULL))
              AND (:organizationId IS NULL OR organization_id = :organizationId)
              AND (:action IS NULL OR action = :action)
            ORDER BY "timestamp" DESC, id DESC
            LIMIT :limit
            """,
        )
        .param("organizationIds", organizationIds)
        .param("includePlatformEvents", includePlatformEvents, Types.BOOLEAN)
        .param("organizationId", organizationId, Types.INTEGER)
        .param("action", action, Types.VARCHAR)
        .param("limit", limit)
        .query(MAPPER)
        .list()

    private companion object {

        /**
         * `metadata` is returned as the object it is rather than as a JSON string,
         * so a caller reads `{"roleCode":"BILLER"}` and not a quoted blob.
         */
        val MAPPER = RowMapper { rs, _ ->
            Event(
                id = rs.getInt("id"),
                organizationId = (rs.getObject("organizationId") as? Number)?.toInt(),
                userId = (rs.getObject("userId") as? Number)?.toInt(),
                action = rs.getString("action"),
                entityType = rs.getString("entityType"),
                entityId = rs.getString("entityId"),
                timestamp = rs.getObject("timestamp", OffsetDateTime::class.java).toInstant(),
                metadata = readMetadata(rs.getString("metadata")),
            )
        }

        private val mapper = jacksonObjectMapper()

        @Suppress("UNCHECKED_CAST")
        private fun readMetadata(json: String?): Map<String, Any?> =
            if (json.isNullOrBlank()) {
                emptyMap()
            } else {
                mapper.readValue(json, Map::class.java) as Map<String, Any?>
            }
    }
}
