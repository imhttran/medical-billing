package com.htt.billing.common

import com.htt.billing.common.error.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
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
     * Money in, cents out. Text rather than a number because a JSON number coerces
     * as written and 150.00 would arrive as a double.
     */
    fun requiredMoney(value: String, field: String): BigDecimal {
        val text = requiredText(value, field)
        return try {
            BigDecimal(text).setScale(2, RoundingMode.HALF_UP)
        } catch (notANumber: NumberFormatException) {
            throw ValidationException("$field must be a number")
        }
    }

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
