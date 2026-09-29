// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.server

import app.subtrack.core.crypto.VaultCrypto
import app.subtrack.core.sync.ErrorResponse
import app.subtrack.core.sync.Protocol
import app.subtrack.core.sync.PushRequest
import app.subtrack.core.sync.RegisterRequest
import app.subtrack.core.sync.RegisterResponse
import app.subtrack.core.sync.ServerInfo
import app.subtrack.core.sync.VaultEnvelope
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import org.slf4j.event.Level

/** Limits that keep a buggy or hostile client from filling the NAS or its small heap. */
object Limits {
    const val MAX_BLOB_CHARS = Protocol.MAX_BLOB_CHARS
    const val MAX_ID_CHARS = 128

    /** Pairing and vault requests are tiny. */
    const val MAX_SMALL_BODY = 16L * 1024

    /** Clients aim for [Protocol.PUSH_BYTES_TARGET]; allow headroom. */
    const val MAX_PUSH_BODY = 2L * Protocol.PUSH_BYTES_TARGET
}

class PayloadTooLargeException(val limit: Long) : Exception("Request body is larger than $limit bytes")

/**
 * Reads and parses a JSON body, refusing anything larger than [limit] bytes, including chunked
 * uploads with no Content-Length, before it can exhaust memory.
 */
suspend fun <T> ApplicationCall.receiveBounded(serializer: KSerializer<T>, limit: Long): T {
    request.contentLength()?.let { if (it > limit) throw PayloadTooLargeException(limit) }
    val bytes = receiveChannel().readRemaining(limit + 1).readByteArray()
    if (bytes.size > limit) throw PayloadTooLargeException(limit)
    return try {
        Protocol.json.decodeFromString(serializer, bytes.decodeToString())
    } catch (e: SerializationException) {
        throw BadRequestException("Malformed request body", e)
    }
}

fun Application.ploverModule(store: SyncStore, pairing: Pairing, clock: () -> Long = System::currentTimeMillis) {
    install(ContentNegotiation) { json(Protocol.json) }
    install(CallLogging) {
        level = Level.INFO
        // Never log bodies or headers: they carry tokens and ciphertext. The once-a-minute container
        // healthcheck (GET /v1/info) is left out so the startup banner stays easy to find.
        filter { call -> call.request.local.uri != "/v1/info" }
        format { call -> "${call.request.local.method.value} ${call.request.local.uri} -> ${call.response.status()?.value}" }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", cause.message ?: "Malformed request"))
        }
        exception<PayloadTooLargeException> { call, cause ->
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large", cause.message ?: "Request too large"))
        }
        exception<SyncStore.QuotaExceededException> { call, cause ->
            call.respond(HttpStatusCode.InsufficientStorage, ErrorResponse("vault_full", cause.message ?: "Storage limit reached"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", cause.message ?: "Invalid request"))
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Unknown or revoked device token"))
        }
    }
    install(Authentication) {
        bearer("device") {
            authenticate { credential -> io { store.authenticate(credential.token, clock()) }?.let(::UserIdPrincipal) }
        }
    }

    routing {
        route("/v1") {
            get("/info") {
                call.respond(ServerInfo(version = ServerVersion.current, protocol = Protocol.VERSION, vaultExists = io { store.vault() } != null))
            }

            post("/devices/register") {
                val request = call.receiveBounded(RegisterRequest.serializer(), Limits.MAX_SMALL_BODY)
                // The socket's address: no reverse DNS lookup, and no forwarded header to spoof.
                when (pairing.check(request.pairingCode, call.request.local.remoteAddress, clock())) {
                    Pairing.Result.Ok -> {
                        val name = request.deviceName.trim().ifEmpty { "Unnamed device" }
                        val device = io { store.registerDevice(name, clock()) }
                        call.application.environment.log.info("Paired new device \"$name\" (${device.id})")
                        call.respond(RegisterResponse(device.id, device.token))
                    }
                    Pairing.Result.Invalid -> call.error(HttpStatusCode.Forbidden, "invalid_pairing_code", "Pairing code was not accepted")
                    Pairing.Result.RateLimited -> call.error(HttpStatusCode.TooManyRequests, "rate_limited", "Too many attempts")
                    Pairing.Result.Disabled -> call.error(HttpStatusCode.Forbidden, "pairing_disabled", "Pairing is disabled on this server")
                }
            }

            authenticate("device") {
                get("/vault") {
                    val header = io { store.vault() }
                    if (header == null) {
                        call.error(HttpStatusCode.NotFound, "no_vault", "No vault has been created yet")
                    } else {
                        call.respond(VaultEnvelope(header))
                    }
                }

                put("/vault") {
                    val envelope = call.receiveBounded(VaultEnvelope.serializer(), Limits.MAX_SMALL_BODY)
                    VaultCrypto.validate(envelope.header)
                    if (io { store.createVault(envelope.header) }) {
                        call.respond(HttpStatusCode.Created, envelope)
                    } else {
                        call.error(HttpStatusCode.Conflict, "vault_exists", "A vault already exists on this server")
                    }
                }

                route("/records") {
                    get {
                        val params = call.request.queryParameters
                        val since = params["since"]?.let { requireNotNull(it.toLongOrNull()?.takeIf { v -> v >= 0 }) { "Invalid since" } } ?: 0
                        val limit = params["limit"]?.let { requireNotNull(it.toIntOrNull()) { "Invalid limit" } }
                            ?.coerceIn(1, Protocol.PULL_LIMIT_MAX) ?: Protocol.PULL_LIMIT_MAX
                        val head = params["head"]?.also { require(it.length <= Limits.MAX_ID_CHARS) { "Invalid head" } }
                        val page = io { store.pull(since, limit, head) }
                        if (!page.headKnown) {
                            call.application.environment.log.warn(
                                "A device has synced changes this server no longer has (was the data folder restored from a backup?). " +
                                    "It will now upload what is missing.",
                            )
                        }
                        call.respond(page)
                    }

                    post {
                        val request = call.receiveBounded(PushRequest.serializer(), Limits.MAX_PUSH_BODY)
                        require(request.records.size <= Protocol.PUSH_LIMIT_MAX) { "At most ${Protocol.PUSH_LIMIT_MAX} records per push" }
                        require(request.records.map { it.id }.toSet().size == request.records.size) { "Duplicate record ids in one push" }
                        for (r in request.records) {
                            require(r.id.isNotBlank() && r.id.length <= Limits.MAX_ID_CHARS) { "Invalid record id" }
                            require(r.blob.length <= Limits.MAX_BLOB_CHARS) { "Record ${r.id} is too large" }
                            require(r.baseRev >= 0) { "Invalid baseRev" }
                        }
                        call.respond(io { store.push(request.records, clock()) })
                    }
                }

                route("/devices") {
                    get { call.respond(io { store.devices() }) }

                    delete("/{id}") {
                        val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing device id")
                        if (io { store.revokeDevice(id) }) {
                            call.application.environment.log.info("Revoked device $id")
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.error(HttpStatusCode.NotFound, "not_found", "No such device")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun RoutingCall.error(status: HttpStatusCode, code: String, message: String) =
    respond(status, ErrorResponse(code, message))

/** SQLite calls block: keep them off the threads that serve requests. */
private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
