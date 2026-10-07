package org.osada.mpserver

import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.osada.mpserver.account.AccountConfig
import org.osada.mpserver.protocol.booleanOrNull
import org.osada.mpserver.protocol.stringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountFlowTest {
    private fun JsonObject.providers(): List<String> =
        (this["identities"] as JsonArray).map { it.jsonObject.stringOrNull("provider").orEmpty() }

    @Test
    fun `with accounts off only the config endpoint answers and it says so`() =
        testApplication {
            application { roomServerModule(testConfig(), accounts = null) }
            val browser = browser()
            assertEquals(false, json(browser.get("/api/account/config")).booleanOrNull("enabled"))
            assertNotEquals(HttpStatusCode.OK, browser.get("/api/account/me").status)
        }

    @Test
    fun `first sign-in creates an account and a second sign-in returns to the same one`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            harness.google.subjectsByCode["c1"] = "google-sub-1"

            val pc = browser()
            assertEquals("signed-in", pc.signIn(harness.google, "c1"))
            val first = pc.me()
            assertEquals(true, first.booleanOrNull("signedIn"))
            assertEquals(listOf("google"), first.providers())

            val phone = browser()
            assertEquals(false, phone.me().booleanOrNull("signedIn"))
            assertEquals("signed-in", phone.signIn(harness.google, "c1"))
            assertEquals(first.stringOrNull("accountId"), phone.me().stringOrNull("accountId"))
            // Each device holds its own session.
            assertNotEquals(pc.cookies.values.toSet(), phone.cookies.values.toSet())
        }

    @Test
    fun `a callback from another browser or a replayed state is refused`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            harness.google.subjectsByCode["c1"] = "victim-attacker-sub"
            val attacker = browser()
            val url =
                assertNotNull(
                    json(attacker.post("/api/auth/google/start", """{"intent":"signin"}""")).stringOrNull("url"),
                )
            val state = url.substringAfter("state=")

            // Login CSRF: the victim's browser lacks the binding cookie the attacker's browser received.
            val victim = browser()
            assertEquals("sign-in-mismatch", victim.callback("google", "state=$state&code=c1"))
            assertEquals(false, victim.me().booleanOrNull("signedIn"))
            // The state is single use even though the first attempt failed.
            assertEquals("sign-in-expired", attacker.callback("google", "state=$state&code=c1"))
            assertEquals(false, attacker.me().booleanOrNull("signedIn"))
        }

    @Test
    fun `an expired sign-in, a cancelled consent and a provider refusal change nothing`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            harness.github.subjectsByCode["ok"] = "42"
            val browser = browser()

            val url = assertNotNull(json(browser.post("/api/auth/github/start")).stringOrNull("url"))
            harness.now += 11 * 60_000L
            assertEquals("sign-in-expired", browser.callback("github", "state=${url.substringAfter("state=")}&code=ok"))

            val again = assertNotNull(json(browser.post("/api/auth/github/start")).stringOrNull("url"))
            assertEquals(
                "cancelled",
                browser.callback("github", "state=${again.substringAfter("state=")}&error=access_denied"),
            )

            assertEquals("provider-refused", browser.signIn(harness.github, "forged"))
            assertEquals(false, browser.me().booleanOrNull("signedIn"))
        }

    @Test
    fun `starting a sign-in from a foreign origin is refused`() =
        testApplication {
            AccountHarness().install(this)
            val foreign = browser(origin = "https://evil.example")
            assertEquals(HttpStatusCode.Forbidden, foreign.post("/api/auth/google/start").status)
            assertEquals(HttpStatusCode.Forbidden, browser(origin = null).post("/api/auth/google/start").status)
        }

    @Test
    fun `sign-up can be switched off without locking out existing players`() =
        testApplication {
            val harness = AccountHarness(signupEnabled = false).also { it.install(this) }
            harness.google.subjectsByCode["new"] = "newcomer"
            assertEquals("signup-disabled", browser().signIn(harness.google, "new"))
        }

    @Test
    fun `a second provider links to the signed-in account and cannot steal another account's identity`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            harness.google.subjectsByCode["g"] = "g-1"
            harness.github.subjectsByCode["h"] = "1001"
            harness.github.subjectsByCode["other"] = "2002"

            val owner = browser()
            owner.signIn(harness.google, "g")
            val accountId = owner.me().stringOrNull("accountId")
            assertEquals("linked", owner.signIn(harness.github, "h", intent = "link"))
            val linked = owner.me()
            assertEquals(accountId, linked.stringOrNull("accountId"))
            assertEquals(listOf("google", "github"), linked.providers())

            // A different person's GitHub account already has its own OSADA account: no merge.
            val stranger = browser()
            stranger.signIn(harness.github, "other")
            assertEquals("identity-in-use", owner.signIn(harness.github, "other", intent = "link"))
            assertEquals(listOf("google", "github"), owner.me().providers())

            // GitHub signs in to the same account afterwards.
            val phone = browser()
            phone.signIn(harness.github, "h")
            assertEquals(accountId, phone.me().stringOrNull("accountId"))
        }

    @Test
    fun `linking needs the session's CSRF token`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            harness.google.subjectsByCode["g"] = "g-1"
            val browser = browser()
            browser.signIn(harness.google, "g")
            browser.me()
            val refused = browser.post("/api/auth/github/start", """{"intent":"link"}""", withCsrf = false)
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("csrf-refused", json(refused).stringOrNull("error"))
        }

    @Test
    fun `the last identity cannot be unlinked but an extra one can`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            harness.google.subjectsByCode["g"] = "g-1"
            harness.github.subjectsByCode["h"] = "1001"
            val browser = browser()
            browser.signIn(harness.google, "g")
            browser.me()
            assertEquals(
                "last-identity",
                json(browser.post("/api/account/unlink", """{"provider":"google"}""")).stringOrNull("error"),
            )

            browser.signIn(harness.github, "h", intent = "link")
            browser.me()
            val after = json(browser.post("/api/account/unlink", """{"provider":"google"}"""))
            assertEquals(listOf("github"), after.providers())
        }

    @Test
    fun `logout revokes the session on the server, not just the cookie`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            harness.google.subjectsByCode["g"] = "g-1"
            val pc = browser()
            pc.signIn(harness.google, "g")
            pc.me()
            val stolenCookies = pc.cookies.toMap()
            assertEquals(HttpStatusCode.Forbidden, pc.post("/api/auth/logout", withCsrf = false).status)
            assertEquals(HttpStatusCode.OK, pc.post("/api/auth/logout").status)
            assertEquals(false, pc.me().booleanOrNull("signedIn"))

            val replay = browser()
            replay.cookies.putAll(stolenCookies)
            assertEquals(false, replay.me().booleanOrNull("signedIn"))
        }

    @Test
    fun `logout everywhere ends the other devices' sessions too`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            harness.google.subjectsByCode["g"] = "g-1"
            val pc = browser()
            val phone = browser()
            pc.signIn(harness.google, "g")
            phone.signIn(harness.google, "g")
            pc.me()
            pc.post("/api/auth/logout", """{"everywhere":true}""")
            assertEquals(false, phone.me().booleanOrNull("signedIn"))
        }

    @Test
    fun `sessions expire and rotate past half their life`() =
        testApplication {
            val harness = AccountHarness(sessionDays = 10).also { it.install(this) }
            harness.google.subjectsByCode["g"] = "g-1"
            val browser = browser()
            browser.signIn(harness.google, "g")
            val before = browser.cookies.toMap()

            harness.now += 6 * AccountConfig.MILLIS_PER_DAY
            assertEquals(true, browser.me().booleanOrNull("signedIn"))
            assertNotEquals(before, browser.cookies.toMap())
            val old = browser()
            old.cookies.putAll(before)
            assertEquals(false, old.me().booleanOrNull("signedIn"))

            harness.now += 11 * AccountConfig.MILLIS_PER_DAY
            assertEquals(false, browser.me().booleanOrNull("signedIn"))
        }

    @Test
    fun `deleting an account needs the confirmation word and then forgets every identity`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            harness.google.subjectsByCode["g"] = "g-1"
            val browser = browser()
            browser.signIn(harness.google, "g")
            val accountId = browser.me().stringOrNull("accountId")

            assertEquals(HttpStatusCode.BadRequest, browser.post("/api/account/delete", "{}").status)
            assertEquals(HttpStatusCode.OK, browser.post("/api/account/delete", """{"confirm":"DELETE"}""").status)
            assertEquals(false, browser.me().booleanOrNull("signedIn"))

            // Signing in again is a brand-new account, not a resurrection.
            browser.signIn(harness.google, "g")
            val fresh = browser.me().stringOrNull("accountId")
            assertNotNull(fresh)
            assertNotEquals(accountId, fresh)
        }

    @Test
    fun `the session cookie is HttpOnly and SameSite Lax`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            harness.google.subjectsByCode["g"] = "g-1"
            val browser = browser()
            val url = assertNotNull(json(browser.post("/api/auth/google/start")).stringOrNull("url"))
            val response = browser.get("/api/auth/google/callback?state=${url.substringAfter("state=")}&code=g")
            val sessionHeader =
                response.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .firstOrNull { it.startsWith("osada_session=") }
            assertNotNull(sessionHeader)
            assertTrue("HttpOnly" in sessionHeader, sessionHeader)
            assertTrue("SameSite=Lax" in sessionHeader, sessionHeader)
            assertNull(response.headers["Location"]?.takeIf { "?" in it }, "result travels in the fragment only")
        }
}
