// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.server

import app.subtrack.core.sync.CertFingerprint
import io.ktor.network.tls.certificates.buildKeyStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.security.auth.x500.X500Principal

/**
 * The server's self-signed TLS identity, created on first start and reused afterwards so the
 * fingerprint devices pinned stays valid. Delete tls.p12 to rotate it (devices must re-pair).
 */
class TlsIdentity private constructor(val keyStore: KeyStore, val password: CharArray) {
    val certificate: X509Certificate get() = keyStore.getCertificate(ALIAS) as X509Certificate
    val fingerprint: CertFingerprint get() = CertFingerprint.of(certificate)

    companion object {
        const val ALIAS = "subtrack"

        fun loadOrCreate(dataDir: Path, hostnames: List<String>): TlsIdentity {
            val storeFile = dataDir.resolve("tls.p12")
            val passFile = dataDir.resolve("tls.pass")
            if (Files.exists(storeFile) && Files.exists(passFile)) {
                val password = Files.readString(passFile).trim().toCharArray()
                val ks = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(storeFile).use { load(it, password) } }
                return TlsIdentity(ks, password)
            }
            val password = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
            val generated = buildKeyStore {
                certificate(ALIAS) {
                    this.password = password
                    subject = X500Principal("CN=Subtrack sync server, O=Subtrack")
                    domains = (listOf("localhost") + hostnames).distinct()
                    daysValid = 365L * 30 // Pinned, not CA-validated: expiry would only force a re-pair.
                    keySizeInBits = 3072
                }
            }
            // Re-save as PKCS12 so the file can be inspected with standard tools (openssl, keytool).
            val ks = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(ALIAS, generated.getKey(ALIAS, password.toCharArray()), password.toCharArray(), generated.getCertificateChain(ALIAS))
            }
            Files.createDirectories(dataDir)
            Files.newOutputStream(storeFile).use { ks.store(it, password.toCharArray()) }
            Files.writeString(passFile, password)
            restrictToOwner(storeFile, passFile)
            return TlsIdentity(ks, password.toCharArray())
        }
    }
}

internal fun restrictToOwner(vararg files: Path) {
    for (file in files) {
        runCatching { Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
    }
}
