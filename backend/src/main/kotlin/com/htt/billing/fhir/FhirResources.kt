package com.htt.billing.fhir

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.parser.IParser
import org.springframework.stereotype.Component

/**
 * The FHIR R4 context, one for the application.
 *
 * Building it is not free — HAPI assembles a model of every R4 resource — so it is
 * created once and shared, which is safe because a context is thread-safe. Parsers
 * are not, so every caller gets its own from the context rather than sharing one.
 *
 * The context is created on first use rather than at startup: a deployment that
 * never talks FHIR should not pay for the R4 model in its boot time.
 */
@Component
class FhirResources {

    private val context: FhirContext by lazy { FhirContext.forR4() }

    /** A pretty-printing parser: an exported resource is read by a person first. */
    fun parser(): IParser = context.newJsonParser().setPrettyPrint(true)
}
