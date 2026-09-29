// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.server

import app.subtrack.core.crypto.KdfParams
import app.subtrack.core.sync.CertFingerprint
import app.subtrack.core.sync.InMemoryLocalStore
import app.subtrack.core.sync.OkHttpSyncApi
import app.subtrack.core.sync.RecordType
import app.subtrack.core.sync.SyncEngine
import app.subtrack.core.sync.SyncException
import app.subtrack.core.sync.VaultSetup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real server over real TLS and syncs two simulated devices through it. */
class EndToEndTest {
    private val dataDir: Path = Files.createTempDirectory("plover-e2e")
    private val port = ServerSocket(0).use { it.localPort }
    private var running: Running? = null
    private val passphrase = "correct horse battery staple".toCharArray()

    private class Running(val tls: TlsIdentity, val store: SyncStore, val stop: () -> Unit)

    private fun start(): Running {
        val tls = TlsIdentity.loadOrCreate(dataDir, emptyList())
        val store = SyncStore(ServerConfig.database(dataDir))
        val server = createServer(port, tls, store, Pairing("PAIR-CODE-2345", enabled = true), host = "127.0.0.1")
        server.start(wait = false)
        return Running(tls, store) { server.stop(0, 1_000); store.close() }.also { running = it }
    }

    @AfterTest fun stop() { running?.stop?.invoke() }

    private val url get() = "127.0.0.1:$port"

    private fun obj(name: String) = buildJsonObject { put("name", name) }

    @Test
    fun `two devices pair over pinned TLS, share a vault and converge`() = runBlocking<Unit> {
        val server = start()

        // Pairing step 1: probe and compare fingerprints (the user compares with the server log).
        val probe = OkHttpSyncApi.probe(url)
        assertEquals(server.tls.fingerprint, probe.fingerprint)
        assertFalse(probe.info.vaultExists)

        // Device A pairs and creates the vault.
        val a = OkHttpSyncApi(url, probe.fingerprint, null).register("pair-code-2345", "Phone")
        val apiA = OkHttpSyncApi(url, probe.fingerprint, a.token)
        val vaultA = VaultSetup.create(apiA, passphrase, now = 1, kdf = KdfParams.TEST)

        // Device B pairs and joins with the passphrase.
        val b = OkHttpSyncApi(url, probe.fingerprint, null).register("PAIR-CODE-2345", "Tablet")
        val apiB = OkHttpSyncApi(url, probe.fingerprint, b.token)
        val vaultB = VaultSetup.join(apiB, passphrase.copyOf())
        assertEquals(vaultA.header.vaultId, vaultB.header.vaultId)

        val storeA = InMemoryLocalStore("A")
        val storeB = InMemoryLocalStore("B")
        val engineA = SyncEngine(apiA, storeA, vaultA.key, vaultA.header.vaultId)
        val engineB = SyncEngine(apiB, storeB, vaultB.key, vaultB.header.vaultId)

        storeA.edit("netflix", RecordType.SUBSCRIPTION, obj("Netflix"), at = 10)
        storeA.edit("gym", RecordType.SUBSCRIPTION, obj("Gym"), at = 10)
        engineA.sync()
        assertEquals(2, engineB.sync().pulled)

        // Concurrent edits: B's is later and must win everywhere. A also deletes the gym.
        storeA.edit("netflix", RecordType.SUBSCRIPTION, obj("Netflix Basic"), at = 20)
        storeB.edit("netflix", RecordType.SUBSCRIPTION, obj("Netflix Premium"), at = 30)
        storeA.edit("gym", RecordType.SUBSCRIPTION, null, at = 25)
        engineA.sync(); engineB.sync(); engineA.sync()

        assertEquals(storeA.visible(), storeB.visible())
        assertEquals(obj("Netflix Premium"), storeA.visible()["netflix"])
        assertNull(storeB.visible()["gym"])

        assertEquals(listOf("Phone", "Tablet"), apiA.devices().map { it.name })
    }

    @Test
    fun `a different certificate is refused`() = runBlocking<Unit> {
        start()
        val wrongPin = CertFingerprint.parse("00".repeat(32))
        assertFailsWith<SyncException.CertificateMismatch> { OkHttpSyncApi(url, wrongPin, null).info() }
    }

    @Test
    fun `wrong pairing code and revoked tokens are reported clearly`() = runBlocking<Unit> {
        val server = start()
        val api = OkHttpSyncApi(url, server.tls.fingerprint, null)
        assertFailsWith<SyncException.InvalidPairingCode> { api.register("WRONG", "x") }
        val device = api.register("PAIR-CODE-2345", "x")
        val authed = OkHttpSyncApi(url, server.tls.fingerprint, device.token)
        authed.revokeDevice(device.deviceId)
        assertFailsWith<SyncException.Unauthorized> { authed.devices() }
    }

    @Test
    fun `unreachable server is reported as such`() = runBlocking<Unit> {
        val closedPort = ServerSocket(0).use { it.localPort }
        assertFailsWith<SyncException.Unreachable> { OkHttpSyncApi.probe("127.0.0.1:$closedPort") }
    }

    @Test
    fun `identity and data survive a restart`() = runBlocking<Unit> {
        val first = start()
        val fingerprint = first.tls.fingerprint
        val device = OkHttpSyncApi(url, fingerprint, null).register("PAIR-CODE-2345", "Phone")
        val api = OkHttpSyncApi(url, fingerprint, device.token)
        val vault = VaultSetup.create(api, passphrase, 1, KdfParams.TEST)
        val store = InMemoryLocalStore("A").apply { edit("s", RecordType.SUBSCRIPTION, obj("Kept"), at = 1) }
        SyncEngine(api, store, vault.key, vault.header.vaultId).sync()
        first.stop(); running = null

        val second = start()
        assertEquals(fingerprint, second.tls.fingerprint)
        val fresh = InMemoryLocalStore("B")
        SyncEngine(api, fresh, vault.key, vault.header.vaultId).sync()
        assertEquals(obj("Kept"), fresh.visible()["s"])
    }

    @Test
    fun `devices recover after the server's data folder is restored from an old backup`() = runBlocking<Unit> {
        val backupDir = Files.createTempDirectory("plover-backup")
        var server = start()
        val fingerprint = server.tls.fingerprint
        val apiA = OkHttpSyncApi(url, fingerprint, OkHttpSyncApi(url, fingerprint, null).register("PAIR-CODE-2345", "A").token)
        val apiB = OkHttpSyncApi(url, fingerprint, OkHttpSyncApi(url, fingerprint, null).register("PAIR-CODE-2345", "B").token)
        val vault = VaultSetup.create(apiA, passphrase, 1, KdfParams.TEST)
        val a = InMemoryLocalStore("A")
        val b = InMemoryLocalStore("B")
        val syncA = SyncEngine(apiA, a, vault.key, vault.header.vaultId)
        val syncB = SyncEngine(apiB, b, vault.key, vault.header.vaultId)

        a.edit("s1", RecordType.SUBSCRIPTION, obj("v1"), at = 10)
        syncA.sync(); syncB.sync()

        // The owner backs up the data folder...
        server.stop(); running = null
        Files.list(dataDir).use { files -> files.forEach { Files.copy(it, backupDir.resolve(it.fileName), StandardCopyOption.REPLACE_EXISTING) } }
        server = start()

        // ...both devices keep using the app...
        for (i in 2..6) { a.edit("s1", RecordType.SUBSCRIPTION, obj("v$i"), at = 10L + i); syncA.sync() }
        a.edit("s2", RecordType.SUBSCRIPTION, obj("added after the backup"), at = 30)
        syncA.sync(); syncB.sync()

        // ...then the NAS disk dies and the folder is restored from the old backup.
        server.stop(); running = null
        Files.list(dataDir).use { files -> files.forEach { Files.delete(it) } }
        Files.list(backupDir).use { files -> files.forEach { Files.copy(it, dataDir.resolve(it.fileName)) } }
        start()

        b.edit("s1", RecordType.SUBSCRIPTION, obj("edited on B after the restore"), at = 100)
        a.edit("s3", RecordType.SUBSCRIPTION, obj("added on A after the restore"), at = 101)
        val first = syncB.sync()
        assertTrue(first.recoveredFromServerRollback, "B noticed the server lost data")
        assertTrue(syncA.sync().recoveredFromServerRollback, "A notices on its own: the server no longer has the history it saw")
        syncB.sync(); syncA.sync()

        val expected = mapOf(
            "s1" to obj("edited on B after the restore"),
            "s2" to obj("added after the backup"),
            "s3" to obj("added on A after the restore"),
        )
        assertEquals(expected, a.visible())
        assertEquals(expected, b.visible())
        assertTrue(a.dirty().isEmpty() && b.dirty().isEmpty(), "nothing is left stuck unsynced")

        // A third device that joins now gets everything from the server alone.
        val c = InMemoryLocalStore("C")
        val apiC = OkHttpSyncApi(url, fingerprint, OkHttpSyncApi(url, fingerprint, null).register("PAIR-CODE-2345", "C").token)
        SyncEngine(apiC, c, vault.key, vault.header.vaultId).sync()
        assertEquals(expected, c.visible())
    }

    @Test
    fun `generated secrets are private to the server user`() {
        start()
        for (name in listOf("tls.p12", "tls.pass", "plover.db")) {
            val perms = Files.getPosixFilePermissions(dataDir.resolve(name)).map { it.name }
            assertTrue(perms.none { it.startsWith("GROUP") || it.startsWith("OTHERS") }, "$name is $perms")
        }
        assertNotEquals("", Files.readString(dataDir.resolve("tls.pass")))
    }
}
