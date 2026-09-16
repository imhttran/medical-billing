package com.htt.template.service

import com.htt.template.config.AppProperties
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.mail.MailParseException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

/**
 * Real SMTP when SMTP_HOST is set; otherwise the email is logged instead of
 * sent, so dev works with no mail server.
 */
@Service
class Mailer(
    private val properties: AppProperties,
    private val sender: JavaMailSender,
    private val objectMapper: ObjectMapper,
) {

    private val noticeLogged = AtomicBoolean(false)

    /**
     * @throws org.springframework.mail.MailException when the message can't be
     *         built or the mail server rejects it. The queue worker records the
     *         message and retries.
     */
    fun send(to: String, subject: String, text: String) {
        if (properties.smtpHost.isEmpty()) {
            if (noticeLogged.compareAndSet(false, true)) {
                log.info("[mailer] SMTP_HOST not set — emails are logged, not sent.")
            }
            log.info("[mailer] email: {}", asJson(to, subject, text))
            return
        }
        if (hasLineBreak(to) || hasLineBreak(subject) || hasLineBreak(properties.mailFrom)) {
            throw MailParseException("invalid header characters in email")
        }
        val message = SimpleMailMessage()
        message.setFrom(properties.mailFrom)
        message.setTo(to)
        message.subject = subject
        message.setText(text)
        sender.send(message)
    }

    private fun asJson(to: String, subject: String, text: String): String {
        val fields = LinkedHashMap<String, Any?>()
        fields["from"] = properties.mailFrom
        fields["to"] = to
        fields["subject"] = subject
        fields["text"] = text
        return try {
            objectMapper.writeValueAsString(fields)
        } catch (unexpected: JsonProcessingException) {
            fields.toString()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Mailer::class.java)

        /** Header injection guard — nothing user-supplied may break out of a header. */
        private fun hasLineBreak(value: String): Boolean =
            value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
    }
}
