// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.core.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/** A [LocalStore] in memory, standing in for a device's database in tests. */
class InMemoryLocalStore(val deviceId: String, private val clock: () -> Long = System::currentTimeMillis) : LocalStore {
    private val mutex = Mutex()
    private val records = linkedMapOf<String, LocalRecord>()
    private var cursor = 0L
    private var head: String? = null

    /** Simulates the user editing a record on this device. */
    suspend fun edit(id: String, type: RecordType, data: JsonElement?, at: Long = clock()) = mutex.withLock {
        val existing = records[id]
        records[id] = LocalRecord(id, type, data, at, deviceId, existing?.rev ?: 0, dirty = true)
    }

    suspend fun snapshot(): Map<String, JsonElement?> = mutex.withLock { records.mapValues { it.value.data } }

    suspend fun visible(): Map<String, JsonElement> = mutex.withLock {
        records.filterValues { it.data != null }.mapValues { it.value.data!! }
    }

    override suspend fun get(id: String): LocalRecord? = mutex.withLock { records[id] }

    override suspend fun dirty(): List<LocalRecord> = mutex.withLock { records.values.filter { it.dirty } }

    override suspend fun applyRemote(record: LocalRecord, expectedModifiedAt: Long?): Boolean = mutex.withLock {
        if (records[record.id]?.modifiedAt != expectedModifiedAt) return@withLock false
        records[record.id] = record.copy(dirty = false)
        true
    }

    override suspend fun markPushed(id: String, rev: Long, pushedModifiedAt: Long) = mutex.withLock {
        val r = records[id] ?: return@withLock
        records[id] = r.copy(rev = rev, dirty = r.modifiedAt != pushedModifiedAt)
    }

    override suspend fun rebase(id: String, rev: Long) = mutex.withLock {
        val r = records[id] ?: return@withLock
        records[id] = r.copy(rev = rev)
    }

    override suspend fun cursor(): Long = mutex.withLock { cursor }

    override suspend fun setCursor(cursor: Long) = mutex.withLock { this.cursor = cursor }

    override suspend fun head(): String? = mutex.withLock { head }

    override suspend fun setHead(head: String) = mutex.withLock { this.head = head }

    override suspend fun resetAfterServerRollback() = mutex.withLock {
        records.replaceAll { _, r -> r.copy(rev = 0, dirty = true) }
        cursor = 0
    }
}
