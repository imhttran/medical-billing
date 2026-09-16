package com.htt.template.api

import com.htt.template.service.JwtService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** The renewed-token header name, kept file-private: only the filter writes it. */
private const val RENEWED_TOKEN_HEADER = "X-Renewed-Token"

/**
 * Sliding JWT sessions: whenever a valid token is past half its 10-minute life,
 * successful responses carry a fresh one (`X-Renewed-Token`) for the client to
 * persist. Active users slide forward; idle ones hit the hard expiry and get
 * bounced to login by the frontend.
 *
 * The header is applied the moment the response status is known and only for
 * 2xx, so error responses never extend a session. Deciding it there (rather
 * than after the chain has run) means a large body flushing the buffer can't
 * drop the header.
 */
@Component
class SessionRenewalFilter(private val jwt: JwtService) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val renewed = jwt.renewIfDue(BearerToken.from(request))
        if (renewed == null) {
            filterChain.doFilter(request, response)
            return
        }
        val wrapper = RenewingResponse(response, renewed)
        filterChain.doFilter(request, wrapper)
        wrapper.applyIfDue()
    }

    private class RenewingResponse(response: HttpServletResponse, private val renewed: String) :
        HttpServletResponseWrapper(response) {

        private var decided = false

        override fun setStatus(status: Int) {
            super.setStatus(status)
            apply(status)
        }

        /** Handlers that never set a status still get their renewed token. */
        fun applyIfDue() {
            if (!decided) {
                apply(status)
            }
        }

        private fun apply(status: Int) {
            if (decided) {
                return
            }
            decided = true
            if (status in HttpServletResponse.SC_OK until HttpServletResponse.SC_MULTIPLE_CHOICES) {
                super.setHeader(RENEWED_TOKEN_HEADER, renewed)
            }
        }
    }
}
