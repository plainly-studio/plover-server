// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.core.sync

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** SHA-256 fingerprint of a DER-encoded certificate, as printed by `openssl x509 -fingerprint -sha256`. */
class CertFingerprint private constructor(private val bytes: ByteArray) {

    /** "AB:CD:…", 32 colon-separated hex pairs. */
    val hex: String get() = bytes.joinToString(":") { "%02X".format(it) }

    fun matches(cert: X509Certificate): Boolean = MessageDigest.isEqual(bytes, sha256(cert.encoded))

    override fun equals(other: Any?) = other is CertFingerprint && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = hex

    companion object {
        fun of(cert: X509Certificate) = CertFingerprint(sha256(cert.encoded))

        /** Accepts colon-separated, space-separated or plain hex, any case. */
        fun parse(text: String): CertFingerprint {
            val clean = text.filter { it.isLetterOrDigit() }.uppercase()
            require(clean.length == 64 && clean.all { it in '0'..'9' || it in 'A'..'F' }) { "Not a SHA-256 fingerprint" }
            return CertFingerprint(ByteArray(32) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() })
        }

        private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
    }
}

/**
 * Trust-on-first-use for a self-signed LAN server: trusts exactly one certificate, the one the
 * user confirmed at pairing, and nothing else, not even certificates from public CAs.
 */
class PinnedTrustManager(private val pin: CertFingerprint) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        if (!pin.matches(leaf)) throw PinMismatchException(CertFingerprint.of(leaf))
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class PinMismatchException(val presented: CertFingerprint) :
    CertificateException("Server certificate $presented does not match the pinned certificate")

/** Records the server's certificate without trusting it; used only to show it to the user for confirmation. */
internal class CapturingTrustManager : X509TrustManager {
    @Volatile var captured: X509Certificate? = null

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        captured = chain?.firstOrNull() ?: throw CertificateException("Empty certificate chain")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun OkHttpClient.Builder.trusting(trustManager: X509TrustManager, pin: CertFingerprint?): OkHttpClient.Builder {
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    sslSocketFactory(context.socketFactory, trustManager)
    // A LAN server is addressed by IP or a local hostname, which a self-signed certificate may not
    // name. The pinned trust manager already guarantees we are talking to exactly the pinned
    // certificate, so hostname checks add nothing; accept the host only if that same pin matches.
    hostnameVerifier(
        HostnameVerifier { _, session ->
            if (pin == null) return@HostnameVerifier true
            val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
            leaf != null && pin.matches(leaf)
        },
    )
    return this
}
