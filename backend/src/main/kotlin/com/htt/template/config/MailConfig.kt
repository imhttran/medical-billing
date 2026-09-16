package com.htt.template.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl

@Configuration
class MailConfig {

    /**
     * Real SMTP when SMTP_HOST is set; when it isn't, Mailer logs the email
     * instead of sending, so dev needs no mail server. That decision lives in
     * Mailer, so the sender is always built here.
     */
    @Bean
    fun javaMailSender(properties: AppProperties): JavaMailSender {
        val sender = JavaMailSenderImpl()
        sender.host = properties.smtpHost
        sender.port = properties.smtpPort
        if (properties.smtpUser.isNotEmpty()) {
            sender.username = properties.smtpUser
            sender.password = properties.smtpPass
        }
        val mail = sender.javaMailProperties
        if (properties.smtpPort == 465) {
            // Implicit TLS.
            mail["mail.smtp.ssl.enable"] = "true"
        } else {
            // Opportunistic STARTTLS: use it when the server offers it, carry on
            // in the clear when it doesn't.
            mail["mail.smtp.starttls.enable"] = "true"
            mail["mail.smtp.starttls.required"] = "false"
        }
        // Bounded waits so an unreachable mail server can't wedge the queue
        // worker (the worker is single threaded).
        mail["mail.smtp.connectiontimeout"] = "10000"
        mail["mail.smtp.timeout"] = "10000"
        mail["mail.smtp.writetimeout"] = "10000"
        return sender
    }
}
