// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.core.sync

import app.subtrack.core.crypto.VaultHeader

/** An in-memory server with the same semantics as the real one. [api] gives each device a client. */
class FakeSyncServer {
    private var vault: VaultHeader? = null
    private var records = linkedMapOf<String, RemoteRecord>()
    private var seq = 0L
    private var history = mutableListOf(newToken())

    /** A copy of the server's whole state, like a backup of its data folder. */
    class Backup internal constructor(
        internal val vault: VaultHeader?,
        internal val records: Map<String, RemoteRecord>,
        internal val history: List<String>,
    )

    fun backup(): Backup = synchronized(this) { Backup(vault, LinkedHashMap(records), history.toList()) }

    /** Replaces everything with [backup]. Like the real server, the next sequence number is the highest stored + 1. */
    fun restore(backup: Backup) = synchronized(this) {
        vault = backup.vault
        records = LinkedHashMap(backup.records)
        seq = records.values.maxOfOrNull { it.seq } ?: 0
        history = backup.history.toMutableList()
    }

    fun ids(): Set<String> = synchronized(this) { records.keys.toSortedSet() }

    /** Lets tests tamper with stored data, as a malicious server could. */
    fun corrupt(id: String, transform: (RemoteRecord) -> RemoteRecord) = synchronized(this) {
        records[id] = transform(records.getValue(id))
    }

    fun stored(id: String): RemoteRecord? = synchronized(this) { records[id] }

    fun api(): SyncApi = object : SyncApi {
        override suspend fun info() = synchronized(this@FakeSyncServer) { ServerInfo(version = "fake", protocol = 1, vaultExists = vault != null) }

        override suspend fun register(pairingCode: String, deviceName: String) = RegisterResponse("device", "token")

        override suspend fun getVault(): VaultHeader? = synchronized(this@FakeSyncServer) { vault }

        override suspend fun createVault(header: VaultHeader) = synchronized(this@FakeSyncServer) {
            if (vault != null) throw SyncException.VaultAlreadyExists()
            vault = header
        }

        override suspend fun pull(since: Long, limit: Int, head: String?): PullResponse = synchronized(this@FakeSyncServer) {
            val changed = records.values.filter { it.seq > since }.sortedBy { it.seq }
            val page = changed.take(limit)
            PullResponse(page, page.lastOrNull()?.seq ?: since, changed.size > page.size, history.last(), head == null || head in history)
        }

        override suspend fun push(records: List<PushRecord>): PushResponse = synchronized(this@FakeSyncServer) {
            val accepted = mutableListOf<Accepted>()
            val conflicts = mutableListOf<RemoteRecord>()
            for (r in records) {
                val current = this@FakeSyncServer.records[r.id]
                if (current != null && current.rev != r.baseRev) {
                    conflicts += current
                    continue
                }
                val next = RemoteRecord(r.id, (current?.rev ?: 0) + 1, ++seq, r.deleted, r.blob)
                this@FakeSyncServer.records[r.id] = next
                accepted += Accepted(r.id, next.rev)
            }
            if (accepted.isNotEmpty()) history += newToken()
            PushResponse(accepted, conflicts, history.last())
        }

        override suspend fun devices() = emptyList<DeviceInfo>()

        override suspend fun revokeDevice(id: String) = Unit
    }

    private companion object {
        fun newToken() = java.util.UUID.randomUUID().toString()
    }
}
