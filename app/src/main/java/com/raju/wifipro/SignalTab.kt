package com.raju.wifipro

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.delay

private data class CachedNet(
    val ssid: String,
    val bssid: String,
    val freq: Int,
    val level: Int,
    val seenAtMs: Long
)

@Suppress("DEPRECATION")
@Composable
fun SignalTab() {
    val context = LocalContext.current
    val wifiManager = remember { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    val connectivityManager = remember { context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    val locationManager = remember { context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    var isWifiConnected by remember { mutableStateOf(false) }
    var isLocationOn by remember { mutableStateOf(true) }
    var ssid by remember { mutableStateOf("Not Connected") }
    var ssidHiddenByLocation by remember { mutableStateOf(false) }
    var bssid by remember { mutableStateOf("") }
    var rssi by remember { mutableIntStateOf(-100) }
    var freq by remember { mutableIntStateOf(0) }
    var linkSpeed by remember { mutableIntStateOf(0) }
    var wifiStandard by remember { mutableStateOf("Unknown") }
    var channelNum by remember { mutableIntStateOf(0) }
    var bandLabel by remember { mutableStateOf("--") }
    var scanStatus by remember { mutableStateOf("Live") }
    val recentMap = remember { mutableStateMapOf<String, CachedNet>() }
    var netList by remember { mutableStateOf<List<CachedNet>>(emptyList()) }

    fun checkLocationEnabled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: Exception) {
            true
        }
    }

    fun mergeScanResults(results: List<ScanResult>) {
        val now = System.currentTimeMillis()
        results.forEach { sr ->
            val key = sr.BSSID ?: "${sr.SSID}_${sr.frequency}"
            val name = if (sr.SSID.isNullOrBlank()) "<Hidden>" else sr.SSID
            recentMap[key] = CachedNet(name, sr.BSSID ?: "", sr.frequency, sr.level, now)
        }
        val staleKeys = recentMap.filterValues { now - it.seenAtMs > 20_000L }.keys
        staleKeys.forEach { recentMap.remove(it) }
        netList = recentMap.values.toList()
    }

    fun triggerScan() {
        isLocationOn = checkLocationEnabled()
        if (!isLocationOn) {
            recentMap.clear()
            netList = emptyList()
            scanStatus = "Location Off"
            return
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val started = wifiManager.startScan()
                scanStatus = if (started) "Scanning..." else "Live"
                mergeScanResults(wifiManager.scanResults ?: emptyList())
            } catch (_: Exception) {
                scanStatus = "Live"
            }
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        if (checkLocationEnabled()) {
                            mergeScanResults(wifiManager.scanResults ?: emptyList())
                            scanStatus = "Updated"
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        triggerScan()
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        var seconds = 0
        while (true) {
            isLocationOn = checkLocationEnabled()

            // Read Wi-Fi transport state & WifiInfo without relying on networkId != -1
            // (Android sets networkId = -1 when Location is OFF, even though RSSI/Freq/LinkSpeed remain valid!)
            var transportWifi = false
            var capWifiInfo: WifiInfo? = null
            try {
                val activeNet = connectivityManager.activeNetwork
                val caps = connectivityManager.getNetworkCapabilities(activeNet)
                transportWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    capWifiInfo = caps?.transportInfo as? WifiInfo
                }
            } catch (_: Exception) {}

            val legacyInfo = try { wifiManager.connectionInfo } catch (_: Exception) { null }
            val primaryInfo = capWifiInfo ?: legacyInfo
            val hasLiveRadio = (primaryInfo?.frequency ?: 0) > 0 || (primaryInfo?.rssi ?: -127) in -120..-10
            val connectedNow = transportWifi || (legacyInfo != null && (legacyInfo.networkId != -1 || hasLiveRadio))
            isWifiConnected = connectedNow

            if (connectedNow && (primaryInfo != null || legacyInfo != null)) {
                val activeRssi = when {
                    capWifiInfo != null && capWifiInfo.rssi in -120..-10 -> capWifiInfo.rssi
                    legacyInfo != null && legacyInfo.rssi in -120..-10 -> legacyInfo.rssi
                    else -> -100
                }
                val activeFreq = when {
                    (capWifiInfo?.frequency ?: 0) > 0 -> capWifiInfo!!.frequency
                    (legacyInfo?.frequency ?: 0) > 0 -> legacyInfo!!.frequency
                    else -> 0
                }
                val activeLink = when {
                    (capWifiInfo?.linkSpeed ?: 0) > 0 -> capWifiInfo!!.linkSpeed
                    (legacyInfo?.linkSpeed ?: 0) > 0 -> legacyInfo!!.linkSpeed
                    else -> 0
                }
                val rawSsid = (capWifiInfo?.ssid ?: legacyInfo?.ssid)?.removeSurrounding("\"") ?: ""
                val isUnknownSsid = rawSsid.isBlank() || rawSsid.equals("<unknown ssid>", ignoreCase = true)

                ssidHiddenByLocation = isUnknownSsid
                ssid = if (isUnknownSsid) "Connected Wi-Fi" else rawSsid
                bssid = (capWifiInfo?.bssid ?: legacyInfo?.bssid)?.takeUnless { it == "02:00:00:00:00:00" } ?: ""
                rssi = activeRssi
                freq = activeFreq
                linkSpeed = activeLink
                channelNum = freqToChannel(activeFreq)
                bandLabel = when {
                    activeFreq in 2400..2500 -> "2.4 GHz"
                    activeFreq in 5000..5900 -> "5 GHz"
                    activeFreq >= 5925 -> "6 GHz"
                    else -> "--"
                }
                val stdInfo = capWifiInfo ?: legacyInfo
                wifiStandard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && stdInfo != null) {
                    when (stdInfo.wifiStandard) {
                        ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (802.11ax)"
                        ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
                        ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (802.11n)"
                        ScanResult.WIFI_STANDARD_LEGACY -> "Legacy (802.11a/b/g)"
                        else -> if (activeFreq >= 5000) "Wi-Fi 5 (5 GHz)" else "Wi-Fi"
                    }
                } else {
                    if (activeFreq >= 5000) "Wi-Fi 5 (5 GHz)" else if (activeFreq > 0) "Wi-Fi 4 (2.4 GHz)" else "Wi-Fi"
                }
            } else {
                ssid = "Not Connected"
                ssidHiddenByLocation = false
                bssid = ""
                rssi = -100
                freq = 0
                linkSpeed = 0
                channelNum = 0
                bandLabel = "--"
                wifiStandard = "Unknown"
            }

            if (!isLocationOn) {
                recentMap.clear()
                netList = emptyList()
                scanStatus = "Location Off"
            } else if (seconds > 0 && seconds % 10 == 0) {
                triggerScan()
            }
            seconds++
            delay(1000)
        }
    }

    val qualityText = when {
        !isWifiConnected -> "Disconnected"
        rssi >= -55 -> "Excellent"
        rssi >= -67 -> "Good"
        rssi >= -75 -> "Fair"
        else -> "Weak"
    }
    val qualityColor = when {
        !isWifiConnected -> Color(0xFFEF4444)
        rssi >= -55 -> AccentGreen
        rssi >= -67 -> AccentCyan
        rssi >= -75 -> AccentAmber
        else -> Color(0xFFEF4444)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Signal & Channel Analyzer", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("Status: $scanStatus", fontSize = 12.sp, color = AccentCyan)
            }
            FilledTonalIconButton(onClick = { triggerScan() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Rescan Wi-Fi", tint = AccentCyan)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ssid, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(wifiStandard, fontSize = 13.sp, color = AccentCyan)
                        if (isWifiConnected && ssidHiddenByLocation) {
                            Text(
                                text = "Name hidden by Android (turn on Location to show SSID)",
                                fontSize = 11.sp,
                                color = AccentAmber
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (isWifiConnected) "$rssi dBm" else "-- dBm", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = qualityColor)
                        Text(qualityText, fontSize = 12.sp, color = qualityColor)
                    }
                }
                HorizontalDivider(color = Color(0xFF334155))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    InfoPill("Band", bandLabel)
                    InfoPill("Channel", if (channelNum > 0) "Ch $channelNum" else "--")
                    InfoPill("Freq", if (freq > 0) "$freq MHz" else "--")
                    InfoPill("Link", if (linkSpeed > 0) "$linkSpeed Mbps" else "--")
                }
            }
        }

        BandListCard(
            title = "5 GHz Channels",
            is5G = true,
            isLocationOn = isLocationOn,
            netList = netList,
            currentSsid = ssid,
            currentBssid = bssid,
            currentFreq = freq
        )

        BandListCard(
            title = "2.4 GHz Channels (Ch 1-11)",
            is5G = false,
            isLocationOn = isLocationOn,
            netList = netList,
            currentSsid = ssid,
            currentBssid = bssid,
            currentFreq = freq
        )
    }
}

private fun isConnectedMatch(net: CachedNet, currentSsid: String, currentBssid: String, currentFreq: Int): Boolean {
    if (currentFreq <= 0) return false
    val netIs5G = net.freq in 5000..5900
    val curIs5G = currentFreq in 5000..5900
    if (netIs5G != curIs5G) return false
    if (currentBssid.isNotBlank() && net.bssid.equals(currentBssid, ignoreCase = true)) return true
    return net.ssid == currentSsid && net.freq == currentFreq
}

@Composable
private fun BandListCard(
    title: String,
    is5G: Boolean,
    isLocationOn: Boolean,
    netList: List<CachedNet>,
    currentSsid: String,
    currentBssid: String,
    currentFreq: Int
) {
    val filtered = netList.filter {
        if (is5G) it.freq in 5000..5900 else it.freq in 2400..2500
    }.sortedByDescending { it.level }

    Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
            if (!isLocationOn) {
                Text(
                    text = "Turn on Location (GPS) to scan nearby ${if (is5G) "5 GHz" else "2.4 GHz"} networks.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            } else if (filtered.isEmpty()) {
                Text("No ${if (is5G) "5 GHz" else "2.4 GHz"} networks found.", fontSize = 12.sp, color = TextSecondary)
            } else {
                filtered.take(10).forEach { net ->
                    val ch = freqToChannel(net.freq)
                    val isMine = isConnectedMatch(net, currentSsid, currentBssid, currentFreq)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = net.ssid + if (isMine) " (Connected)" else "",
                            fontSize = 13.sp,
                            color = if (isMine) AccentGreen else TextPrimary,
                            fontWeight = if (isMine) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Ch $ch  |  ${net.level} dBm",
                            fontSize = 12.sp,
                            color = if (isMine) AccentGreen else TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoPill(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = TextSecondary)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    }
}

fun freqToChannel(freq: Int): Int = when {
    freq == 2484 -> 14
    freq in 2412..2472 -> (freq - 2407) / 5
    freq in 5170..5825 -> (freq - 5000) / 5
    freq in 5955..7115 -> (freq - 5950) / 5
    else -> 0
}
