package org.osada.ui

import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.scenario.AuthorCredits
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structured author credits (`docs/player-comfort-roadmap.md`, "Authorship metadata contract").
 *
 * The rules that matter: a credit is a row of its own with a localized label and untranslated
 * names, content with no credit renders nothing at all, and a malformed sidecar degrades instead of
 * taking the selection screen down with it.
 */
class AuthorRowTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
        AuthorCredits.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        AuthorCredits.resetForTest()
    }

    @Test
    fun aSingleCreditRendersASingularLabelAndABareName() {
        AuthorCredits.setForTest(
            mapOf("camp6.json" to listOf(AuthorCredits.Credit("Tim Ruger", AuthorCredits.Role.ORIGINAL))),
        )

        val html = AuthorRow.html("camp6.json")

        assertTrue(html.contains("<b>Author</b>"), html)
        assertTrue(html.contains("Tim Ruger"), html)
        assertFalse(html.contains("original author"), "the ordinary case stays a bare name: $html")
    }

    @Test
    fun severalCreditsUseThePluralLabelAndNameTheirRoles() {
        AuthorCredits.setForTest(
            mapOf(
                "x.json" to
                    listOf(
                        AuthorCredits.Credit("A. Original", AuthorCredits.Role.ORIGINAL),
                        AuthorCredits.Credit("B. Porter", AuthorCredits.Role.CONVERSION),
                        AuthorCredits.Credit("C. Translator", AuthorCredits.Role.TRANSLATION),
                    ),
            ),
        )

        val text = AuthorRow.text("x.json")

        assertTrue(text.startsWith("Authors"), text)
        assertTrue(text.contains("A. Original;"), text)
        assertTrue(text.contains("B. Porter (conversion)"), text)
        assertTrue(text.contains("C. Translator (translation)"), text)
    }

    @Test
    fun contentWithNoCreditRendersNothingRatherThanAnEmptyRow() {
        AuthorCredits.setForTest(emptyMap())

        assertEquals("", AuthorRow.html("uncredited.json"))
        assertEquals("", AuthorRow.text("uncredited.json"))
        assertEquals("", AuthorRow.html(null))
    }

    @Test
    fun aNameCanNeverInjectMarkupIntoTheDossier() {
        AuthorCredits.setForTest(
            mapOf("x.json" to listOf(AuthorCredits.Credit("<img src=x onerror=1>", AuthorCredits.Role.ORIGINAL))),
        )

        val html = AuthorRow.html("x.json")

        assertFalse(html.contains("<img"), html)
        assertTrue(html.contains("&lt;img"), html)
    }

    // ---- sidecar parsing ----------------------------------------------------------------------

    @Test
    fun theSidecarIsKeyedByStableFileIdAndCarriesRoles() {
        val parsed =
            AuthorCredits.parse(
                """{"version":1,"entries":{"camp6.json":[{"name":"Tim Ruger","role":"original"}],
                   "n_kiel.xml":[{"name":"Matze","role":"conversion"}]}}""",
            )

        assertEquals(2, parsed.size)
        assertEquals(AuthorCredits.Role.ORIGINAL, parsed["camp6.json"]?.single()?.role)
        assertEquals(AuthorCredits.Role.CONVERSION, parsed["n_kiel.xml"]?.single()?.role)
    }

    @Test
    fun aMalformedSidecarDegradesInsteadOfThrowing() {
        assertEquals(emptyMap(), AuthorCredits.parse("""{"version":1}"""))
        assertEquals(emptyMap(), AuthorCredits.parse("""{"entries":{"a.json":[]}}"""))
        assertEquals(
            emptyMap(),
            AuthorCredits.parse("""{"entries":{"a.json":[{"role":"original"}]}}"""),
            "a credit with no name credits nobody",
        )
    }

    @Test
    fun anUnknownRoleStillCountsAsACredit() {
        val parsed = AuthorCredits.parse("""{"entries":{"a.json":[{"name":"Someone","role":"chief typist"}]}}""")

        assertEquals(AuthorCredits.Role.ORIGINAL, parsed["a.json"]?.single()?.role)
        assertEquals("Someone", parsed["a.json"]?.single()?.name)
    }
}
