package com.htt.template.service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import org.bouncycastle.crypto.generators.SCrypt
import org.springframework.stereotype.Service

/**
 * scrypt password hashing, in the exact format the hashes already in the
 * database use, so existing rows keep verifying: `hex(salt):hex(key)`,
 * N=16384, r=8, p=1, 16-byte salt, 64-byte key. The salt input is the
 * hex-encoded string itself, not the raw bytes.
 */
@Service
class PasswordHasher {

    private val random = SecureRandom()

    fun hash(password: String): String {
        val saltHex = hex(randomBytes(SALT_LENGTH))
        return "$saltHex:${hex(derive(password, saltHex))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val separator = stored.indexOf(':')
        if (separator < 0) {
            return false
        }
        val saltHex = stored.substring(0, separator)
        val expected = try {
            HexFormat.of().parseHex(stored.substring(separator + 1))
        } catch (malformed: IllegalArgumentException) {
            return false
        }
        // Constant-time, and a length mismatch simply compares unequal.
        return MessageDigest.isEqual(expected, derive(password, saltHex))
    }

    private fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return bytes
    }

    private fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)

    companion object {
        private const val LOG_N = 14 // N = 16384
        private const val N = 1 shl LOG_N
        private const val R = 8
        private const val P = 1
        private const val SALT_LENGTH = 16
        private const val KEY_LENGTH = 64

        private fun derive(password: String, saltHex: String): ByteArray = SCrypt.generate(
            password.toByteArray(StandardCharsets.UTF_8),
            saltHex.toByteArray(StandardCharsets.UTF_8),
            N,
            R,
            P,
            KEY_LENGTH,
        )
    }
}
