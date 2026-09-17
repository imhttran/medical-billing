package com.htt.billing

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan // AppProperties
@EnableScheduling // the email queue worker
class BillingApplication

fun main(args: Array<String>) {
    SpringApplication.run(BillingApplication::class.java, *args)
}
