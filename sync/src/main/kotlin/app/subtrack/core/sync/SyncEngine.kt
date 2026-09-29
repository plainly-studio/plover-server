// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.core.sync

import app.subtrack.core.crypto.DecryptionException
import app.subtrack.core.crypto.VaultCrypto
import app.subtrack.core.crypto.VaultKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import java.util.Base64

enum class RecordType { SUBSCRIPTION, CATEGORY, SETTINGS }

/**
 * A syncable record as the device sees it. [data] is the record's JSON (null for a deletion).
 * [rev] is the last server revision this device knows about (0 = never synced).
 */
data class LocalRecord(
    val id: String,
    val type: RecordType,
    val data: JsonElement?,
    val modifiedAt: Long,
    /** Device that made the latest change; breaks last-writer-wins ties deterministically. */
    val modifiedBy: String,
    val rev: Long,
    val dirty: Boolean,
) {
    val deleted: Boolean get() = data == null
}

/** Device-side storage the engine reads from and writes to. Implementations must be thread-safe. */
interface LocalStore {
    suspend fun get(id: String): LocalRecord?

    /** Records changed locally and not yet accepted by the server. */
    suspend fun dirty(): List<LocalRecord>

    /**
     * Stores a record received from the server and marks it clean, but only if the local copy is
     * still the one the engine inspected: its modifiedAt equals [expectedModifiedAt] (null = absent).
     * Returns false if a local edit raced in, so the engine can decide again.
     */
    suspend fun applyRemote(record: LocalRecord, expectedModifiedAt: Long?): Boolean

    /** The server accepted a push. Clear the dirty flag unless the record was edited again meanwhile. */
    suspend fun markPushed(id: String, rev: Long, pushedModifiedAt: Long)

    /** Local change won a conflict: keep it dirty, but base the next push on server revision [rev]. */
    suspend fun rebase(id: String, rev: Long)

    suspend fun cursor(): Long
    suspend fun setCursor(cursor: Long)

    /** The latest server history token this device has seen (see [PullResponse.head]); null before the first sync. */
    suspend fun head(): String?
    suspend fun setHead(head: String)

    /**
     * The server lost data (restored from a backup, or reset): forget every server revision, mark
     * everything the user created dirty, and rewind the cursor to 0. The engine then pulls the
     * server's whole state and clears what the server still has, so only what it lost is uploaded.
     */
    suspend fun resetAfterServerRollback()
}

/** The plaintext inside every encrypted blob. */
@Serializable
internal data class RecordEnvelope(
    val type: RecordType,
    val modifiedAt: Long,
    val modifiedBy: String,
    val data: JsonElement? = null,
)

data class SyncResult(
    val pulled: Int,
    val pushed: Int,
    val conflictsResolved: Int,
    /** Records that failed to decrypt (tampered or from another vault); skipped, never applied. */
    val rejected: List<String>,
    /** The server had lost data; this device reconciled with it and re-uploaded what was missing. */
    val recoveredFromServerRollback: Boolean = false,
)

/**
 * One sync pass: pull remote changes since the stored cursor, merge them, then push local changes.
 *
 * Merging is per record, last writer wins by the (encrypted) modification time, with the device id
 * as a tie-breaker so every device reaches the same result. Pushes use optimistic concurrency: a
 * push carries the revision it was based on; if another device got there first the server returns
 * its copy, which is merged the same way before trying again.
 *
 * Server rollback: every accepted write adds a random token to the server's history, and the device
 * remembers the latest token it has seen (from a pull or its own push). If the server's data folder
 * is restored from an older backup, that token is missing from the restored history, whatever
 * sequence numbers or revisions the server hands out afterwards. The device then reconciles: it
 * pulls everything, keeps what the server still has, and uploads only what the server lost.
 */
class SyncEngine(
    private val api: SyncApi,
    private val store: LocalStore,
    private val key: VaultKey,
    private val vaultId: String,
) {
    suspend fun sync(): SyncResult {
        val header = api.getVault() ?: throw SyncException.NoVault()
        if (header.vaultId != vaultId) throw SyncException.VaultMismatch()

        val stats = Stats()
        pull(stats)
        push(stats)
        if (stats.serverLostData && !stats.recovered) {
            // Noticed only while pushing (a conflict older than what we had): reconcile now.
            recover(stats)
            pull(stats)
            push(stats)
        }
        return SyncResult(stats.pulled, stats.pushed, stats.conflicts, stats.rejected, stats.recovered)
    }

    private suspend fun pull(stats: Stats) {
        var cursor = store.cursor()
        var page = api.pull(cursor, head = store.head())
        if (!page.headKnown) {
            // The server no longer has history this device has seen: it was restored from an older copy.
            recover(stats)
            cursor = 0
            page = api.pull(cursor)
        }
        while (true) {
            for (remote in page.records) {
                if (integrate(remote, stats)) stats.pulled++
            }
            cursor = page.cursor
            store.setCursor(cursor)
            if (!page.hasMore) break
            page = api.pull(cursor)
        }
        store.setHead(page.head)
    }

    private suspend fun recover(stats: Stats) {
        store.resetAfterServerRollback()
        stats.recovered = true
    }

    private suspend fun push(stats: Stats) {
        repeat(MAX_PUSH_ROUNDS) {
            val dirty = store.dirty()
            if (dirty.isEmpty()) return
            for (chunk in batches(dirty.map { it to encode(it) })) {
                val response = api.push(chunk.map { it.second })
                // Remember the history that includes these writes before marking them clean, so a
                // later rollback that loses them is noticed.
                if (response.accepted.isNotEmpty()) store.setHead(response.head)
                val byId = chunk.associate { it.first.id to it.first }
                for (accepted in response.accepted) {
                    val local = byId[accepted.id] ?: continue
                    store.markPushed(accepted.id, accepted.rev, local.modifiedAt)
                    stats.pushed++
                }
                for (conflict in response.conflicts) {
                    stats.conflicts++
                    integrate(conflict, stats, fromConflict = true)
                }
            }
        }
    }

    /** Splits a push by record count and by payload size, so no request exceeds the server's limits. */
    private fun batches(records: List<Pair<LocalRecord, PushRecord>>): List<List<Pair<LocalRecord, PushRecord>>> {
        val result = mutableListOf<MutableList<Pair<LocalRecord, PushRecord>>>()
        var bytes = 0
        for (record in records) {
            val size = record.second.blob.length + record.second.id.length + 64
            val current = result.lastOrNull()
            if (current == null || current.size >= Protocol.PUSH_LIMIT_MAX || bytes + size > Protocol.PUSH_BYTES_TARGET) {
                result += mutableListOf(record)
                bytes = size
            } else {
                current += record
                bytes += size
            }
        }
        return result
    }

    /** Merges one server record into the local store. Returns true if local data changed. */
    private suspend fun integrate(remote: RemoteRecord, stats: Stats, fromConflict: Boolean = false): Boolean {
        val incoming = try {
            decode(remote)
        } catch (e: DecryptionException) {
            stats.rejected += remote.id
            return false
        }
        repeat(MAX_CAS_ATTEMPTS) {
            val local = store.get(remote.id)
            when {
                local == null -> if (store.applyRemote(incoming, null)) return true
                // The server already has exactly this version (e.g. while reconciling after a
                // rollback): nothing to upload, just adopt the server's revision.
                local.dirty && local.sameVersionAs(incoming) -> {
                    store.markPushed(remote.id, remote.rev, local.modifiedAt)
                    return false
                }
                local.rev >= remote.rev -> {
                    if (fromConflict && local.dirty) {
                        // The server's copy is older than one we already had: it lost data. Base the
                        // retry on what it has now, and resync everything once this pass is over.
                        stats.serverLostData = true
                        store.rebase(remote.id, remote.rev)
                    }
                    return false
                }
                !local.dirty || remoteWins(local, incoming) ->
                    if (store.applyRemote(incoming, local.modifiedAt)) return true
                else -> {
                    store.rebase(remote.id, remote.rev)
                    return false
                }
            }
        }
        return false
    }

    private fun encode(record: LocalRecord): PushRecord {
        val envelope = RecordEnvelope(record.type, record.modifiedAt, record.modifiedBy, record.data)
        val plaintext = Protocol.json.encodeToString(RecordEnvelope.serializer(), envelope).toByteArray()
        val blob = VaultCrypto.encryptRecord(key, record.id, plaintext)
        return PushRecord(record.id, record.rev, record.deleted, b64.encodeToString(blob))
    }

    private fun decode(remote: RemoteRecord): LocalRecord {
        val plaintext = try {
            VaultCrypto.decryptRecord(key, remote.id, b64d.decode(remote.blob))
        } catch (e: IllegalArgumentException) {
            throw DecryptionException("Record ${remote.id} is not valid base64", e)
        }
        val envelope = try {
            Protocol.json.decodeFromString(RecordEnvelope.serializer(), plaintext.decodeToString())
        } catch (e: SerializationException) {
            throw DecryptionException("Record ${remote.id} has an unreadable payload", e)
        }
        // Deletion comes from the authenticated envelope, never from the server's plaintext flag:
        // a server that flips `deleted` must not be able to delete data.
        if (remote.deleted != (envelope.data == null)) {
            throw DecryptionException("Record ${remote.id} has a deletion flag that doesn't match its contents")
        }
        return LocalRecord(
            id = remote.id,
            type = envelope.type,
            data = envelope.data,
            modifiedAt = envelope.modifiedAt,
            modifiedBy = envelope.modifiedBy,
            rev = remote.rev,
            dirty = false,
        )
    }

    private class Stats {
        var pulled = 0
        var pushed = 0
        var conflicts = 0
        val rejected = mutableListOf<String>()
        var serverLostData = false
        var recovered = false
    }

    companion object {
        private const val MAX_PUSH_ROUNDS = 5
        private const val MAX_CAS_ATTEMPTS = 3
        private val b64 = Base64.getEncoder()
        private val b64d = Base64.getDecoder()

        private fun LocalRecord.sameVersionAs(other: LocalRecord) =
            modifiedAt == other.modifiedAt && modifiedBy == other.modifiedBy && data == other.data

        fun remoteWins(local: LocalRecord, remote: LocalRecord): Boolean =
            remote.modifiedAt > local.modifiedAt ||
                (remote.modifiedAt == local.modifiedAt && remote.modifiedBy > local.modifiedBy)
    }
}
