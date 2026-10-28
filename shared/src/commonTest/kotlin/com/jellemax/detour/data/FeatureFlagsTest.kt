package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import okio.IOException

class FeatureFlagsTest {

    private val on = FlagDef("on-by-default", true)
    private val off = FlagDef("off-by-default", false)

    private val body = """
        {"flags":[
          {"key":"off-by-default","enabled":true,"source":"account"},
          {"key":"on-by-default","enabled":false,"source":"default"}
        ]}
    """.trimIndent()

    // --- parse --------------------------------------------------------------

    @Test
    fun aFlagsDocumentParsesByKeyWithItsSource() {
        assertEquals(
            mapOf(
                "off-by-default" to ServerFlag(true, FlagSource.ACCOUNT),
                "on-by-default" to ServerFlag(false, FlagSource.SERVER),
            ),
            FeatureFlags.parse(body),
        )
    }

    @Test
    fun aGatewayHtmlPageIsNotAFlagsDocument() {
        assertNull(FeatureFlags.parse("<html><body>502 Bad Gateway</body></html>"))
    }

    @Test
    fun anObjectWithoutAFlagsArrayIsNotAFlagsDocument() {
        assertNull(FeatureFlags.parse("{}"))
    }

    @Test
    fun anEmptyFlagsArrayIsADocumentWithNoFlags() {
        assertEquals(emptyMap(), FeatureFlags.parse("""{"flags":[]}"""))
    }

    @Test
    fun anUnknownSourceReadsAsTheServers() {
        assertEquals(
            mapOf("off-by-default" to ServerFlag(true, FlagSource.SERVER)),
            FeatureFlags.parse("""{"flags":[{"key":"off-by-default","enabled":true,"source":"cohort"}]}"""),
        )
    }

    @Test
    fun anEntryWithoutAKeyOrABooleanIsSkipped() {
        assertEquals(
            mapOf("off-by-default" to ServerFlag(true, FlagSource.SERVER)),
            FeatureFlags.parse(
                """{"flags":[{"enabled":true},{"key":"x","enabled":"yes"},""" +
                    """{"key":"off-by-default","enabled":true,"source":"default"}]}""",
            ),
        )
    }

    // --- overlay ------------------------------------------------------------

    @Test
    fun aKeyTheServerDoesNotSendKeepsItsCompiledDefault() {
        assertEquals(
            listOf(
                FlagReading("on-by-default", true, FlagSource.DEFAULT),
                FlagReading("off-by-default", false, FlagSource.DEFAULT),
            ),
            FeatureFlags.overlay(listOf(on, off), emptyMap()),
        )
    }

    @Test
    fun theServersValueReplacesTheDefaultAndAnAccountOverrideKeepsItsSource() {
        assertEquals(
            listOf(
                FlagReading("on-by-default", false, FlagSource.SERVER),
                FlagReading("off-by-default", true, FlagSource.ACCOUNT),
            ),
            FeatureFlags.overlay(listOf(on, off), FeatureFlags.parse(body)!!),
        )
    }

    @Test
    fun aKeyTheRegistryDoesNotKnowIsIgnored() {
        assertEquals(
            listOf(FlagReading("off-by-default", false, FlagSource.DEFAULT)),
            FeatureFlags.overlay(
                listOf(off),
                mapOf("retired-flag" to ServerFlag(true, FlagSource.ACCOUNT)),
            ),
        )
    }

    @Test
    fun everyRegisteredFlagStartsAtItsCompiledDefault() {
        assertEquals(
            Flag.all.map { FlagReading(it.key, it.default, FlagSource.DEFAULT) },
            FeatureFlags.overlay(Flag.all, emptyMap()),
        )
    }

    // --- refresh decision ---------------------------------------------------

    @Test
    fun aFlagsDocumentReplacesTheOverlayAndIsCached() {
        assertEquals(
            FlagsRefresh.Replace(FeatureFlags.parse(body)!!, body),
            FeatureFlags.refreshDecision(Result.success(body), sameSession = true),
        )
    }

    @Test
    fun aServerWithoutTheEndpointClearsBackToTheDefaults() {
        assertEquals(
            FlagsRefresh.Clear,
            FeatureFlags.refreshDecision(Result.failure(HttpStatusException(404, "")), sameSession = true),
        )
    }

    @Test
    fun aServerErrorKeepsTheCache() {
        assertEquals(
            FlagsRefresh.Keep,
            FeatureFlags.refreshDecision(Result.failure(HttpStatusException(503, "")), sameSession = true),
        )
    }

    @Test
    fun aNetworkFailureOrARefused401KeepsTheCache() {
        assertEquals(
            FlagsRefresh.Keep,
            FeatureFlags.refreshDecision(Result.failure(IOException("offline")), sameSession = true),
        )
    }

    @Test
    fun aDeadSessionKeepsTheCache() {
        assertEquals(
            FlagsRefresh.Keep,
            FeatureFlags.refreshDecision(Result.failure(AuthException("Session expired")), sameSession = true),
        )
    }

    @Test
    fun aBodyThatIsNotAFlagsDocumentKeepsTheCache() {
        assertEquals(
            FlagsRefresh.Keep,
            FeatureFlags.refreshDecision(Result.success("<html>sign in to the wifi</html>"), sameSession = true),
        )
    }

    @Test
    fun anAnswerFromAnEarlierSessionIsDropped() {
        // Sent with rider A's bearer, landing after a switch to rider B: committing it would put
        // A's overrides into B's bucket. A 404 from then must not delete B's cache either.
        assertIs<FlagsRefresh.Keep>(FeatureFlags.refreshDecision(Result.success(body), sameSession = false))
        assertIs<FlagsRefresh.Keep>(
            FeatureFlags.refreshDecision(Result.failure(HttpStatusException(404, "")), sameSession = false),
        )
    }
}
