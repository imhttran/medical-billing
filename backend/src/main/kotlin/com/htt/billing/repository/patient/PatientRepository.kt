package com.htt.billing.repository.patient

import java.sql.Types
import java.time.LocalDate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Every `patients` query. A patient is tenant-owned, so nothing here is queried
 * by id alone — the caller passes the organization ids it is allowed to see.
 */
@Repository
class PatientRepository(private val jdbc: JdbcClient) {

    data class Patient(
        val id: Int,
        val organizationId: Int,
        val externalId: String?,
        val firstName: String,
        val lastName: String,
        val dateOfBirth: LocalDate,
        val sex: String?,
        val addressLine1: String?,
        val addressLine2: String?,
        val city: String?,
        val state: String?,
        val postalCode: String?,
        val phone: String?,
        val active: Boolean,
    )

    fun findById(id: Int): Patient? = jdbc
        .sql("$SELECT WHERE id = :id")
        .param("id", id)
        .query(MAPPER)
        .optional()
        .orElse(null)

    /**
     * The patient a source system means, by the identifier it used for them. That
     * is what makes a re-import an update rather than a second record.
     */
    fun findByExternalId(organizationId: Int, externalId: String): Patient? = jdbc
        .sql("$SELECT WHERE organization_id = :organizationId AND external_id = :externalId")
        .param("organizationId", organizationId)
        .param("externalId", externalId)
        .query(MAPPER)
        .optional()
        .orElse(null)

    fun countInOrganization(organizationId: Int): Int = jdbc
        .sql("SELECT count(*) FROM patients WHERE organization_id = :organizationId")
        .param("organizationId", organizationId)
        .query(Int::class.javaObjectType)
        .single()

    /** @return rows deleted. Coverages cascade from here. */
    fun deleteInOrganization(organizationId: Int): Int = jdbc
        .sql("DELETE FROM patients WHERE organization_id = :organizationId")
        .param("organizationId", organizationId)
        .update()

    /**
     * Patients in [organizationIds], optionally narrowed by an already-built LIKE
     * [namePattern] (see [com.htt.billing.common.Inputs.containsPattern]).
     * [organizationIds] must already be the caller's permitted set.
     */
    fun findIn(organizationIds: List<Int>, namePattern: String?): List<Patient> {
        val nameFilter = if (namePattern.isNullOrBlank()) {
            ""
        } else {
            " AND (first_name ILIKE :pattern OR last_name ILIKE :pattern)"
        }
        return jdbc
            .sql("$SELECT WHERE organization_id IN (:organizationIds)$nameFilter$ORDER")
            .param("organizationIds", organizationIds)
            .apply { if (!namePattern.isNullOrBlank()) param("pattern", namePattern) }
            .query(MAPPER)
            .list()
    }

    fun insert(
        organizationId: Int,
        externalId: String?,
        firstName: String,
        lastName: String,
        dateOfBirth: LocalDate,
        sex: String?,
        addressLine1: String?,
        addressLine2: String?,
        city: String?,
        state: String?,
        postalCode: String?,
        phone: String?,
    ): Patient = jdbc
        .sql(
            """
            INSERT INTO patients
                (organization_id, external_id, first_name, last_name, date_of_birth, sex,
                 address_line1, address_line2, city, state, postal_code, phone)
            VALUES
                (:organizationId, :externalId, :firstName, :lastName, :dateOfBirth, :sex,
                 :addressLine1, :addressLine2, :city, :state, :postalCode, :phone)
            RETURNING $COLUMNS
            """,
        )
        .param("organizationId", organizationId)
        .param("externalId", externalId, Types.VARCHAR)
        .param("firstName", firstName)
        .param("lastName", lastName)
        .param("dateOfBirth", dateOfBirth)
        .param("sex", sex, Types.VARCHAR)
        .param("addressLine1", addressLine1, Types.VARCHAR)
        .param("addressLine2", addressLine2, Types.VARCHAR)
        .param("city", city, Types.VARCHAR)
        .param("state", state, Types.VARCHAR)
        .param("postalCode", postalCode, Types.VARCHAR)
        .param("phone", phone, Types.VARCHAR)
        .query(MAPPER)
        .single()

    /** @return the updated row, or null when there is no such patient. */
    fun update(
        id: Int,
        firstName: String,
        lastName: String,
        dateOfBirth: LocalDate,
        sex: String?,
        addressLine1: String?,
        addressLine2: String?,
        city: String?,
        state: String?,
        postalCode: String?,
        phone: String?,
    ): Patient? = jdbc
        .sql(
            """
            UPDATE patients
            SET first_name = :firstName, last_name = :lastName, date_of_birth = :dateOfBirth,
                sex = :sex, address_line1 = :addressLine1, address_line2 = :addressLine2,
                city = :city, state = :state, postal_code = :postalCode, phone = :phone,
                updated_at = now()
            WHERE id = :id
            RETURNING $COLUMNS
            """,
        )
        .param("id", id)
        .param("firstName", firstName)
        .param("lastName", lastName)
        .param("dateOfBirth", dateOfBirth)
        .param("sex", sex, Types.VARCHAR)
        .param("addressLine1", addressLine1, Types.VARCHAR)
        .param("addressLine2", addressLine2, Types.VARCHAR)
        .param("city", city, Types.VARCHAR)
        .param("state", state, Types.VARCHAR)
        .param("postalCode", postalCode, Types.VARCHAR)
        .param("phone", phone, Types.VARCHAR)
        .query(MAPPER)
        .optional()
        .orElse(null)

    private companion object {

        const val COLUMNS = """
            id, organization_id AS "organizationId", external_id AS "externalId",
            first_name AS "firstName", last_name AS "lastName",
            date_of_birth AS "dateOfBirth", sex,
            address_line1 AS "addressLine1", address_line2 AS "addressLine2",
            city, state, postal_code AS "postalCode", phone, active
        """

        const val SELECT = "SELECT $COLUMNS FROM patients"

        const val ORDER = " ORDER BY last_name ASC, first_name ASC, id ASC"

        val MAPPER = RowMapper { rs, _ ->
            Patient(
                rs.getInt("id"),
                rs.getInt("organizationId"),
                rs.getString("externalId"),
                rs.getString("firstName"),
                rs.getString("lastName"),
                rs.getObject("dateOfBirth", LocalDate::class.java),
                rs.getString("sex"),
                rs.getString("addressLine1"),
                rs.getString("addressLine2"),
                rs.getString("city"),
                rs.getString("state"),
                rs.getString("postalCode"),
                rs.getString("phone"),
                rs.getBoolean("active"),
            )
        }
    }
}
