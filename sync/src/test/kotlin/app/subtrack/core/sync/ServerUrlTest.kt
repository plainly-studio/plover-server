// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerUrlTest {
    private fun n(s: String) = OkHttpSyncApi.normalizeServerUrl(s).toString()

    @Test
    fun `bare addresses get https and the default port`() {
        assertEquals("https://192.168.1.20:8443/", n("192.168.1.20"))
        assertEquals("https://nas.local:8443/", n(" nas.local/ "))
    }

    @Test
    fun `explicit ports and schemes are kept`() {
        assertEquals("https://192.168.1.20:9000/", n("192.168.1.20:9000"))
        assertEquals("https://nas.local/", n("https://nas.local:443"))
        assertEquals("https://nas.local:8443/", n("https://nas.local/some/path"))
    }

    @Test
    fun `IPv6 literals get the default port unless one is given`() {
        assertEquals("https://[fe80::1]:8443/", n("[fe80::1]"))
        assertEquals("https://[fe80::1]:8443/", n("https://[fe80::1]"))
        assertEquals("https://[fe80::1]:9000/", n("[fe80::1]:9000"))
    }

    @Test
    fun `plain http and garbage are refused`() {
        assertFailsWith<IllegalArgumentException> { n("http://192.168.1.20") }
        assertFailsWith<IllegalArgumentException> { n("") }
        assertFailsWith<IllegalArgumentException> { n("not a url at all") }
    }

    @Test
    fun `fingerprints parse in any common format`() {
        val colon = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89"
        assertEquals(colon, CertFingerprint.parse(colon.lowercase().replace(":", " ")).hex)
        assertFailsWith<IllegalArgumentException> { CertFingerprint.parse("AB:CD") }
    }
}
