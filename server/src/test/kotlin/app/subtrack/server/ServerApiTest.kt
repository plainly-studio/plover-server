// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.server

import app.subtrack.core.crypto.KdfParams
import app.subtrack.core.sync.ErrorResponse
import app.subtrack.core.sync.Protocol
import app.subtrack.core.sync.PullResponse
import app.subtrack.core.sync.PushRecord
import app.subtrack.core.sync.PushRequest
import app.subtrack.core.sync.PushResponse
import app.subtrack.core.sync.RegisterRequest
import app.subtrack.core.sync.RegisterResponse
import app.subtrack.core.sync.VaultEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerApiTest {
    private val dir = Files.createTempDirectory("plover-test")
    private val store = SyncStore(dir.resolve("db.sqlite"))
    private val pairing = Pairing("ABCD-EFGH-JKMN", enabled = true)
    private val header = app.subtrack.core.crypto.VaultCrypto.createVault("a long passphrase".toCharArray(), "v1", 0, KdfParams.TEST).first

    private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { ploverModule(store, pairing) }
        block()
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = Protocol.json.decodeFromString(bodyAsText())

    private suspend fun HttpClient.json(method: String, path: String, body: String, token: String? = null): HttpResponse {
        val builder: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            contentType(ContentType.Application.Json)
            setBody(body)
            token?.let { bearerAuth(it) }
        }
        return when (method) {
            "POST" -> post(path, builder)
            "PUT" -> put(path, builder)
            else -> error(method)
        }
    }

    private suspend fun HttpClient.register(code: String = "abcd efgh jkmn"): HttpResponse =
        json("POST", "/v1/devices/register", Protocol.json.encodeToString(RegisterRequest.serializer(), RegisterRequest(code, "Pixel")))

    @Test
    fun `info is public and reports the vault`() = test {
        val response = client.get("/v1/info")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(""""vaultExists":false""" in response.bodyAsText())
    }

    @Test
    fun `pairing accepts the code in any format and rejects wrong codes`() = test {
        assertEquals(HttpStatusCode.OK, client.register().status)
        val wrong = client.register("WRNG-CODE-0000")
        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        assertEquals("invalid_pairing_code", wrong.decode<ErrorResponse>().error)
    }

    @Test
    fun `pairing is rate limited after repeated failures`() = test {
        repeat(5) { assertEquals(HttpStatusCode.Forbidden, client.register("nope").status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.register("nope").status)
        // Even the right code is refused until the window passes.
        assertEquals(HttpStatusCode.TooManyRequests, client.register().status)
    }

    @Test
    fun `invalid paging parameters are rejected`() = test {
        val token = client.register().decode<RegisterResponse>().token
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/records?since=abc") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/records?since=-1") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/records?since=0&limit=9999") { bearerAuth(token) }.status)
    }

    @Test
    fun `everything else needs a valid device token`() = test {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/vault").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/records") { bearerAuth("forged") }.status)
    }

    @Test
    fun `vault can only be created once`() = test {
        val token = client.register().decode<RegisterResponse>().token
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/vault") { bearerAuth(token) }.status)
        val body = Protocol.json.encodeToString(VaultEnvelope.serializer(), VaultEnvelope(header))
        val bogus = Protocol.json.encodeToString(VaultEnvelope.serializer(), VaultEnvelope(header.copy(kdf = header.kdf.copy(memoryKiB = 4_000_000))))
        assertEquals(HttpStatusCode.BadRequest, client.json("PUT", "/v1/vault", bogus, token).status)
        assertEquals(HttpStatusCode.Created, client.json("PUT", "/v1/vault", body, token).status)
        val second = client.json("PUT", "/v1/vault", body, token)
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals("vault_exists", second.decode<ErrorResponse>().error)
        assertEquals(header, client.get("/v1/vault") { bearerAuth(token) }.decode<VaultEnvelope>().header)
    }

    @Test
    fun `push uses optimistic concurrency and pull pages by sequence`() = test {
        val token = client.register().decode<RegisterResponse>().token
        fun push(vararg r: PushRecord) = Protocol.json.encodeToString(PushRequest.serializer(), PushRequest(r.toList()))

        val first = client.json("POST", "/v1/records", push(PushRecord("a", 0, false, "AAA"), PushRecord("b", 0, false, "BBB")), token)
            .decode<PushResponse>()
        assertEquals(listOf(1L, 1L), first.accepted.map { it.rev })

        val stale = client.json("POST", "/v1/records", push(PushRecord("a", 0, false, "stale")), token).decode<PushResponse>()
        assertTrue(stale.accepted.isEmpty())
        assertEquals("AAA", stale.conflicts.single().blob)

        val update = client.json("POST", "/v1/records", push(PushRecord("a", 1, true, "tomb")), token).decode<PushResponse>()
        assertEquals(2L, update.accepted.single().rev)

        val page1 = client.get("/v1/records?since=0&limit=1") { bearerAuth(token) }.decode<PullResponse>()
        assertEquals(listOf("b"), page1.records.map { it.id })
        assertTrue(page1.hasMore)
        val page2 = client.get("/v1/records?since=${page1.cursor}") { bearerAuth(token) }.decode<PullResponse>()
        assertEquals(listOf("a"), page2.records.map { it.id })
        assertTrue(page2.records.single().deleted)
        val empty = client.get("/v1/records?since=${page2.cursor}") { bearerAuth(token) }.decode<PullResponse>()
        assertTrue(empty.records.isEmpty())
        assertEquals(page2.cursor, empty.cursor)
    }

    @Test
    fun `every accepted write extends the history, and unknown history is reported`() = test {
        val token = client.register().decode<RegisterResponse>().token
        fun push(vararg r: PushRecord) = Protocol.json.encodeToString(PushRequest.serializer(), PushRequest(r.toList()))
        suspend fun pull(head: String?) = client.get("/v1/records?since=0" + (head?.let { "&head=$it" } ?: "")) { bearerAuth(token) }.decode<PullResponse>()

        val start = pull(null).head
        val written = client.json("POST", "/v1/records", push(PushRecord("a", 0, false, "AAA")), token).decode<PushResponse>()
        assertTrue(written.head != start, "an accepted write starts a new history token")
        val conflict = client.json("POST", "/v1/records", push(PushRecord("a", 0, false, "stale")), token).decode<PushResponse>()
        assertEquals(written.head, conflict.head, "a rejected write changes nothing")

        assertEquals(written.head, pull(start).head)
        assertTrue(pull(start).headKnown && pull(written.head).headKnown)
        assertEquals(false, pull("never-issued-by-this-server").headKnown)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/records?head=" + "x".repeat(200)) { bearerAuth(token) }.status)
    }

    @Test
    fun `oversized and malformed pushes are refused`() = test {
        val token = client.register().decode<RegisterResponse>().token
        val huge = PushRequest(listOf(PushRecord("a", 0, false, "x".repeat(Limits.MAX_BLOB_CHARS + 1))))
        assertEquals(HttpStatusCode.BadRequest, client.json("POST", "/v1/records", Protocol.json.encodeToString(PushRequest.serializer(), huge), token).status)
        assertEquals(HttpStatusCode.BadRequest, client.json("POST", "/v1/records", "{not json", token).status)
        val dupes = PushRequest(listOf(PushRecord("a", 0, false, "1"), PushRecord("a", 0, false, "2")))
        assertEquals(HttpStatusCode.BadRequest, client.json("POST", "/v1/records", Protocol.json.encodeToString(PushRequest.serializer(), dupes), token).status)
    }

    @Test
    fun `oversized bodies are refused before they are read into memory`() = test {
        val big = "x".repeat((Limits.MAX_SMALL_BODY + 1).toInt())
        val register = client.json("POST", "/v1/devices/register", """{"pairingCode":"$big","deviceName":"x"}""")
        assertEquals(HttpStatusCode.PayloadTooLarge, register.status)
        val token = client.register().decode<RegisterResponse>().token
        val push = "{\"records\":[" + (1..80).joinToString(",") { """{"id":"r$it","baseRev":0,"deleted":false,"blob":"${"A".repeat(60_000)}"}""" } + "]}"
        assertEquals(HttpStatusCode.PayloadTooLarge, client.json("POST", "/v1/records", push, token).status)
    }

    @Test
    fun `a revoked device is locked out`() = test {
        val a = client.register().decode<RegisterResponse>()
        val b = client.register().decode<RegisterResponse>()
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/devices/${b.deviceId}") { bearerAuth(a.token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices") { bearerAuth(b.token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/devices") { bearerAuth(a.token) }.status)
    }

    @Test
    fun `pairing can be disabled`() = testApplication {
        application { ploverModule(store, Pairing("ABCD-EFGH-JKMN", enabled = false)) }
        val response = client.register()
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("pairing_disabled", response.decode<ErrorResponse>().error)
    }

    @Test
    fun `a vault can't grow past its storage quota`() = testApplication {
        val small = SyncStore(dir.resolve("small.sqlite"), SyncStore.Quota(maxRecords = 2, maxBytes = 10))
        application { ploverModule(small, pairing) }
        val token = client.register().decode<RegisterResponse>().token
        fun push(vararg r: PushRecord) = Protocol.json.encodeToString(PushRequest.serializer(), PushRequest(r.toList()))

        assertEquals(HttpStatusCode.OK, client.json("POST", "/v1/records", push(PushRecord("a", 0, false, "AAAA"), PushRecord("b", 0, false, "BBBB")), token).status)
        val third = client.json("POST", "/v1/records", push(PushRecord("c", 0, false, "C")), token)
        assertEquals(HttpStatusCode.InsufficientStorage, third.status)
        assertEquals("vault_full", third.decode<ErrorResponse>().error)
        val tooBig = client.json("POST", "/v1/records", push(PushRecord("a", 1, false, "AAAAAAAAA")), token)
        assertEquals(HttpStatusCode.InsufficientStorage, tooBig.status)
        // Rejected batches leave nothing behind, and shrinking a record is always allowed.
        assertEquals(setOf("a", "b"), small.pull(0, 10).records.map { it.id }.toSet())
        assertEquals(HttpStatusCode.OK, client.json("POST", "/v1/records", push(PushRecord("a", 1, false, "A")), token).status)
    }

    @Test
    fun `database files are private to the server user`() {
        store.registerDevice("Pixel", 0)
        for (name in listOf("db.sqlite", "db.sqlite-wal", "db.sqlite-shm")) {
            val file = dir.resolve(name)
            if (!Files.exists(file)) continue
            val perms = Files.getPosixFilePermissions(file).map { it.name }
            assertTrue(perms.none { it.startsWith("GROUP") || it.startsWith("OTHERS") }, "$name is $perms")
        }
    }
}
