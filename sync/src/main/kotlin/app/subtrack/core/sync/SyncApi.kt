// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.core.sync

import app.subtrack.core.crypto.VaultHeader

/** The sync server's HTTP API, as seen by a paired device. */
interface SyncApi {
    suspend fun info(): ServerInfo
    suspend fun register(pairingCode: String, deviceName: String): RegisterResponse

    /** null when no vault has been created on the server yet. */
    suspend fun getVault(): VaultHeader?

    /** @throws SyncException.VaultAlreadyExists if another device created a vault first. */
    suspend fun createVault(header: VaultHeader)

    /**
     * Changes after [since]. With [head], the server also says whether that history token is part of
     * its history ([PullResponse.headKnown]); if not, its data was rolled back.
     */
    suspend fun pull(since: Long, limit: Int = Protocol.PULL_LIMIT_MAX, head: String? = null): PullResponse
    suspend fun push(records: List<PushRecord>): PushResponse

    suspend fun devices(): List<DeviceInfo>
    suspend fun revokeDevice(id: String)
}

/** Everything that can go wrong talking to the server, phrased so the UI can explain it. */
sealed class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Network error: typically the phone is not on the home network, or the NAS is off. */
    class Unreachable(cause: Throwable) : SyncException("Server unreachable: ${cause.message}", cause)

    /** The server presented a different TLS certificate from the one pinned at pairing. */
    class CertificateMismatch(cause: Throwable? = null) :
        SyncException("The server's certificate does not match the one you paired with", cause)

    /** The device token was revoked or the server was reset. */
    class Unauthorized : SyncException("This device is no longer authorized by the server")

    class InvalidPairingCode : SyncException("Pairing code was not accepted")
    class TooManyAttempts : SyncException("Too many pairing attempts; wait a minute and try again")
    class VaultAlreadyExists : SyncException("A vault already exists on this server")
    class NoVault : SyncException("No vault exists on the server yet")

    /** The server's vault header is malformed or has unreasonable parameters. */
    class InvalidVault(cause: Throwable) : SyncException("The server's vault data is invalid", cause)

    /** The server holds a different vault than the one this device joined (e.g. it was reset). */
    class VaultMismatch : SyncException("The server's vault is not the one this device joined")

    /** The server refused new data: this vault reached its storage limit. */
    class StorageFull : SyncException("The server's storage limit for this vault is reached")

    class Server(val code: Int, message: String) : SyncException("Server error $code: $message")
}
