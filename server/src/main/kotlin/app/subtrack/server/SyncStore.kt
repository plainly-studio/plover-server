// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.server

import app.subtrack.core.crypto.VaultHeader
import app.subtrack.core.sync.Accepted
import app.subtrack.core.sync.DeviceInfo
import app.subtrack.core.sync.Protocol
import app.subtrack.core.sync.PullResponse
import app.subtrack.core.sync.PushRecord
import app.subtrack.core.sync.PushResponse
import app.subtrack.core.sync.RemoteRecord
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Base64
import java.util.UUID

/**
 * Persistent server state in a single SQLite file. The server is a single-user home service, so
 * one connection guarded by a lock is plenty and keeps every operation trivially serializable.
 */
class SyncStore(file: Path, private val quota: Quota = Quota()) : AutoCloseable {
    private val connection: Connection

    /** Caps what one vault may store, so a paired device can't fill the NAS disk. */
    data class Quota(val maxRecords: Int = 100_000, val maxBytes: Long = 256L * 1024 * 1024)

    class QuotaExceededException : RuntimeException("This vault has reached the server's storage limit")

    init {
        file.parent?.let(Files::createDirectories)
        // Created owner-only before SQLite opens it: SQLite gives its -wal and -shm files the
        // database file's permissions, so they stay private too (also when run outside Docker).
        if (Files.notExists(file)) Files.createFile(file)
        restrictToOwner(file)
        connection = DriverManager.getConnection("jdbc:sqlite:$file")
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.execute("CREATE TABLE IF NOT EXISTS vault (id INTEGER PRIMARY KEY CHECK (id = 1), header TEXT NOT NULL)")
            // Append-only log of random tokens, one per accepted write batch (see PullResponse.head).
            st.execute("CREATE TABLE IF NOT EXISTS history (n INTEGER PRIMARY KEY AUTOINCREMENT, token TEXT NOT NULL UNIQUE)")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS records (
                    id TEXT PRIMARY KEY,
                    rev INTEGER NOT NULL,
                    seq INTEGER NOT NULL UNIQUE,
                    deleted INTEGER NOT NULL,
                    blob TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE,
                    created_at INTEGER NOT NULL,
                    last_seen_at INTEGER
                )
                """.trimIndent(),
            )
        }
        restrictToOwner(file, file.resolveSibling("${file.fileName}-wal"), file.resolveSibling("${file.fileName}-shm"))
    }

    // --- Devices ---------------------------------------------------------------------------

    data class NewDevice(val id: String, val token: String)

    fun registerDevice(name: String, now: Long): NewDevice = locked {
        val id = UUID.randomUUID().toString()
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        connection.prepareStatement("INSERT INTO devices (id, name, token_hash, created_at) VALUES (?, ?, ?, ?)").use {
            it.setString(1, id)
            it.setString(2, name.take(MAX_DEVICE_NAME))
            it.setString(3, hash(token))
            it.setLong(4, now)
            it.executeUpdate()
        }
        NewDevice(id, token)
    }

    /** Returns the device id for a valid token and records that it was seen. */
    fun authenticate(token: String, now: Long): String? = locked {
        val id = connection.prepareStatement("SELECT id FROM devices WHERE token_hash = ?").use {
            it.setString(1, hash(token))
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: return@locked null
        connection.prepareStatement("UPDATE devices SET last_seen_at = ? WHERE id = ?").use {
            it.setLong(1, now)
            it.setString(2, id)
            it.executeUpdate()
        }
        id
    }

    fun devices(): List<DeviceInfo> = locked {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT id, name, created_at, last_seen_at FROM devices ORDER BY created_at").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(DeviceInfo(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4).takeUnless { rs.wasNull() }))
                    }
                }
            }
        }
    }

    fun revokeDevice(id: String): Boolean = locked {
        connection.prepareStatement("DELETE FROM devices WHERE id = ?").use {
            it.setString(1, id)
            it.executeUpdate() > 0
        }
    }

    // --- Vault -----------------------------------------------------------------------------

    fun vault(): VaultHeader? = locked {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT header FROM vault WHERE id = 1").use { rs ->
                if (rs.next()) Protocol.json.decodeFromString(VaultHeader.serializer(), rs.getString(1)) else null
            }
        }
    }

    /** Stores the vault header only if none exists. Returns false if a vault already exists. */
    fun createVault(header: VaultHeader): Boolean = locked {
        connection.prepareStatement("INSERT OR IGNORE INTO vault (id, header) VALUES (1, ?)").use {
            it.setString(1, Protocol.json.encodeToString(VaultHeader.serializer(), header))
            it.executeUpdate() == 1
        }
    }

    /** Deletes the vault and every record (devices stay registered). Used when the passphrase is lost. */
    fun resetVault() = locked {
        transaction {
            connection.createStatement().use { st ->
                st.executeUpdate("DELETE FROM vault")
                st.executeUpdate("DELETE FROM records")
                st.executeUpdate("DELETE FROM history")
            }
            appendHistory()
        }
    }

    // --- Records ---------------------------------------------------------------------------

    // --- History ----------------------------------------------------------------------------

    /** The latest history token; the first one is created on first use. */
    private fun head(): String =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT token FROM history ORDER BY n DESC LIMIT 1").use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: appendHistory()

    private fun inHistory(token: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM history WHERE token = ?").use {
            it.setString(1, token)
            it.executeQuery().use { rs -> rs.next() }
        }

    private fun appendHistory(): String {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))
        connection.prepareStatement("INSERT INTO history (token) VALUES (?)").use {
            it.setString(1, token)
            it.executeUpdate()
        }
        return token
    }

    /**
     * Changes after [since]. If the device sends the last history token it saw ([head]) and this
     * server never issued it, the server's data was rolled back: `headKnown` is false.
     */
    fun pull(since: Long, limit: Int, head: String? = null): PullResponse = locked {
        val rows = connection.prepareStatement(
            "SELECT id, rev, seq, deleted, blob FROM records WHERE seq > ? ORDER BY seq LIMIT ?",
        ).use {
            it.setLong(1, since)
            it.setInt(2, limit + 1)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRecord()) } }
        }
        val page = rows.take(limit)
        PullResponse(page, page.lastOrNull()?.seq ?: since, rows.size > limit, head(), head == null || inHistory(head))
    }

    /**
     * Applies each write only if its baseRev matches the stored revision (optimistic concurrency).
     * A record the server has no copy of is always accepted, which also lets devices re-upload
     * everything to a server whose data was lost.
     */
    fun push(records: List<PushRecord>, now: Long): PushResponse = locked {
        transaction {
            var seq = connection.createStatement().use { st ->
                st.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM records").use { rs -> rs.next(); rs.getLong(1) }
            }
            var (count, bytes) = connection.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*), COALESCE(SUM(LENGTH(blob)), 0) FROM records").use { rs -> rs.next(); rs.getInt(1) to rs.getLong(2) }
            }
            val accepted = mutableListOf<Accepted>()
            val conflicts = mutableListOf<RemoteRecord>()
            for (r in records) {
                val current = find(r.id)
                if (current != null && current.rev != r.baseRev) {
                    conflicts += current
                    continue
                }
                if (current == null) count++
                bytes += r.blob.length - (current?.blob?.length ?: 0)
                // Throwing rolls the whole batch back.
                if (count > quota.maxRecords || bytes > quota.maxBytes) throw QuotaExceededException()
                val rev = (current?.rev ?: 0) + 1
                seq++
                connection.prepareStatement(
                    """
                    INSERT INTO records (id, rev, seq, deleted, blob, updated_at) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET rev = excluded.rev, seq = excluded.seq,
                        deleted = excluded.deleted, blob = excluded.blob, updated_at = excluded.updated_at
                    """.trimIndent(),
                ).use {
                    it.setString(1, r.id)
                    it.setLong(2, rev)
                    it.setLong(3, seq)
                    it.setInt(4, if (r.deleted) 1 else 0)
                    it.setString(5, r.blob)
                    it.setLong(6, now)
                    it.executeUpdate()
                }
                accepted += Accepted(r.id, rev)
            }
            PushResponse(accepted, conflicts, if (accepted.isEmpty()) head() else appendHistory())
        }
    }

    fun recordCount(): Int = locked {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM records WHERE deleted = 0").use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun find(id: String): RemoteRecord? =
        connection.prepareStatement("SELECT id, rev, seq, deleted, blob FROM records WHERE id = ?").use {
            it.setString(1, id)
            it.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
        }

    private fun ResultSet.toRecord() = RemoteRecord(getString(1), getLong(2), getLong(3), getInt(4) == 1, getString(5))

    private inline fun <T> locked(block: () -> T): T = synchronized(connection) { block() }

    private inline fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        try {
            return block().also { connection.commit() }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = connection.close()

    companion object {
        const val MAX_DEVICE_NAME = 64
        private val random = SecureRandom()

        private fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
