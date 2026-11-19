package com.raju.wifipro

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
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

    var ssid by remember { mutableStateOf("Not Connected") }
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
                        mergeScanResults(wifiManager.scanResults ?: emptyList())
                        scanStatus = "Updated"
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
            val info = wifiManager.connectionInfo
            if (info != null && info.networkId != -1) {
                ssid = info.ssid?.removeSurrounding("\"") ?: "Connected Wi-Fi"
                bssid = info.bssid ?: ""
                rssi = info.rssi
                freq = info.frequency
                linkSpeed = info.linkSpeed
                channelNum = freqToChannel(freq)
                bandLabel = when {
                    freq in 2400..2500 -> "2.4 GHz"
                    freq in 5000..5900 -> "5 GHz"
                    freq >= 5925 -> "6 GHz"
                    else -> "--"
                }
                wifiStandard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    when (info.wifiStandard) {
                        ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (802.11ax)"
                        ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
                        ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (802.11n)"
                        ScanResult.WIFI_STANDARD_LEGACY -> "Legacy (802.11a/b/g)"
                        else -> "Wi-Fi"
                    }
                } else {
                    if (freq >= 5000) "Wi-Fi 5 (5 GHz)" else "Wi-Fi 4 (2.4 GHz)"
                }
            }

            if (seconds > 0 && seconds % 10 == 0) {
                triggerScan()
            }
            seconds++
            delay(1000)
        }
    }

    val qualityText = when {
        rssi >= -55 -> "Excellent"
        rssi >= -67 -> "Good"
        rssi >= -75 -> "Fair"
        else -> "Weak"
    }
    val qualityColor = when {
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
                    Column {
                        Text(ssid, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(wifiStandard, fontSize = 13.sp, color = AccentCyan)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("$rssi dBm", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = qualityColor)
                        Text(qualityText, fontSize = 12.sp, color = qualityColor)
                    }
                }
                HorizontalDivider(color = Color(0xFF334155))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    InfoPill("Band", bandLabel)
                    InfoPill("Channel", if (channelNum > 0) "Ch $channelNum" else "--")
                    InfoPill("Freq", if (freq > 0) "$freq MHz" else "--")
                    InfoPill("Link", "$linkSpeed Mbps")
                }
            }
        }

        BandListCard(
            title = "5 GHz Channels",
            is5G = true,
            netList = netList,
            currentSsid = ssid,
            currentBssid = bssid,
            currentFreq = freq
        )

        BandListCard(
            title = "2.4 GHz Channels (Ch 1-11)",
            is5G = false,
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
            if (filtered.isEmpty()) {
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
