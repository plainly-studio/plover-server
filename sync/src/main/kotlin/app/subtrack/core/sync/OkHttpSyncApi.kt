// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.core.sync

import app.subtrack.core.crypto.VaultHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * [SyncApi] over HTTPS, trusting only the pinned server certificate.
 *
 * @param baseUrl e.g. `https://192.168.1.20:8443/`
 * @param token the device token from [register]; null before pairing.
 */
class OkHttpSyncApi(
    baseUrl: String,
    pin: CertFingerprint,
    private val token: String?,
    base: OkHttpClient = OkHttpClient(),
) : SyncApi {
    private val root: HttpUrl = normalizeServerUrl(baseUrl)
    private val client = base.newBuilder()
        .trusting(PinnedTrustManager(pin), pin)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun info(): ServerInfo = call(get("v1/info", auth = false), ServerInfo.serializer())

    override suspend fun register(pairingCode: String, deviceName: String): RegisterResponse =
        call(
            post("v1/devices/register", RegisterRequest(pairingCode.trim(), deviceName), RegisterRequest.serializer(), auth = false),
            RegisterResponse.serializer(),
        )

    override suspend fun getVault(): VaultHeader? = try {
        call(get("v1/vault"), VaultEnvelope.serializer()).header
    } catch (e: SyncException.NoVault) {
        null
    }

    override suspend fun createVault(header: VaultHeader) {
        call<Unit>(put("v1/vault", VaultEnvelope(header), VaultEnvelope.serializer()), null)
    }

    override suspend fun pull(since: Long, limit: Int, head: String?): PullResponse {
        val url = root.newBuilder().addPathSegments("v1/records")
            .addQueryParameter("since", since.toString())
            .addQueryParameter("limit", limit.toString())
            .apply { if (head != null) addQueryParameter("head", head) }
            .build()
        return call(Request.Builder().url(url).authorized().get().build(), PullResponse.serializer())
    }

    override suspend fun push(records: List<PushRecord>): PushResponse =
        call(post("v1/records", PushRequest(records), PushRequest.serializer()), PushResponse.serializer())

    override suspend fun devices(): List<DeviceInfo> = call(get("v1/devices"), ListSerializer(DeviceInfo.serializer()))

    override suspend fun revokeDevice(id: String) {
        val url = root.newBuilder().addPathSegments("v1/devices").addPathSegment(id).build()
        call<Unit>(Request.Builder().url(url).authorized().delete().build(), null)
    }

    private fun get(path: String, auth: Boolean = true) =
        Request.Builder().url(root.resolve(path)!!).apply { if (auth) authorized() }.get().build()

    private fun <T> post(path: String, body: T, serializer: KSerializer<T>, auth: Boolean = true) =
        Request.Builder().url(root.resolve(path)!!).apply { if (auth) authorized() }
            .post(Protocol.json.encodeToString(serializer, body).toRequestBody(JSON)).build()

    private fun <T> put(path: String, body: T, serializer: KSerializer<T>) =
        Request.Builder().url(root.resolve(path)!!).authorized()
            .put(Protocol.json.encodeToString(serializer, body).toRequestBody(JSON)).build()

    private fun Request.Builder.authorized(): Request.Builder = apply {
        token?.let { header("Authorization", "Bearer $it") }
    }

    private suspend fun <T> call(request: Request, serializer: KSerializer<T>?): T = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw mapNetworkError(e)
        }
        response.use {
            if (!it.isSuccessful) throw mapHttpError(it)
            @Suppress("UNCHECKED_CAST")
            if (serializer == null) return@withContext Unit as T
            try {
                Protocol.json.decodeFromString(serializer, it.body.string())
            } catch (e: SerializationException) {
                throw SyncException.Server(it.code, "Unexpected response: ${e.message}")
            }
        }
    }

    private fun mapHttpError(response: Response): SyncException {
        val error = runCatching {
            Protocol.json.decodeFromString(ErrorResponse.serializer(), response.body.string())
        }.getOrNull()
        return when {
            response.code == 401 -> SyncException.Unauthorized()
            error?.error == "invalid_pairing_code" -> SyncException.InvalidPairingCode()
            response.code == 429 -> SyncException.TooManyAttempts()
            error?.error == "vault_exists" -> SyncException.VaultAlreadyExists()
            error?.error == "no_vault" -> SyncException.NoVault()
            error?.error == "vault_full" -> SyncException.StorageFull()
            else -> SyncException.Server(response.code, error?.message ?: response.message)
        }
    }

    companion object {
        /** A ":port" after the host, which is a bracketed IPv6 literal or a name or IPv4 address. */
        private val explicitPort = Regex("^https://(\\[[^\\]]*]|[^/:\\[]+):\\d+", RegexOption.IGNORE_CASE)

        private val JSON = "application/json".toMediaType()

        /**
         * Connects without trusting the server and returns its certificate, so the user can compare
         * the fingerprint with the one printed in the server log before pinning it.
         */
        suspend fun probe(baseUrl: String, base: OkHttpClient = OkHttpClient()): ProbeResult = withContext(Dispatchers.IO) {
            val root = normalizeServerUrl(baseUrl)
            val capture = CapturingTrustManager()
            val client = base.newBuilder()
                .trusting(capture, pin = null)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(root.resolve("v1/info")!!).get().build()
            val info = try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw SyncException.Server(response.code, "Not a Plover server")
                    try {
                        Protocol.json.decodeFromString(ServerInfo.serializer(), response.body.string())
                    } catch (e: SerializationException) {
                        throw SyncException.Server(response.code, "Not a Plover server")
                    }
                }
            } catch (e: IOException) {
                throw mapNetworkError(e)
            }
            val cert = capture.captured ?: throw SyncException.Server(0, "Server did not present a certificate")
            ProbeResult(root.toString(), info, CertFingerprint.of(cert), cert.subjectX500Principal.name, cert.notAfter)
        }

        /** Accepts "192.168.1.20", "192.168.1.20:8443" or a full URL; defaults to https and port 8443. */
        fun normalizeServerUrl(input: String): HttpUrl {
            val trimmed = input.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "Enter the server address" }
            require(!trimmed.startsWith("http://", ignoreCase = true)) { "The server only accepts HTTPS" }
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val parsed = runCatching { withScheme.toHttpUrl() }.getOrNull()
                ?: throw IllegalArgumentException("That doesn't look like a server address")
            // Host is a bracketed IPv6 literal or a name/IPv4 address; only a ":port" after it counts.
            val hasExplicitPort = explicitPort.containsMatchIn(withScheme)
            return parsed.newBuilder()
                .port(if (hasExplicitPort) parsed.port else DEFAULT_PORT)
                .encodedPath("/")
                .build()
        }

        const val DEFAULT_PORT = 8443

        private fun mapNetworkError(e: IOException): SyncException = when {
            e is SSLPeerUnverifiedException -> SyncException.CertificateMismatch(e)
            e is SSLHandshakeException && e.findCause<PinMismatchException>() != null -> SyncException.CertificateMismatch(e)
            e is SSLException && e.findCause<PinMismatchException>() != null -> SyncException.CertificateMismatch(e)
            else -> SyncException.Unreachable(e)
        }

        private inline fun <reified T : Throwable> Throwable.findCause(): T? {
            var current: Throwable? = this
            while (current != null) {
                if (current is T) return current
                current = current.cause
            }
            return null
        }
    }
}

data class ProbeResult(
    val url: String,
    val info: ServerInfo,
    val fingerprint: CertFingerprint,
    val subject: String,
    val validUntil: Date,
)
