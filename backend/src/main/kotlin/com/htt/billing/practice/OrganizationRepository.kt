package com.htt.billing.practice

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Every `organizations` query. A practice is the tenant boundary — every
 * tenant-owned billing record carries its id.
 */
@Repository
class OrganizationRepository(private val jdbc: JdbcClient) {

    data class Organization(
        val id: Int,
        val name: String,
        val npi: String?,
        val taxId: String?,
        val active: Boolean,
    )

    fun findAllActive(): List<Organization> = jdbc
        .sql("$SELECT_ACTIVE WHERE active ORDER BY name ASC")
        .query(MAPPER)
        .list()

    /** The demo reset finds its practice by name, since the id is not fixed. */
    fun findByName(name: String): Organization? = jdbc
        .sql("$SELECT_ACTIVE WHERE name = :name")
        .param("name", name)
        .query(MAPPER)
        .optional()
        .orElse(null)

    /** The caller has already decided these ids are permitted. */
    fun findActiveByIds(ids: List<Int>): List<Organization> = jdbc
        .sql("$SELECT_ACTIVE WHERE active AND id IN (:ids) ORDER BY name ASC")
        .param("ids", ids)
        .query(MAPPER)
        .list()

    fun insert(name: String, npi: String?, taxId: String?): Organization = jdbc
        .sql(
            """
            INSERT INTO organizations (name, npi, tax_id)
            VALUES (:name, :npi, :taxId)
            RETURNING id, name, npi, tax_id AS "taxId", active
            """,
        )
        .param("name", name)
        .param("npi", npi)
        .param("taxId", taxId)
        .query(MAPPER)
        .single()

    private companion object {

        const val SELECT_ACTIVE =
            "SELECT id, name, npi, tax_id AS \"taxId\", active FROM organizations"

        val MAPPER = RowMapper { rs, _ ->
            Organization(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("npi"),
                rs.getString("taxId"),
                rs.getBoolean("active"),
            )
        }
    }
}
