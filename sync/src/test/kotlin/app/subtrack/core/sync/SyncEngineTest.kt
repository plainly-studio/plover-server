// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.core.sync

import app.subtrack.core.crypto.KdfParams
import app.subtrack.core.crypto.WrongPassphraseException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {
    private val passphrase = "a long enough passphrase".toCharArray()

    private fun obj(name: String) = buildJsonObject { put("name", name) }

    private class Device(val store: InMemoryLocalStore, val engine: SyncEngine)

    private suspend fun pair(server: FakeSyncServer): Pair<Device, Device> {
        val api = server.api()
        val created = VaultSetup.create(api, passphrase, 0, KdfParams.TEST)
        val joined = VaultSetup.join(api, passphrase.copyOf())
        val a = InMemoryLocalStore("device-a")
        val b = InMemoryLocalStore("device-b")
        return Device(a, SyncEngine(api, a, created.key, created.header.vaultId)) to
            Device(b, SyncEngine(api, b, joined.key, joined.header.vaultId))
    }

    @Test
    fun `changes flow between devices`() = runTest {
        val server = FakeSyncServer()
        val (a, b) = pair(server)
        a.store.edit("s1", RecordType.SUBSCRIPTION, obj("Netflix"), at = 1)
        a.store.edit("s2", RecordType.SUBSCRIPTION, obj("Spotify"), at = 2)
        assertEquals(2, a.engine.sync().pushed)
        assertEquals(2, b.engine.sync().pulled)
        assertEquals(a.store.visible(), b.store.visible())
        assertTrue(a.store.dirty().isEmpty())
        assertTrue(b.store.dirty().isEmpty())
    }

    @Test
    fun `the server only stores ciphertext`() = runTest {
        val server = FakeSyncServer()
        val (a, _) = pair(server)
        a.store.edit("s1", RecordType.SUBSCRIPTION, obj("Very Secret Streaming"), at = 1)
        a.engine.sync()
        val blob = Base64.getDecoder().decode(server.stored("s1")!!.blob).decodeToString()
        assertFalse("Secret" in blob)
    }

    @Test
    fun `concurrent edits converge on the latest one on both devices`() = runTest {
        val server = FakeSyncServer()
        val (a, b) = pair(server)
        a.store.edit("s1", RecordType.SUBSCRIPTION, obj("v1"), at = 1)
        a.engine.sync(); b.engine.sync()

        a.store.edit("s1", RecordType.SUBSCRIPTION, obj("from A"), at = 10)
        b.store.edit("s1", RecordType.SUBSCRIPTION, obj("from B"), at = 20)
        a.engine.sync()
        val result = b.engine.sync() // B pulls A's older change; B's is newer and wins
        a.engine.sync()

        assertEquals(obj("from B"), a.store.visible()["s1"])
        assertEquals(obj("from B"), b.store.visible()["s1"])
        assertTrue(result.pushed == 1)
    }

    @Test
    fun `an older concurrent edit loses even if pushed last`() = runTest {
        val server = FakeSyncServer()
        val (a, b) = pair(server)
        a.store.edit("s1", RecordType.SUBSCRIPTION, obj("v1"), at = 1)
        a.engine.sync(); b.engine.sync()

        b.store.edit("s1", RecordType.SUBSCRIPTION, obj("newer from B"), at = 30)
        b.engine.sync()
        a.store.edit("s1", RecordType.SUBSCRIPTION, obj("older from A"), at = 20)
        a.engine.sync()
        b.engine.sync()

        assertEquals(obj("newer from B"), a.store.visible()["s1"])
        assertEquals(obj("newer from B"), b.store.visible()["s1"])
        assertTrue(a.store.dirty().isEmpty())
    }

    @Test
    fun `push conflicts are resolved without a pull`() = runTest {
        val server = FakeSyncServer()
        val api = server.api()
        val created = VaultSetup.create(api, passphrase, 0, KdfParams.TEST)
        val a = InMemoryLocalStore("device-a")
        val b = InMemoryLocalStore("device-b")
        val engineA = SyncEngine(api, a, created.key, created.header.vaultId)
        // B's pull happens just before A's push lands, so B only learns about it from the conflict.
        val racingApi = object : SyncApi by api {
            override suspend fun pull(since: Long, limit: Int, head: String?) = api.pull(since, limit, head).let { page ->
                page.copy(records = page.records.filter { it.rev < 2 }, cursor = minOf(page.cursor, 1))
            }
        }
        val engineB = SyncEngine(racingApi, b, created.key, created.header.vaultId)
        a.edit("s1", RecordType.SUBSCRIPTION, obj("v1"), at = 1)
        engineA.sync(); engineB.sync()

        a.edit("s1", RecordType.SUBSCRIPTION, obj("A"), at = 5)
        engineA.sync()
        b.edit("s1", RecordType.SUBSCRIPTION, obj("B"), at = 9)
        val result = engineB.sync()
        assertEquals(1, result.conflictsResolved)
        assertEquals(1, result.pushed)
        assertFalse(result.recoveredFromServerRollback)
        engineA.sync()
        assertEquals(obj("B"), a.visible()["s1"])
    }

    @Test
    fun `devices resync everything when the server loses data`() = runTest {
        val server = FakeSyncServer()
        val (a, b) = pair(server)
        a.store.edit("s1", RecordType.SUBSCRIPTION, obj("kept"), at = 1)
        a.engine.sync(); b.engine.sync()
        val restored = FakeSyncServer() // an empty server with the same vault: the data folder was lost
        restored.api().createVault(server.api().getVault()!!)
        val engineA = SyncEngine(restored.api(), a.store, VaultSetup.join(restored.api(), passphrase).key, server.api().getVault()!!.vaultId)
        assertTrue(engineA.sync().recoveredFromServerRollback)
        assertEquals(listOf("s1"), restored.api().pull(0).records.map { it.id }, "re-uploaded to the empty server")
        assertEquals(obj("kept"), a.store.visible()["s1"])
        assertTrue(a.store.dirty().isEmpty())
    }

    @Test
    fun `a restore is noticed even after another device reuses the lost sequence numbers`() = runTest {
        val server = FakeSyncServer()
        val (a, b) = pair(server)
        a.store.edit("base", RecordType.SUBSCRIPTION, obj("base"), at = 1)
        a.engine.sync(); b.engine.sync()
        val backup = server.backup()
        for (i in 1..3) a.store.edit("a$i", RecordType.SUBSCRIPTION, obj("A $i"), at = 10L + i)
        a.engine.sync(); a.engine.sync() // A's cursor is now past the backup
        server.restore(backup)
        // B last synced before the backup, so it has lost nothing. Its writes reuse A's sequence numbers.
        for (i in 1..5) b.store.edit("b$i", RecordType.SUBSCRIPTION, obj("B $i"), at = 20L + i)
        assertFalse(b.engine.sync().recoveredFromServerRollback)

        assertTrue(a.engine.sync().recoveredFromServerRollback)
        b.engine.sync()
        val expected = listOf("a1", "a2", "a3", "b1", "b2", "b3", "b4", "b5", "base")
        assertEquals(expected, a.store.visible().keys.sorted())
        assertEquals(a.store.visible(), b.store.visible())
        assertEquals(expected.toSet(), server.ids())
    }

    @Test
    fun `a restore that loses a device's latest push is noticed before its next pull`() = runTest {
        val server = FakeSyncServer()
        val (a, b) = pair(server)
        a.store.edit("s1", RecordType.SUBSCRIPTION, obj("v1"), at = 1)
        a.engine.sync(); b.engine.sync()
        // Backed up after A's last pull but before its next push: the cursor alone can't tell.
        val backup = server.backup()
        a.store.edit("s2", RecordType.SUBSCRIPTION, obj("pushed just before the restore"), at = 2)
        a.engine.sync()
        server.restore(backup)

        assertTrue(a.engine.sync().recoveredFromServerRollback)
        b.engine.sync()
        assertEquals(obj("pushed just before the restore"), b.store.visible()["s2"])
    }

    @Test
    fun `recovery uploads only what the server lost`() = runTest {
        val server = FakeSyncServer()
        val (a, _) = pair(server)
        for (i in 1..10) a.store.edit("s$i", RecordType.SUBSCRIPTION, obj("v$i"), at = i.toLong())
        a.engine.sync()
        val backup = server.backup()
        a.store.edit("s11", RecordType.SUBSCRIPTION, obj("after the backup"), at = 20)
        a.store.edit("s1", RecordType.SUBSCRIPTION, obj("edited after the backup"), at = 21)
        a.engine.sync()
        server.restore(backup)

        val result = a.engine.sync()
        assertTrue(result.recoveredFromServerRollback)
        assertEquals(2, result.pushed, "only s1's edit and s11 are uploaded again")
        assertTrue(a.store.dirty().isEmpty())
        assertEquals(obj("edited after the backup"), a.store.visible()["s1"])
        assertFalse(a.engine.sync().recoveredFromServerRollback, "one reconciliation is enough")
    }

    @Test
    fun `a server that keeps its history never triggers recovery`() = runTest {
        val server = FakeSyncServer()
        val (a, b) = pair(server)
        repeat(5) { i ->
            a.store.edit("s$i", RecordType.SUBSCRIPTION, obj("A$i"), at = i * 2L)
            b.store.edit("s$i", RecordType.SUBSCRIPTION, obj("B$i"), at = i * 2L + 1)
            assertFalse(a.engine.sync().recoveredFromServerRollback)
            assertFalse(b.engine.sync().recoveredFromServerRollback)
        }
    }

    @Test
    fun `deletions propagate and beat older edits`() = runTest {
        val server = FakeSyncServer()
        val (a, b) = pair(server)
        a.store.edit("s1", RecordType.SUBSCRIPTION, obj("Gym"), at = 1)
        a.engine.sync(); b.engine.sync()

        b.store.edit("s1", RecordType.SUBSCRIPTION, obj("Gym (edited)"), at = 5)
        a.store.edit("s1", RecordType.SUBSCRIPTION, null, at = 8)
        a.engine.sync(); b.engine.sync(); a.engine.sync()

        assertNull(a.store.visible()["s1"])
        assertNull(b.store.visible()["s1"])
        assertTrue(server.stored("s1")!!.deleted)
    }

    @Test
    fun `an edit made during a push stays dirty`() = runTest {
        val server = FakeSyncServer()
        val api = server.api()
        val vault = VaultSetup.create(api, passphrase, 0, KdfParams.TEST)
        val store = InMemoryLocalStore("a")
        store.edit("s1", RecordType.SUBSCRIPTION, obj("v1"), at = 1)
        var raced = false
        val racingApi = object : SyncApi by api {
            override suspend fun push(records: List<PushRecord>): PushResponse {
                // The user saves another edit while the first push is in flight.
                if (!raced) store.edit("s1", RecordType.SUBSCRIPTION, obj("v2"), at = 2).also { raced = true }
                return api.push(records)
            }
        }
        SyncEngine(racingApi, store, vault.key, vault.header.vaultId).sync()
        // The engine pushes again in the same pass, so the latest edit reaches the server too.
        assertTrue(store.dirty().isEmpty())
        val other = InMemoryLocalStore("b")
        SyncEngine(api, other, vault.key, vault.header.vaultId).sync()
        assertEquals(obj("v2"), other.visible()["s1"])
    }

    @Test
    fun `tampered records are rejected, not applied`() = runTest {
        val server = FakeSyncServer()
        val (a, b) = pair(server)
        a.store.edit("s1", RecordType.SUBSCRIPTION, obj("x"), at = 1)
        a.store.edit("s2", RecordType.SUBSCRIPTION, obj("y"), at = 1)
        a.engine.sync()
        // A malicious server swaps s2's ciphertext into s1.
        val s2Blob = server.stored("s2")!!.blob
        server.corrupt("s1") { it.copy(blob = s2Blob) }
        val result = b.engine.sync()
        assertEquals(listOf("s1"), result.rejected)
        assertNull(b.store.get("s1"))
        assertEquals(obj("y"), b.store.visible()["s2"])
    }

    @Test
    fun `a server cannot delete records by flipping the plaintext deletion flag`() = runTest {
        val server = FakeSyncServer()
        val (a, b) = pair(server)
        a.store.edit("s1", RecordType.SUBSCRIPTION, obj("Keep me"), at = 1)
        a.engine.sync()
        server.corrupt("s1") { it.copy(deleted = true, rev = it.rev + 1, seq = it.seq + 100) }
        val result = b.engine.sync()
        assertEquals(listOf("s1"), result.rejected)
        assertNull(b.store.get("s1"))
    }

    @Test
    fun `a device refuses to sync into a different vault`() = runTest {
        val server = FakeSyncServer()
        val (a, _) = pair(server)
        val otherServer = FakeSyncServer()
        VaultSetup.create(otherServer.api(), passphrase, 0, KdfParams.TEST)
        val engine = SyncEngine(otherServer.api(), a.store, VaultSetup.join(otherServer.api(), passphrase).key, "the-old-vault-id")
        assertFailsWith<SyncException.VaultMismatch> { engine.sync() }
    }

    @Test
    fun `vault setup errors`() = runTest {
        val server = FakeSyncServer()
        assertFailsWith<SyncException.NoVault> { VaultSetup.join(server.api(), passphrase) }
        assertFailsWith<IllegalArgumentException> { VaultSetup.create(server.api(), "short".toCharArray(), 0, KdfParams.TEST) }
        VaultSetup.create(server.api(), passphrase, 0, KdfParams.TEST)
        assertFailsWith<SyncException.VaultAlreadyExists> { VaultSetup.create(server.api(), passphrase, 0, KdfParams.TEST) }
        assertFailsWith<WrongPassphraseException> { VaultSetup.join(server.api(), "a different passphrase".toCharArray()) }
    }

    @Test
    fun `pull pages through large change sets`() = runTest {
        val server = FakeSyncServer()
        val (a, b) = pair(server)
        repeat(1_234) { a.store.edit("s$it", RecordType.SUBSCRIPTION, JsonPrimitive(it), at = 1) }
        a.engine.sync()
        assertEquals(1_234, b.engine.sync().pulled)
        assertEquals(1_234, b.store.visible().size)
    }
}
