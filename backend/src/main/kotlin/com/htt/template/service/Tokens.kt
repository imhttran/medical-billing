package com.htt.template.service

import com.htt.template.config.AppProperties
import java.security.SecureRandom
import java.util.HexFormat
import org.springframework.stereotype.Service

/** The random values the auth flow hands out. */
@Service
class Tokens(private val properties: AppProperties) {

    private val random = SecureRandom()

    /** 32 random bytes, hex encoded — verification, reset and pending-login tokens. */
    fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }

    /**
     * A 4-digit login code. In development it is always 1234 so testing needs
     * no mail server; otherwise a random code.
     */
    fun randomCode(): String {
        if (properties.isDevelopment()) {
            return "1234"
        }
        return "%04d".format(random.nextInt(10000))
    }

    companion object {
        private const val TOKEN_BYTES = 32
    }
}
