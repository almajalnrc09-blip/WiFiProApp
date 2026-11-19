package com.raju.wifipro

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LanDevice(val ip: String, val name: String, val tag: String)

private val COMMON_PORTS = intArrayOf(
    80, 443, 554, 8080, 8443, 5353, 62078,
    22, 23, 135, 139, 445, 5000, 7000, 9100, 32400
)

private val GENERIC_TITLES = setOf(
    "login", "log in", "index", "welcome", "error", "404", "403", "401",
    "301", "302", "untitled", "document", "redirect", "home", "loading", "web"
)

private val GENERIC_SERVERS = setOf(
    "nginx", "apache", "lighttpd", "httpd", "boa", "mini_httpd",
    "goahead", "uhttpd", "micro_httpd", "webserver"
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
            val discoveredNames = ConcurrentHashMap<String, String>()
            val semaphore = Semaphore(64)
            var done = 0

            withContext(Dispatchers.IO) {
                // Run passive/multicast LAN name discovery in parallel with host scan
                val ssdpDeferred = async { discoverSsdpNames(subnet) }
                val onvifDeferred = async { discoverOnvifNames(subnet) }

                val jobs = (1..254).map { host ->
                    async {
                        semaphore.withPermit {
                            val ip = "$subnet.$host"
                            val probePort = probeHost(ip)
                            val reachable = probePort != -1
                            if (reachable || ip == myIp || ip == gatewayIp) {
                                val tag = when (ip) {
                                    gatewayIp -> "Router / Gateway"
                                    myIp -> "This Phone"
                                    else -> "Active Device"
                                }
                                val autoName = when (ip) {
                                    myIp -> getThisPhoneName()
                                    gatewayIp -> resolveDeviceName(ip, probePort) ?: "Router / Gateway"
                                    else -> resolveDeviceName(ip, probePort) ?: "Network Device"
                                }
                                synchronized(foundMap) {
                                    foundMap[ip] = LanDevice(ip, getSavedOrDefaultName(ip, autoName), tag)
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

                // Collect multicast SSDP (routers, TVs, UPnP devices) & ONVIF (Tapo/IP cameras, NVRs)
                val ssdpMap = ssdpDeferred.await()
                val onvifMap = onvifDeferred.await()
                discoveredNames.putAll(ssdpMap)
                discoveredNames.putAll(onvifMap) // ONVIF camera model names take priority

                // Merge discovered multicast names and any extra IPs that replied
                discoveredNames.forEach { (ip, smartName) ->
                    synchronized(foundMap) {
                        val existing = foundMap[ip]
                        val tag = when (ip) {
                            gatewayIp -> "Router / Gateway"
                            myIp -> "This Phone"
                            else -> "Active Device"
                        }
                        val bestName = if (ip == myIp) getThisPhoneName() else smartName
                        if (existing == null || existing.name == "Network Device" || existing.name == "IP Camera" || existing.name == "Router / Gateway") {
                            foundMap[ip] = LanDevice(ip, getSavedOrDefaultName(ip, bestName), tag)
                        }
                    }
                }

                // Also merge entries from ARP table if readable
                readArpIps(subnet).forEach { ip ->
                    synchronized(foundMap) {
                        if (!foundMap.containsKey(ip)) {
                            val tag = when (ip) {
                                gatewayIp -> "Router / Gateway"
                                myIp -> "This Phone"
                                else -> "Active Device"
                            }
                            val fallbackName = when (ip) {
                                myIp -> getThisPhoneName()
                                gatewayIp -> "Router / Gateway"
                                else -> discoveredNames[ip] ?: resolveDeviceName(ip, 0) ?: "Network Device"
                            }
                            foundMap[ip] = LanDevice(ip, getSavedOrDefaultName(ip, fallbackName), tag)
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

private fun getThisPhoneName(): String {
    val manu = Build.MANUFACTURER?.trim().orEmpty()
    val model = Build.MODEL?.trim().orEmpty()
    val label = if (model.startsWith(manu, ignoreCase = true)) model else "$manu $model".trim()
    val clean = label.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    return if (clean.isNotBlank()) "$clean (This Phone)" else "This Phone"
}

// Returns open port number (or 0 if ICMP reachable, -1 if unreachable)
private fun probeHost(ip: String): Int {
    try {
        if (InetAddress.getByName(ip).isReachable(280)) return 0
    } catch (_: Exception) {}
    for (port in COMMON_PORTS) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), 130)
                return port
            }
        } catch (_: Exception) {}
    }
    return -1
}

private fun resolveDeviceName(ip: String, openPort: Int): String? {
    // 1. NetBIOS (UDP 137) - catches Windows PCs, Android/Linux Samba, routers
    queryNetBiosName(ip)?.let { return it }

    // 2. mDNS Reverse PTR (UDP 5353) - catches phones, Apple, Android, smart IoT
    queryMdnsReverseName(ip)?.let { return it }

    // 3. HTTP Title / Server Header (Ports 80 / 8080) - catches routers, NVRs, web panels
    if (openPort == 80 || openPort == 8080 || openPort == 0) {
        queryHttpDeviceName(ip)?.let { return it }
    }

    // 4. RTSP Camera check (Port 554)
    if (openPort == 554) {
        queryRtspServerName(ip)?.let { return it }
        return "IP Camera"
    }

    // 5. Standard Reverse DNS fallback
    try {
        val h = InetAddress.getByName(ip).canonicalHostName
        if (!h.isNullOrBlank() && h != ip) {
            return h.substringBefore(".local").substringBefore(".lan")
        }
    } catch (_: Exception) {}

    return null
}

// NetBIOS NBSTAT Query (UDP Port 137)
private fun queryNetBiosName(ip: String): String? {
    return try {
        val query = byteArrayOf(
            0x13, 0x37, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x20,
            0x43, 0x4B,
            0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
            0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
            0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
            0x00, 0x00, 0x21, 0x00, 0x01
        )
        DatagramSocket().use { socket ->
            socket.soTimeout = 260
            val addr = InetAddress.getByName(ip)
            socket.send(DatagramPacket(query, query.size, addr, 137))
            val buf = ByteArray(1024)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            val len = resp.length
            if (len < 57) return null
            val numNames = buf[56].toInt() and 0xFF
            var offset = 57
            for (i in 0 until numNames) {
                if (offset + 18 > len) break
                val name = String(buf, offset, 15, Charsets.US_ASCII).trim()
                val suffix = buf[offset + 15].toInt() and 0xFF
                val flags = ((buf[offset + 16].toInt() and 0xFF) shl 8) or (buf[offset + 17].toInt() and 0xFF)
                val isGroup = (flags and 0x8000) != 0
                if (!isGroup && (suffix == 0x00 || suffix == 0x20) && name.isNotBlank() && !name.startsWith("__")) {
                    return name
                }
                offset += 18
            }
            null
        }
    } catch (_: Exception) {
        null
    }
}

// mDNS Reverse PTR Query (UDP Port 5353)
private fun queryMdnsReverseName(ip: String): String? {
    val parts = ip.split(".")
    if (parts.size != 4) return null
    return try {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        val labels = listOf(parts[3], parts[2], parts[1], parts[0], "in-addr", "arpa")
        for (lbl in labels) {
            val b = lbl.toByteArray(Charsets.US_ASCII)
            out.write(b.size)
            out.write(b)
        }
        out.write(0x00)
        out.write(byteArrayOf(0x00, 0x0C)) // PTR
        out.write(byteArrayOf(0x80.toByte(), 0x01)) // IN + QU (unicast response)
        val packetBytes = out.toByteArray()

        DatagramSocket().use { socket ->
            socket.soTimeout = 260
            socket.send(DatagramPacket(packetBytes, packetBytes.size, InetAddress.getByName(ip), 5353))
            val buf = ByteArray(1024)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            val len = resp.length
            if (len < 12) return null
            val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
            val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
            var pos = 12
            repeat(qdCount) {
                pos = readDnsName(buf, pos, len).second + 4
            }
            repeat(anCount) {
                if (pos >= len) return@repeat
                pos = readDnsName(buf, pos, len).second
                if (pos + 10 > len) return@repeat
                val type = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                val rdLen = ((buf[pos + 8].toInt() and 0xFF) shl 8) or (buf[pos + 9].toInt() and 0xFF)
                pos += 10
                if (type == 12 && pos + rdLen <= len) {
                    val host = readDnsName(buf, pos, len).first
                        .substringBefore(".local")
                        .substringBefore(".lan")
                        .trim()
                    if (host.isNotBlank() && host != ip) return host
                }
                pos += rdLen
            }
            null
        }
    } catch (_: Exception) {
        null
    }
}

private fun readDnsName(data: ByteArray, startOffset: Int, len: Int): Pair<String, Int> {
    val sb = StringBuilder()
    var pos = startOffset
    var jumped = false
    var endPos = startOffset
    var guard = 0
    while (pos < len && guard < 32) {
        guard++
        val labelLen = data[pos].toInt() and 0xFF
        if (labelLen == 0) {
            if (!jumped) endPos = pos + 1
            break
        }
        if ((labelLen and 0xC0) == 0xC0) {
            if (pos + 1 >= len) break
            val ptr = ((labelLen and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
            if (!jumped) endPos = pos + 2
            pos = ptr
            jumped = true
        } else {
            pos++
            if (pos + labelLen > len) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(data, pos, labelLen, Charsets.UTF_8))
            pos += labelLen
            if (!jumped) endPos = pos
        }
    }
    return Pair(sb.toString(), endPos)
}

// ONVIF WS-Discovery (UDP 3702) - discovers Tapo cameras, IP cameras & NVRs
private fun discoverOnvifNames(subnet: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val probe = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
 xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
 xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
 xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
 <e:Header>
  <w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID>
  <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
  <w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
 </e:Header>
 <e:Body>
  <d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe>
 </e:Body>
</e:Envelope>""".toByteArray(Charsets.UTF_8)

    try {
        DatagramSocket().use { socket ->
            socket.soTimeout = 350
            val mcast = InetAddress.getByName("239.255.255.250")
            socket.send(DatagramPacket(probe, probe.size, mcast, 3702))
            val deadline = System.currentTimeMillis() + 1800L
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val ip = pkt.address.hostAddress ?: continue
                    if (!ip.startsWith("$subnet.")) continue
                    val xml = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val nameMatch = Regex("onvif://www\\.onvif\\.org/name/([^\\s<]+)", RegexOption.IGNORE_CASE).find(xml)
                    val hwMatch = Regex("onvif://www\\.onvif\\.org/hardware/([^\\s<]+)", RegexOption.IGNORE_CASE).find(xml)
                    val raw = nameMatch?.groupValues?.get(1) ?: hwMatch?.groupValues?.get(1)
                    if (!raw.isNullOrBlank()) {
                        val decoded = try {
                            URLDecoder.decode(raw, "UTF-8").replace("_", " ").trim()
                        } catch (_: Exception) {
                            raw.replace("%20", " ").replace("_", " ").trim()
                        }
                        if (decoded.isNotBlank()) result[ip] = decoded
                    }
                } catch (_: Exception) {
                    // timeout tick, loop until deadline
                }
            }
        }
    } catch (_: Exception) {}
    return result
}

// SSDP / UPnP Discovery (UDP 1900) - discovers routers, smart TVs, NVRs & UPnP devices
private fun discoverSsdpNames(subnet: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val locations = mutableMapOf<String, String>()
    val msearch = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: ssdp:all\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)

    try {
        DatagramSocket().use { socket ->
            socket.soTimeout = 350
            val mcast = InetAddress.getByName("239.255.255.250")
            socket.send(DatagramPacket(msearch, msearch.size, mcast, 1900))
            val deadline = System.currentTimeMillis() + 1800L
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val ip = pkt.address.hostAddress ?: continue
                    if (!ip.startsWith("$subnet.")) continue
                    val resp = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val loc = Regex("(?im)^LOCATION:\\s*(.+)$").find(resp)?.groupValues?.get(1)?.trim()
                    if (!loc.isNullOrBlank() && !locations.containsKey(ip)) {
                        locations[ip] = loc
                    }
                } catch (_: Exception) {
                }
            }
        }
    } catch (_: Exception) {}

    for ((ip, locUrl) in locations) {
        try {
            val conn = URL(locUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 900
            conn.readTimeout = 1000
            conn.requestMethod = "GET"
            if (conn.responseCode in 200..299) {
                val xml = conn.inputStream.bufferedReader().use { it.readText().take(12000) }
                val friendly = Regex("<friendlyName>(.*?)</friendlyName>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(xml)?.groupValues?.get(1)?.trim()
                val model = Regex("<modelName>(.*?)</modelName>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(xml)?.groupValues?.get(1)?.trim()
                val picked = listOfNotNull(friendly, model).firstOrNull { it.isNotBlank() && it.length <= 42 }
                if (!picked.isNullOrBlank()) result[ip] = picked
            }
            conn.disconnect()
        } catch (_: Exception) {}
    }
    return result
}

private fun queryHttpDeviceName(ip: String): String? {
    for (port in intArrayOf(80, 8080)) {
        try {
            val conn = URL("http://$ip:$port/").openConnection() as HttpURLConnection
            conn.connectTimeout = 350
            conn.readTimeout = 500
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val serverHdr = conn.getHeaderField("Server")?.trim()
            val body = try {
                if (code in 200..399) {
                    conn.inputStream.bufferedReader().use { r ->
                        val arr = CharArray(6144)
                        val n = r.read(arr)
                        if (n > 0) String(arr, 0, n) else ""
                    }
                } else ""
            } catch (_: Exception) { "" }
            conn.disconnect()

            val title = Regex("<title>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(body)?.groupValues?.get(1)
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
            if (!title.isNullOrBlank() && title.length in 2..40 && title.lowercase() !in GENERIC_TITLES) {
                return title
            }
            if (!serverHdr.isNullOrBlank()) {
                val cleanSrv = serverHdr.substringBefore("/").trim()
                if (cleanSrv.length in 2..30 && cleanSrv.lowercase() !in GENERIC_SERVERS) {
                    return cleanSrv
                }
            }
        } catch (_: Exception) {}
    }
    return null
}

private fun queryRtspServerName(ip: String): String? {
    return try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, 554), 250)
            s.soTimeout = 300
            val req = "OPTIONS rtsp://$ip:554/ RTSP/1.0\r\nCSeq: 1\r\n\r\n"
            s.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
            val buf = ByteArray(512)
            val n = s.getInputStream().read(buf)
            if (n > 0) {
                val resp = String(buf, 0, n, Charsets.US_ASCII)
                val srv = Regex("(?im)^Server:\\s*(.+)$").find(resp)?.groupValues?.get(1)?.trim()
                if (!srv.isNullOrBlank() && srv.length <= 36) return srv
            }
            null
        }
    } catch (_: Exception) {
        null
    }
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
