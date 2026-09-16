package com.htt.template.service.error

/*
 * The service-layer error taxonomy. ApiExceptionHandler is the only place that
 * knows how each one maps to an HTTP status.
 */

/** Input the caller can fix. Mapped to a 400. */
class ValidationException(message: String) : RuntimeException(message)

/**
 * The change collided with something that already exists (in practice, a unique
 * email or an existing profile). Mapped to a 400.
 */
class ConflictException(message: String) : RuntimeException(message)

/** Bad credentials. Mapped to a 401. */
class UnauthenticatedException(message: String) : RuntimeException(message)

/** Allowed to sign in, not allowed to proceed. Mapped to a 403. */
class ForbiddenException(message: String) : RuntimeException(message)

/** No such record. Mapped to a 404. */
class NotFoundException(message: String) : RuntimeException(message)

/** Rate limit hit. Mapped to a 429. */
class TooManyRequestsException(message: String) : RuntimeException(message)

/**
 * Something went wrong on our side. The [context] is logged (the reason is
 * never sent to the client) and [withSuccess] picks which of the two response
 * body shapes this endpoint uses.
 */
class ServerErrorException(
    val context: String,
    cause: Throwable,
    val withSuccess: Boolean,
) : RuntimeException("$context: ${cause.message}", cause)
