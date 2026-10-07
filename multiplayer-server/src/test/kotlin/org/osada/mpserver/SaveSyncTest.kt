package org.osada.mpserver

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.osada.mpserver.protocol.booleanOrNull
import org.osada.mpserver.protocol.longOrNull
import org.osada.mpserver.protocol.stringOrNull
import org.osada.mpserver.saves.SaveCodec
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Online-save protocol (§4, §8 scenario matrix) against the real routes and an in-memory database. */
class SaveSyncTest {
    private val run = "run_0123456789"

    private suspend fun ApplicationTestBuilder.signedIn(
        harness: AccountHarness,
        code: String = "g",
        subject: String = "player-1",
    ): Browser {
        harness.google.subjectsByCode[code] = subject
        return browser().also {
            it.signIn(harness.google, code)
            it.me()
        }
    }

    private fun save(turn: Int): String = """{"scenario":{"turn":$turn},"players":[],"campaign":null}"""

    private suspend fun Browser.upload(
        payload: String,
        base: Long?,
        runId: String = run,
        epoch: Long = 1,
        requestId: String = "req_" + UUID.randomUUID().toString().take(12),
        hash: String = SaveCodec.contentHash(payload.toByteArray()),
        path: String = "/api/saves/runs/$runId",
        extra: Map<String, String> = emptyMap(),
    ): HttpResponse =
        send(
            HttpMethod.Put,
            path,
            payload,
            buildMap {
                if (base == null) put(HttpHeaders.IfNoneMatch, "*") else put(HttpHeaders.IfMatch, "\"r$base\"")
                put("Idempotency-Key", requestId)
                put("X-Osada-Epoch", epoch.toString())
                put("X-Osada-Content-Hash", hash)
                put("X-Osada-Campaign-File", URLEncoder.encode("resources/campaigns/test.xml", Charsets.UTF_8))
                put(
                    "X-Osada-Summary",
                    Base64.getUrlEncoder().encodeToString("""{"turn":3,"device":"PC"}""".toByteArray()),
                )
                put("X-Osada-Played-At", "1799999999000")
                putAll(extra)
            },
        )

    private suspend fun Browser.manifest(): JsonObject = json(get("/api/saves"))

    private fun JsonObject.runs(): List<JsonObject> = (this["runs"] as JsonArray).map { it.jsonObject }

    private suspend fun revisionOf(response: HttpResponse): Long? =
        json(response)["run"]?.jsonObject?.longOrNull("revision")

    @Test
    fun `create, continue on another device and download the exact bytes`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            val pc = signedIn(harness)
            val created = pc.upload(save(1), base = null)
            assertEquals(HttpStatusCode.OK, created.status)
            assertEquals(1L, revisionOf(created))
            assertEquals(HttpStatusCode.OK, pc.upload(save(2), base = 1).status)

            val phone = signedIn(harness)
            val listed = phone.manifest().runs().single()
            assertEquals(2L, listed.longOrNull("revision"))
            assertEquals(1L, listed.longOrNull("parentRevision"))
            assertEquals("PC", listed["summary"]?.jsonObject?.stringOrNull("device"))
            val download = phone.get("/api/saves/runs/$run")
            assertEquals(save(2), download.bodyAsText())
            assertEquals("\"r2\"", download.headers[HttpHeaders.ETag])
            assertEquals(SaveCodec.contentHash(save(2).toByteArray()), download.headers["X-Osada-Content-Hash"])
            // Previous-good is retained one generation back.
            assertEquals(save(1), phone.get("/api/saves/runs/$run?role=previous").bodyAsText())
        }

    @Test
    fun `a device that diverged offline gets 412 and both versions survive`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            val pc = signedIn(harness)
            pc.upload(save(12), base = null)
            val phone = signedIn(harness)
            phone.upload(save(13), base = 1)
            phone.upload(save(15), base = 2)

            val stale = pc.upload(save(14), base = 1)
            assertEquals(HttpStatusCode.PreconditionFailed, stale.status)
            val body = json(stale)
            assertEquals("stale", body.stringOrNull("error"))
            assertEquals(3L, body["run"]?.jsonObject?.longOrNull("revision"))
            assertEquals(save(15), pc.get("/api/saves/runs/$run").bodyAsText())
        }

    @Test
    fun `identical content on both devices is neither a conflict nor a new revision`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            val pc = signedIn(harness)
            pc.upload(save(5), base = null)
            val phone = signedIn(harness)
            val same = phone.upload(save(5), base = null)
            assertEquals(HttpStatusCode.OK, same.status)
            assertEquals(true, json(same).booleanOrNull("unchanged"))
            assertEquals(
                1L,
                phone
                    .manifest()
                    .runs()
                    .single()
                    .longOrNull("revision"),
            )
        }

    @Test
    fun `a retried upload returns the original receipt, never a second revision`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            val pc = signedIn(harness)
            pc.upload(save(1), base = null)
            val first = pc.upload(save(2), base = 1, requestId = "retry_key_1")
            repeat(3) {
                val retry = pc.upload(save(2), base = 1, requestId = "retry_key_1")
                assertEquals(HttpStatusCode.OK, retry.status)
                assertEquals(first.bodyAsText(), retry.bodyAsText())
            }
            assertEquals(
                2L,
                pc
                    .manifest()
                    .runs()
                    .single()
                    .longOrNull("revision"),
            )

            // An old receipt does not vouch for newer edits made with the same key.
            val misuse = pc.upload(save(3), base = 2, requestId = "retry_key_1")
            assertEquals(422, misuse.status.value)
            assertEquals(
                2L,
                pc
                    .manifest()
                    .runs()
                    .single()
                    .longOrNull("revision"),
            )
        }

    @Test
    fun `another account cannot list, read, overwrite or delete my run`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            val me = signedIn(harness, "mine", "player-1")
            me.upload(save(1), base = null)
            val other = signedIn(harness, "theirs", "player-2")

            assertTrue(other.manifest().runs().isEmpty())
            assertEquals(HttpStatusCode.NotFound, other.get("/api/saves/runs/$run").status)
            // Their write to the same id creates THEIR run; mine is untouched.
            other.upload(save(99), base = null)
            assertEquals(save(1), me.get("/api/saves/runs/$run").bodyAsText())
            assertEquals(
                HttpStatusCode.PreconditionFailed,
                other
                    .send(
                        HttpMethod.Delete,
                        "/api/saves/runs/$run",
                        headers =
                            mapOf(
                                HttpHeaders.IfMatch to "\"r7\"",
                            ),
                    ).status,
            )
            assertEquals(1, me.manifest().runs().size)
        }

    @Test
    fun `signed-out, foreign-origin and CSRF-less writes are refused`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            val guest = browser()
            assertEquals(HttpStatusCode.Unauthorized, guest.get("/api/saves").status)
            assertEquals(HttpStatusCode.Forbidden, guest.upload(save(1), base = null).status)

            val player = signedIn(harness)
            player.csrf = null
            assertEquals(HttpStatusCode.Forbidden, player.upload(save(1), base = null).status)
            player.me()
            assertEquals(HttpStatusCode.OK, player.upload(save(1), base = null).status)
        }

    @Test
    fun `unconditional, corrupt and oversized uploads are refused before anything is stored`() =
        testApplication {
            val harness = AccountHarness(maxSnapshotBytes = 200).also { it.install(this) }
            val pc = signedIn(harness)
            val unconditional = pc.upload(save(1), base = null, extra = mapOf(HttpHeaders.IfMatch to "\"r1\""))
            assertEquals(428, unconditional.status.value)
            assertEquals(
                "hash-mismatch",
                json(pc.upload(save(1), base = null, hash = "sha256-forged")).stringOrNull("error"),
            )
            assertEquals(HttpStatusCode.PayloadTooLarge, pc.upload("x".repeat(201), base = null).status)
            assertTrue(pc.manifest().runs().isEmpty())
        }

    @Test
    fun `an expired epoch answers 410 and quota refusal keeps the earlier save`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            val pc = signedIn(harness)
            pc.upload(save(1), base = null)
            val gone = pc.upload(save(2), base = 1, epoch = 0)
            assertEquals(HttpStatusCode.Gone, gone.status)
            assertEquals("epoch-expired", json(gone).stringOrNull("error"))

            harness.database.transactionBlocking { update("UPDATE accounts SET quota_bytes = 10") }
            val full = pc.upload(save(2), base = 1)
            assertEquals(HttpStatusCode.InsufficientStorage, full.status)
            assertEquals("quota-exceeded", json(full).stringOrNull("error"))
            assertEquals(save(1), pc.get("/api/saves/runs/$run").bodyAsText())
        }

    @Test
    fun `a deleted run becomes a tombstone an offline device cannot resurrect`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            val phone = signedIn(harness)
            phone.upload(save(1), base = null)
            val deleted =
                phone.send(
                    HttpMethod.Delete,
                    "/api/saves/runs/$run",
                    headers =
                        mapOf(
                            HttpHeaders.IfMatch to "\"r1\"",
                        ),
                )
            assertEquals(HttpStatusCode.OK, deleted.status)
            assertEquals(
                "deleted",
                phone
                    .manifest()
                    .runs()
                    .single()
                    .stringOrNull("state"),
            )
            assertEquals(0L, phone.manifest().longOrNull("usedBytes"))

            val offlinePc = signedIn(harness)
            assertEquals("run-deleted", json(offlinePc.upload(save(2), base = 1)).stringOrNull("error"))
            assertEquals("run-deleted", json(offlinePc.upload(save(2), base = null)).stringOrNull("error"))
        }

    @Test
    fun `a mission start is written once and never replaced by different content`() =
        testApplication {
            val harness = AccountHarness().also { it.install(this) }
            val pc = signedIn(harness)
            pc.upload(save(1), base = null)
            val path = "/api/saves/runs/$run/mission-start"
            val key = mapOf("X-Osada-Mission-Key" to "mission_bn9s00")
            assertEquals(HttpStatusCode.OK, pc.upload(save(0), base = 1, path = path, extra = key).status)
            assertEquals(HttpStatusCode.OK, pc.upload(save(0), base = 1, path = path, extra = key).status)
            assertEquals(HttpStatusCode.Conflict, pc.upload(save(7), base = 1, path = path, extra = key).status)
            assertEquals(save(0), pc.get(path).bodyAsText())
            // The ordinary resume channel is unaffected.
            assertEquals(save(1), pc.get("/api/saves/runs/$run").bodyAsText())
        }

    @Test
    fun `uploads can be disabled while reads keep working`() =
        testApplication {
            val harness = AccountHarness(uploadsEnabled = false).also { it.install(this) }
            val pc = signedIn(harness)
            assertEquals(HttpStatusCode.ServiceUnavailable, pc.upload(save(1), base = null).status)
            val manifest = pc.manifest()
            assertEquals(false, manifest.booleanOrNull("uploadsEnabled"))
            assertNull(manifest.runs().firstOrNull())
            assertNotNull(manifest.longOrNull("quotaBytes"))
        }
}
