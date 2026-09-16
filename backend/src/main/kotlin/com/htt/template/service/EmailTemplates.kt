package com.htt.template.service

/**
 * The email bodies. The wording and the links are deliberately unchanged, so
 * recipients see exactly what this template has always sent.
 */
object EmailTemplates {

    data class Email(val to: String, val subject: String, val body: String)

    fun welcome(to: String): Email = Email(
        to,
        "Your account has been created",
        "Hi,\n\nYou've been successfully added to our system.\n\nThanks,\nThe Team",
    )

    fun verification(to: String, link: String): Email = Email(
        to,
        "Verify your email address",
        "Hi,\n\nPlease verify your email address by visiting this link:\n\n" +
            link +
            "\n\nThanks,\nThe Team",
    )

    fun passwordReset(to: String, link: String): Email = Email(
        to,
        "Reset your password",
        "Hi,\n\nA password reset was requested for this account. Click the link below to choose a new" +
            " password (expires in 1 hour):\n\n" +
            link +
            "\n\nIf you didn't request this, you can ignore this email.\n\nThanks,\nThe Team",
    )

    fun loginCode(to: String, code: String): Email = Email(
        to,
        "Your login code",
        "Hi,\n\nYour login verification code is:\n\n" +
            code +
            "\n\nIt expires in 10 minutes.\n\nThanks,\nThe Team",
    )

    /** Next.js client routes (no .html). */
    fun tokenLink(frontendUrl: String, page: String, token: String): String =
        "$frontendUrl/$page?token=$token"
}
