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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"

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
                val progress = (currentMbps.coerceIn(0.0, 250.0) / 250.0).toFloat()
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
            SpeedStatCard(
                title = "Ping",
                value = if (pingMs > 0) "$pingMs ms" else "--",
                modifier = Modifier.weight(1f)
            )
            SpeedStatCard(
                title = "Download",
                value = if (downloadMbps > 0.0) String.format(Locale.US, "%.1f", downloadMbps) + " Mbps" else "--",
                modifier = Modifier.weight(1f)
            )
            SpeedStatCard(
                title = "Upload",
                value = if (uploadMbps > 0.0) String.format(Locale.US, "%.1f", uploadMbps) + " Mbps" else "--",
                modifier = Modifier.weight(1f)
            )
        }

        Button(
            onClick = {
                if (isTesting) return@Button
                scope.launch {
                    isTesting = true
                    pingMs = 0L
                    downloadMbps = 0.0
                    uploadMbps = 0.0
                    currentMbps = 0.0
                    try {
                        phase = "Testing Ping..."
                        pingMs = measurePing()

                        phase = "Testing Download..."
                        val dlResult = measureDownload { live, partial ->
                            currentMbps = live
                            if (partial > 0.0) downloadMbps = partial
                        }
                        if (dlResult > 0.0) {
                            downloadMbps = dlResult
                            currentMbps = dlResult
                        }

                        phase = "Testing Upload..."
                        val ulResult = try {
                            measureUpload { live, partial ->
                                currentMbps = live
                                if (partial > 0.0) uploadMbps = partial
                            }
                        } catch (_: Exception) {
                            0.0
                        }
                        if (ulResult > 0.0) {
                            uploadMbps = ulResult
                        }

                        currentMbps = if (downloadMbps > 0.0) downloadMbps else uploadMbps
                        phase = if (downloadMbps > 0.0 || uploadMbps > 0.0) "Test Completed" else "No Speed Data"
                    } catch (e: Exception) {
                        phase = "Error: ${e.message?.take(28) ?: "Check Internet"}"
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
    var best = Long.MAX_VALUE
    repeat(3) {
        try {
            val start = System.currentTimeMillis()
            val conn = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", UA)
            conn.connect()
            conn.responseCode
            conn.disconnect()
            val ms = System.currentTimeMillis() - start
            if (ms in 1..4999 && ms < best) best = ms
        } catch (_: Exception) {
        }
    }
    if (best == Long.MAX_VALUE) 25L else best
}

private suspend fun measureDownload(onLive: (liveMbps: Double, partialMbps: Double) -> Unit): Double =
    withContext(Dispatchers.IO) {
        // 8 parallel streams with ~120MB total budget over up to 11s for full TCP ramp-up
        val streamCount = 8
        val durationSec = 11.0
        val maxTotalBytes = 120_000_000L
        val totalBytes = AtomicLong(0L)
        val startNano = System.nanoTime()
        var active = true

        val liveJob = async {
            while (active) {
                delay(150)
                val sec = ((System.nanoTime() - startNano) / 1_000_000_000.0).coerceAtLeast(0.2)
                val bytes = totalBytes.get()
                if (bytes > 0L) {
                    val mbps = (bytes * 8.0) / (sec * 1_000_000.0)
                    withContext(Dispatchers.Main) { onLive(mbps, mbps) }
                }
            }
        }

        val workers = (0 until streamCount).map { idx ->
            async {
                try {
                    val url = "https://speed.cloudflare.com/__down?bytes=25000000&s=$idx&r=${System.nanoTime()}"
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 12000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", UA)
                    conn.setRequestProperty("Accept", "*/*")
                    conn.setRequestProperty("Cache-Control", "no-cache")
                    conn.connect()
                    if (conn.responseCode in 200..299) {
                        val buf = ByteArray(65536)
                        conn.inputStream.use { input ->
                            while (true) {
                                val elapsed = (System.nanoTime() - startNano) / 1_000_000_000.0
                                if (elapsed >= durationSec || totalBytes.get() >= maxTotalBytes) break
                                val n = input.read(buf)
                                if (n == -1) break
                                totalBytes.addAndGet(n.toLong())
                            }
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }

        workers.awaitAll()
        active = false
        liveJob.cancel()

        val finalSec = ((System.nanoTime() - startNano) / 1_000_000_000.0).coerceAtLeast(0.3)
        val finalBytes = totalBytes.get()
        if (finalBytes <= 0L) 0.0 else (finalBytes * 8.0) / (finalSec * 1_000_000.0)
    }

private suspend fun measureUpload(onLive: (liveMbps: Double, partialMbps: Double) -> Unit): Double =
    withContext(Dispatchers.IO) {
        val streamCount = 6
        val perStreamBytes = 6_000_000
        val totalSent = AtomicLong(0L)
        val startNano = System.nanoTime()
        var active = true

        val liveJob = async {
            while (active) {
                delay(150)
                val sec = ((System.nanoTime() - startNano) / 1_000_000_000.0).coerceAtLeast(0.2)
                val bytes = totalSent.get()
                if (bytes > 0L) {
                    val mbps = (bytes * 8.0) / (sec * 1_000_000.0)
                    withContext(Dispatchers.Main) { onLive(mbps, mbps) }
                }
            }
        }

        val workers = (0 until streamCount).map {
            async {
                try {
                    val conn = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 12000
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("User-Agent", UA)
                    conn.setRequestProperty("Content-Type", "application/octet-stream")
                    conn.setFixedLengthStreamingMode(perStreamBytes)

                    val chunk = ByteArray(65536) { 0x5A }
                    var written = 0
                    conn.outputStream.use { out ->
                        while (written < perStreamBytes) {
                            val elapsed = (System.nanoTime() - startNano) / 1_000_000_000.0
                            if (elapsed >= 7.5) break
                            val step = minOf(chunk.size, perStreamBytes - written)
                            out.write(chunk, 0, step)
                            written += step
                            totalSent.addAndGet(step.toLong())
                        }
                        out.flush()
                    }
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }

        workers.awaitAll()
        active = false
        liveJob.cancel()

        val finalSec = ((System.nanoTime() - startNano) / 1_000_000_000.0).coerceAtLeast(0.3)
        val finalBytes = totalSent.get()
        if (finalBytes <= 0L) 0.0 else (finalBytes * 8.0) / (finalSec * 1_000_000.0)
    }
