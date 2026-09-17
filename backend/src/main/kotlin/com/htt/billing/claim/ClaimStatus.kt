package com.htt.billing.claim

import com.htt.billing.common.error.ValidationException

/**
 * The claim state machine.
 *
 * Only these moves exist. A status is never set from a request body — every
 * change goes through an application service that performs the transition the
 * domain allows and nothing else, which is what keeps a CRUD endpoint from
 * putting a claim into a state it cannot be in.
 *
 * Reachable today: DRAFT, READY, SUBMITTED, ACCEPTED, ADJUDICATED, DENIED,
 * REJECTED, CORRECTED and RESUBMITTED. The rest are named because they are part
 * of the intended state space, and the workflows that reach them (payments,
 * closure) arrive with their own milestones.
 */
enum class ClaimStatus {

    DRAFT,
    READY,
    SUBMITTED,
    ACCEPTED,
    ADJUDICATED,
    DENIED,
    REJECTED,
    CORRECTED,
    RESUBMITTED,
    PARTIALLY_PAID,
    PAID,
    CLOSED;

    companion object {

        private val ALLOWED: Map<ClaimStatus, Set<ClaimStatus>> = mapOf(
            DRAFT to setOf(READY),
            // Back to draft is allowed: a validated claim can be edited again
            // before it is submitted.
            READY to setOf(SUBMITTED, DRAFT),
            SUBMITTED to setOf(ACCEPTED, REJECTED),
            ACCEPTED to setOf(ADJUDICATED, DENIED),
            ADJUDICATED to setOf(PARTIALLY_PAID, PAID, CLOSED),
            PARTIALLY_PAID to setOf(PAID, CLOSED),
            PAID to setOf(CLOSED),
            REJECTED to setOf(CORRECTED),
            CORRECTED to setOf(RESUBMITTED),
            RESUBMITTED to setOf(ACCEPTED, REJECTED),
        )

        fun canMove(from: ClaimStatus, to: ClaimStatus): Boolean =
            ALLOWED[from]?.contains(to) == true

        /** @throws ValidationException when the move is not one the domain allows. */
        fun requireMove(from: ClaimStatus, to: ClaimStatus) {
            if (!canMove(from, to)) {
                throw ValidationException("A claim cannot move from $from to $to")
            }
        }

        /**
         * A claim in one of these may still be edited. REJECTED and CORRECTED are
         * here because a rejected claim has to be fixable: correcting it is the
         * move back out of REJECTED, and editing a corrected one before it goes
         * out again is the same work.
         */
        fun isEditable(status: ClaimStatus): Boolean =
            status == DRAFT || status == READY || status == REJECTED || status == CORRECTED
    }
}
