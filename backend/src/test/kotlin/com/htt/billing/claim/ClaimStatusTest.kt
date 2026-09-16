package com.htt.billing.claim

import com.htt.billing.claim.ClaimStatus.ACCEPTED
import com.htt.billing.claim.ClaimStatus.ADJUDICATED
import com.htt.billing.claim.ClaimStatus.CORRECTED
import com.htt.billing.claim.ClaimStatus.DENIED
import com.htt.billing.claim.ClaimStatus.DRAFT
import com.htt.billing.claim.ClaimStatus.PAID
import com.htt.billing.claim.ClaimStatus.READY
import com.htt.billing.claim.ClaimStatus.REJECTED
import com.htt.billing.claim.ClaimStatus.RESUBMITTED
import com.htt.billing.claim.ClaimStatus.SUBMITTED
import com.htt.billing.common.error.ValidationException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The state machine on its own. Every move a claim can make is here, so a status
 * cannot reach a state the domain does not have.
 */
class ClaimStatusTest {

    @Test
    fun theGoldenPathIsAllowedStepByStep() {
        assertTrue(ClaimStatus.canMove(DRAFT, READY))
        assertTrue(ClaimStatus.canMove(READY, SUBMITTED))
        assertTrue(ClaimStatus.canMove(SUBMITTED, ACCEPTED))
        assertTrue(ClaimStatus.canMove(ACCEPTED, ADJUDICATED))
        assertTrue(ClaimStatus.canMove(ACCEPTED, DENIED))
        assertTrue(ClaimStatus.canMove(ADJUDICATED, PAID))
    }

    @Test
    fun theCorrectionPathIsAllowed() {
        assertTrue(ClaimStatus.canMove(SUBMITTED, REJECTED))
        assertTrue(ClaimStatus.canMove(REJECTED, CORRECTED))
        assertTrue(ClaimStatus.canMove(CORRECTED, RESUBMITTED))
        assertTrue(ClaimStatus.canMove(RESUBMITTED, ACCEPTED))
    }

    @Test
    fun stepsCannotBeSkipped() {
        assertFalse(ClaimStatus.canMove(DRAFT, SUBMITTED))
        assertFalse(ClaimStatus.canMove(DRAFT, ADJUDICATED))
        assertFalse(ClaimStatus.canMove(READY, ADJUDICATED))
        assertFalse(ClaimStatus.canMove(SUBMITTED, ADJUDICATED))
    }

    @Test
    fun aPricedClaimCannotGoBackToBeingEdited() {
        assertFalse(ClaimStatus.canMove(ADJUDICATED, DRAFT))
        assertFalse(ClaimStatus.canMove(DENIED, SUBMITTED))
        assertFalse(ClaimStatus.canMove(PAID, ADJUDICATED))
    }

    @Test
    fun requireMoveNamesBothStatesWhenItRefuses() {
        val refused = assertThrows<ValidationException> { ClaimStatus.requireMove(DRAFT, PAID) }
        val message = refused.message.orEmpty()
        assertTrue(message.contains("DRAFT")) { message }
        assertTrue(message.contains("PAID")) { message }
    }

    @Test
    fun onlyAPreSubmissionClaimIsEditable() {
        assertTrue(ClaimStatus.isEditable(DRAFT))
        assertTrue(ClaimStatus.isEditable(READY))
        assertFalse(ClaimStatus.isEditable(SUBMITTED))
        assertFalse(ClaimStatus.isEditable(ADJUDICATED))
        assertFalse(ClaimStatus.isEditable(DENIED))
    }
}
