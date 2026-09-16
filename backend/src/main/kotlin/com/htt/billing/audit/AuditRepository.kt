package com.htt.billing.audit

import java.sql.Types
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
}
