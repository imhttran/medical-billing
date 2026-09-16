package com.htt.template

import com.htt.template.cli.SetRoleCommand
import kotlin.system.exitProcess
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan // AppProperties
@EnableScheduling // the email queue worker
class TemplateApplication

fun main(args: Array<String>) {
    // set-role <email> <role>: roles are granted out-of-band, there is no HTTP
    // endpoint for it. Deliberately does NOT load .env files and does not start
    // Spring — it reads DATABASE_URL directly, like the CLI subcommand it
    // replaces.
    if (args.isNotEmpty() && args[0] == "set-role") {
        exitProcess(SetRoleCommand.run(args.copyOfRange(1, args.size)))
    }
    SpringApplication.run(TemplateApplication::class.java, *args)
}
