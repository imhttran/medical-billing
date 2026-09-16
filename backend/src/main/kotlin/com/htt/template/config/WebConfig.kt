package com.htt.template.config

import com.htt.template.api.AuthUserArgumentResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(private val authUserArgumentResolver: AuthUserArgumentResolver) : WebMvcConfigurer {

    /**
     * Declaring an `AuthUser` parameter IS the auth check — token verification,
     * user lookup, the verification flag and the onboarding gates all run in
     * the resolver. Public routes simply don't ask for one.
     */
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(authUserArgumentResolver)
    }
}
