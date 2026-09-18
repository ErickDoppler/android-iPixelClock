package com.example.ipixelclock.settings

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the one live [Settings] object and persists it.
 *
 * Writes are atomic: a patch produces a whole new immutable [Settings], which is
 * published to every listener and written to disk as a single JSON string. That
 * matters because three things mutate settings concurrently — the in-app
 * WebView, a remote browser, and the service's own auto-brightness — and a
 * half-applied change would be visible on the panel.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val listeners = CopyOnWriteArrayList<(Settings) -> Unit>()

    @Volatile
    var current: Settings = load()
        private set

    /** Called on the caller's thread whenever settings change. */
    fun addListener(l: (Settings) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (Settings) -> Unit) {
        listeners.remove(l)
    }

    /** Applies a JSON patch and persists. Returns the new settings. */
    @Synchronized
    fun patch(json: JSONObject): Settings {
        val next = current.patched(json)
        if (next == current) return current
        current = next
        persist(next)
        notifyAll(next)
        return next
    }

    /** Applies a typed change, for the service's own updates. */
    @Synchronized
    fun update(block: (Settings) -> Settings): Settings {
        val next = block(current)
        if (next == current) return current
        current = next
        persist(next)
        notifyAll(next)
        return next
    }

    @Synchronized
    fun reset(): Settings {
        current = Settings()
        persist(current)
        notifyAll(current)
        return current
    }

    private fun notifyAll(s: Settings) {
        for (l in listeners) {
            try {
                l(s)
            } catch (e: Exception) {
                Log.w(TAG, "settings listener threw", e)
            }
        }
    }

    private fun persist(s: Settings) {
        try {
            prefs.edit().putString(KEY, s.toJson().toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "could not persist settings", e)
        }
    }

    private fun load(): Settings {
        val raw = prefs.getString(KEY, null) ?: return Settings()
        return try {
            Settings.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            // A corrupt blob must not brick the clock: fall back to defaults
            // and let the next write replace it.
            Log.w(TAG, "settings unreadable, using defaults", e)
            Settings()
        }
    }

    companion object {
        private const val PREFS = "ipixel_clock"
        private const val KEY = "settings"
        private const val TAG = "SettingsStore"
    }
}
