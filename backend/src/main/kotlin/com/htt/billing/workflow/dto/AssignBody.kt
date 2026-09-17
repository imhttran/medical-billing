package com.htt.billing.workflow.dto

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/** Who a work item is being given to. A zero id reads as absent. */
data class AssignBody(
    @field:JsonSetter(nulls = Nulls.SKIP) var userId: Int = 0,
)
