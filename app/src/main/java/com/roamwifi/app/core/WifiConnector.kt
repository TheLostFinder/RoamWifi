package com.roamwifi.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay

data class ConnectOutcome(val ok: Boolean, val note: String)

/**
 * Moves the phone onto a chosen saved network.
 *
 * Android 10+ no longer lets apps force a connection, so on those versions this uses Network Suggestions:
 * the app tells the OS "prefer this network" (priority on Android 12+) and the OS performs the actual switch,
 * which is also what makes the hand-over fast. Android 8-9 use the classic enableNetwork() path.
 */
class WifiConnector(context: Context, private val scanner: WifiScanner) {

    private val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val suggestions = HashMap<String, WifiNetworkSuggestion>()
    private var priorityCounter = 1_000

    suspend fun connect(target: SavedNetwork, security: Security, leaving: String?): ConnectOutcome {
        if (security != Security.OPEN && target.password.isNullOrEmpty()) {
            return ConnectOutcome(false, "no password saved for ${target.ssid}")
        }
        val submitted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                submitModern(target, security, leaving)
            } else {
                submitLegacy(target, security)
            }
        } catch (e: SecurityException) {
            ConnectOutcome(false, "permission denied: ${e.message}")
        } catch (e: Exception) {
            ConnectOutcome(false, "error: ${e.message}")
        }
        if (!submitted.ok) return submitted

        val deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (scanner.currentLink()?.ssid == target.ssid) return ConnectOutcome(true, "connected")
            delay(400)
        }
        return ConnectOutcome(false, "timed out waiting for ${target.ssid}")
    }

    /** Makes sure every saved network is known to the OS so it can also auto-join on its own. */
    fun sync(saved: List<SavedNetwork>, securityOf: (String) -> Security?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val names = saved.map { it.ssid }.toSet()
        for (gone in suggestions.keys.toList()) {
            if (gone !in names) forget(gone)
        }
        for (n in saved) {
            if (n.ssid in suggestions) continue
            val sec = securityOf(n.ssid) ?: continue // only add once we have seen it and know its security type
            if (sec != Security.OPEN && n.password.isNullOrEmpty()) continue
            try { add(n, sec, 0) } catch (_: Exception) {}
        }
    }

    /** Re-adds a network at the lowest priority (used after we temporarily removed it to force a hand-over). */
    fun restore(net: SavedNetwork, security: Security) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || net.ssid in suggestions) return
        try { add(net, security, 0) } catch (_: Exception) {}
    }

    fun forget(ssid: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        suggestions.remove(ssid)?.let {
            try { wm.removeNetworkSuggestions(listOf(it)) } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- modern (Android 10+)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun submitModern(target: SavedNetwork, security: Security, leaving: String?): ConnectOutcome {
        // Removing the suggestion of the network we are on makes the OS drop it and pick the next best one.
        if (leaving != null && leaving != target.ssid) forget(leaving)
        forget(target.ssid)

        val status = add(target, security, ++priorityCounter)
        return when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> ConnectOutcome(true, "suggested")
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
                ConnectOutcome(false, "Android is blocking suggestions: allow RoamWifi to suggest networks when prompted")
            else -> ConnectOutcome(false, "suggestion rejected (code $status)")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun add(net: SavedNetwork, security: Security, priority: Int): Int {
        val b = WifiNetworkSuggestion.Builder()
            .setSsid(net.ssid)
            .setIsAppInteractionRequired(false)
            .setIsUserInteractionRequired(false)
        when (security) {
            Security.WPA3 -> b.setWpa3Passphrase(net.password.orEmpty())
            Security.WPA2 -> b.setWpa2Passphrase(net.password.orEmpty())
            Security.OPEN -> Unit
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setPriority(priority)
            b.setIsInitialAutojoinEnabled(true)
        }
        val s = b.build()
        val status = wm.addNetworkSuggestions(listOf(s))
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) suggestions[net.ssid] = s
        return status
    }

    // ---------------------------------------------------------------- legacy (Android 8-9)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun submitLegacy(target: SavedNetwork, security: Security): ConnectOutcome {
        val conf = WifiConfiguration().apply {
            SSID = "\"${target.ssid}\""
            if (security == Security.OPEN) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"${target.password}\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
        }
        var id = wm.addNetwork(conf)
        if (id == -1) {
            id = wm.configuredNetworks?.firstOrNull { it.SSID == "\"${target.ssid}\"" }?.networkId ?: -1
        }
        if (id == -1) return ConnectOutcome(false, "could not add ${target.ssid}")
        val ok = wm.enableNetwork(id, true)
        wm.reconnect()
        return if (ok) ConnectOutcome(true, "enabled") else ConnectOutcome(false, "enableNetwork failed")
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
