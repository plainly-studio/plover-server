// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.server

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ServerConfigTest {
    @Test
    fun `settings are read from PLOVER_ names`() {
        val config = ServerConfig.fromEnvironment(
            mapOf(
                "PLOVER_DATA_DIR" to "/srv/plover",
                "PLOVER_PORT" to "9443",
                "PLOVER_PAIRING_CODE" to " ABCD-EFGH-JKMN ",
                "PLOVER_PAIRING_ENABLED" to "False",
                "PLOVER_HOSTNAMES" to "nas.local, pi.local",
            ),
        )
        assertEquals(Paths.get("/srv/plover"), config.dataDir)
        assertEquals(9443, config.port)
        assertEquals("ABCD-EFGH-JKMN", config.pairingCode)
        assertFalse(config.pairingEnabled)
        assertEquals(listOf("nas.local", "pi.local"), config.hostnames)
    }

    @Test
    fun `SUBTRACK_ names from before the rename still work, and PLOVER_ ones win`() {
        val config = ServerConfig.fromEnvironment(
            mapOf("SUBTRACK_PORT" to "9443", "SUBTRACK_PAIRING_ENABLED" to "false", "SUBTRACK_HOSTNAMES" to "old.local", "PLOVER_HOSTNAMES" to "new.local"),
        )
        assertEquals(9443, config.port)
        assertFalse(config.pairingEnabled)
        assertEquals(listOf("new.local"), config.hostnames)
    }

    @Test
    fun `nothing set gives the defaults`() {
        val config = ServerConfig.fromEnvironment(emptyMap())
        assertEquals(Paths.get("/data"), config.dataDir)
        assertEquals(8443, config.port)
        assertNull(config.pairingCode)
        assertEquals(true, config.pairingEnabled)
        assertEquals(emptyList(), config.hostnames)
    }

    @Test
    fun `an old subtrack database is moved to plover db, and keeps its data`() {
        val dir = Files.createTempDirectory("plover-config")
        SyncStore(dir.resolve("subtrack.db")).use { it.registerDevice("Phone", now = 1L) }
        val db = ServerConfig.database(dir)
        assertEquals(dir.resolve("plover.db"), db)
        assertFalse(Files.exists(dir.resolve("subtrack.db")))
        assertEquals(listOf("Phone"), SyncStore(db).use { store -> store.devices().map { it.name } })
    }

    @Test
    fun `an existing plover db is never replaced`() {
        val dir = Files.createTempDirectory("plover-config")
        Files.writeString(dir.resolve("plover.db"), "new")
        Files.writeString(dir.resolve("subtrack.db"), "old")
        assertEquals(dir.resolve("plover.db"), ServerConfig.database(dir))
        assertEquals("new", Files.readString(dir.resolve("plover.db")))
        assertEquals("old", Files.readString(dir.resolve("subtrack.db")))
    }
}
