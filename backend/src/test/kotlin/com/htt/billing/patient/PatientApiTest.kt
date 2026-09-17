package com.htt.billing.patient

import com.htt.billing.repository.patient.PatientRepository.Patient
import com.htt.billing.security.RoleCodes
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Patient create, read, update and search over HTTP, including the tenant
 * boundary: a patient in one practice is invisible from another.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PatientApiTest : BillingApiTest() {

    @Test
    fun createsAndReadsBackAPatient() {
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)

        val created = env.doJson("POST", "/api/patients", admin.token, patientBody(practiceA.id))
        assertStatus(201, created)
        val id = created.body.path("patient").path("id").asInt()
        assertTrue(id > 0) { "no patient id in ${created.text}" }

        val fetched = env.doJson("GET", "/api/patients/$id", admin.token, null)
        assertStatus(200, fetched)
        assertEquals("Smith", fetched.body.path("patient").path("lastName").asText())
        assertEquals("1980-04-12", fetched.body.path("patient").path("dateOfBirth").asText())
        assertEquals(practiceA.id, fetched.body.path("patient").path("organizationId").asInt())
    }

    @Test
    fun updatesAPatient() {
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val id = createPatient(admin.token, practiceA.id)

        val updated = env.doJson(
            "PUT",
            "/api/patients/$id",
            admin.token,
            patientBody(practiceA.id) + mapOf("firstName" to "Janet", "sex" to "female"),
        )
        assertStatus(200, updated)
        assertEquals("Janet", updated.body.path("patient").path("firstName").asText())
        // Normalized on the way in, so the payer and FHIR layers see one spelling.
        assertEquals("FEMALE", updated.body.path("patient").path("sex").asText())
    }

    @Test
    fun listAndSearchSeeOnlyTheCallersPractice() {
        val adminA = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        val adminB = signIn(RoleCodes.PRACTICE_ADMIN, practiceB.id)
        createPatient(adminA.token, practiceA.id, lastName = "Anderson")
        createPatient(adminB.token, practiceB.id, lastName = "Baker")

        val allForA = env.doJson("GET", "/api/patients", adminA.token, null)
        assertStatus(200, allForA)
        assertEquals(listOf("Anderson"), allForA.body.path("patients").map { it.path("lastName").asText() })

        val searched = env.doJson("GET", "/api/patients?query=ander", adminA.token, null)
        assertStatus(200, searched)
        assertEquals(listOf("Anderson"), searched.body.path("patients").map { it.path("lastName").asText() })

        // A search that matches nothing in this practice stays empty rather than
        // reaching across the boundary for Baker.
        val noMatch = env.doJson("GET", "/api/patients?query=baker", adminA.token, null)
        assertStatus(200, noMatch)
        assertTrue(noMatch.body.path("patients").isEmpty) { "leaked across practices: ${noMatch.text}" }
    }

    @Test
    fun readingOrEditingAnotherPracticesPatientIsNotFound() {
        val adminB = signIn(RoleCodes.PRACTICE_ADMIN, practiceB.id)
        val billerA = signIn(RoleCodes.BILLER, practiceA.id)
        val patientInB = createPatient(adminB.token, practiceB.id)

        // 404 rather than 403: the response must not confirm that the record
        // exists in some other practice.
        assertStatus(404, env.doJson("GET", "/api/patients/$patientInB", billerA.token, null))
        assertStatus(
            404,
            env.doJson("PUT", "/api/patients/$patientInB", billerA.token, patientBody(practiceB.id)),
        )
        // And it is absent from the list, so the id cannot be discovered either.
        val listed = env.doJson("GET", "/api/patients", billerA.token, null)
        assertTrue(listed.body.path("patients").isEmpty) { "leaked across practices: ${listed.text}" }
    }

    @Test
    fun viewWithoutCreateIsRefused() {
        val reader = signIn(RoleCodes.READ_ONLY, practiceA.id)

        assertStatus(200, env.doJson("GET", "/api/patients", reader.token, null))
        assertStatus(403, env.doJson("POST", "/api/patients", reader.token, patientBody(practiceA.id)))
    }

    @Test
    fun rejectsIncompleteOrImpossiblePatients() {
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)

        assertStatus(400, env.doJson("POST", "/api/patients", admin.token, patientBody(practiceA.id, firstName = "")))
        assertStatus(
            400,
            env.doJson(
                "POST",
                "/api/patients",
                admin.token,
                patientBody(practiceA.id, dateOfBirth = "next-tuesday"),
            ),
        )
        assertStatus(
            400,
            env.doJson("POST", "/api/patients", admin.token, patientBody(practiceA.id, dateOfBirth = "2999-01-01")),
        )
        assertStatus(
            400,
            env.doJson("POST", "/api/patients", admin.token, patientBody(practiceA.id, sex = "unspecified")),
        )
    }

    @Test
    fun creatingWithoutAnOrganizationUsesTheOnlyOneTheCallerHas() {
        val admin = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)

        val created = env.doJson("POST", "/api/patients", admin.token, patientBody(0))
        assertStatus(201, created)
        assertEquals(practiceA.id, created.body.path("patient").path("organizationId").asInt())

        // Naming a practice they are not assigned to is still refused.
        assertStatus(403, env.doJson("POST", "/api/patients", admin.token, patientBody(practiceB.id)))
    }

    @Test
    fun creatingForAnAccountWithSeveralPracticesRequiresOneToBeNamed() {
        val both = signIn(RoleCodes.PRACTICE_ADMIN, practiceA.id)
        assign(both.userId, RoleCodes.PRACTICE_ADMIN, practiceB.id)

        // Ambiguous, so it is the caller's job to say which.
        assertStatus(400, env.doJson("POST", "/api/patients", both.token, patientBody(0)))

        val created = env.doJson("POST", "/api/patients", both.token, patientBody(practiceB.id))
        assertStatus(201, created)
        assertEquals(practiceB.id, created.body.path("patient").path("organizationId").asInt())
    }

    private fun createPatient(
        token: String,
        organizationId: Int,
        firstName: String = "Jane",
        lastName: String = "Smith",
        dateOfBirth: String = "1980-04-12",
    ): Int = created(
        "/api/patients",
        token,
        patientBody(organizationId, firstName, lastName, dateOfBirth),
        "patient",
    )

    private fun patientBody(
        organizationId: Int,
        firstName: String = "Jane",
        lastName: String = "Smith",
        dateOfBirth: String = "1980-04-12",
        sex: String = "",
    ): Map<String, Any?> = mapOf(
        "organizationId" to organizationId,
        "firstName" to firstName,
        "lastName" to lastName,
        "dateOfBirth" to dateOfBirth,
        "sex" to sex,
        "city" to "Austin",
        "state" to "TX",
        "postalCode" to "78701",
    )
}
