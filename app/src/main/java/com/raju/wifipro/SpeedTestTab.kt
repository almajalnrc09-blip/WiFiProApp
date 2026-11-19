package com.raju.wifipro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

@Composable
fun SpeedTestTab() {
    val scope = rememberCoroutineScope()
    var isTesting by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("Ready to Test") }
    var currentMbps by remember { mutableDoubleStateOf(0.0) }
    var pingMs by remember { mutableLongStateOf(0L) }
    var downloadMbps by remember { mutableDoubleStateOf(0.0) }
    var uploadMbps by remember { mutableDoubleStateOf(0.0) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Speed Test", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(250.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 20.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2, stroke / 2)
                drawArc(
                    color = CardDark,
                    startAngle = 140f,
                    sweepAngle = 260f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                val progress = (currentMbps.coerceIn(0.0, 200.0) / 200.0).toFloat()
                drawArc(
                    color = if (phase.contains("Upload")) AccentGreen else AccentCyan,
                    startAngle = 140f,
                    sweepAngle = 260f * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = String.format(Locale.US, "%.1f", currentMbps),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text("Mbps", fontSize = 16.sp, color = TextSecondary)
                Spacer(Modifier.height(6.dp))
                Text(phase, fontSize = 13.sp, color = AccentCyan)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SpeedStatCard("Ping", if (pingMs > 0) "$pingMs ms" else "--", Modifier.weight(1f))
            SpeedStatCard("Download", if (downloadMbps > 0) String.format(Locale.US, "%.1f", downloadMbps) + " Mbps" else "--", Modifier.weight(1f))
            SpeedStatCard("Upload", if (uploadMbps > 0) String.format(Locale.US, "%.1f", uploadMbps) + " Mbps" else "--", Modifier.weight(1f))
        }

        Button(
            onClick = {
                if (isTesting) return@Button
                scope.launch {
                    isTesting = true
                    pingMs = 0L; downloadMbps = 0.0; uploadMbps = 0.0; currentMbps = 0.0
                    try {
                        phase = "Testing Ping..."
                        pingMs = measurePing()

                        phase = "Testing Download..."
                        downloadMbps = measureDownload { live -> currentMbps = live }

                        phase = "Testing Upload..."
                        uploadMbps = measureUpload { live -> currentMbps = live }

                        currentMbps = downloadMbps
                        phase = "Test Completed"
                    } catch (e: Exception) {
                        phase = "Error: Check Internet"
                    } finally {
                        isTesting = false
                    }
                }
            },
            enabled = !isTesting,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgDark)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isTesting) "Testing..." else "START SPEED TEST", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SpeedStatCard(title: String, value: String, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 12.sp, color = TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }
    }
}

private suspend fun measurePing(): Long = withContext(Dispatchers.IO) {
    var total = 0L
    val count = 3
    repeat(count) {
        val start = System.currentTimeMillis()
        val conn = URL("https://1.1.1.1/cdn-cgi/trace").openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.requestMethod = "HEAD"
        conn.connect()
        conn.responseCode
        conn.disconnect()
        total += (System.currentTimeMillis() - start)
    }
    total / count
}

private suspend fun measureDownload(onLive: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
    val conn = URL("https://speed.cloudflare.com/__down?bytes=15000000").openConnection() as HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 10000
    val stream = conn.inputStream
    val buffer = ByteArray(16384)
    var totalBytes = 0L
    val startTime = System.nanoTime()
    var lastUpdate = startTime
    while (true) {
        val read = stream.read(buffer)
        if (read == -1) break
        totalBytes += read
        val now = System.nanoTime()
        val elapsedSec = (now - startTime) / 1_000_000_000.0
        if ((now - lastUpdate) > 200_000_000L && elapsedSec > 0.1) {
            val mbps = (totalBytes * 8.0) / (elapsedSec * 1_000_000.0)
            withContext(Dispatchers.Main) { onLive(mbps) }
            lastUpdate = now
        }
        if (elapsedSec > 6.5) break
    }
    stream.close()
    conn.disconnect()
    val totalSec = ((System.nanoTime() - startTime) / 1_000_000_000.0).coerceAtLeast(0.1)
    (totalBytes * 8.0) / (totalSec * 1_000_000.0)
}

private suspend fun measureUpload(onLive: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
    val conn = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 10000
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setChunkedStreamingMode(16384)
    val chunk = ByteArray(16384) { 0x55 }
    val out = conn.outputStream
    var totalBytes = 0L
    val startTime = System.nanoTime()
    var lastUpdate = startTime
    while (totalBytes < 3_500_000L) {
        out.write(chunk)
        totalBytes += chunk.size
        val now = System.nanoTime()
        val elapsedSec = (now - startTime) / 1_000_000_000.0
        if ((now - lastUpdate) > 200_000_000L && elapsedSec > 0.1) {
            val mbps = (totalBytes * 8.0) / (elapsedSec * 1_000_000.0)
            withContext(Dispatchers.Main) { onLive(mbps) }
            lastUpdate = now
        }
        if (elapsedSec > 5.0) break
    }
    out.flush()
    out.close()
    conn.responseCode
    conn.disconnect()
    val totalSec = ((System.nanoTime() - startTime) / 1_000_000_000.0).coerceAtLeast(0.1)
    (totalBytes * 8.0) / (totalSec * 1_000_000.0)
}