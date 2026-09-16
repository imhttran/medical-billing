package com.htt.billing.common.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DatabaseUrlTest {

    @Test
    fun translatesTheLibpqFormTheBackendHasAlwaysUsed() {
        val url = DatabaseUrl
            .parse("postgres://postgres:postgres@localhost:5432/htt-billing-db?sslmode=disable")
        assertEquals("jdbc:postgresql://localhost:5432/htt-billing-db?sslmode=disable", url.jdbcUrl)
        assertEquals("postgres", url.username)
        assertEquals("postgres", url.password)
    }

    @Test
    fun defaultsThePortAndToleratesMissingCredentials() {
        val url = DatabaseUrl.parse("postgresql://db.example.com/htt-billing-db")
        assertEquals("jdbc:postgresql://db.example.com:5432/htt-billing-db", url.jdbcUrl)
        assertNull(url.username)
        assertNull(url.password)
    }

    @Test
    fun passesAUrlThatIsAlreadyJdbcShaped() {
        val url = DatabaseUrl.parse("jdbc:postgresql://localhost:5432/htt-billing-db")
        assertEquals("jdbc:postgresql://localhost:5432/htt-billing-db", url.jdbcUrl)
    }

    @Test
    fun decodesEscapedCredentials() {
        val url = DatabaseUrl.parse("postgres://user%40corp:p%40ss%3Aword@localhost:5432/htt-billing-db")
        assertEquals("user@corp", url.username)
        assertEquals("p@ss:word", url.password)
    }

    @Test
    fun rejectsAnythingElse() {
        assertThrows<IllegalStateException> { DatabaseUrl.parse("mysql://localhost/htt-billing-db") }
    }
}
