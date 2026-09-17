package com.htt.billing.fhir

import ca.uhn.fhir.parser.DataFormatException
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That HAPI is wired up and does what the import and export rely on: it reads a
 * resource into the R4 model, it refuses one that is not JSON it recognises, and it
 * writes a resource back out. The mapping itself is covered against a real database
 * in the API tests.
 */
class FhirResourcesTest {

    private val fhir = FhirResources()

    @Test
    fun readsAResourceIntoTheR4Model() {
        val patient = fhir.parser().parseResource(
            """
            {"resourceType":"Patient",
             "identifier":[{"system":"http://hospital.example/mrn","value":"4471"}],
             "name":[{"family":"Smith","given":["Jane"]}],
             "birthDate":"1979-03-14"}
            """.trimIndent(),
        ) as Patient

        assertEquals("Smith", patient.nameFirstRep.family)
        assertEquals("Jane", patient.nameFirstRep.given.first().value)
        assertEquals("1979-03-14", patient.birthDateElement.valueAsString)
    }

    @Test
    fun readsABundleAndItsEntries() {
        val bundle = fhir.parser().parseResource(
            """
            {"resourceType":"Bundle","type":"collection","entry":[
              {"resource":{"resourceType":"Patient","name":[{"family":"Smith"}]}},
              {"resource":{"resourceType":"Practitioner","name":[{"family":"Reyes"}]}}
            ]}
            """.trimIndent(),
        ) as Bundle

        assertEquals(2, bundle.entry.size)
        assertEquals("Patient", bundle.entry[0].resource.resourceType.name)
    }

    @Test
    fun refusesWhatIsNotAFhirResource() {
        // The floor the parser gives us for nothing: a body that is not a resource,
        // and a resource whose primitives are malformed, never reach the mapping.
        assertThrows(DataFormatException::class.java) { fhir.parser().parseResource("{\"hello\":true}") }

        val badDate = assertThrows(DataFormatException::class.java) {
            fhir.parser().parseResource(
                """{"resourceType":"Patient","name":[{"family":"Smith"}],"birthDate":"not-a-date"}""",
            )
        }
        assertTrue(badDate.message.orEmpty().contains("birthDate")) { badDate.message.orEmpty() }
    }

    @Test
    fun writesAResourceBackOut() {
        val patient = Patient()
        patient.nameFirstRep.family = "Smith"
        patient.birthDateElement.valueAsString = "1979-03-14"

        val json = fhir.parser().encodeResourceToString(patient)

        // Read back rather than matched as text: the spacing is HAPI's business, the
        // content is ours.
        val read = fhir.parser().parseResource(json) as Patient
        assertEquals("Patient", read.resourceType.name)
        assertEquals("Smith", read.nameFirstRep.family)
        assertEquals("1979-03-14", read.birthDateElement.valueAsString)
    }
}
