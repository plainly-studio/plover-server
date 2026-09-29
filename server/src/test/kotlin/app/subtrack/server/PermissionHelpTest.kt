// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.server

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class PermissionHelpTest {
    private val data = Paths.get("/data")

    @Test
    fun `names the file, both users and the command that fixes it`() {
        val help = permissionHelp("/data/tls.p12", data, process = 1000 to 100, owner = 0)
        assertContains(help, "/data/tls.p12: permission denied")
        assertContains(help, "runs as user 1000, group 100, but the folder belongs to user 0 (root).")
        assertContains(help, "sudo chown -R 1000:100 <your data folder>")
        assertContains(help, "PUID and PGID")
        assertFalse(help.contains("Exception"))
    }

    @Test
    fun `still says what to do when the ids can't be read`() {
        val help = permissionHelp(null, data, process = null, owner = null)
        assertContains(help, "(/data: permission denied)")
        assertContains(help, "sudo chown -R <user>:<group> <your data folder>")
    }
}
