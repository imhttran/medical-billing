package com.htt.billing.payment

import com.htt.billing.claim.ClaimStatus
import com.htt.billing.security.RoleCodes
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Payments and balances over HTTP: the payer's remittance arriving with its
 * adjudication, the patient's payments entered by hand, and the balance those two
 * leave behind.
 *
 * The rows the claim needs are written straight to the store — fixtures may do
 * that, the way the shared RBAC fixtures already do — because what is under test
 * here is the money, not patient entry. The claim itself goes through the API, so
 * the figures come from the real submission path.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PaymentApiTest : BillingApiTest() {

    @Test
    fun thePayersRemittanceArrivesWithItsAnswer() {
        val claimId = submittedClaim()

        val payments = env.doJson("GET", "/api/claims/$claimId/payments", collector.token, null)
        assertStatus(200, payments)

        val insurance = payments.body.path("payments").path("insurancePayments")
        assertEquals(1, insurance.size()) { payments.text }
        assertEquals(80.0, insurance.get(0).path("amount").asDouble())
        // The trace number ties the remittance to the submission it answers.
        assertTrue(insurance.get(0).path("referenceNumber").asText().startsWith("SIM-CLM-")) {
            payments.text
        }

        // The patient has paid nothing yet, so the whole responsibility is open.
        assertEquals(30.0, payments.body.path("payments").path("patientResponsibility").asDouble())
        assertEquals(0.0, payments.body.path("payments").path("patientPaid").asDouble())
        assertEquals(30.0, payments.body.path("payments").path("balance").asDouble())
    }

    @Test
    fun aPatientPaymentLeavesAPartialBalanceAndASettledOneClosesTheClaim() {
        val claimId = submittedClaim()

        val partial = env.doJson(
            "POST",
            "/api/claims/$claimId/patient-payments",
            collector.token,
            mapOf("amount" to "10.00", "paymentMethod" to "cash", "paymentDate" to "2026-03-10"),
        )
        assertStatus(201, partial)
        // Lowercase in, canonical out: the vocabulary is the database's.
        assertEquals(
            "CASH",
            partial.body.path("payments").path("patientPayments").get(0).path("paymentMethod").asText()
        )
        assertEquals(20.0, partial.body.path("payments").path("balance").asDouble())
        assertEquals(ClaimStatus.PARTIALLY_PAID.name, partial.body.path("claim").path("status").asText())

        val rest = env.doJson(
            "POST",
            "/api/claims/$claimId/patient-payments",
            collector.token,
            mapOf(
                "amount" to "20.00",
                "paymentMethod" to "CARD",
                "paymentDate" to "2026-03-11",
                "referenceNumber" to "AUTH-4471",
            ),
        )
        assertStatus(201, rest)
        assertEquals(0.0, rest.body.path("payments").path("balance").asDouble())
        assertEquals(ClaimStatus.PAID.name, rest.body.path("claim").path("status").asText())

        // Both payments are still on the claim, and the patient owes nothing.
        val payments = env.doJson("GET", "/api/claims/$claimId/payments", collector.token, null)
        assertEquals(2, payments.body.path("payments").path("patientPayments").size())
        assertEquals(30.0, payments.body.path("payments").path("patientPaid").asDouble())
    }

    @Test
    fun aPaymentLargerThanTheBalanceIsRefused() {
        val claimId = submittedClaim()

        val tooMuch = env.doJson(
            "POST",
            "/api/claims/$claimId/patient-payments",
            collector.token,
            mapOf("amount" to "40.00", "paymentMethod" to "CASH", "paymentDate" to "2026-03-10"),
        )
        assertStatus(400, tooMuch)
        assertTrue(tooMuch.body.path("message").asText().contains("30.00")) { tooMuch.text }

        // Nothing was written, so the balance is untouched.
        val payments = env.doJson("GET", "/api/claims/$claimId/payments", collector.token, null)
        assertEquals(30.0, payments.body.path("payments").path("balance").asDouble())
    }

    @Test
    fun aClaimThePayerHasNotPricedIsNotPayable() {
        // Draft, so no adjudication and therefore nothing owed.
        val claimId = createClaim()

        val refused = env.doJson(
            "POST",
            "/api/claims/$claimId/patient-payments",
            collector.token,
            mapOf("amount" to "10.00", "paymentMethod" to "CASH", "paymentDate" to "2026-03-10"),
        )
        assertStatus(400, refused)
        assertTrue(refused.body.path("message").asText().contains("Nothing is owed")) { refused.text }
    }

    @Test
    fun anUnknownPaymentMethodIsRefused() {
        val claimId = submittedClaim()

        val refused = env.doJson(
            "POST",
            "/api/claims/$claimId/patient-payments",
            collector.token,
            mapOf("amount" to "10.00", "paymentMethod" to "FAVOURS", "paymentDate" to "2026-03-10"),
        )
        assertStatus(400, refused)
        assertTrue(refused.body.path("message").asText().contains("paymentMethod")) { refused.text }
    }

    @Test
    fun thePatientBalanceIsTheSumOfWhatTheirClaimsStillAskFor() {
        val first = submittedClaim()
        val second = submittedClaim()
        assertStatus(
            201,
            env.doJson(
                "POST",
                "/api/claims/$first/patient-payments",
                collector.token,
                mapOf("amount" to "30.00", "paymentMethod" to "CASH", "paymentDate" to "2026-03-10"),
            ),
        )

        val balance = env.doJson("GET", "/api/patients/$patientId/balance", collector.token, null)
        assertStatus(200, balance)
        assertEquals(30.0, balance.body.path("balance").path("balance").asDouble())
        // The settled claim is not listed: a balance screen is about what is owed.
        val claims = balance.body.path("balance").path("claims")
        assertEquals(1, claims.size()) { balance.text }
        assertEquals(second, claims.get(0).path("claimId").asInt())
        assertEquals(30.0, claims.get(0).path("balance").asDouble())

        // And it goes to zero once the second claim is paid too.
        assertStatus(
            201,
            env.doJson(
                "POST",
                "/api/claims/$second/patient-payments",
                collector.token,
                mapOf("amount" to "30.00", "paymentMethod" to "CASH", "paymentDate" to "2026-03-10"),
            ),
        )
        val settled = env.doJson("GET", "/api/patients/$patientId/balance", collector.token, null)
        assertEquals(0.0, settled.body.path("balance").path("balance").asDouble())
        assertTrue(settled.body.path("balance").path("claims").isEmpty) { settled.text }
    }

    @Test
    fun paymentsAreScopedToThePracticeThatOwnsTheClaim() {
        val claimId = submittedClaim()
        val outsider = signIn(RoleCodes.BILLER, practiceB.id)

        assertStatus(404, env.doJson("GET", "/api/claims/$claimId/payments", outsider.token, null))
        assertStatus(
            404,
            env.doJson(
                "POST",
                "/api/claims/$claimId/patient-payments",
                outsider.token,
                mapOf("amount" to "10.00", "paymentMethod" to "CASH", "paymentDate" to "2026-03-10"),
            ),
        )
        assertStatus(404, env.doJson("GET", "/api/patients/$patientId/balance", outsider.token, null))
    }

    @Test
    fun readingIsPermittedWhereRecordingIsNot() {
        val claimId = submittedClaim()
        val reader = signIn(RoleCodes.READ_ONLY, practiceA.id)

        // READ_ONLY holds PAYMENT_VIEW and no mutating permission anywhere.
        assertStatus(200, env.doJson("GET", "/api/claims/$claimId/payments", reader.token, null))
        assertStatus(
            403,
            env.doJson(
                "POST",
                "/api/claims/$claimId/patient-payments",
                reader.token,
                mapOf("amount" to "10.00", "paymentMethod" to "CASH", "paymentDate" to "2026-03-10"),
            ),
        )
        // PROVIDER holds neither, so even reading is refused.
        val provider = signIn(RoleCodes.PROVIDER, practiceA.id)
        assertStatus(403, env.doJson("GET", "/api/claims/$claimId/payments", provider.token, null))
    }

    private fun submittedClaim(): Int {
        val id = createClaim()
        assertStatus(200, env.doJson("POST", "/api/claims/$id/ready", collector.token, null))
        val submitted = env.doJson("POST", "/api/claims/$id/submit", collector.token, null)
        assertStatus(200, submitted)
        // The starting point of every money test: the plan's own figures.
        assertEquals(ClaimStatus.ADJUDICATED.name, submitted.body.path("claim").path("status").asText())
        assertEquals(30.0, submitted.body.path("adjudication").path("patientResponsibility").asDouble())
        return id
    }

    private fun createClaim(): Int = created(
        "/api/claims",
        collector.token,
        mapOf(
            "patientId" to patientId,
            "providerId" to providerId,
            "coverageId" to coverageId,
            "serviceDate" to SERVICE_DATE,
            "diagnoses" to listOf("J06.9"),
            "lines" to listOf(
                mapOf("procedureCode" to "99213", "quantity" to 1, "chargeAmount" to "150.00"),
            ),
        ),
        "claim",
    )

    /** The rows a claim needs, written straight to the store. */
    private val patientId: Int by lazy {
        jdbc.sql(
            """
            INSERT INTO patients (organization_id, first_name, last_name, date_of_birth)
            VALUES (:organizationId, 'Pay', 'Ments', '1980-01-01')
            RETURNING id
            """,
        ).param("organizationId", practiceA.id).query(Int::class.javaObjectType).single()
    }

    private val providerId: Int by lazy {
        jdbc.sql(
            """
            INSERT INTO providers (organization_id, first_name, last_name, npi)
            VALUES (:organizationId, 'Dana', 'Reyes', '1245319599')
            RETURNING id
            """,
        ).param("organizationId", practiceA.id).query(Int::class.javaObjectType).single()
    }

    private val coverageId: Int by lazy {
        jdbc.sql(
            """
            INSERT INTO coverages
                (organization_id, patient_id, payer_id, member_id, effective_date, priority)
            VALUES
                (:organizationId, :patientId, :payerId, 'PAY123456', '2024-01-01', 1)
            RETURNING id
            """,
        )
            .param("organizationId", practiceA.id)
            .param("patientId", patientId)
            .param("payerId", payerId())
            .query(Int::class.javaObjectType)
            .single()
    }

    /** BILLER holds CLAIM_CREATE, CLAIM_SUBMIT and PAYMENT_RECORD. */
    private val collector by lazy { signIn(RoleCodes.BILLER, practiceA.id) }

    private companion object {
        /** After the demo coverage begins, so the payer does not reject it. */
        const val SERVICE_DATE = "2026-03-02"
    }
}
