package com.htt.billing.service.coding

import com.htt.billing.common.Inputs
import com.htt.billing.repository.coding.CodingRepository
import com.htt.billing.repository.coding.CodingRepository.DiagnosisCode
import com.htt.billing.repository.coding.CodingRepository.ProcedureCode
import org.springframework.stereotype.Service

/**
 * Terminology search for the claim screen.
 *
 * No permission is checked beyond authentication: the code sets are the same for
 * every practice and contain no patient data, so there is nothing to scope. A
 * blank query browses from the start of the code list rather than returning
 * nothing, which is what an empty picker needs.
 */
@Service
class CodingService(private val codes: CodingRepository) {

    fun searchDiagnoses(query: String?, limit: Int?): List<DiagnosisCode> =
        codes.searchDiagnoses(patternFor(query), cappedLimit(limit))

    fun searchProcedures(query: String?, limit: Int?): List<ProcedureCode> =
        codes.searchProcedures(patternFor(query), cappedLimit(limit))

    private fun patternFor(query: String?): String =
        Inputs.optionalText(query)?.let { Inputs.containsPattern(it) } ?: "%"

    /** A cap rather than a page, so one request cannot ask for the whole table. */
    private fun cappedLimit(limit: Int?): Int = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    private companion object {
        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
    }
}
