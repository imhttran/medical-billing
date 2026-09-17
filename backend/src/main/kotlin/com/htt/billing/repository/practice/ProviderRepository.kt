package com.htt.billing.repository.practice

import java.sql.Types
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Every `providers` query. Providers are tenant-owned, like patients. */
@Repository
class ProviderRepository(private val jdbc: JdbcClient) {

    data class Provider(
        val id: Int,
        val organizationId: Int,
        val userId: Int?,
        val externalId: String?,
        val firstName: String,
        val lastName: String,
        val npi: String?,
        val taxonomyCode: String?,
        val active: Boolean,
    )

    fun findById(id: Int): Provider? = jdbc
        .sql("$SELECT WHERE id = :id")
        .param("id", id)
        .query(MAPPER)
        .optional()
        .orElse(null)

    /**
     * The provider a source system means, by the identifier it used for them, or by
     * NPI when the export carried no identifier of its own.
     */
    fun findByExternalId(organizationId: Int, externalId: String): Provider? = jdbc
        .sql("$SELECT WHERE organization_id = :organizationId AND external_id = :externalId")
        .param("organizationId", organizationId)
        .param("externalId", externalId)
        .query(MAPPER)
        .optional()
        .orElse(null)

    fun findByNpi(organizationId: Int, npi: String): Provider? = jdbc
        .sql("$SELECT WHERE organization_id = :organizationId AND npi = :npi")
        .param("organizationId", organizationId)
        .param("npi", npi)
        .query(MAPPER)
        .optional()
        .orElse(null)

    fun findIn(organizationIds: List<Int>): List<Provider> = jdbc
        .sql("$SELECT WHERE organization_id IN (:organizationIds) ORDER BY last_name ASC, first_name ASC, id ASC")
        .param("organizationIds", organizationIds)
        .query(MAPPER)
        .list()

    /** @return rows deleted. */
    fun deleteInOrganization(organizationId: Int): Int = jdbc
        .sql("DELETE FROM providers WHERE organization_id = :organizationId")
        .param("organizationId", organizationId)
        .update()

    fun insert(
        organizationId: Int,
        firstName: String,
        lastName: String,
        npi: String?,
        taxonomyCode: String?,
    ): Provider = jdbc
        .sql(
            """
            INSERT INTO providers (organization_id, first_name, last_name, npi, taxonomy_code)
            VALUES (:organizationId, :firstName, :lastName, :npi, :taxonomyCode)
            RETURNING $COLUMNS
            """,
        )
        .param("organizationId", organizationId)
        .param("firstName", firstName)
        .param("lastName", lastName)
        .param("npi", npi, Types.VARCHAR)
        .param("taxonomyCode", taxonomyCode, Types.VARCHAR)
        .query(MAPPER)
        .single()

    fun insert(
        organizationId: Int,
        firstName: String,
        lastName: String,
        npi: String?,
        taxonomyCode: String?,
        externalId: String? = null,
    ): Provider = jdbc
        .sql(
            """
            INSERT INTO providers
                (organization_id, external_id, first_name, last_name, npi, taxonomy_code)
            VALUES (:organizationId, :externalId, :firstName, :lastName, :npi, :taxonomyCode)
            RETURNING $COLUMNS
            """,
        )
        .param("organizationId", organizationId)
        .param("externalId", externalId, Types.VARCHAR)
        .param("firstName", firstName)
        .param("lastName", lastName)
        .param("npi", npi, Types.VARCHAR)
        .param("taxonomyCode", taxonomyCode, Types.VARCHAR)
        .query(MAPPER)
        .single()

    /** @return the updated row, or null when there is no such provider. */
    fun update(
        id: Int,
        firstName: String,
        lastName: String,
        npi: String?,
        taxonomyCode: String?,
        externalId: String?,
    ): Provider? = jdbc
        .sql(
            """
            UPDATE providers
            SET external_id = :externalId, first_name = :firstName, last_name = :lastName,
                npi = :npi, taxonomy_code = :taxonomyCode
            WHERE id = :id
            RETURNING $COLUMNS
            """,
        )
        .param("id", id)
        .param("externalId", externalId, Types.VARCHAR)
        .param("firstName", firstName)
        .param("lastName", lastName)
        .param("npi", npi, Types.VARCHAR)
        .param("taxonomyCode", taxonomyCode, Types.VARCHAR)
        .query(MAPPER)
        .optional()
        .orElse(null)

    private companion object {

        const val COLUMNS = """
            id, organization_id AS "organizationId", user_id AS "userId",
            external_id AS "externalId", first_name AS "firstName",
            last_name AS "lastName", npi, taxonomy_code AS "taxonomyCode", active
        """

        const val SELECT = "SELECT $COLUMNS FROM providers"

        val MAPPER = RowMapper { rs, _ ->
            Provider(
                rs.getInt("id"),
                rs.getInt("organizationId"),
                (rs.getObject("userId") as? Number)?.toInt(),
                rs.getString("externalId"),
                rs.getString("firstName"),
                rs.getString("lastName"),
                rs.getString("npi"),
                rs.getString("taxonomyCode"),
                rs.getBoolean("active"),
            )
        }
    }
}
