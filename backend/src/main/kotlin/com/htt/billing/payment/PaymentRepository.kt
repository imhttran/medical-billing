package com.htt.billing.payment

import java.math.BigDecimal
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Every payment query, and the two reads the balances are built from.
 *
 * One repository for both tables because they answer one question — what has been
 * paid on this claim — and nothing reaches either except through it.
 */
@Repository
class PaymentRepository(private val jdbc: JdbcClient) {

    data class InsurancePayment(
        val id: Int,
        val claimId: Int,
        val organizationId: Int,
        val amount: BigDecimal,
        val paymentDate: LocalDate,
        val referenceNumber: String,
        val createdAt: Instant,
    )

    data class PatientPayment(
        val id: Int,
        val claimId: Int,
        val patientId: Int,
        val organizationId: Int,
        val amount: BigDecimal,
        val paymentMethod: String,
        val paymentDate: LocalDate,
        val referenceNumber: String?,
        val createdAt: Instant,
    )

    /** What one claim still asks of the patient, before anything has been paid. */
    data class Owed(val claimId: Int, val claimNumber: String, val owed: BigDecimal, val paid: BigDecimal)

    fun insertInsurancePayment(
        claimId: Int,
        organizationId: Int,
        amount: BigDecimal,
        paymentDate: LocalDate,
        referenceNumber: String,
    ): InsurancePayment = jdbc
        .sql(
            """
            INSERT INTO insurance_payments
                (claim_id, organization_id, amount, payment_date, reference_number)
            VALUES (:claimId, :organizationId, :amount, :paymentDate, :referenceNumber)
            RETURNING $INSURANCE_COLUMNS
            """,
        )
        .param("claimId", claimId)
        .param("organizationId", organizationId)
        .param("amount", amount)
        .param("paymentDate", paymentDate, Types.DATE)
        .param("referenceNumber", referenceNumber)
        .query(INSURANCE_MAPPER)
        .single()

    fun insertPatientPayment(
        claimId: Int,
        patientId: Int,
        organizationId: Int,
        amount: BigDecimal,
        paymentMethod: String,
        paymentDate: LocalDate,
        referenceNumber: String?,
    ): PatientPayment = jdbc
        .sql(
            """
            INSERT INTO patient_payments
                (claim_id, patient_id, organization_id, amount, payment_method,
                 payment_date, reference_number)
            VALUES
                (:claimId, :patientId, :organizationId, :amount, :paymentMethod,
                 :paymentDate, :referenceNumber)
            RETURNING $PATIENT_COLUMNS
            """,
        )
        .param("claimId", claimId)
        .param("patientId", patientId)
        .param("organizationId", organizationId)
        .param("amount", amount)
        .param("paymentMethod", paymentMethod)
        .param("paymentDate", paymentDate, Types.DATE)
        .param("referenceNumber", referenceNumber, Types.VARCHAR)
        .query(PATIENT_MAPPER)
        .single()

    fun findInsuranceForClaim(claimId: Int): List<InsurancePayment> = jdbc
        .sql("SELECT $INSURANCE_COLUMNS FROM insurance_payments WHERE claim_id = :claimId ORDER BY id ASC")
        .param("claimId", claimId)
        .query(INSURANCE_MAPPER)
        .list()

    fun findPatientForClaim(claimId: Int): List<PatientPayment> = jdbc
        .sql("SELECT $PATIENT_COLUMNS FROM patient_payments WHERE claim_id = :claimId ORDER BY id ASC")
        .param("claimId", claimId)
        .query(PATIENT_MAPPER)
        .list()

    /**
     * What the patient owes on one claim: the latest adjudication's patient
     * responsibility, and everything paid against it. Zero for a claim the payer
     * has not priced, which is why recording a payment against one is refused.
     */
    fun owedOn(claimId: Int): BigDecimal = jdbc
        .sql(
            """
            SELECT COALESCE(
                (SELECT patient_responsibility FROM adjudications
                 WHERE claim_id = :claimId ORDER BY id DESC LIMIT 1), 0)
            """,
        )
        .param("claimId", claimId)
        .query(BigDecimal::class.java)
        .single()

    fun patientPaidOn(claimId: Int): BigDecimal = jdbc
        .sql("SELECT COALESCE(sum(amount), 0) FROM patient_payments WHERE claim_id = :claimId")
        .param("claimId", claimId)
        .query(BigDecimal::class.java)
        .single()

    /**
     * Every claim of the patient that still asks for money, with what it asks and
     * what has come in. Settled claims are left out: a balance screen that lists
     * the visits nobody owes for is noise, and the claim's own page shows those.
     */
    fun owedByClaimForPatient(patientId: Int): List<Owed> = jdbc
        .sql(
            """
            SELECT c.id AS "claimId", c.claim_number AS "claimNumber",
                   COALESCE((SELECT a.patient_responsibility FROM adjudications a
                             WHERE a.claim_id = c.id ORDER BY a.id DESC LIMIT 1), 0) AS "owed",
                   COALESCE((SELECT sum(p.amount) FROM patient_payments p
                             WHERE p.claim_id = c.id), 0) AS "paid"
            FROM claims c
            WHERE c.patient_id = :patientId
            ORDER BY c.id DESC
            """,
        )
        .param("patientId", patientId)
        .query(
            RowMapper { rs, _ ->
                Owed(
                    rs.getInt("claimId"),
                    rs.getString("claimNumber"),
                    rs.getBigDecimal("owed"),
                    rs.getBigDecimal("paid"),
                )
            },
        )
        .list()
        .filter { Balances.outstanding(it.owed, it.paid).signum() > 0 }

    /** @return rows deleted. Claims take their payments with them. */
    fun deleteInOrganization(organizationId: Int): Int =
        jdbc.sql("DELETE FROM insurance_payments WHERE organization_id = :organizationId")
            .param("organizationId", organizationId)
            .update() +
                jdbc.sql("DELETE FROM patient_payments WHERE organization_id = :organizationId")
                    .param("organizationId", organizationId)
                    .update()

    private companion object {

        const val INSURANCE_COLUMNS = """
            id, claim_id AS "claimId", organization_id AS "organizationId", amount,
            payment_date AS "paymentDate", reference_number AS "referenceNumber",
            created_at AS "createdAt"
        """

        const val PATIENT_COLUMNS = """
            id, claim_id AS "claimId", patient_id AS "patientId",
            organization_id AS "organizationId", amount,
            payment_method AS "paymentMethod", payment_date AS "paymentDate",
            reference_number AS "referenceNumber", created_at AS "createdAt"
        """

        val INSURANCE_MAPPER = RowMapper { rs, _ ->
            InsurancePayment(
                rs.getInt("id"),
                rs.getInt("claimId"),
                rs.getInt("organizationId"),
                rs.getBigDecimal("amount"),
                rs.getObject("paymentDate", LocalDate::class.java),
                rs.getString("referenceNumber"),
                rs.getObject("createdAt", OffsetDateTime::class.java).toInstant(),
            )
        }

        val PATIENT_MAPPER = RowMapper { rs, _ ->
            PatientPayment(
                rs.getInt("id"),
                rs.getInt("claimId"),
                rs.getInt("patientId"),
                rs.getInt("organizationId"),
                rs.getBigDecimal("amount"),
                rs.getString("paymentMethod"),
                rs.getObject("paymentDate", LocalDate::class.java),
                rs.getString("referenceNumber"),
                rs.getObject("createdAt", OffsetDateTime::class.java).toInstant(),
            )
        }
    }
}
