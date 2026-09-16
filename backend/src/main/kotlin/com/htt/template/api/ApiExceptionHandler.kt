package com.htt.template.api

import com.htt.template.service.error.ConflictException
import com.htt.template.service.error.ForbiddenException
import com.htt.template.service.error.NotFoundException
import com.htt.template.service.error.ServerErrorException
import com.htt.template.service.error.TooManyRequestsException
import com.htt.template.service.error.UnauthenticatedException
import com.htt.template.service.error.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Turns service exceptions into the response shapes the API has always
 * returned. The defaults here are the common case for each exception; endpoints
 * whose 400s use the other shape catch the exception themselves (see
 * ProfileController and UsersController).
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ApiRejection::class)
    fun rejection(rejection: ApiRejection): ResponseEntity<Any> = rejection.response

    @ExceptionHandler(ValidationException::class)
    fun validation(invalid: ValidationException): ResponseEntity<Any> =
        Api.respond(HttpStatus.BAD_REQUEST, Api.fail(invalid.message))

    @ExceptionHandler(ConflictException::class)
    fun conflict(conflict: ConflictException): ResponseEntity<Any> =
        Api.respond(HttpStatus.BAD_REQUEST, Api.fail(conflict.message))

    @ExceptionHandler(NotFoundException::class)
    fun notFound(missing: NotFoundException): ResponseEntity<Any> =
        Api.respond(HttpStatus.NOT_FOUND, Api.msg(missing.message))

    @ExceptionHandler(UnauthenticatedException::class)
    fun unauthenticated(rejected: UnauthenticatedException): ResponseEntity<Any> =
        Api.respond(HttpStatus.UNAUTHORIZED, Api.fail(rejected.message))

    @ExceptionHandler(ForbiddenException::class)
    fun forbidden(forbidden: ForbiddenException): ResponseEntity<Any> =
        Api.respond(HttpStatus.FORBIDDEN, Api.fail(forbidden.message))

    @ExceptionHandler(TooManyRequestsException::class)
    fun tooManyRequests(throttled: TooManyRequestsException): ResponseEntity<Any> =
        Api.respond(HttpStatus.TOO_MANY_REQUESTS, Api.fail(throttled.message))

    /** The reason is logged, never sent. */
    @ExceptionHandler(ServerErrorException::class)
    fun serverError(failure: ServerErrorException): ResponseEntity<Any> {
        // The cause is stringified on purpose: passing the Throwable itself
        // would make SLF4J print a stack trace instead.
        log.error("{}: {}", failure.context, failure.cause.toString())
        return Api.respond(
            HttpStatus.INTERNAL_SERVER_ERROR,
            if (failure.withSuccess) Api.fail("Internal server error") else Api.msg("Internal server error"),
        )
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(failure: Exception): ResponseEntity<Any> {
        if (failure is ErrorResponse) {
            // Framework-raised statuses keep their meaning: an unmapped path is
            // still a 404, a wrong method still a 405.
            val status = HttpStatus.valueOf(failure.statusCode.value())
            return Api.respond(status, Api.msg(status.reasonPhrase))
        }
        log.error("Unhandled error", failure)
        return Api.respond(HttpStatus.INTERNAL_SERVER_ERROR, Api.msg("Internal server error"))
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}
