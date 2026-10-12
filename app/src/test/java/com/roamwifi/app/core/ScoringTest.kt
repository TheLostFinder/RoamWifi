package com.roamwifi.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {

    private val now = 1_000_000L
    private val longAgo = 0L

    private fun cand(
        ssid: String,
        smoothed: Double,
        slope: Double = 0.0,
        quality: Quality? = null,
        saved: Boolean = true,
        connected: Boolean = false,
    ) = Candidate(
        ssid = ssid,
        rssi = smoothed.toInt(),
        smoothed = smoothed,
        slope = slope,
        quality = quality,
        score = Scoring.score(smoothed, slope, quality, now),
        saved = saved,
        connected = connected,
        security = Security.WPA2,
    )

    private fun good() = Quality(true, 30, 8_000, now)
    private fun dead() = Quality(false, null, null, now)

    // ------------------------------------------------------------ SignalTrack

    @Test
    fun slopeIsPositiveWhenApproaching() {
        val t = SignalTrack()
        for (i in 0 until 6) t.add(i * 2_000L + 1, -80 + i * 4)
        assertTrue(t.slope() > 1.5)
    }

    @Test
    fun slopeIsNegativeWhenLeaving() {
        val t = SignalTrack()
        for (i in 0 until 6) t.add(i * 2_000L + 1, -50 - i * 4)
        assertTrue(t.slope() < -1.5)
    }

    @Test
    fun slopeIsZeroWithTooFewSamples() {
        val t = SignalTrack()
        t.add(1_000, -60)
        t.add(5_000, -50)
        assertEquals(0.0, t.slope(), 0.0)
    }

    @Test
    fun duplicateAndOlderSamplesAreIgnored() {
        val t = SignalTrack()
        t.add(5_000, -60)
        t.add(5_000, -10)
        t.add(4_000, -10)
        assertEquals(-60.0, t.ema, 0.0001)
    }

    // ------------------------------------------------------------ decide

    @Test
    fun staysWhenCurrentIsStrongAndNothingBeatsIt() {
        val cur = cand("A", -50.0, quality = good(), connected = true)
        val other = cand("B", -55.0, quality = good())
        assertEquals(Decision.Stay, Scoring.decide(cur, listOf(cur, other), now, longAgo, emptyMap()))
    }

    @Test
    fun switchesToClearlyStrongerNetwork() {
        val cur = cand("A", -78.0, quality = good(), connected = true)
        val other = cand("B", -50.0, quality = good())
        val d = Scoring.decide(cur, listOf(cur, other), now, longAgo, emptyMap())
        assertTrue(d is Decision.Switch && d.target == "B")
    }

    @Test
    fun movingTowardsANetworkLetsItWinEarlier() {
        val cur = cand("A", -66.0, slope = -1.5, quality = good(), connected = true)
        val ahead = cand("B", -66.0, slope = 1.5, quality = good())
        val d = Scoring.decide(cur, listOf(cur, ahead), now, longAgo, emptyMap())
        assertTrue(d is Decision.Switch && d.target == "B")
    }

    @Test
    fun hysteresisPreventsFlappingBetweenSimilarNetworks() {
        val cur = cand("A", -60.0, quality = good(), connected = true)
        val other = cand("B", -56.0, quality = good())
        assertEquals(Decision.Stay, Scoring.decide(cur, listOf(cur, other), now, longAgo, emptyMap()))
    }

    @Test
    fun respectsMinimumDwellAfterASwitch() {
        val cur = cand("A", -80.0, quality = good(), connected = true)
        val other = cand("B", -45.0, quality = good())
        val justSwitched = now - 5_000
        assertEquals(Decision.Stay, Scoring.decide(cur, listOf(cur, other), now, justSwitched, emptyMap()))
    }

    @Test
    fun neverSwitchesToANetworkWithoutInternet() {
        val cur = cand("A", -80.0, quality = good(), connected = true)
        val broken = cand("B", -40.0, quality = dead())
        assertEquals(Decision.Stay, Scoring.decide(cur, listOf(cur, broken), now, longAgo, emptyMap()))
    }

    @Test
    fun leavesCurrentNetworkQuicklyWhenItHasNoInternet() {
        val cur = cand("A", -45.0, quality = dead(), connected = true)
        val other = cand("B", -65.0, quality = good())
        val d = Scoring.decide(cur, listOf(cur, other), now, now - 9_000, emptyMap())
        assertTrue(d is Decision.Switch && d.target == "B")
    }

    @Test
    fun ignoresUnsavedAndTooWeakAndCoolingDownNetworks() {
        val cur = cand("A", -78.0, quality = good(), connected = true)
        val unsaved = cand("U", -40.0, saved = false)
        val tooWeak = cand("W", -85.0, quality = good())
        val failed = cand("F", -45.0, quality = good())
        val d = Scoring.decide(cur, listOf(cur, unsaved, tooWeak, failed), now, longAgo, mapOf("F" to now + 60_000))
        assertEquals(Decision.Stay, d)
    }

    @Test
    fun picksBestSavedNetworkImmediatelyWhenDisconnected() {
        val a = cand("A", -70.0, quality = good())
        val b = cand("B", -55.0, quality = good())
        val d = Scoring.decide(null, listOf(a, b), now, now, emptyMap())
        assertTrue(d is Decision.Switch && d.target == "B")
    }

    @Test
    fun unknownQualityIsNeutralNotPenalised() {
        assertEquals(0.0, Scoring.qualityBonus(null, now), 0.0)
        assertTrue(Scoring.qualityBonus(good(), now) > 0)
    }

    @Test
    fun brokenVerdictExpires() {
        val old = Quality(false, null, null, 0L)
        assertTrue(Scoring.isBroken(old, 1_000L))
        assertTrue(!Scoring.isBroken(old, Tuning.BROKEN_TTL_MS + 1))
    }

    @Test
    fun scansSlowDownWhenStillAndSpeedUpWhenMoving() {
        assertEquals(12_000L, Scoring.nextDelayMs(-50.0, 0.0, 120_000))
        assertEquals(2_500L, Scoring.nextDelayMs(-50.0, 1.0, 120_000))
        assertEquals(2_500L, Scoring.nextDelayMs(null, 0.0, 0))
    }

    @Test
    fun securityDetectionTreatsTransitionNetworksAsWpa2() {
        assertEquals(Security.WPA3, Security.fromCapabilities("[RSN-SAE-CCMP][ESS]"))
        assertEquals(Security.WPA2, Security.fromCapabilities("[RSN-PSK-CCMP][RSN-SAE-CCMP][ESS]"))
        assertEquals(Security.OPEN, Security.fromCapabilities("[ESS]"))
    }
}
