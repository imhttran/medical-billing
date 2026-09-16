package com.htt.billing.demo

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * The gate on the demo reset: it exists only where `app.env` says development or
 * demo, and in production nothing registers it — no controller, so no route to
 * reach, rather than a route that refuses whoever asks.
 *
 * A condition on `app.env` rather than `@Profile`, because `app.env` is the
 * switch this backend already runs on and it resolves from application.yml.
 * A profile would not work here: `.env` files are loaded by an
 * `EnvironmentPostProcessor`, which runs after Spring has already read
 * `spring.profiles.active`, so a profile named there never activates. The same
 * trap means an unset `app.env` is treated as production below — this endpoint
 * deletes data, so it fails closed.
 */
class DemoEnvironment : Condition {

    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val env = context.environment.getProperty(APP_ENV)
        return env == DEVELOPMENT || env == DEMO
    }

    companion object {
        const val APP_ENV = "app.env"
        const val DEVELOPMENT = "development"
        const val DEMO = "demo"
    }
}
