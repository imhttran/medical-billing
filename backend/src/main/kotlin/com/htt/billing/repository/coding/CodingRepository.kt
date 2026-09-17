package com.htt.billing.repository.coding

import java.math.BigDecimal
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * The two terminology tables. Both are read-only reference data — the code sets
 * are loaded by migration, not through the API — so this is search only.
 */
@Repository
class CodingRepository(private val jdbc: JdbcClient) {

    data class DiagnosisCode(val code: String, val description: String)

    data class ProcedureCode(
        val code: String,
        val codeSystem: String,
        val description: String,
        val defaultCharge: BigDecimal?,
    )

    /** [pattern] is an already-built LIKE pattern (see Inputs.containsPattern). */
    fun searchDiagnoses(pattern: String, limit: Int): List<DiagnosisCode> = jdbc
        .sql(
            """
            SELECT code, description
            FROM diagnosis_codes
            WHERE active AND (code ILIKE :pattern OR description ILIKE :pattern)
            ORDER BY code ASC
            LIMIT :limit
            """,
        )
        .param("pattern", pattern)
        .param("limit", limit)
        .query(RowMapper { rs, _ -> DiagnosisCode(rs.getString("code"), rs.getString("description")) })
        .list()

    fun searchProcedures(pattern: String, limit: Int): List<ProcedureCode> = jdbc
        .sql(
            """
            SELECT code, code_system AS "codeSystem", description,
                   default_charge AS "defaultCharge"
            FROM procedure_codes
            WHERE active AND (code ILIKE :pattern OR description ILIKE :pattern)
            ORDER BY code ASC
            LIMIT :limit
            """,
        )
        .param("pattern", pattern)
        .param("limit", limit)
        .query(
            RowMapper { rs, _ ->
                ProcedureCode(
                    rs.getString("code"),
                    rs.getString("codeSystem"),
                    rs.getString("description"),
                    rs.getBigDecimal("defaultCharge"),
                )
            },
        )
        .list()

    /**
     * Which of [codes] are not in the table, for validating a claim before it is
     * submitted. Blank entries are excluded: whether a code was left out is a
     * different rule from whether it exists.
     */
    fun unknownDiagnosisCodes(codes: List<String>): Set<String> =
        unknownCodes("diagnosis_codes", codes)

    fun unknownProcedureCodes(codes: List<String>): Set<String> =
        unknownCodes("procedure_codes", codes)

    /** Only ever called with the two literals above. */
    private fun unknownCodes(table: String, codes: List<String>): Set<String> {
        val wanted = codes.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) {
            return emptySet()
        }
        val known = jdbc
            .sql("SELECT code FROM $table WHERE code IN (:codes)")
            .param("codes", wanted.toList())
            .query(String::class.java)
            .list()
            .toSet()
        return wanted - known
    }
}
