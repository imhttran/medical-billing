package com.htt.billing.repository.adjudication

import com.htt.billing.adjudication.PayerSimulator.Rate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * What each payer allows per procedure. Shared reference data, configured by
 * migration rather than through the API.
 */
@Repository
class FeeScheduleRepository(private val jdbc: JdbcClient) {

    /** Keyed by procedure code, which is what a claim line carries. */
    fun ratesFor(payerId: Int): Map<String, Rate> = jdbc
        .sql(
            """
            SELECT procedure_code AS "procedureCode",
                   allowed_amount AS "allowedAmount",
                   patient_copay  AS "patientCopay"
            FROM payer_fee_schedule
            WHERE payer_id = :payerId
            """,
        )
        .param("payerId", payerId)
        .query(
            RowMapper { rs, _ ->
                rs.getString("procedureCode") to Rate(
                    rs.getBigDecimal("allowedAmount"),
                    rs.getBigDecimal("patientCopay"),
                )
            },
        )
        .list()
        .toMap()
}
