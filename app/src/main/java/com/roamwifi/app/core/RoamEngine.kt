package com.roamwifi.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.roamwifi.app.data.NetworkStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The roaming loop. Every tick it: reads scans, updates per-network trends, keeps the current network's
 * internet quality fresh, and asks [Scoring.decide] whether to hand over to a better saved network.
 * It runs only while the service is alive, so closing the app stops all scanning.
 */
class RoamEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: NetworkStore,
    private val scanner: WifiScanner,
    private val connector: WifiConnector,
    private val prober: InternetProber,
) {
    private val tracks = HashMap<String, SignalTrack>()
    private val quality = HashMap<String, Quality>()
    private val failedUntil = HashMap<String, Long>()
    private val securityBySsid = HashMap<String, Security>()
    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var job: Job? = null
    private var saved: List<SavedNetwork> = emptyList()
    private var seenSavedVersion = -1

    private var lastSwitchMs = Long.MIN_VALUE / 2
    private var nextProbeAtMs = 0L
    private var lastDeepProbeMs = Long.MIN_VALUE / 2
    private var probedSsid: String? = null
    private var probing = false
    private var consecutiveProbeFails = 0
    private var stableSinceMs = 0L
    private var lastLoggedSsid: String? = null
    private var switching = false

    fun start() {
        if (job?.isActive == true) return
        log("Started")
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        EngineBus.state.value = EngineState(running = false, status = "Stopped", log = logLines.toList())
    }

    // ------------------------------------------------------------------ loop

    private suspend fun loop() {
        var errors = 0
        while (scope.isActive) {
            var wait = 3_000L
            try {
                wait = tick()
                errors = 0
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                errors++
                log("Error: ${t.javaClass.simpleName} ${t.message ?: ""}".trim())
                wait = (2_000L * errors).coerceAtMost(30_000L) // back off, never spin on a persistent failure
            }
            delay(wait)
        }
    }

    /** One iteration; returns how long to sleep before the next one. */
    private suspend fun tick(): Long {
        val now = SystemClock.elapsedRealtime()

        val issue = preconditionIssue()
        if (issue != null) {
            publish(null, emptyList(), "Waiting", issue)
            return 3_000L
        }

        reloadSavedIfNeeded()
        scanner.requestScan()

        val scan = Scoring.bestPerSsid(scanner.results().filter { now - it.timestampMs <= Tuning.STALE_MS })
        val link = scanner.currentLink()

        for (e in scan.values) {
            securityBySsid[e.ssid] = e.security
            tracks.getOrPut(e.ssid) { SignalTrack() }.add(e.timestampMs, e.rssi)
        }
        if (link != null) tracks.getOrPut(link.ssid) { SignalTrack() }.add(now, link.rssi)

        connector.sync(saved) { securityBySsid[it] }

        if (link?.ssid != lastLoggedSsid) {
            lastLoggedSsid = link?.ssid
            log(if (link != null) "Connected to ${link.ssid}" else "Not connected")
        }

        maybeProbe(link, now)

        val candidates = buildCandidates(scan, link, now)
        val current = candidates.firstOrNull { it.connected }

        if (current == null || kotlin.math.abs(current.slope) > 0.3) stableSinceMs = now
        if (stableSinceMs == 0L) stableSinceMs = now

        val forced = EngineBus.forceConnect?.also { EngineBus.forceConnect = null }
        val decision = when {
            switching -> Decision.Stay
            forced != null && forced != link?.ssid && saved.any { it.ssid == forced } ->
                Decision.Switch(forced, "requested")
            else -> Scoring.decide(current, candidates, now, lastSwitchMs, failedUntil)
        }

        var status = when {
            link != null -> "Connected to ${link.ssid}"
            else -> "Searching for a saved network"
        }
        if (decision is Decision.Switch) {
            status = "Switching to ${decision.target}"
            publish(link, candidates, status, null)
            doSwitch(decision, link, scan)
        } else {
            publish(link, candidates, status, null)
        }

        return Scoring.nextDelayMs(current?.smoothed, current?.slope ?: 0.0, now - stableSinceMs)
    }

    // ------------------------------------------------------------------ switching

    private suspend fun doSwitch(d: Decision.Switch, link: Link?, scan: Map<String, ScanEntry>) {
        val target = saved.firstOrNull { it.ssid == d.target } ?: return
        val security = scan[target.ssid]?.security ?: securityBySsid[target.ssid] ?: Security.WPA2
        val leaving = link?.ssid
        val leavingNet = leaving?.let { l -> saved.firstOrNull { it.ssid == l } }

        switching = true
        try {
            log("Switching to ${target.ssid}: ${d.reason}")
            val outcome = connector.connect(target, security, leaving)
            val now = SystemClock.elapsedRealtime()
            if (outcome.ok) {
                lastSwitchMs = now
                consecutiveProbeFails = 0
                probedSsid = null
                nextProbeAtMs = now + 2_500L // verify the new network's internet almost immediately
                log("Now on ${target.ssid}")
            } else {
                failedUntil[target.ssid] = now + Tuning.FAIL_COOLDOWN_MS
                log("Could not join ${target.ssid}: ${outcome.note}")
            }
        } finally {
            // Whatever happened, give the network we left back to the OS so it can still auto-join it.
            if (leavingNet != null) {
                connector.restore(leavingNet, securityBySsid[leavingNet.ssid] ?: Security.WPA2)
            }
            switching = false
        }
    }

    // ------------------------------------------------------------------ probing

    private fun maybeProbe(link: Link?, now: Long) {
        if (link == null || probing || now < nextProbeAtMs) return
        val ssid = link.ssid
        val deep = probedSsid != ssid || now - lastDeepProbeMs >= DEEP_PROBE_EVERY_MS
        probing = true
        nextProbeAtMs = now + LIGHT_PROBE_EVERY_MS
        scope.launch {
            try {
                val q = prober.probe(deep)
                if (scanner.currentLink()?.ssid != ssid) return@launch // moved on while probing: discard
                applyProbe(ssid, q, deep)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A failed probe must never take the loop down.
            } finally {
                probing = false
            }
        }
    }

    private fun applyProbe(ssid: String, q: Quality, deep: Boolean) {
        val now = SystemClock.elapsedRealtime()
        probedSsid = ssid
        if (deep) lastDeepProbeMs = now
        val prior = quality[ssid]?.takeIf { it.working }
        if (q.working) {
            consecutiveProbeFails = 0
            val merged = q.copy(kbps = q.kbps ?: prior?.kbps)
            quality[ssid] = merged
            store.saveQuality(ssid, merged)
        } else {
            consecutiveProbeFails++
            if (consecutiveProbeFails >= 2) {
                // Two misses in a row: declare it dead so the engine leaves and does not come back for a while.
                quality[ssid] = q
                store.saveQuality(ssid, q)
                log("$ssid has no internet")
            } else {
                nextProbeAtMs = now + 3_000L // quick re-check before judging
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun buildCandidates(scan: Map<String, ScanEntry>, link: Link?, now: Long): List<Candidate> {
        val ssids = LinkedHashSet<String>()
        link?.let { ssids += it.ssid }
        ssids += scan.keys
        val savedNames = saved.map { it.ssid }.toSet()

        return ssids.mapNotNull { ssid ->
            val track = tracks[ssid] ?: return@mapNotNull null
            val smoothed = track.ema
            if (smoothed.isNaN()) return@mapNotNull null
            val slope = track.slope()
            val q = quality[ssid] ?: store.loadQuality(ssid, now)?.also { quality[ssid] = it }
            val rssi = link?.takeIf { it.ssid == ssid }?.rssi ?: scan[ssid]?.rssi ?: smoothed.toInt()
            Candidate(
                ssid = ssid,
                rssi = rssi,
                smoothed = smoothed,
                slope = slope,
                quality = q,
                score = Scoring.score(smoothed, slope, q, now),
                saved = ssid in savedNames,
                connected = ssid == link?.ssid,
                security = scan[ssid]?.security ?: securityBySsid[ssid] ?: Security.WPA2,
            )
        }.sortedByDescending { it.smoothed }
    }

    private fun preconditionIssue(): String? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return "Location permission is needed so Android lets the app read Wi-Fi signals."
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) return "Turn on Location (Android requires it for Wi-Fi scanning)."
        if (!scanner.isWifiEnabled()) return "Wi-Fi is turned off."
        return null
    }

    private fun reloadSavedIfNeeded() {
        val v = EngineBus.savedVersion.value
        if (v == seenSavedVersion) return
        seenSavedVersion = v
        saved = store.load()
    }

    private fun publish(link: Link?, candidates: List<Candidate>, status: String, issue: String?) {
        val ssid = link?.ssid
        EngineBus.state.value = EngineState(
            running = true,
            currentSsid = ssid,
            currentRssi = link?.rssi,
            currentQuality = ssid?.let { quality[it] },
            candidates = candidates,
            status = status,
            issue = issue,
            log = logLines.toList(),
        )
    }

    private fun log(msg: String) {
        logLines.addFirst("${timeFmt.format(Date())}  $msg")
        while (logLines.size > MAX_LOG) logLines.removeLast()
    }

    private companion object {
        const val LIGHT_PROBE_EVERY_MS = 20_000L
        const val DEEP_PROBE_EVERY_MS = 5 * 60_000L
        const val MAX_LOG = 40
    }
}
