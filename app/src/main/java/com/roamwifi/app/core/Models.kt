package com.roamwifi.app.core

enum class Security {
    OPEN, WPA2, WPA3;

    companion object {
        /** Parses ScanResult.capabilities. WPA2/WPA3 transition networks list both PSK and SAE: treat as WPA2. */
        fun fromCapabilities(capabilities: String): Security {
            val c = capabilities.uppercase()
            return when {
                c.contains("SAE") && !c.contains("PSK") -> WPA3
                c.contains("PSK") || c.contains("WPA") || c.contains("WEP") -> WPA2
                else -> OPEN
            }
        }
    }
}

/** A network the user saved. The password lives only in memory here; on disk it is Keystore-encrypted. */
data class SavedNetwork(val ssid: String, val password: String?)

/** Strongest reading for one SSID from a single scan (many access points can share an SSID). */
data class ScanEntry(
    val ssid: String,
    val rssi: Int,
    val security: Security,
    /** Scan timestamp in ms on the SystemClock.elapsedRealtime() timeline. */
    val timestampMs: Long,
)

/** Result of an internet-quality probe on a connected network. */
data class Quality(
    val working: Boolean,
    val latencyMs: Int?,
    val kbps: Int?,
    val atMs: Long,
)

data class Candidate(
    val ssid: String,
    val rssi: Int,
    val smoothed: Double,
    val slope: Double,
    val quality: Quality?,
    val score: Double,
    val saved: Boolean,
    val connected: Boolean,
    val security: Security,
)

data class EngineState(
    val running: Boolean = false,
    val currentSsid: String? = null,
    val currentRssi: Int? = null,
    val currentQuality: Quality? = null,
    val candidates: List<Candidate> = emptyList(),
    val status: String = "Stopped",
    /** Something the user must fix (permission, location off, Wi-Fi off). */
    val issue: String? = null,
    val log: List<String> = emptyList(),
)

sealed interface Decision {
    data object Stay : Decision
    data class Switch(val target: String, val reason: String) : Decision
}
