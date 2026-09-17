package com.htt.billing.service.workflow

import com.htt.billing.adjudication.PayerSimulator
import com.htt.billing.common.error.NotFoundException
import com.htt.billing.common.error.ValidationException
import com.htt.billing.repository.audit.AuditRepository
import com.htt.billing.repository.claim.ClaimRepository
import com.htt.billing.repository.workflow.WorkItemRepository
import com.htt.billing.repository.workflow.WorkItemRepository.Row
import com.htt.billing.security.Permissions
import com.htt.billing.service.security.AuthorizationService
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * The work queue: what the payer's answers left for someone to do.
 *
 * An item is opened when a claim is rejected or when a service is not covered, and
 * the queue is the list of what is still open. Nothing here decides what a claim
 * does — the claim's own state machine owns that — so an item is a record of work
 * rather than a second copy of the claim's status.
 *
 * [openForRejection], [openForDenial] and [resolveForClaim] run inside the
 * submitter's transaction and only touch rows: the caller already owns the
 * transaction, and the payer's answer is what the audit trail is about. The actions
 * a biller takes from the queue — assign, resolve — own theirs.
 */
@Service
class WorkQueueService(
    private val items: WorkItemRepository,
    private val claims: ClaimRepository,
    private val authorization: AuthorizationService,
    private val audit: AuditRepository,
    private val transactions: TransactionTemplate,
) {

    /** A rejected claim: the payer would not process it, so somebody has to fix it. */
    fun openForRejection(claim: ClaimRepository.Claim, rejection: PayerSimulator.Rejection) {
        open(
            claim = claim,
            type = TYPE_REJECTION,
            reasonCode = rejection.code,
            reasonText = rejection.message,
        )
    }

    /**
     * A service the payer would not cover. One item for the claim rather than one
     * per line: the biller works the claim, and the reason names the services.
     */
    fun openForDenial(claim: ClaimRepository.Claim, deniedProcedures: List<String>) {
        open(
            claim = claim,
            type = TYPE_DENIAL,
            reasonCode = PayerSimulator.DENIAL_SERVICE_NOT_COVERED,
            reasonText = "Service not covered: ${deniedProcedures.joinToString(", ")}",
        )
    }

    /** The claim went back to the payer, so the follow-up it needed has been done. */
    fun resolveForClaim(claimId: Int, actorId: Int) {
        val resolved = items.resolveOpenForClaim(claimId, actorId)
        if (resolved.isEmpty()) {
            return
        }
        val organizationId = claims.findById(claimId)?.organizationId
        resolved.forEach { itemId ->
            audit.record(
                userId = actorId,
                organizationId = organizationId,
                action = ACTION_RESOLVED,
                entityType = ENTITY_WORK_ITEM,
                entityId = itemId.toString(),
                metadataJson = """{"claimId":$claimId,"via":"resubmission"}""",
            )
        }
    }

    /** The queue, scoped to the caller's practices. */
    fun list(userId: Int, status: String?): List<Row> {
        val organizationIds = authorization.permittedOrganizationIds(userId, Permissions.WORK_QUEUE_VIEW)
        if (organizationIds.isEmpty()) {
            return emptyList()
        }
        return items.findIn(organizationIds, requireStatus(status))
    }

    fun get(userId: Int, id: Int): Row = requireVisible(userId, id)

    fun assign(userId: Int, id: Int, assigneeId: Int): Row {
        val item = requireVisible(userId, id)
        authorization.require(userId, Permissions.WORK_QUEUE_ASSIGN, item.organizationId)
        // Work goes to someone who can see this queue, which also makes an unknown
        // id a refusal: an assignee who cannot open the claim cannot work the item.
        if (!authorization.can(assigneeId, Permissions.WORK_QUEUE_VIEW, item.organizationId)) {
            throw ValidationException("userId must name a user who can see this practice's queue")
        }

        var assigned: Row? = null
        transactions.executeWithoutResult {
            assigned = items.assign(id, assigneeId) ?: throw NotFoundException("Work item not found")
            audit.record(
                userId = userId,
                organizationId = item.organizationId,
                action = ACTION_ASSIGNED,
                entityType = ENTITY_WORK_ITEM,
                entityId = id.toString(),
                metadataJson = """{"claimId":${item.claimId},"assignedUserId":$assigneeId}""",
            )
        }
        return checkNotNull(assigned)
    }

    /**
     * Closes an item. Resolving one that is already closed is refused rather than
     * done twice, so the timestamp keeps meaning when it happened.
     */
    fun resolve(userId: Int, id: Int): Row {
        val item = requireVisible(userId, id)
        authorization.require(userId, Permissions.WORK_QUEUE_RESOLVE, item.organizationId)
        if (item.status != STATUS_OPEN) {
            throw ValidationException("This work item is already resolved")
        }

        var resolved: Row? = null
        transactions.executeWithoutResult {
            resolved = items.resolve(id, userId) ?: throw NotFoundException("Work item not found")
            audit.record(
                userId = userId,
                organizationId = item.organizationId,
                action = ACTION_RESOLVED,
                entityType = ENTITY_WORK_ITEM,
                entityId = id.toString(),
                metadataJson = """{"claimId":${item.claimId},"via":"biller"}""",
            )
        }
        return checkNotNull(resolved)
    }

    /**
     * Opens an item, if there is not one already. The partial unique index is what
     * enforces that: a claim rejected twice before anyone touches it keeps one item.
     */
    private fun open(claim: ClaimRepository.Claim, type: String, reasonCode: String, reasonText: String) {
        items.insert(
            organizationId = claim.organizationId,
            claimId = claim.id,
            type = type,
            reasonCode = reasonCode,
            reasonText = reasonText,
        )
    }

    /** Open is what the queue screen works from; `all` is the history view. */
    private fun requireStatus(raw: String?): String? {
        val status = raw?.trim()?.uppercase() ?: return STATUS_OPEN
        return when (status) {
            "", "OPEN" -> STATUS_OPEN
            "RESOLVED" -> STATUS_RESOLVED
            "ALL" -> null
            else -> throw ValidationException("status must be open, resolved or all")
        }
    }

    /** Visibility first, then the action, as everywhere else. */
    private fun requireVisible(userId: Int, id: Int): Row {
        val item = items.findById(id) ?: throw NotFoundException("Work item not found")
        authorization.requireVisible(
            userId,
            Permissions.WORK_QUEUE_VIEW,
            item.organizationId,
            "Work item not found",
        )
        return item
    }

    companion object {
        const val TYPE_REJECTION = "REJECTION"
        const val TYPE_DENIAL = "DENIAL"

        const val STATUS_OPEN = "OPEN"
        const val STATUS_RESOLVED = "RESOLVED"

        const val ACTION_ASSIGNED = "WORK_ITEM_ASSIGNED"
        const val ACTION_RESOLVED = "WORK_ITEM_RESOLVED"
        const val ENTITY_WORK_ITEM = "WorkItem"
    }
}
