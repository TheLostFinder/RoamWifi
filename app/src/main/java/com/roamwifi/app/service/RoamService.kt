package com.roamwifi.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.roamwifi.app.R
import com.roamwifi.app.core.EngineBus
import com.roamwifi.app.core.InternetProber
import com.roamwifi.app.core.RoamEngine
import com.roamwifi.app.core.WifiConnector
import com.roamwifi.app.core.WifiScanner
import com.roamwifi.app.data.NetworkStore
import com.roamwifi.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hosts the roaming engine. Started when the app is opened, stopped when the app is closed
 * (back button, swipe from recents, or the notification's Stop button). START_NOT_STICKY ensures the OS
 * never silently revives it, so nothing scans in the background without the user knowing.
 */
class RoamService : Service() {

    private var scope: CoroutineScope? = null
    private var engine: RoamEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification("Starting…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (_: Exception) {
            // The OS refused to let us run in the foreground (e.g. started from the background): give up cleanly.
            stopSelf()
            return START_NOT_STICKY
        }

        if (engine == null) {
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
            scope = s
            val scanner = WifiScanner(this)
            engine = RoamEngine(
                context = applicationContext,
                scope = s,
                store = NetworkStore(this),
                scanner = scanner,
                connector = WifiConnector(this, scanner),
                prober = InternetProber(this),
            ).also { it.start() }

            // Keep the notification text in sync with the connected network.
            s.launch {
                EngineBus.state
                    .map { it.currentSsid?.let { n -> "Connected to $n" } ?: it.status }
                    .distinctUntilChanged()
                    .collect { text ->
                        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RoamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle("RoamWifi is roaming")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "roaming"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.roamwifi.app.STOP"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RoamService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RoamService::class.java))
        }
    }
}
