package com.htt.billing.coverage

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Every `payers` query. Payers are shared reference data, not tenant-owned, so
 * there is no organization filter anywhere here.
 */
@Repository
class PayerRepository(private val jdbc: JdbcClient) {

    data class Payer(val id: Int, val name: String, val payerCode: String, val active: Boolean)

    fun findAllActive(): List<Payer> = jdbc
        .sql("SELECT id, name, payer_code AS \"payerCode\", active FROM payers WHERE active ORDER BY name ASC")
        .query(Payer::class.java)
        .list()

    /** An inactive payer is not a valid target for a new coverage. */
    fun findActiveById(id: Int): Payer? = jdbc
        .sql(
            """
            SELECT id, name, payer_code AS "payerCode", active
            FROM payers WHERE id = :id AND active
            """,
        )
        .param("id", id)
        .query(Payer::class.java)
        .optional()
        .orElse(null)

    /** The demo reset attaches its coverage to this payer. */
    fun findActiveByCode(payerCode: String): Payer? = jdbc
        .sql(
            """
            SELECT id, name, payer_code AS "payerCode", active
            FROM payers WHERE payer_code = :payerCode AND active
            """,
        )
        .param("payerCode", payerCode)
        .query(Payer::class.java)
        .optional()
        .orElse(null)

    /**
     * A payer by its name, for an imported coverage that names its payor by display
     * rather than by code. Case-insensitive: a display name is prose, not a key.
     */
    fun findActiveByName(name: String): Payer? = jdbc
        .sql(
            """
            SELECT id, name, payer_code AS "payerCode", active
            FROM payers WHERE lower(name) = lower(:name) AND active
            """,
        )
        .param("name", name.trim())
        .query(Payer::class.java)
        .optional()
        .orElse(null)
}
