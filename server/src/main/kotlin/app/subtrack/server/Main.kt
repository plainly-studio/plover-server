// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.server

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import org.slf4j.LoggerFactory
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("plover")

fun main(args: Array<String>) {
    val config = ServerConfig.fromEnvironment()
    try {
        command(args, config)
    } catch (e: AccessDeniedException) {
        // The usual first-run problem: the data folder belongs to someone other than the container's user.
        System.err.println(permissionHelp(e.file, config.dataDir, processIds(), ownerOf(config.dataDir)))
        exitProcess(1)
    }
}

private fun command(args: Array<String>, config: ServerConfig) {
    when (args.firstOrNull()) {
        null, "serve" -> serve(config)
        "show-pairing" -> {
            val tls = TlsIdentity.loadOrCreate(config.dataDir, config.hostnames)
            val pairing = Pairing.loadOrCreate(config.dataDir, config.pairingCode, config.pairingEnabled)
            println(banner(config, tls, pairing, null))
        }
        "rotate-pairing-code" -> {
            check(config.pairingCode == null) { "PLOVER_PAIRING_CODE is set in the environment; change it there instead" }
            println("New pairing code: ${Pairing.rotate(config.dataDir)}  (restart the server to use it)")
        }
        "reset-vault" -> {
            if (args.getOrNull(1) != "--yes") {
                println("This permanently deletes the vault and all synced data on the server.")
                println("Devices keep their local copy and can create a new vault. Re-run with: reset-vault --yes")
                exitProcess(1)
            }
            SyncStore(ServerConfig.database(config.dataDir)).use { it.resetVault() }
            println("Vault deleted.")
        }
        else -> {
            println("Usage: plover-server [serve | show-pairing | rotate-pairing-code | reset-vault --yes]")
            exitProcess(2)
        }
    }
}

/** What to do when the data folder can't be written, instead of a stack trace. */
fun permissionHelp(file: String?, dataDir: Path, process: Pair<Int, Int>?, owner: Int?): String = buildString {
    appendLine()
    appendLine("  Plover can't write to its data folder (${file ?: dataDir}: permission denied).")
    appendLine()
    val runsAs = process?.let { (uid, gid) -> "user $uid, group $gid" }
    if (runsAs != null) {
        append("  The server runs as $runsAs")
        appendLine(if (owner != null) ", but the folder belongs to user $owner${if (owner == 0) " (root)" else ""}." else ".")
    }
    appendLine("  On the NAS, give the folder to that user, then restart the container:")
    appendLine()
    appendLine("    sudo chown -R ${process?.let { (uid, gid) -> "$uid:$gid" } ?: "<user>:<group>"} <your data folder>")
    appendLine()
    appendLine("  Your data folder is the left side of the volumes: line in compose.yaml.")
    appendLine("  To run as a different user, set PUID and PGID instead.")
}

/** The process's user and group ids, from /proc (Linux, as in the container); null elsewhere. */
private fun processIds(): Pair<Int, Int>? = runCatching {
    val status = Files.readAllLines(Paths.get("/proc/self/status"))
    fun id(name: String) = status.first { it.startsWith("$name:") }.split(Regex("\\s+"))[1].toInt()
    id("Uid") to id("Gid")
}.getOrNull()

private fun ownerOf(dir: Path): Int? = runCatching { Files.getAttribute(dir, "unix:uid") as Int }.getOrNull()

fun serve(config: ServerConfig) {
    val tls = TlsIdentity.loadOrCreate(config.dataDir, config.hostnames)
    val pairing = Pairing.loadOrCreate(config.dataDir, config.pairingCode, config.pairingEnabled)
    val store = SyncStore(ServerConfig.database(config.dataDir))
    val server = createServer(config.port, tls, store, pairing)
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(1_000, 5_000); store.close() })

    log.info(banner(config, tls, pairing, store))
    server.start(wait = true)
}

fun createServer(port: Int, tls: TlsIdentity, store: SyncStore, pairing: Pairing, host: String = "0.0.0.0"):
    EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
    embeddedServer(
        Netty,
        environment = applicationEnvironment { this.log = LoggerFactory.getLogger("plover.http") },
        configure = {
            sslConnector(
                keyStore = tls.keyStore,
                keyAlias = TlsIdentity.ALIAS,
                keyStorePassword = { tls.password },
                privateKeyPassword = { tls.password },
            ) {
                this.port = port
                this.host = host
                enabledProtocols = listOf("TLSv1.3", "TLSv1.2")
            }
        },
        module = { ploverModule(store, pairing) },
    )

/** The pairing details shown at startup (and by `show-pairing`, when the log has scrolled away). */
fun banner(config: ServerConfig, tls: TlsIdentity, pairing: Pairing, store: SyncStore?): String = buildString {
    appendLine()
    appendLine("  Plover sync server ${ServerVersion.current} on port ${config.port} (HTTPS)")
    appendLine()
    appendLine("  In the app, enter:   https://<your NAS IP address>:${config.port}")
    appendLine("  Pairing code:        ${if (pairing.enabled) pairing.code else "(pairing disabled)"}")
    appendLine("  Certificate SHA-256 fingerprint (check it matches what the app shows):")
    tls.fingerprint.hex.chunked(24).forEach { appendLine("    " + it.trimEnd(':')) }
    if (store != null) {
        appendLine()
        appendLine("  Vault: ${if (store.vault() != null) "created, ${store.recordCount()} records" else "not created yet"}")
        appendLine("  Paired devices: ${store.devices().size}")
    }
}
