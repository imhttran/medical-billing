package com.htt.billing.api.demo

import com.htt.billing.common.Api
import com.htt.billing.demo.DemoEnvironment
import com.htt.billing.identity.AuthUser
import com.htt.billing.service.demo.DemoResetService
import org.springframework.context.annotation.Conditional
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The demo reset. Two gates, and the plan wants both.
 *
 * The bean only exists in a development or demo environment, so in production
 * there is no route here at all — a platform administrator cannot erase the
 * environment through it, however they are authorized. Where it does exist,
 * [DemoResetService.reset] still requires the platform-scoped `SYSTEM_RESET`
 * permission, so authorization is checked as well rather than instead.
 */
@RestController
@Conditional(DemoEnvironment::class)
class SystemResetController(private val demo: DemoResetService) {

    @PostMapping("/api/system/reset")
    fun resetDemoData(user: AuthUser): ResponseEntity<Any> {
        val dataset = demo.reset(user.id)
        return Api.respond(
            HttpStatus.OK,
            mapOf(
                "success" to true,
                "message" to "Demo data reset",
                "dataset" to mapOf(
                    "organizationId" to dataset.organizationId,
                    "providers" to dataset.providers,
                    "patients" to dataset.patients,
                    "coverages" to dataset.coverages,
                ),
            ),
        )
    }
}
