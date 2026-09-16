package com.htt.billing.security

import java.sql.Types
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Roles, their permissions, and role assignments. The assignment row is the
 * pair (who, which role, in which organization) — read as one query per user,
 * never as a `users.role` column.
 */
@Repository
class RbacRepository(private val jdbc: JdbcClient) {

    /**
     * One permission a user holds and the scope it was granted at. A null
     * [organizationId] is a platform-scoped grant.
     */
    data class Grant(val permission: String, val organizationId: Int?)

    data class Role(val id: Int, val code: String, val scopeType: String)

    /**
     * Every permission the user holds, through every active assignment. Inactive
     * roles and assignments contribute nothing, so revoking is a flag flip.
     */
    fun permissionGrants(userId: Int): List<Grant> = jdbc
        .sql(
            """
            SELECT p.code          AS "permission",
                   a.organization_id AS "organizationId"
            FROM user_role_assignments a
            JOIN roles r ON r.id = a.role_id AND r.active
            JOIN role_permissions rp ON rp.role_id = r.id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE a.user_id = :userId AND a.active
            """,
        )
        .param("userId", userId)
        .query(
            RowMapper { rs, _ ->
                Grant(
                    rs.getString("permission"),
                    (rs.getObject("organizationId") as? Number)?.toInt(),
                )
            },
        )
        .list()

    fun findRoleByCode(code: String): Role? = jdbc
        .sql("SELECT id, code, scope_type AS \"scopeType\" FROM roles WHERE code = :code AND active")
        .param("code", code)
        .query(Role::class.java)
        .optional()
        .orElse(null)

    /** @return the new assignment id. */
    fun insertAssignment(userId: Int, roleId: Int, organizationId: Int?, createdBy: Int?): Int = jdbc
        .sql(
            """
            INSERT INTO user_role_assignments (user_id, role_id, organization_id, created_by)
            VALUES (:userId, :roleId, :organizationId, :createdBy)
            RETURNING id
            """,
        )
        .param("userId", userId)
        .param("roleId", roleId)
        .param("organizationId", organizationId)
        .param("createdBy", createdBy)
        .query(Int::class.javaObjectType)
        .single()

    /**
     * @return the new assignment id, or null when the user already holds that role
     *         at that scope — so a seed can run on every boot without failing.
     */
    fun insertAssignmentIfAbsent(
        userId: Int,
        roleId: Int,
        organizationId: Int?,
        createdBy: Int?,
    ): Int? = jdbc
        .sql(
            """
            INSERT INTO user_role_assignments (user_id, role_id, organization_id, created_by)
            VALUES (:userId, :roleId, :organizationId, :createdBy)
            ON CONFLICT DO NOTHING
            RETURNING id
            """,
        )
        .param("userId", userId)
        .param("roleId", roleId)
        .param("organizationId", organizationId, Types.INTEGER)
        .param("createdBy", createdBy, Types.INTEGER)
        .query(Int::class.javaObjectType)
        .optional()
        .orElse(null)

    fun permissionCodes(): List<String> = jdbc
        .sql("SELECT code FROM permissions ORDER BY code")
        .query(String::class.java)
        .list()

    fun roleCodes(): List<String> = jdbc
        .sql("SELECT code FROM roles ORDER BY code")
        .query(String::class.java)
        .list()
}
