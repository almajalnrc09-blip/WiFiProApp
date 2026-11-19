package com.raju.wifipro

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class LanDevice(val ip: String, val name: String, val tag: String)

@Composable
fun DevicesTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isScanning by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var devices by remember { mutableStateOf<List<LanDevice>>(emptyList()) }

    fun startScan() {
        if (isScanning) return
        scope.launch {
            isScanning = true
            progress = 0f
            devices = emptyList()
            val myIp = getLocalIp(context)
            val gatewayIp = detectGatewayIp(context)
            val subnet = myIp.substringBeforeLast(".", "192.168.0")
            val found = mutableListOf<LanDevice>()
            val semaphore = Semaphore(40)
            var done = 0

            withContext(Dispatchers.IO) {
                val jobs = (1..254).map { host ->
                    async {
                        semaphore.withPermit {
                            val ip = "$subnet.$host"
                            val reachable = checkHostReachable(ip)
                            if (reachable || ip == myIp || ip == gatewayIp) {
                                val hostName = try {
                                    val h = InetAddress.getByName(ip).canonicalHostName
                                    if (h == ip) "Network Device" else h
                                } catch (_: Exception) { "Network Device" }

                                val tag = when (ip) {
                                    gatewayIp -> "Router / Gateway"
                                    myIp -> "This Phone"
                                    else -> "Active Device"
                                }
                                synchronized(found) {
                                    found.add(LanDevice(ip, if (tag != "Active Device") tag else hostName, tag))
                                }
                            }
                            done++
                            if (done % 10 == 0 || done == 254) {
                                withContext(Dispatchers.Main) {
                                    progress = done / 254f
                                    devices = synchronized(found) { found.sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 999 } }
                                }
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
            isScanning = false
        }
    }

    LaunchedEffect(Unit) { startScan() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Connected Devices", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("${devices.size} devices found on network", fontSize = 13.sp, color = TextSecondary)
            }
            FilledTonalIconButton(onClick = { startScan() }, enabled = !isScanning) {
                Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = AccentCyan)
            }
        }

        if (isScanning) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = AccentCyan)
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(devices) { dev ->
                Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val icon = when (dev.tag) {
                            "Router / Gateway" -> Icons.Default.Router
                            "This Phone" -> Icons.Default.Smartphone
                            else -> Icons.Default.Devices
                        }
                        Icon(icon, contentDescription = null, tint = if (dev.tag == "Router / Gateway") AccentGreen else AccentCyan)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(dev.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(dev.ip, fontSize = 13.sp, color = TextSecondary)
                        }
                        Text(dev.tag, fontSize = 11.sp, color = AccentCyan)
                    }
                }
            }
        }
    }
}

private fun checkHostReachable(ip: String): Boolean {
    try {
        if (InetAddress.getByName(ip).isReachable(280)) return true
    } catch (_: Exception) {}
    for (port in intArrayOf(80, 554)) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), 150)
                return true
            }
        } catch (_: Exception) {}
    }
    return false
}

@Suppress("DEPRECATION")
private fun getLocalIp(context: Context): String {
    return try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    } catch (_: Exception) {
        "192.168.0.100"
    }
}