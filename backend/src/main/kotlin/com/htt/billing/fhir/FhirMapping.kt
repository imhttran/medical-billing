package com.htt.billing.fhir

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.TimeZone
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Money
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.Reference
import ca.uhn.fhir.model.api.TemporalPrecisionEnum

/**
 * What a billing application has to know about FHIR, in one place: the code systems
 * it writes, how an external identifier is kept, and the small translations between
 * the two vocabularies.
 *
 * Pure, so the parts of the mapping that are not about our own rows can be tested
 * without a database.
 *
 * Terminology systems are named as FHIR names them. The CPT system URI is the one
 * HL7 publishes for it, and only a handful of codes exist in this deployment: the
 * full code set has licensing terms and is not redistributed (see docs/FEATURE.md).
 */
object FhirMapping {

    /** Code systems, spelled out rather than abbreviated at the call site. */
    object Systems {
        const val ICD_10_CM = "http://hl7.org/fhir/sid/icd-10-cm"
        const val CPT = "http://www.ama-assn.org/go/cpt"
        const val HCPCS = "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets"
        const val CLAIM_TYPE = "http://terminology.hl7.org/CodeSystem/claim-type"
        const val ADJUDICATION = "http://terminology.hl7.org/CodeSystem/adjudication"
        const val NPI = "http://hl7.org/fhir/sid/us-npi"
        const val V3_ACT_CODE = "http://terminology.hl7.org/CodeSystem/v3-ActCode"
    }

    /** The adjudication categories an exported result is broken into. */
    object Adjudication {
        const val SUBMITTED = "submitted"
        const val ALLOWED = "allowed"
        const val DEDUCTION = "deduction"
        const val COPAY = "copay"
        const val BENEFIT = "benefit"
    }

    /**
     * An external identifier as one string: `system|value` when the source system
     * named one, the bare value when it did not.
     *
     * One string because that is what the column holds, and reversible because an
     * export splits it back out — a bundle that is imported and then exported gives
     * the identifier back the way it came in.
     */
    fun externalIdentifier(system: String?, value: String): String =
        if (system.isNullOrBlank()) value else "$system|$value"

    /** @return the system and value [externalIdentifier] joined, or null for empty. */
    fun splitIdentifier(identifier: String?): Pair<String?, String>? {
        val text = identifier?.trim().orEmpty()
        if (text.isEmpty()) {
            return null
        }
        val separator = text.indexOf('|')
        return if (separator < 0) {
            null to text
        } else {
            text.substring(0, separator) to text.substring(separator + 1)
        }
    }

    /** Our `sex` vocabulary from FHIR's administrative gender. Null when absent. */
    fun sex(gender: Enumerations.AdministrativeGender?): String? = when (gender) {
        Enumerations.AdministrativeGender.MALE -> "MALE"
        Enumerations.AdministrativeGender.FEMALE -> "FEMALE"
        Enumerations.AdministrativeGender.OTHER -> "OTHER"
        Enumerations.AdministrativeGender.UNKNOWN -> "UNKNOWN"
        else -> null
    }

    /** FHIR's administrative gender from ours: a null in, a null out. */
    fun gender(sex: String?): Enumerations.AdministrativeGender? = when (sex?.uppercase()) {
        "MALE" -> Enumerations.AdministrativeGender.MALE
        "FEMALE" -> Enumerations.AdministrativeGender.FEMALE
        "OTHER" -> Enumerations.AdministrativeGender.OTHER
        "UNKNOWN" -> Enumerations.AdministrativeGender.UNKNOWN
        else -> null
    }

    /** A code in its system, which is all a CodeableConcept is here. */
    fun coded(system: String, code: String, display: String? = null): CodeableConcept =
        CodeableConcept(Coding(system, code, display))

    /** A diagnosis, whose system is ICD-10-CM. */
    fun icd10(code: String, display: String? = null): CodeableConcept =
        coded(Systems.ICD_10_CM, code, display)

    /** A service, whose system depends on whether the code is CPT or HCPCS. */
    fun procedure(code: String, codeSystem: String?, display: String? = null): CodeableConcept =
        coded(if (codeSystem.equals("HCPCS", ignoreCase = true)) Systems.HCPCS else Systems.CPT, code, display)

    /** FHIR money is a value and a currency; every figure here is in US dollars. */
    fun money(amount: BigDecimal?): Money {
        val money = Money()
        if (amount != null) {
            money.value = amount.setScale(2, RoundingMode.HALF_UP)
            money.currency = "USD"
        }
        return money
    }

    fun quantity(value: Int): Quantity = Quantity(value.toLong())

    /** A reference to a resource in this deployment, with its identifier when known. */
    fun reference(
        resourceType: String,
        id: Int,
        identifier: String? = null,
        display: String? = null,
    ): Reference {
        val reference = Reference("$resourceType/$id")
        splitIdentifier(identifier)?.let { (system, value) ->
            reference.identifier.system = system
            reference.identifier.value = value
        }
        if (!display.isNullOrBlank()) {
            reference.display = display
        }
        return reference
    }

    /**
     * Primitives take a Date, and HAPI formats them in the server's own time zone
     * unless it is told otherwise — which turns a service date of 2026-03-02 into
     * 2026-03-01 on a server west of UTC. So date-only elements are pinned to UTC and
     * to day precision: a service date is a day, not an instant.
     */
    fun dateOnly(on: LocalDate): DateTimeType = DateTimeType().apply {
        timeZone = TimeZone.getTimeZone("UTC")
        value = Date.from(on.atStartOfDay(ZoneOffset.UTC).toInstant())
        precision = TemporalPrecisionEnum.DAY
    }

    /** The same, for an element FHIR types as a `date` rather than a `dateTime`. */
    fun dateOnlyElement(on: LocalDate): DateType = DateType().apply {
        timeZone = TimeZone.getTimeZone("UTC")
        value = Date.from(on.atStartOfDay(ZoneOffset.UTC).toInstant())
        precision = TemporalPrecisionEnum.DAY
    }

    /** An instant, in UTC, so two servers export the same moment the same way. */
    fun instant(at: Instant): DateTimeType = DateTimeType().apply {
        timeZone = TimeZone.getTimeZone("UTC")
        value = Date.from(at)
        precision = TemporalPrecisionEnum.MILLI
    }
}
