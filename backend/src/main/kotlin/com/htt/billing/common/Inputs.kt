package com.htt.billing.common

import com.htt.billing.common.error.ValidationException
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Turning decoded request text into domain values, with the 400s in one place.
 * Request bodies default every field to `""`, so blank reads as absent.
 */
object Inputs {

    fun requiredText(value: String, field: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            throw ValidationException("$field is required")
        }
        return trimmed
    }

    /** Blank becomes null, so an omitted optional field is stored as NULL. */
    fun optionalText(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    fun requiredDate(value: String, field: String): LocalDate =
        parseDate(requiredText(value, field), field)

    fun optionalDate(value: String?, field: String): LocalDate? =
        optionalText(value)?.let { parseDate(it, field) }

    /**
     * A LIKE pattern for a name or code search. The metacharacters are escaped so
     * a literal `%` in the query matches a percent sign rather than every row.
     * Postgres treats backslash as the escape character unless told otherwise.
     */
    fun containsPattern(query: String): String =
        "%" + query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%"

    private fun parseDate(raw: String, field: String): LocalDate = try {
        LocalDate.parse(raw)
    } catch (notADate: DateTimeParseException) {
        throw ValidationException("$field must be a date as YYYY-MM-DD")
    }
}
