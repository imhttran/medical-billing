package com.htt.template.api

import com.htt.template.service.AuthService
import com.htt.template.service.AuthUser
import com.htt.template.service.error.ForbiddenException
import com.htt.template.service.error.NotFoundException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * Resolves a logged-in user for any handler that declares an [AuthUser]
 * parameter. Declaring the parameter IS the auth check (the port of
 * `requireAuth`) — token verification, user lookup, the verification flag and
 * the onboarding gates all run here, and public routes simply don't ask for
 * one.
 */
@Component
class AuthUserArgumentResolver(private val auth: AuthService) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        AuthUser::class.java == parameter.parameterType

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val request = webRequest.getNativeRequest(HttpServletRequest::class.java)!!
        val token = BearerToken.from(request)
        if (token.isEmpty()) {
            throw ApiRejection(HttpStatus.UNAUTHORIZED, Api.msg("No token provided"))
        }
        val user = authenticate(token)

        val route = request.method + " " + request.requestURI
        if (route !in ONBOARDING_EXEMPT_ROUTES) {
            // A logged-in user can be mid-onboarding — temp password not yet
            // changed, registration details not yet filled in, possibly both at
            // once. The gates only need to know a profile exists, not its
            // contents.
            if (user.mustChangePassword) {
                throw ApiRejection(HttpStatus.FORBIDDEN, Api.msg("Password change required"))
            }
            if (!user.hasProfile) {
                throw ApiRejection(HttpStatus.FORBIDDEN, Api.msg("Profile information required"))
            }
        }
        return user
    }

    /**
     * Service exceptions are re-shaped here: every gate response is the bare
     * `{"message": …}` form, while the same exception types mean something else
     * on the login routes.
     */
    private fun authenticate(token: String): AuthUser = try {
        auth.authenticate(token)
    } catch (rejected: ForbiddenException) {
        throw ApiRejection(HttpStatus.FORBIDDEN, Api.msg(rejected.message))
    } catch (missing: NotFoundException) {
        throw ApiRejection(HttpStatus.NOT_FOUND, Api.msg(missing.message))
    }

    private companion object {
        /**
         * A user working through one onboarding gate can still reach the other
         * gate's route, so these are exempt from every gate (not just their
         * own). The frontend redirect isn't the only thing stopping a temp
         * password or an empty profile from driving the API.
         */
        private val ONBOARDING_EXEMPT_ROUTES = setOf(
            "GET /api/me",
            "POST /api/change-password",
            "GET /api/profile",
            "POST /api/profile",
        )
    }
}
