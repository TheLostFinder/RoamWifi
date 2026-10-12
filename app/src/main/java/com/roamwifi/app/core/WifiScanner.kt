package com.roamwifi.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.SystemClock

data class Link(val ssid: String, val rssi: Int)

/** Thin, crash-proof wrapper around WifiManager scanning and connection info. */
class WifiScanner(context: Context) {

    private val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lastScanRequestMs = 0L

    fun isWifiEnabled(): Boolean = try { wm.isWifiEnabled } catch (_: Exception) { true }

    /**
     * Asks the OS for a fresh scan. Android throttles this (about 4 per 2 minutes in the foreground),
     * so a refusal is normal: we simply keep using the newest cached results.
     */
    @Suppress("DEPRECATION")
    fun requestScan() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanRequestMs < MIN_SCAN_GAP_MS) return
        lastScanRequestMs = now
        try {
            wm.startScan()
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission") // SecurityException is handled; the engine also checks the permission up front
    @Suppress("DEPRECATION")
    fun results(): List<ScanEntry> {
        val raw: List<ScanResult> = try { wm.scanResults ?: emptyList() } catch (_: Exception) { emptyList() }
        val out = ArrayList<ScanEntry>(raw.size)
        for (r in raw) {
            val ssid = r.SSID?.trim().orEmpty()
            if (ssid.isEmpty()) continue // hidden networks cannot be matched by name
            out += ScanEntry(
                ssid = ssid,
                rssi = r.level,
                security = Security.fromCapabilities(r.capabilities.orEmpty()),
                timestampMs = r.timestamp / 1000L, // microseconds since boot -> ms on the elapsedRealtime timeline
            )
        }
        return out
    }

    /** Current Wi-Fi link, or null if not associated (or the SSID is hidden from us by missing permissions). */
    @Suppress("DEPRECATION")
    fun currentLink(): Link? = try {
        val info = wm.connectionInfo
        if (info == null || info.supplicantState != SupplicantState.COMPLETED) {
            null
        } else {
            val ssid = info.ssid?.trim('"').orEmpty()
            if (ssid.isEmpty() || ssid == "<unknown ssid>") null else Link(ssid, info.rssi)
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MIN_SCAN_GAP_MS = 4_000L
    }
}
