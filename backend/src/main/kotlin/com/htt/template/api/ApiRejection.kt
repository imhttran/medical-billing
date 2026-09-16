package com.htt.template.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * A response the API layer has already decided on — the auth gates, the role
 * gates and the path-id parser. Those responses are always the bare
 * `{"message": …}` shape, whatever their status, so they can't share the
 * per-exception defaults the service exceptions use.
 */
class ApiRejection(status: HttpStatus, body: Map<String, Any?>) :
    RuntimeException(body["message"].toString()) {

    val response: ResponseEntity<Any> = ResponseEntity.status(status).body<Any>(body)
}
