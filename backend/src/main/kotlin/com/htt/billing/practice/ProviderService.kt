package com.htt.billing.practice

import com.htt.billing.common.Inputs
import com.htt.billing.common.error.NotFoundException
import com.htt.billing.practice.ProviderRepository.Provider
import com.htt.billing.practice.dto.ProviderBody
import com.htt.billing.security.AuthorizationService
import com.htt.billing.security.Permissions
import org.springframework.stereotype.Service

/**
 * Providers, scoped to the caller's practice, with the same rule as patients —
 * an out-of-scope read is "not found", not "forbidden".
 */
@Service
class ProviderService(
    private val providers: ProviderRepository,
    private val authorization: AuthorizationService,
) {

    fun list(userId: Int): List<Provider> {
        val organizationIds = authorization.permittedOrganizationIds(userId, Permissions.PROVIDER_VIEW)
        if (organizationIds.isEmpty()) {
            return emptyList()
        }
        return providers.findIn(organizationIds)
    }

    fun get(userId: Int, providerId: Int): Provider {
        val provider = providers.findById(providerId) ?: throw NotFoundException("Provider not found")
        authorization.requireVisible(userId, Permissions.PROVIDER_VIEW, provider.organizationId, "Provider not found")
        return provider
    }

    /**
     * A provider is written to the caller's practice, derived rather than named —
     * the same rule every other create follows, so the browser never supplies the
     * tenant it writes to. A named practice is still checked against the caller's
     * grants.
     */
    fun create(userId: Int, body: ProviderBody): Provider {
        val organizationId = authorization.resolveWriteOrganization(
            userId,
            Permissions.PROVIDER_MANAGE,
            body.organizationId,
        )
        return providers.insert(
            organizationId = organizationId,
            firstName = Inputs.requiredText(body.firstName, "firstName"),
            lastName = Inputs.requiredText(body.lastName, "lastName"),
            npi = Inputs.optionalText(body.npi),
            taxonomyCode = Inputs.optionalText(body.taxonomyCode),
        )
    }
}
