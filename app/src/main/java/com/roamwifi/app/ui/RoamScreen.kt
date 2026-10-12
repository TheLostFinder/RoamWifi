package com.roamwifi.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roamwifi.app.core.Candidate
import com.roamwifi.app.core.EngineBus
import com.roamwifi.app.core.Quality
import com.roamwifi.app.core.Security
import com.roamwifi.app.core.SavedNetwork
import com.roamwifi.app.data.NetworkStore
import com.roamwifi.app.service.RoamService

private fun hasLocation(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
fun RoamScreen() {
    val ctx = LocalContext.current
    val state by EngineBus.state.collectAsStateWithLifecycle()
    val store = remember { NetworkStore(ctx) }
    val prefs = remember { ctx.getSharedPreferences("roamwifi_ui", Context.MODE_PRIVATE) }

    var autoRoam by remember { mutableStateOf(prefs.getBoolean("auto", true)) }
    var granted by remember { mutableStateOf(hasLocation(ctx)) }
    var addFor by remember { mutableStateOf<Candidate?>(null) }
    var manage by remember { mutableStateOf<Candidate?>(null) }

    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasLocation(ctx)
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(permissions) }

    // Scanning runs only while the app is open and the switch is on.
    LaunchedEffect(granted, autoRoam) {
        if (granted && autoRoam) RoamService.start(ctx) else RoamService.stop(ctx)
    }

    Scaffold { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RoamWifi", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (autoRoam) "Auto-roam on" else "Auto-roam off",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoRoam, onCheckedChange = {
                        autoRoam = it
                        prefs.edit().putBoolean("auto", it).apply()
                    })
                }
            }

            item {
                StatusCard(
                    ssid = state.currentSsid,
                    rssi = state.currentRssi,
                    quality = state.currentQuality,
                    status = state.status,
                    issue = state.issue ?: if (!granted) "Location permission is needed to read Wi-Fi signals." else null,
                    onFix = {
                        if (!granted) {
                            launcher.launch(permissions)
                        } else {
                            val action = if (state.issue?.contains("Wi-Fi") == true) {
                                Settings.ACTION_WIFI_SETTINGS
                            } else {
                                Settings.ACTION_LOCATION_SOURCE_SETTINGS
                            }
                            runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    },
                )
            }

            item {
                Text("Networks", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }

            if (state.candidates.isEmpty()) {
                item {
                    Text(
                        "Nothing in range yet. Keep the app open and walk around.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.candidates, key = { it.ssid }) { c ->
                NetworkRow(c, onClick = { if (c.saved) manage = c else addFor = c })
                HorizontalDivider()
            }

            item {
                Text("Activity", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            items(state.log.take(8)) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    addFor?.let { c ->
        AddNetworkDialog(
            candidate = c,
            onDismiss = { addFor = null },
            onSave = { pw ->
                store.save(SavedNetwork(c.ssid, pw))
                EngineBus.bumpSaved()
                addFor = null
            },
        )
    }

    manage?.let { c ->
        AlertDialog(
            onDismissRequest = { manage = null },
            title = { Text(c.ssid) },
            text = { Text("Saved. The password is stored encrypted on this phone.") },
            confirmButton = {
                if (!c.connected) {
                    TextButton(onClick = {
                        EngineBus.forceConnect = c.ssid
                        manage = null
                    }) { Text("Connect now") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    store.remove(c.ssid)
                    EngineBus.bumpSaved()
                    // If the engine is not running it cannot clean up the OS-level suggestion itself.
                    if (!state.running && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching {
                            (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                                .removeNetworkSuggestions(emptyList())
                        }
                    }
                    manage = null
                }) { Text("Forget") }
            },
        )
    }
}

@Composable
private fun StatusCard(
    ssid: String?,
    rssi: Int?,
    quality: Quality?,
    status: String,
    issue: String?,
    onFix: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (issue != null) {
                Text(issue, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onFix) { Text("Fix") }
            } else {
                Text(ssid ?: "Not connected", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                val parts = buildList {
                    rssi?.let { add("$it dBm") }
                    when {
                        quality == null -> {}
                        !quality.working -> add("no internet")
                        else -> {
                            quality.latencyMs?.let { add("$it ms") }
                            quality.kbps?.let { add(formatSpeed(it)) }
                        }
                    }
                }
                if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodyLarge)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NetworkRow(c: Candidate, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.ssid,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (c.connected) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (c.saved) {
                    Text("  saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (c.security == Security.OPEN) {
                    Text("  open", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val q = c.quality
            val detail = buildList {
                add("${c.rssi} dBm")
                add(trendArrow(c.slope))
                if (q != null) add(if (q.working) q.latencyMs?.let { "$it ms" } ?: "ok" else "no internet")
            }.joinToString("  ")
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            bars(c.rssi),
            color = if (c.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddNetworkDialog(candidate: Candidate, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    val open = candidate.security == Security.OPEN
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(candidate.ssid) },
        text = {
            if (open) {
                Text("Open network. Save it so RoamWifi can roam onto it?")
            } else {
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Wi-Fi password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = open || pw.length >= 8, onClick = { onSave(if (open) null else pw) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun bars(rssi: Int): String = when {
    rssi > -55 -> "▂▄▆█"
    rssi > -65 -> "▂▄▆"
    rssi > -75 -> "▂▄"
    else -> "▂"
}

private fun trendArrow(slope: Double): String = when {
    slope > 0.4 -> "↑ closer"
    slope < -0.4 -> "↓ fading"
    else -> "→ steady"
}

private fun formatSpeed(kbps: Int): String =
    if (kbps >= 1000) "%.1f Mbps".format(kbps / 1000.0) else "$kbps kbps"
