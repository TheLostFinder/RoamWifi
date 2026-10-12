package com.roamwifi.app.core

import kotlin.math.abs

/** All thresholds in one place so behaviour can be tuned without touching the logic. */
object Tuning {
    const val EMA_ALPHA = 0.35
    const val WINDOW = 8

    /** How far ahead (seconds) the trend is projected when scoring. This is the "direction of movement" part. */
    const val LOOKAHEAD_S = 6.0
    const val MAX_TREND_BONUS = 12.0

    /** A candidate must beat the current network by this many points (hysteresis against flapping). */
    const val SWITCH_MARGIN = 9.0
    const val SWITCH_MARGIN_WEAK = 4.0

    /** Below this smoothed dBm the current network is considered weak. */
    const val WEAK_DBM = -74.0

    /** Never roam onto something weaker than this. */
    const val MIN_CANDIDATE_DBM = -78.0

    const val MIN_DWELL_MS = 20_000L
    const val BROKEN_DWELL_MS = 8_000L
    const val FAIL_COOLDOWN_MS = 120_000L

    /** A scan entry older than this is treated as "out of range". */
    const val STALE_MS = 45_000L

    /** How long a "no internet" verdict is trusted before the network may be retried. */
    const val BROKEN_TTL_MS = 10 * 60_000L
}

/** Rolling RSSI history for one SSID: smoothed level plus slope (dB per second). */
class SignalTrack {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Int>()

    var ema: Double = Double.NaN
        private set

    var lastSeenMs: Long = Long.MIN_VALUE
        private set

    /** Adds a sample. Samples that are not newer than the last one are ignored (scan results repeat). */
    fun add(timeMs: Long, rssi: Int) {
        if (timeMs <= lastSeenMs) return
        lastSeenMs = timeMs
        ema = if (ema.isNaN()) rssi.toDouble() else Tuning.EMA_ALPHA * rssi + (1 - Tuning.EMA_ALPHA) * ema
        times.addLast(timeMs)
        values.addLast(rssi)
        while (times.size > Tuning.WINDOW) {
            times.removeFirst()
            values.removeFirst()
        }
    }

    /** Least-squares slope in dB/s; 0 when there is not enough data to say anything. */
    fun slope(): Double {
        val n = times.size
        if (n < 3) return 0.0
        val spanMs = times.last() - times.first()
        if (spanMs < 3_000) return 0.0
        val t0 = times.first()
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val x = (times[i] - t0) / 1000.0
            val y = values[i].toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-9) return 0.0
        return (n * sxy - sx * sy) / denom
    }
}

object Scoring {
    fun trendBonus(slope: Double): Double =
        (slope * Tuning.LOOKAHEAD_S).coerceIn(-Tuning.MAX_TREND_BONUS, Tuning.MAX_TREND_BONUS)

    /**
     * Quality contributes 0..18 points when the network works, a large penalty when it is known broken,
     * and nothing when it was never measured (unknown is neutral, not bad).
     */
    fun qualityBonus(q: Quality?, nowMs: Long): Double {
        if (q == null) return 0.0
        if (!q.working) {
            return if (nowMs - q.atMs < Tuning.BROKEN_TTL_MS) -100.0 else -10.0
        }
        val latency = q.latencyMs?.let { (8.0 - it / 50.0).coerceIn(0.0, 8.0) } ?: 0.0
        val speed = q.kbps?.let { (it / 1000.0).coerceIn(0.0, 10.0) } ?: 0.0
        return latency + speed
    }

    fun score(smoothed: Double, slope: Double, quality: Quality?, nowMs: Long): Double =
        smoothed + trendBonus(slope) + qualityBonus(quality, nowMs)

    fun isBroken(q: Quality?, nowMs: Long): Boolean =
        q != null && !q.working && nowMs - q.atMs < Tuning.BROKEN_TTL_MS

    /**
     * Decides whether to leave the current network. Pure function, no Android types, fully unit-tested.
     *
     * @param failedUntil per-SSID timestamp until which a previously failed connect is not retried
     */
    fun decide(
        current: Candidate?,
        candidates: List<Candidate>,
        nowMs: Long,
        lastSwitchMs: Long,
        failedUntil: Map<String, Long>,
    ): Decision {
        val eligible = candidates.filter {
            it.saved &&
                !it.connected &&
                it.smoothed >= Tuning.MIN_CANDIDATE_DBM &&
                (failedUntil[it.ssid] ?: 0L) <= nowMs &&
                !isBroken(it.quality, nowMs)
        }
        val best = eligible.maxByOrNull { it.score } ?: return Decision.Stay

        // Not connected to anything: take the best saved network right away.
        if (current == null) return Decision.Switch(best.ssid, "not connected")

        val sinceSwitch = nowMs - lastSwitchMs
        val currentBroken = current.quality != null && !current.quality.working

        // Connected but no internet: leave quickly, there is little to lose.
        if (currentBroken && sinceSwitch >= Tuning.BROKEN_DWELL_MS) {
            return Decision.Switch(best.ssid, "${current.ssid} has no internet")
        }

        if (sinceSwitch < Tuning.MIN_DWELL_MS) return Decision.Stay

        val margin = if (current.smoothed <= Tuning.WEAK_DBM) Tuning.SWITCH_MARGIN_WEAK else Tuning.SWITCH_MARGIN
        val gain = best.score - current.score
        return if (gain >= margin) {
            Decision.Switch(best.ssid, "stronger ahead (+${gain.toInt()})")
        } else {
            Decision.Stay
        }
    }

    /** Adaptive scan cadence: scan fast while things are changing, slow when sitting still on a good link. */
    fun nextDelayMs(currentSmoothed: Double?, currentSlope: Double, stableForMs: Long): Long {
        if (currentSmoothed == null) return 2_500L
        val moving = abs(currentSlope) > 0.3
        val weak = currentSmoothed <= Tuning.WEAK_DBM
        return when {
            moving || weak -> 2_500L
            stableForMs > 60_000L && currentSmoothed > -60.0 -> 12_000L
            else -> 6_000L
        }
    }

    fun bestPerSsid(entries: List<ScanEntry>): Map<String, ScanEntry> {
        val out = HashMap<String, ScanEntry>()
        for (e in entries) {
            val prev = out[e.ssid]
            if (prev == null || e.rssi > prev.rssi) out[e.ssid] = e
        }
        return out
    }
}
