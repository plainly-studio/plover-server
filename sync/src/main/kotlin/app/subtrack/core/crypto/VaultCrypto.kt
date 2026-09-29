// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.core.crypto

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Key-derivation settings, stored in the clear with the vault so other devices can repeat them. */
@Serializable
data class KdfParams(
    val algorithm: String = "argon2id",
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    companion object {
        /** OWASP-recommended Argon2id settings; about a second on a mid-range phone. */
        val DEFAULT = KdfParams(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)

        /** Fast, weak settings for tests only. */
        val TEST = KdfParams(memoryKiB = 256, iterations = 1, parallelism = 1)

        /**
         * Upper bounds for parameters read from a server. Weaker parameters can't hurt (a key derived
         * with different parameters simply fails to unwrap the vault key), but huge ones could make a
         * hostile or buggy server exhaust the phone's memory or CPU.
         */
        const val MAX_MEMORY_KIB = 256 * 1024
        const val MAX_ITERATIONS = 10
        const val MAX_PARALLELISM = 4
    }

    /** @throws IllegalArgumentException for unsupported or unreasonable parameters. */
    fun requireSane() {
        require(algorithm == "argon2id") { "Unsupported KDF $algorithm" }
        require(memoryKiB in 8..MAX_MEMORY_KIB) { "Unreasonable KDF memory: $memoryKiB KiB" }
        require(iterations in 1..MAX_ITERATIONS) { "Unreasonable KDF iterations: $iterations" }
        require(parallelism in 1..MAX_PARALLELISM) { "Unreasonable KDF parallelism: $parallelism" }
    }
}

/**
 * Public vault metadata stored on the server. It contains the random data key wrapped (encrypted)
 * with a key derived from the passphrase: only someone who knows the passphrase can unwrap it.
 */
@Serializable
data class VaultHeader(
    val formatVersion: Int = 1,
    val vaultId: String,
    val kdf: KdfParams,
    val salt: String,
    val wrappedKey: String,
    val createdAt: Long,
)

class WrongPassphraseException : Exception("The passphrase does not unlock this vault")

class DecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The 256-bit vault data key. Every record is encrypted with it. */
class VaultKey(bytes: ByteArray) {
    private val key = bytes.copyOf()

    init {
        require(key.size == KEY_BYTES) { "Vault key must be $KEY_BYTES bytes" }
    }

    fun encoded(): ByteArray = key.copyOf()

    internal fun spec() = SecretKeySpec(key, "AES")

    companion object {
        const val KEY_BYTES = 32
    }
}

/**
 * End-to-end encryption for the vault.
 *
 * - The passphrase is stretched with Argon2id into a key-encryption key (KEK).
 * - A random data key (DEK) is generated once per vault and stored on the server wrapped by the KEK
 *   (AES-256-GCM). Changing devices only needs the passphrase; the server never sees either key.
 * - Each record is encrypted with the DEK using AES-256-GCM, a fresh 96-bit nonce, and the record id
 *   as associated data, so the server cannot move a ciphertext from one record to another.
 */
object VaultCrypto {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val WRAP_AAD_PREFIX = "subtrack/vault-key/v1"
    private const val WRAPPED_KEY_BYTES = NONCE_BYTES + VaultKey.KEY_BYTES + TAG_BITS / 8
    private const val RECORD_AAD_PREFIX = "subtrack/record/v1/"

    private val random = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    fun createVault(
        passphrase: CharArray,
        vaultId: String,
        now: Long,
        kdf: KdfParams = KdfParams.DEFAULT,
    ): Pair<VaultHeader, VaultKey> {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val dek = ByteArray(VaultKey.KEY_BYTES).also(random::nextBytes)
        val kek = deriveKek(passphrase, salt, kdf)
        val wrapped = seal(SecretKeySpec(kek, "AES"), dek, wrapAad(vaultId, kdf))
        kek.fill(0)
        val header = VaultHeader(
            vaultId = vaultId,
            kdf = kdf,
            salt = b64.encodeToString(salt),
            wrappedKey = b64.encodeToString(wrapped),
            createdAt = now,
        )
        return header to VaultKey(dek).also { dek.fill(0) }
    }

    /** @throws WrongPassphraseException if the passphrase is wrong (or the header was tampered with). */
    fun unlock(header: VaultHeader, passphrase: CharArray): VaultKey {
        validate(header)
        val kek = deriveKek(passphrase, b64d.decode(header.salt), header.kdf)
        try {
            val dek = open(SecretKeySpec(kek, "AES"), b64d.decode(header.wrappedKey), wrapAad(header.vaultId, header.kdf))
            return VaultKey(dek).also { dek.fill(0) }
        } catch (e: GeneralSecurityException) {
            throw WrongPassphraseException()
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Checks a header from the server before any expensive work: format, bounded KDF parameters,
     * and correctly sized salt and wrapped key. Used by devices and by the server on upload.
     * @throws IllegalArgumentException if the header is malformed or unreasonable.
     */
    fun validate(header: VaultHeader) {
        require(header.formatVersion == 1) { "Unsupported vault format ${header.formatVersion}" }
        require(header.vaultId.isNotBlank() && header.vaultId.length <= 64) { "Invalid vault id" }
        header.kdf.requireSane()
        val salt = runCatching { b64d.decode(header.salt) }.getOrNull()
        require(salt != null && salt.size == SALT_BYTES) { "Invalid salt" }
        val wrapped = runCatching { b64d.decode(header.wrappedKey) }.getOrNull()
        require(wrapped != null && wrapped.size == WRAPPED_KEY_BYTES) { "Invalid wrapped key" }
    }

    /** The wrapped key is bound to its vault and KDF settings: changing either makes unwrapping fail. */
    private fun wrapAad(vaultId: String, kdf: KdfParams): ByteArray =
        "$WRAP_AAD_PREFIX|$vaultId|${kdf.algorithm}|${kdf.memoryKiB}|${kdf.iterations}|${kdf.parallelism}".toByteArray()

    fun encryptRecord(key: VaultKey, recordId: String, plaintext: ByteArray): ByteArray =
        seal(key.spec(), plaintext, (RECORD_AAD_PREFIX + recordId).toByteArray())

    /** @throws DecryptionException if the ciphertext was modified or belongs to another record/vault. */
    fun decryptRecord(key: VaultKey, recordId: String, ciphertext: ByteArray): ByteArray = try {
        open(key.spec(), ciphertext, (RECORD_AAD_PREFIX + recordId).toByteArray())
    } catch (e: GeneralSecurityException) {
        throw DecryptionException("Record $recordId failed authentication", e)
    } catch (e: IllegalArgumentException) {
        throw DecryptionException("Record $recordId is malformed", e)
    }

    internal fun deriveKek(passphrase: CharArray, salt: ByteArray, kdf: KdfParams): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(kdf.memoryKiB)
            .withIterations(kdf.iterations)
            .withParallelism(kdf.parallelism)
            .build()
        val out = ByteArray(VaultKey.KEY_BYTES)
        // Encode without creating an immutable String copy of the passphrase.
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val password = ByteArray(buffer.remaining()).also { buffer.get(it) }
        if (buffer.hasArray()) buffer.array().fill(0)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(password, out)
        password.fill(0)
        return out
    }

    /** nonce || ciphertext || tag */
    private fun seal(key: SecretKeySpec, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    private fun open(key: SecretKeySpec, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(sealed.size > NONCE_BYTES + TAG_BITS / 8) { "Ciphertext too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }
}
