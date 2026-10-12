package com.roamwifi.app.data

import android.content.Context
import com.roamwifi.app.core.Quality
import com.roamwifi.app.core.SavedNetwork
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists saved networks (passwords encrypted via [CryptoBox]) and the last known quality per SSID.
 * Every read is defensive: a corrupt file yields an empty list instead of a crash.
 */
class NetworkStore(context: Context, private val crypto: CryptoBox = CryptoBox()) {

    private val prefs = context.applicationContext.getSharedPreferences("roamwifi_store", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<SavedNetwork> = try {
        val arr = JSONArray(prefs.getString(KEY_NETWORKS, "[]"))
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ssid = o.getString("ssid")
                val enc = o.optString("pw", "")
                if (enc.isEmpty()) {
                    add(SavedNetwork(ssid, null))
                } else {
                    // If the key is gone we cannot recover the password; skip so the user can re-add it.
                    crypto.decrypt(enc)?.let { add(SavedNetwork(ssid, it)) }
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun save(network: SavedNetwork) {
        val all = load().filterNot { it.ssid == network.ssid } + network
        write(all)
    }

    @Synchronized
    fun remove(ssid: String) {
        write(load().filterNot { it.ssid == ssid })
        prefs.edit().remove(KEY_QUALITY_PREFIX + ssid).apply()
    }

    private fun write(list: List<SavedNetwork>) {
        val arr = JSONArray()
        for (n in list) {
            arr.put(
                JSONObject()
                    .put("ssid", n.ssid)
                    .put("pw", n.password?.let { crypto.encrypt(it) } ?: ""),
            )
        }
        prefs.edit().putString(KEY_NETWORKS, arr.toString()).apply()
    }

    @Synchronized
    fun saveQuality(ssid: String, q: Quality) {
        val o = JSONObject()
            .put("w", q.working)
            .put("l", q.latencyMs ?: -1)
            .put("k", q.kbps ?: -1)
            .put("t", q.atMs)
        prefs.edit().putString(KEY_QUALITY_PREFIX + ssid, o.toString()).apply()
    }

    /**
     * Quality timestamps use elapsedRealtime, which resets on reboot, so a stored value from a previous boot
     * would look "in the future". Callers pass the current elapsedRealtime and we drop anything newer than that.
     */
    @Synchronized
    fun loadQuality(ssid: String, nowMs: Long): Quality? = try {
        val s = prefs.getString(KEY_QUALITY_PREFIX + ssid, null) ?: return null
        val o = JSONObject(s)
        val at = o.getLong("t")
        if (at > nowMs) {
            null
        } else {
            Quality(
                working = o.getBoolean("w"),
                latencyMs = o.getInt("l").takeIf { it >= 0 },
                kbps = o.getInt("k").takeIf { it >= 0 },
                atMs = at,
            )
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val KEY_NETWORKS = "networks"
        const val KEY_QUALITY_PREFIX = "q_"
    }
}
