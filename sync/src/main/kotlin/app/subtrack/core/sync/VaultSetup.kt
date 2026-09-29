// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.core.sync

import app.subtrack.core.crypto.KdfParams
import app.subtrack.core.crypto.VaultCrypto
import app.subtrack.core.crypto.VaultHeader
import app.subtrack.core.crypto.VaultKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class UnlockedVault(val header: VaultHeader, val key: VaultKey)

/** Creating a new vault on the server, or joining the existing one from another device. */
object VaultSetup {
    const val MIN_PASSPHRASE_LENGTH = 10

    suspend fun create(api: SyncApi, passphrase: CharArray, now: Long, kdf: KdfParams = KdfParams.DEFAULT): UnlockedVault {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters" }
        if (api.getVault() != null) throw SyncException.VaultAlreadyExists()
        val (header, key) = withContext(Dispatchers.Default) {
            VaultCrypto.createVault(passphrase, UUID.randomUUID().toString(), now, kdf)
        }
        api.createVault(header)
        return UnlockedVault(header, key)
    }

    /** @throws app.subtrack.core.crypto.WrongPassphraseException */
    suspend fun join(api: SyncApi, passphrase: CharArray): UnlockedVault {
        val header = api.getVault() ?: throw SyncException.NoVault()
        val key = withContext(Dispatchers.Default) {
            try {
                VaultCrypto.unlock(header, passphrase)
            } catch (e: IllegalArgumentException) {
                throw SyncException.InvalidVault(e)
            }
        }
        return UnlockedVault(header, key)
    }
}
