package com.raju.wifipro

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
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
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class LanDevice(val ip: String, val name: String, val tag: String)

private val COMMON_PORTS = intArrayOf(
    80, 443, 554, 8080, 8443, 5353, 62078,
    22, 23, 135, 139, 445, 5000, 7000, 9100, 32400
)

@Composable
fun DevicesTab() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("device_names", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var isScanning by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var devices by remember { mutableStateOf<List<LanDevice>>(emptyList()) }

    var editingDevice by remember { mutableStateOf<LanDevice?>(null) }
    var renameInput by remember { mutableStateOf("") }

    fun getSavedOrDefaultName(ip: String, defaultName: String): String {
        val saved = prefs.getString(ip, null)
        return if (!saved.isNullOrBlank()) saved else defaultName
    }

    fun startScan() {
        if (isScanning) return
        scope.launch {
            isScanning = true
            progress = 0f
            devices = emptyList()
            val myIp = getLocalIp(context)
            val gatewayIp = detectGatewayIp(context)
            val subnet = myIp.substringBeforeLast(".", "192.168.8")
            val foundMap = mutableMapOf<String, LanDevice>()
            val semaphore = Semaphore(64)
            var done = 0

            withContext(Dispatchers.IO) {
                val jobs = (1..254).map { host ->
                    async {
                        semaphore.withPermit {
                            val ip = "$subnet.$host"
                            val reachable = checkHostReachable(ip)
                            if (reachable || ip == myIp || ip == gatewayIp) {
                                val hostName = resolveFriendlyName(ip)
                                val tag = when (ip) {
                                    gatewayIp -> "Router / Gateway"
                                    myIp -> "This Phone"
                                    else -> "Active Device"
                                }
                                val defaultName = if (tag != "Active Device") tag else hostName
                                synchronized(foundMap) {
                                    foundMap[ip] = LanDevice(ip, getSavedOrDefaultName(ip, defaultName), tag)
                                }
                            }
                            done++
                            if (done % 10 == 0 || done == 254) {
                                withContext(Dispatchers.Main) {
                                    progress = done / 254f
                                    devices = synchronized(foundMap) {
                                        foundMap.values.sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 999 }
                                    }
                                }
                            }
                        }
                    }
                }
                jobs.awaitAll()

                readArpIps(subnet).forEach { ip ->
                    synchronized(foundMap) {
                        if (!foundMap.containsKey(ip)) {
                            val tag = when (ip) {
                                gatewayIp -> "Router / Gateway"
                                myIp -> "This Phone"
                                else -> "Active Device"
                            }
                            val defaultName = if (tag != "Active Device") tag else resolveFriendlyName(ip)
                            foundMap[ip] = LanDevice(ip, getSavedOrDefaultName(ip, defaultName), tag)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progress = 1f
                    devices = synchronized(foundMap) {
                        foundMap.values.sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 999 }
                    }
                }
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
                Text("${devices.size} devices found (tap any to rename)", fontSize = 12.sp, color = TextSecondary)
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
            items(devices, key = { it.ip }) { dev ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        editingDevice = dev
                        renameInput = dev.name
                    },
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                        IconButton(onClick = {
                            editingDevice = dev
                            renameInput = dev.name
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename", tint = AccentCyan, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    editingDevice?.let { dev ->
        AlertDialog(
            onDismissRequest = { editingDevice = null },
            containerColor = CardDark,
            title = { Text("Rename Device (${dev.ip})", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Custom Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameInput.trim()
                        if (trimmed.isNotEmpty()) {
                            prefs.edit().putString(dev.ip, trimmed).apply()
                            devices = devices.map { if (it.ip == dev.ip) it.copy(name = trimmed) else it }
                        } else {
                            prefs.edit().remove(dev.ip).apply()
                        }
                        editingDevice = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgDark)
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingDevice = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

private fun resolveFriendlyName(ip: String): String {
    return try {
        val h = InetAddress.getByName(ip).canonicalHostName
        if (h.isNullOrBlank() || h == ip) "Network Device" else h.substringBefore(".local").substringBefore(".lan")
    } catch (_: Exception) {
        "Network Device"
    }
}

private fun checkHostReachable(ip: String): Boolean {
    try {
        if (InetAddress.getByName(ip).isReachable(350)) return true
    } catch (_: Exception) {}
    for (port in COMMON_PORTS) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), 140)
                return true
            }
        } catch (_: Exception) {}
    }
    return false
}

private fun readArpIps(subnet: String): List<String> {
    return try {
        val f = File("/proc/net/arp")
        if (!f.exists()) return emptyList()
        f.readLines().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val ip = parts.getOrNull(0) ?: return@mapNotNull null
            val mac = parts.getOrNull(3) ?: ""
            if (ip.startsWith("$subnet.") && mac.isNotBlank() && mac != "00:00:00:00:00:00") ip else null
        }
    } catch (_: Exception) {
        emptyList()
    }
}

@Suppress("DEPRECATION")
private fun getLocalIp(context: Context): String {
    return try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    } catch (_: Exception) {
        "192.168.8.100"
    }
}
