package com.abdllh.aura.link

import android.util.Log
import com.abdllh.aura.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Who may do what on [PhoneServer] (AMRI Link v2). A phone is paired once: the unit shows a code (a QR of the address
 * with it, and the six digits), single use, valid [CODE_MS] and only while it is shown; the phone trades it for a token.
 * Only the token's hash is kept here, and a token is never written to a log. The code gives [MAX_TRIES] tries: six
 * digits are a pairing aid against mistakes, not a secret that could stand a search.
 *
 * What a token is worth, honestly: adbd on port 5555 gives root to anything on the hotspot (docs/DEV.md). The token
 * stops accidents and other phones, not a determined person who has the hotspot's password.
 */
internal object Pairing {
    private const val TAG = "AuraPairing"
    private const val KEY_TOKENS = "phoneTokens"
    private const val KEY_UNIT = "phoneUnitId"
    const val CODE_MS = 5 * 60_000L
    private const val MAX_TRIES = 5
    private const val MAX_DEVICES = 4

    private val random by lazy { SecureRandom() }
    private var code: String? = null
    private var codeUntil = 0L
    private var tries = 0

    /** This unit's name on the link ("k2501-ab12"): stays the same, says nothing about anybody. */
    val unit: String
        @Synchronized get() {
            Prefs.raw.getString(KEY_UNIT, null)?.let { return it }
            val id = String.format(Locale.ROOT, "k2501-%04x", random.nextInt(0x10000))
            Prefs.raw.edit().putString(KEY_UNIT, id).apply()
            return id
        }

    // ------------------------------------------------------------------------------------------ the code
    /** The code to show now: the one that is showing, or a new one. */
    @Synchronized
    fun show(now: Long): String {
        val c = code
        if (c != null && now < codeUntil && tries < MAX_TRIES) return c
        val fresh = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))
        code = fresh
        codeUntil = now + CODE_MS
        tries = 0
        return fresh
    }

    /** The screen with the code has gone: it is good for nothing now. */
    @Synchronized
    fun hide() { code = null }

    /** How long the code showing has left (ms), 0 when none is. */
    @Synchronized
    fun left(now: Long): Long = if (code == null) 0L else (codeUntil - now).coerceAtLeast(0L)

    @Synchronized
    fun showing(now: Long): Boolean = code != null && now < codeUntil && tries < MAX_TRIES

    class Redeemed(val token: String?, val triesLeft: Int)

    /** The code for a token. A wrong one counts; the last wrong one ends it (the screen then shows a new one). */
    @Synchronized
    fun redeem(given: String, device: String, now: Long): Redeemed {
        val c = code
        if (c == null || now >= codeUntil || tries >= MAX_TRIES) return Redeemed(null, 0)
        if (!MessageDigest.isEqual(c.toByteArray(), given.toByteArray())) {
            tries++
            return Redeemed(null, MAX_TRIES - tries)
        }
        code = null
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val token = hex(bytes)
        remember(token, device, System.currentTimeMillis())
        return Redeemed(token, MAX_TRIES)
    }

    // ------------------------------------------------------------------------------------------ the tokens
    @Synchronized
    private fun remember(token: String, device: String, at: Long) {
        val old = list()
        val keep = JSONArray()
        // (the oldest goes when the list is full; the same phone paired again leaves its old token behind until then)
        for (o in old.takeLast(MAX_DEVICES - 1)) keep.put(o)
        keep.put(JSONObject().put("h", hash(token)).put("d", device).put("at", at))
        Prefs.raw.edit().putString(KEY_TOKENS, keep.toString()).apply()
        Log.i(TAG, "paired a device (${keep.length()} now)")
    }

    private fun list(): List<JSONObject> = try {
        val a = JSONArray(Prefs.raw.getString(KEY_TOKENS, "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    } catch (_: Throwable) {
        emptyList()
    }

    /** Is this a token given out here? */
    fun known(token: String?): Boolean {
        if (token.isNullOrEmpty() || token.length > 128) return false
        val h = hash(token).toByteArray()
        return synchronized(this) { list() }.any { MessageDigest.isEqual(it.optString("h").toByteArray(), h) }
    }

    /** The paired phones' names, oldest first. */
    @Synchronized
    fun devices(): List<String> = list().map { it.optString("d") }

    @Synchronized
    fun forgetAll() { Prefs.raw.edit().remove(KEY_TOKENS).apply() }

    private fun hash(s: String) = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

    private fun hex(b: ByteArray) = buildString(b.size * 2) { for (x in b) append(String.format(Locale.ROOT, "%02x", x)) }
}
