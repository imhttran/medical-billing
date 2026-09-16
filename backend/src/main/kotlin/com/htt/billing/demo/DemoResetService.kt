package com.htt.billing.demo

import com.htt.billing.audit.AuditRepository
import com.htt.billing.common.error.ServerErrorException
import com.htt.billing.coverage.CoverageRepository
import com.htt.billing.coverage.PayerRepository
import com.htt.billing.identity.DevAdminSeeder
import com.htt.billing.identity.UserRepository
import com.htt.billing.patient.PatientRepository
import com.htt.billing.practice.OrganizationRepository
import com.htt.billing.practice.ProviderRepository
import com.htt.billing.security.AuthorizationService
import com.htt.billing.security.Permissions
import com.htt.billing.security.RbacRepository
import com.htt.billing.security.RoleCodes
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Rebuilding the synthetic dataset, so the golden path and the error scenarios
 * can be exercised repeatedly from the same starting point.
 *
 * The reset is scoped to the demo practice's billing records. It deliberately
 * does not touch users, role assignments, other practices, the terminology
 * tables, or the audit trail — those are configured, not synthetic, and erasing
 * them would turn a data reset into an environment wipe. The practice row itself
 * survives for the same reason: audit events point at it.
 *
 * Claims, adjudications, payments and work items do not exist yet. When they do,
 * clearing them belongs in [apply], next to the statements already there.
 */
@Service
class DemoResetService(
    private val organizations: OrganizationRepository,
    private val patients: PatientRepository,
    private val providers: ProviderRepository,
    private val coverages: CoverageRepository,
    private val payers: PayerRepository,
    private val users: UserRepository,
    private val rbac: RbacRepository,
    private val authorization: AuthorizationService,
    private val audit: AuditRepository,
    private val transactions: TransactionTemplate,
) {

    /** What the dataset holds once a reset has finished. */
    data class Dataset(
        val organizationId: Int,
        val providers: Int,
        val patients: Int,
        val coverages: Int,
    )

    /**
     * The operator path — a platform user asking for a reset through the API.
     *
     * `SYSTEM_RESET` is a platform-scoped permission, so an organization-scoped
     * grant can never satisfy it, and the practice roles do not hold it at all.
     */
    fun reset(actorId: Int): Dataset {
        authorization.require(actorId, Permissions.SYSTEM_RESET)
        var dataset: Dataset? = null
        transactions.executeWithoutResult { dataset = apply(actorId) }
        return checkNotNull(dataset) { "the reset transaction returned no dataset" }
    }

    /**
     * The boot path — seed only when the demo practice is empty, so restarting
     * the backend never destroys whatever a developer has been working on.
     *
     * @return the dataset built, or null when there was already data to keep.
     */
    fun seedIfAbsent(): Dataset? {
        val existing = organizations.findByName(DemoDataset.ORGANIZATION_NAME)
        if (existing != null && patients.countInOrganization(existing.id) > 0) {
            // The data stays, but the grant is still ensured: it was added after
            // some local databases had already been seeded, and it is idempotent.
            grantDevAdminAccess(existing.id)
            return null
        }
        var seeded: Dataset? = null
        transactions.executeWithoutResult { seeded = apply(null) }
        return seeded
    }

    /**
     * The whole reset, in one transaction: a failure part way through leaves the
     * previous dataset rather than half of each.
     */
    private fun apply(actorId: Int?): Dataset {
        val organizationId = organizations.findByName(DemoDataset.ORGANIZATION_NAME)?.id
            ?: organizations.insert(
                DemoDataset.ORGANIZATION_NAME,
                DemoDataset.ORGANIZATION_NPI,
                DemoDataset.ORGANIZATION_TAX_ID,
            ).id

        coverages.deleteInOrganization(organizationId)
        patients.deleteInOrganization(organizationId)
        providers.deleteInOrganization(organizationId)

        // Seeded by migration, so a missing payer is a broken database, not bad
        // input. The reset cannot invent one without guessing its id.
        val payer = payers.findActiveByCode(DemoDataset.PAYER_CODE)
            ?: throw ServerErrorException(
                "Demo payer ${DemoDataset.PAYER_CODE} is missing",
                IllegalStateException("seeded payer not found"),
                false,
            )

        DemoDataset.PROVIDERS.forEach { provider ->
            providers.insert(
                organizationId = organizationId,
                firstName = provider.firstName,
                lastName = provider.lastName,
                npi = provider.npi,
                taxonomyCode = provider.taxonomyCode,
            )
        }

        val patient = patients.insert(
            organizationId = organizationId,
            externalId = DemoDataset.PATIENT_EXTERNAL_ID,
            firstName = DemoDataset.PATIENT_FIRST_NAME,
            lastName = DemoDataset.PATIENT_LAST_NAME,
            dateOfBirth = DemoDataset.PATIENT_DATE_OF_BIRTH,
            sex = DemoDataset.PATIENT_SEX,
            addressLine1 = DemoDataset.PATIENT_ADDRESS,
            addressLine2 = null,
            city = DemoDataset.PATIENT_CITY,
            state = DemoDataset.PATIENT_STATE,
            postalCode = DemoDataset.PATIENT_POSTAL_CODE,
            phone = DemoDataset.PATIENT_PHONE,
        )

        coverages.insert(
            organizationId = organizationId,
            patientId = patient.id,
            payerId = payer.id,
            memberId = DemoDataset.COVERAGE_MEMBER_ID,
            groupNumber = DemoDataset.COVERAGE_GROUP_NUMBER,
            subscriberName = "${DemoDataset.PATIENT_FIRST_NAME} ${DemoDataset.PATIENT_LAST_NAME}",
            relationshipToSubscriber = DemoDataset.COVERAGE_RELATIONSHIP,
            effectiveDate = DemoDataset.COVERAGE_EFFECTIVE_DATE,
            terminationDate = null,
            priority = 1,
        )

        // Boot seeding has no actor to attribute it to, so it is not audited; a
        // reset that someone asked for is.
        if (actorId != null) {
            audit.record(
                userId = actorId,
                organizationId = organizationId,
                action = ACTION_DEMO_RESET,
                entityType = ENTITY_ORGANIZATION,
                entityId = organizationId.toString(),
                metadataJson = """{"providers":${DemoDataset.PROVIDERS.size},"patients":1,"coverages":1}""",
            )
        }

        grantDevAdminAccess(organizationId)

        return Dataset(
            organizationId = organizationId,
            providers = DemoDataset.PROVIDERS.size,
            patients = 1,
            coverages = 1,
        )
    }

    /**
     * Makes the seeded practice usable by the local login. The dev admin is a
     * platform account with no billing role, and the billing screens show nothing
     * without one — every query is scoped to the caller's grants. Granted here
     * rather than in the identity seeder so the two stay independent, and skipped
     * when the account is absent (tests, qa, production).
     */
    private fun grantDevAdminAccess(organizationId: Int) {
        val devAdminId = users.findIdByEmail(DevAdminSeeder.DEV_ADMIN_EMAIL) ?: return
        DemoDataset.DEV_ADMIN_ROLES.forEach { roleCode ->
            val role = rbac.findRoleByCode(roleCode) ?: return@forEach
            rbac.insertAssignmentIfAbsent(devAdminId, role.id, organizationId, createdBy = null)
        }
    }

    companion object {
        const val ACTION_DEMO_RESET = "DEMO_DATA_RESET"
        const val ENTITY_ORGANIZATION = "Organization"
    }
}
