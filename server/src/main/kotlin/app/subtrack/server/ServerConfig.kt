// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.server

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
        fun fromEnvironment(env: Map<String, String> = System.getenv()) = ServerConfig(
            dataDir = Paths.get(env["SUBTRACK_DATA_DIR"] ?: "/data"),
            port = env["SUBTRACK_PORT"]?.toIntOrNull() ?: 8443,
            pairingCode = env["SUBTRACK_PAIRING_CODE"]?.trim()?.takeIf { it.isNotEmpty() },
            pairingEnabled = env["SUBTRACK_PAIRING_ENABLED"]?.lowercase() != "false",
            hostnames = env["SUBTRACK_HOSTNAMES"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
}

object ServerVersion {
    val current: String = ServerVersion::class.java.`package`?.implementationVersion ?: "dev"
}
