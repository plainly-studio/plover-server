// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class VaultCryptoTest {
    private val passphrase = "correct horse battery staple".toCharArray()

    @Test
    fun `the passphrase unlocks the same key on another device`() {
        val (header, key) = VaultCrypto.createVault(passphrase, "vault-1", 0, KdfParams.TEST)
        val again = VaultCrypto.unlock(header, "correct horse battery staple".toCharArray())
        assertContentEquals(key.encoded(), again.encoded())
    }

    @Test
    fun `a wrong passphrase is rejected`() {
        val (header, _) = VaultCrypto.createVault(passphrase, "vault-1", 0, KdfParams.TEST)
        assertFailsWith<WrongPassphraseException> { VaultCrypto.unlock(header, "correct horse battery stapler".toCharArray()) }
    }

    @Test
    fun `a tampered header is rejected like a wrong passphrase`() {
        val (header, _) = VaultCrypto.createVault(passphrase, "vault-1", 0, KdfParams.TEST)
        val weaker = header.copy(kdf = header.kdf.copy(iterations = header.kdf.iterations + 1))
        assertFailsWith<WrongPassphraseException> { VaultCrypto.unlock(weaker, passphrase) }
    }

    @Test
    fun `unreasonable headers from a server are refused before deriving anything`() {
        val (header, _) = VaultCrypto.createVault(passphrase, "vault-1", 0, KdfParams.TEST)
        val hostile = listOf(
            header.copy(kdf = header.kdf.copy(memoryKiB = 4_000_000)), // would exhaust the phone's memory
            header.copy(kdf = header.kdf.copy(iterations = 1_000)),
            header.copy(kdf = header.kdf.copy(parallelism = 64)),
            header.copy(salt = "eA=="),
            header.copy(wrappedKey = "Z2FyYmFnZQ=="),
            header.copy(formatVersion = 2),
        )
        for (h in hostile) assertFailsWith<IllegalArgumentException> { VaultCrypto.unlock(h, passphrase) }
    }

    @Test
    fun `a header moved to another vault id no longer unlocks`() {
        val (header, _) = VaultCrypto.createVault(passphrase, "vault-1", 0, KdfParams.TEST)
        assertFailsWith<WrongPassphraseException> { VaultCrypto.unlock(header.copy(vaultId = "vault-2"), passphrase) }
    }

    @Test
    fun `records round-trip and use a fresh nonce each time`() {
        val (_, key) = VaultCrypto.createVault(passphrase, "vault-1", 0, KdfParams.TEST)
        val plaintext = """{"name":"Netflix"}""".toByteArray()
        val a = VaultCrypto.encryptRecord(key, "rec-1", plaintext)
        val b = VaultCrypto.encryptRecord(key, "rec-1", plaintext)
        assertFalse(a.contentEquals(b))
        assertContentEquals(plaintext, VaultCrypto.decryptRecord(key, "rec-1", a))
    }

    @Test
    fun `ciphertext cannot be modified or moved to another record`() {
        val (_, key) = VaultCrypto.createVault(passphrase, "vault-1", 0, KdfParams.TEST)
        val sealed = VaultCrypto.encryptRecord(key, "rec-1", "secret".toByteArray())
        val flipped = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<DecryptionException> { VaultCrypto.decryptRecord(key, "rec-1", flipped) }
        assertFailsWith<DecryptionException> { VaultCrypto.decryptRecord(key, "rec-2", sealed) }
        assertFailsWith<DecryptionException> { VaultCrypto.decryptRecord(key, "rec-1", ByteArray(5)) }
    }

    @Test
    fun `different vaults have different keys`() {
        val (_, a) = VaultCrypto.createVault(passphrase, "a", 0, KdfParams.TEST)
        val (_, b) = VaultCrypto.createVault(passphrase, "b", 0, KdfParams.TEST)
        assertNotEquals(a.encoded().toList(), b.encoded().toList())
        val sealed = VaultCrypto.encryptRecord(a, "rec", "x".toByteArray())
        assertFailsWith<DecryptionException> { VaultCrypto.decryptRecord(b, "rec", sealed) }
    }

    @Test
    fun `argon2id matches the RFC 9106 reference implementation`() {
        // Known-answer check against the Argon2 reference implementation (phc-winner-argon2):
        // echo -n "password" | argon2 somesalt -id -t 2 -m 16 -p 1 -l 32 -r
        val out = VaultCrypto.deriveKek("password".toCharArray(), "somesalt".toByteArray(), KdfParams(memoryKiB = 65536, iterations = 2, parallelism = 1))
        assertContentEquals(hex("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7"), out)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
