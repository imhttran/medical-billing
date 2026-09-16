package com.htt.billing.coding

import com.htt.billing.security.RoleCodes
import com.htt.billing.support.BillingApiTest
import com.htt.billing.support.TestEnv
import com.htt.billing.support.assertStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Terminology search, which is what the claim screen searches against. The
 * seeded set is small on purpose — the search behaves the same over twelve rows
 * as over the real code lists.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CodingSearchTest : BillingApiTest() {

    @Test
    fun findsADiagnosisByCodeAndByName() {
        val user = signIn(RoleCodes.BILLER, practiceA.id)

        val byCode = env.doJson("GET", "/api/codes/diagnoses?query=J06", user.token, null)
        assertStatus(200, byCode)
        assertTrue(codes(byCode, "diagnoses").contains("J06.9")) { byCode.text }

        val byName = env.doJson("GET", "/api/codes/diagnoses?query=hypertension", user.token, null)
        assertStatus(200, byName)
        assertTrue(codes(byName, "diagnoses").contains("I10")) { byName.text }
    }

    @Test
    fun findsAProcedureWithItsDefaultCharge() {
        val user = signIn(RoleCodes.BILLER, practiceA.id)

        val found = env.doJson("GET", "/api/codes/procedures?query=99213", user.token, null)
        assertStatus(200, found)
        val entry = found.body.path("procedures").first { it.path("code").asText() == "99213" }
        // Compared numerically: the test harness parses the body with a plain
        // ObjectMapper, which reads JSON decimals as doubles, so 150.00 arrives
        // as 150.0 and says nothing about the scale the server sent.
        assertEquals(150.0, entry.path("defaultCharge").asDouble())
        assertEquals("CPT", entry.path("codeSystem").asText())
    }

    @Test
    fun blankQueryBrowsesFromTheStartOfTheList() {
        val user = signIn(RoleCodes.BILLER, practiceA.id)

        val browsed = env.doJson("GET", "/api/codes/diagnoses", user.token, null)
        assertStatus(200, browsed)
        assertTrue(browsed.body.path("diagnoses").size() > 5) { "empty picker: ${browsed.text}" }
    }

    @Test
    fun limitIsAppliedAndASearchThatMatchesNothingIsEmpty() {
        val user = signIn(RoleCodes.BILLER, practiceA.id)

        val limited = env.doJson("GET", "/api/codes/diagnoses?limit=1", user.token, null)
        assertStatus(200, limited)
        assertEquals(1, limited.body.path("diagnoses").size())

        val noMatch = env.doJson("GET", "/api/codes/diagnoses?query=zzzznotacode", user.token, null)
        assertStatus(200, noMatch)
        assertTrue(noMatch.body.path("diagnoses").isEmpty) { noMatch.text }
    }

    @Test
    fun aWildcardInTheQueryIsNotAWildcard() {
        val user = signIn(RoleCodes.BILLER, practiceA.id)

        // `_` matches any single character in LIKE, so unescaped "J06_9" would
        // match J06.9.
        val underscore = env.doJson("GET", "/api/codes/diagnoses?query=J06_9", user.token, null)
        assertStatus(200, underscore)
        assertTrue(underscore.body.path("diagnoses").isEmpty) { "unescaped wildcard: ${underscore.text}" }

        // And `%` would return everything. Compared against the unfiltered list
        // rather than an empty one, so this still discriminates if the client
        // encoding ever changes.
        val unfiltered = env.doJson("GET", "/api/codes/procedures", user.token, null)
        val percent = env.doJson("GET", "/api/codes/procedures?query=%25", user.token, null)
        assertStatus(200, percent)
        assertTrue(
            percent.body.path("procedures").size() < unfiltered.body.path("procedures").size(),
        ) { "unescaped wildcard returned the whole table: ${percent.text}" }
    }

    private fun codes(response: TestEnv.Response, key: String): List<String> =
        response.body.path(key).map { it.path("code").asText() }
}
