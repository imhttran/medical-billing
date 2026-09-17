package com.htt.billing.api.workflow

import com.htt.billing.common.Api
import com.htt.billing.identity.AuthUser
import com.htt.billing.service.workflow.WorkQueueService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The work queue. Reads default to what is still open, because that is the list a
 * biller works from; `status=all` is there for the history.
 */
@RestController
@RequestMapping("/api/work-items")
class WorkItemController(private val queue: WorkQueueService) {

    @GetMapping
    fun listWorkItems(
        user: AuthUser,
        @RequestParam(name = "status", required = false) status: String?,
    ): ResponseEntity<Any> = Api.respond(
        HttpStatus.OK,
        mapOf("workItems" to queue.list(user.id, status)),
    )

    @GetMapping("/{id}")
    fun getWorkItem(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> =
        Api.respond(HttpStatus.OK, mapOf("workItem" to queue.get(user.id, Api.parseId(id, "work item id"))))

    @PostMapping("/{id}/assign")
    fun assignWorkItem(
        user: AuthUser,
        @PathVariable("id") id: String,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        val assigned = queue.assign(
            user.id,
            Api.parseId(id, "work item id"),
            Api.decode(body, AssignBody::class.java).userId,
        )
        return Api.respond(
            HttpStatus.OK,
            mapOf("success" to true, "message" to "Work item assigned", "workItem" to assigned),
        )
    }

    @PostMapping("/{id}/resolve")
    fun resolveWorkItem(user: AuthUser, @PathVariable("id") id: String): ResponseEntity<Any> {
        val resolved = queue.resolve(user.id, Api.parseId(id, "work item id"))
        return Api.respond(
            HttpStatus.OK,
            mapOf("success" to true, "message" to "Work item resolved", "workItem" to resolved),
        )
    }
}
