// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class ServerConfig(
    val dataDir: Path,
    val port: Int,
    /** Fixed pairing code from the environment; otherwise one is generated and kept in the data dir. */
    val pairingCode: String?,
    /** Set to false once all devices are paired to refuse new registrations entirely. */
    val pairingEnabled: Boolean,
    /** Extra host names (e.g. "nas.local") to put in the self-signed certificate. */
    val hostnames: List<String>,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            // PLOVER_*, or SUBTRACK_* from before the app was renamed.
            fun setting(name: String) = env["PLOVER_$name"] ?: env["SUBTRACK_$name"]
            return ServerConfig(
                dataDir = Paths.get(setting("DATA_DIR") ?: "/data"),
                port = setting("PORT")?.toIntOrNull() ?: 8443,
                pairingCode = setting("PAIRING_CODE")?.trim()?.takeIf { it.isNotEmpty() },
                pairingEnabled = setting("PAIRING_ENABLED")?.lowercase() != "false",
                hostnames = setting("HOSTNAMES").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            )
        }

        /**
         * The database in [dataDir]. It was subtrack.db before the rename; an old one is moved to the
         * new name, with its WAL files, the first time the server starts.
         */
        fun database(dataDir: Path): Path {
            val db = dataDir.resolve("plover.db")
            val old = dataDir.resolve("subtrack.db")
            if (Files.notExists(db) && Files.exists(old)) {
                for (suffix in listOf("", "-wal", "-shm")) {
                    val from = dataDir.resolve("subtrack.db$suffix")
                    if (Files.exists(from)) Files.move(from, dataDir.resolve("plover.db$suffix"))
                }
            }
            return db
        }
    }
}

object ServerVersion {
    val current: String = ServerVersion::class.java.`package`?.implementationVersion ?: "dev"
}
