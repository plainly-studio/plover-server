// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.core.sync

import app.subtrack.core.crypto.VaultHeader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire format between devices and the sync server. The server only ever sees opaque record ids,
 * revision counters, a tombstone flag and base64 ciphertext.
 */
object Protocol {
    const val VERSION = 1
    const val PULL_LIMIT_MAX = 500
    const val PUSH_LIMIT_MAX = 500

    /** Largest encrypted record the server stores (base64 characters). */
    const val MAX_BLOB_CHARS = 64 * 1024

    /** Clients split pushes so a request body stays under this; the server accepts up to twice it. */
    const val PUSH_BYTES_TARGET = 2 * 1024 * 1024

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

@Serializable
data class ServerInfo(val name: String = "subtrack-server", val version: String, val protocol: Int, val vaultExists: Boolean)

@Serializable
data class RegisterRequest(val pairingCode: String, val deviceName: String)

@Serializable
data class RegisterResponse(val deviceId: String, val token: String)

@Serializable
data class DeviceInfo(val id: String, val name: String, val createdAt: Long, val lastSeenAt: Long?)

@Serializable
data class VaultEnvelope(val header: VaultHeader)

/** A record as stored by the server. [rev] increments on every accepted write; [seq] is the global change cursor. */
@Serializable
data class RemoteRecord(
    val id: String,
    val rev: Long,
    val seq: Long,
    val deleted: Boolean,
    /** base64(nonce || AES-GCM ciphertext) */
    val blob: String,
)

@Serializable
data class PullResponse(
    val records: List<RemoteRecord>,
    val cursor: Long,
    val hasMore: Boolean,
    /**
     * The server's latest history token. Every accepted write appends a new random token to the
     * server's history, so a device that remembers the last token it saw can later ask whether the
     * server still has that history (see `head` on [SyncApi.pull]).
     */
    val head: String,
    /**
     * False when the device sent a history token this server has never issued: the server's data
     * was rolled back (restored from an older backup, or lost), so the device must reconcile.
     */
    val headKnown: Boolean = true,
)

@Serializable
data class PushRecord(
    val id: String,
    /** The revision this change was based on; 0 for a record the server has never seen. */
    val baseRev: Long,
    val deleted: Boolean,
    val blob: String,
)

@Serializable
data class PushRequest(val records: List<PushRecord>)

@Serializable
data class Accepted(val id: String, val rev: Long)

@Serializable
data class PushResponse(
    val accepted: List<Accepted>,
    /** Records whose baseRev was stale, with the server's current copy. */
    val conflicts: List<RemoteRecord>,
    /** The history token after this push (see [PullResponse.head]). */
    val head: String,
)

@Serializable
data class ErrorResponse(val error: String, val message: String)
