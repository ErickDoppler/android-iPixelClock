package com.example.ipixelclock.web

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Password protection for the web interface.
 *
 * The rules, which are deliberately strict because this server listens on the
 * LAN with no TLS in front of it:
 *
 *  - The password is set **only from the Android app**, one input, no
 *    confirmation field. The web UI can never change it — otherwise anyone who
 *    got in once could lock the owner out.
 *  - Until a password has been set, remote access is **refused outright**, not
 *    left open. An unconfigured clock is not an open clock.
 *  - Only a salted SHA-256 hash is stored. The plaintext never touches disk.
 *  - Failed attempts are throttled per source address with a growing delay, so
 *    a four-character password is not brute-forceable over a weekend.
 *  - Requests from the loopback address are the app's own WebView and are
 *    always allowed; nothing off-device can forge that.
 */
class Auth(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val random = SecureRandom()

    /** token -> expiry (elapsed millis). */
    private val sessions = ConcurrentHashMap<String, Long>()

    /** source address -> (failures, next allowed attempt). */
    private val attempts = ConcurrentHashMap<String, Attempt>()

    private class Attempt(var failures: Int, var nextAllowedMs: Long)

    // ------------------------------------------------------------- password

    val isConfigured: Boolean
        get() = !prefs.getString(KEY_HASH, null).isNullOrEmpty()

    /**
     * Sets (or replaces) the web password. Every existing session is dropped, so
     * changing the password really does lock out whoever was already in.
     * Blank clears it, which puts the server back to refusing remote access.
     */
    fun setPassword(plain: String) {
        if (plain.isEmpty()) {
            prefs.edit().remove(KEY_HASH).remove(KEY_SALT).apply()
        } else {
            val salt = ByteArray(16).also { random.nextBytes(it) }
            prefs.edit()
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_HASH, Base64.encodeToString(hash(salt, plain), Base64.NO_WRAP))
                .apply()
        }
        sessions.clear()
        attempts.clear()
    }

    /** Constant-time comparison against the stored hash. */
    fun verify(plain: String): Boolean {
        val saltB64 = prefs.getString(KEY_SALT, null) ?: return false
        val hashB64 = prefs.getString(KEY_HASH, null) ?: return false
        val salt = try {
            Base64.decode(saltB64, Base64.NO_WRAP)
        } catch (_: Exception) {
            return false
        }
        val expected = try {
            Base64.decode(hashB64, Base64.NO_WRAP)
        } catch (_: Exception) {
            return false
        }
        return constantTimeEquals(hash(salt, plain), expected)
    }

    private fun hash(salt: ByteArray, plain: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(plain.toByteArray(Charsets.UTF_8))
        // A few thousand rounds costs a couple of milliseconds here and makes an
        // offline dictionary attack on a stolen prefs file meaningfully slower.
        var out = md.digest()
        repeat(4096) {
            val m = MessageDigest.getInstance("SHA-256")
            m.update(salt)
            m.update(out)
            out = m.digest()
        }
        return out
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    // -------------------------------------------------------------- sessions

    fun newSession(): String {
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
            .trimEnd('=')
        sessions[token] = now() + SESSION_TTL_MS
        pruneSessions()
        return token
    }

    fun isValidSession(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val expiry = sessions[token] ?: return false
        if (expiry < now()) {
            sessions.remove(token)
            return false
        }
        // Sliding expiry: an open browser tab should not be logged out mid-use.
        sessions[token] = now() + SESSION_TTL_MS
        return true
    }

    fun dropSession(token: String?) {
        if (token != null) sessions.remove(token)
    }

    private fun pruneSessions() {
        val t = now()
        val it = sessions.entries.iterator()
        while (it.hasNext()) if (it.next().value < t) it.remove()
    }

    // ------------------------------------------------------------ throttling

    /** Milliseconds the caller must wait, or 0 when an attempt is allowed. */
    fun throttleRemainingMs(source: String): Long {
        val a = attempts[source] ?: return 0L
        return (a.nextAllowedMs - now()).coerceAtLeast(0L)
    }

    fun recordFailure(source: String) {
        val a = attempts.getOrPut(source) { Attempt(0, 0L) }
        a.failures++
        // 0, 1, 2, 4, 8 ... seconds, capped at a minute. Slow enough to make
        // guessing pointless, short enough not to punish a real typo.
        val delay = when {
            a.failures <= 2 -> 0L
            else -> (1000L shl (a.failures - 3).coerceAtMost(6)).coerceAtMost(60_000L)
        }
        a.nextAllowedMs = now() + delay
    }

    fun recordSuccess(source: String) {
        attempts.remove(source)
    }

    private fun now() = android.os.SystemClock.elapsedRealtime()

    companion object {
        private const val PREFS = "ipixel_auth"
        private const val KEY_HASH = "hash"
        private const val KEY_SALT = "salt"
        const val COOKIE = "ipxsession"
        private const val SESSION_TTL_MS = 7L * 24 * 60 * 60 * 1000 // a week
    }
}
