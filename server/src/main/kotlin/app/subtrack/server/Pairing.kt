// Copyright 2026 Plainly Studio
// SPDX-License-Identifier: Apache-2.0

package app.subtrack.server

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom

/** The code a new device must present to register. Printed in the server log at startup. */
class Pairing(val code: String, val enabled: Boolean) {
    // Per client address, so one misbehaving host on the LAN can't lock everyone else out.
    private val limiters = object : LinkedHashMap<String, FailureLimiter>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FailureLimiter>) = size > MAX_TRACKED_CLIENTS
    }

    sealed interface Result {
        data object Ok : Result
        data object Invalid : Result
        data object RateLimited : Result
        data object Disabled : Result
    }

    fun check(candidate: String, client: String, now: Long = System.currentTimeMillis()): Result {
        if (!enabled) return Result.Disabled
        val limiter = synchronized(limiters) { limiters.getOrPut(client) { FailureLimiter(maxFailures = 5, windowMillis = 60_000) } }
        if (limiter.isBlocked(now)) return Result.RateLimited
        val ok = MessageDigest.isEqual(normalize(candidate).toByteArray(), normalize(code).toByteArray())
        if (!ok) limiter.recordFailure(now)
        return if (ok) Result.Ok else Result.Invalid
    }

    companion object {
        private const val MAX_TRACKED_CLIENTS = 1024

        // No 0/O, 1/I/L: easy to read from a log and type on a phone.
        private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

        fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

        fun loadOrCreate(dataDir: Path, fixed: String?, enabled: Boolean): Pairing {
            if (fixed != null) return Pairing(fixed, enabled)
            val file = dataDir.resolve("pairing-code")
            if (Files.exists(file)) return Pairing(Files.readString(file).trim(), enabled)
            val code = generate()
            Files.createDirectories(dataDir)
            Files.writeString(file, code)
            restrictToOwner(file)
            return Pairing(code, enabled)
        }

        fun rotate(dataDir: Path): String {
            val code = generate()
            val file = dataDir.resolve("pairing-code")
            Files.writeString(file, code)
            // Run via `docker exec`, which bypasses the entrypoint's umask.
            restrictToOwner(file)
            return code
        }

        private fun generate(): String {
            val random = SecureRandom()
            val raw = (1..12).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            return raw.chunked(4).joinToString("-")
        }
    }
}

/** Blocks pairing for the rest of the window after too many wrong codes (brute-force protection). */
internal class FailureLimiter(private val maxFailures: Int, private val windowMillis: Long) {
    private val failures = ArrayDeque<Long>()

    @Synchronized fun isBlocked(now: Long): Boolean {
        while (failures.isNotEmpty() && now - failures.first() > windowMillis) failures.removeFirst()
        return failures.size >= maxFailures
    }

    @Synchronized fun recordFailure(now: Long) {
        failures.addLast(now)
    }
}
