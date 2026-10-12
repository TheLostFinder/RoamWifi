package com.roamwifi.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Measures whether the *Wi-Fi* network really reaches the internet, and how fast.
 * Traffic is bound to the Wi-Fi Network object so mobile data can never mask a dead hostel router.
 */
class InternetProber(context: Context) {

    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun wifiNetwork(): Network? = try {
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    } catch (_: Exception) {
        null
    }

    /**
     * @param deep also measure download throughput (a ~250 KB transfer); done rarely to save data and battery.
     * A 204 response proves real internet; a 200/302 means a captive portal, which counts as "no internet".
     */
    suspend fun probe(deep: Boolean): Quality = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        val net = wifiNetwork() ?: return@withContext Quality(false, null, null, now)

        var latency: Int? = null
        for (url in LATENCY_URLS) {
            latency = generate204(net, url)
            if (latency != null) break
        }
        if (latency == null) return@withContext Quality(false, null, null, now)

        val kbps = if (deep) download(net) else null
        Quality(true, latency, kbps, now)
    }

    private fun generate204(net: Network, url: String): Int? {
        val t0 = SystemClock.elapsedRealtime()
        var c: HttpURLConnection? = null
        return try {
            c = net.openConnection(URL(url)) as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = false
            c.useCaches = false
            if (c.responseCode == 204) (SystemClock.elapsedRealtime() - t0).toInt() else null
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun download(net: Network): Int? {
        var c: HttpURLConnection? = null
        return try {
            val t0 = SystemClock.elapsedRealtime()
            c = net.openConnection(URL(DOWNLOAD_URL)) as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.useCaches = false
            if (c.responseCode != 200) return null
            val buf = ByteArray(16 * 1024)
            var total = 0L
            c.inputStream.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
            val ms = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1)
            // bytes * 8 bits / ms == kilobits per second
            (total * 8 / ms).toInt().takeIf { total > 0 }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    private companion object {
        const val TIMEOUT_MS = 4_000
        const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=250000"
        val LATENCY_URLS = listOf(
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        )
    }
}
