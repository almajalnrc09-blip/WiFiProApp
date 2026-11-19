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
                val progress = (currentMbps.coerceIn(0.0, 300.0) / 300.0).toFloat()
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
                        downloadMbps = measureParallelDownload { live -> currentMbps = live }

                        phase = "Testing Upload..."
                        uploadMbps = try {
                            measureParallelUpload { live -> currentMbps = live }
                        } catch (_: Exception) {
                            0.0
                        }

                        currentMbps = downloadMbps
                        phase = "Test Completed"
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
    repeat(4) {
        val start = System.currentTimeMillis()
        val conn = URL("https://speed.cloudflare.com/__down?bytes=1").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", UA)
        conn.requestMethod = "GET"
        conn.connect()
        conn.inputStream.read()
        conn.disconnect()
        val elapsed = System.currentTimeMillis() - start
        if (elapsed < best) best = elapsed
    }
    if (best == Long.MAX_VALUE) 0L else best
}

private suspend fun measureParallelDownload(onLive: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
    val streams = 4
    val maxDurationSec = 7.5
    val totalBytes = AtomicLong(0L)
    val startTime = System.nanoTime()
    var running = true

    val reporter = async {
        while (running) {
            kotlinx.coroutines.delay(150)
            val elapsedSec = ((System.nanoTime() - startTime) / 1_000_000_000.0).coerceAtLeast(0.1)
            val mbps = (totalBytes.get() * 8.0) / (elapsedSec * 1_000_000.0)
            withContext(Dispatchers.Main) { onLive(mbps) }
        }
    }

    val workers = (1..streams).map { idx ->
        async {
            try {
                val conn = URL("https://speed.cloudflare.com/__down?bytes=100000000&s=$idx").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", "*/*")
                conn.setRequestProperty("Cache-Control", "no-cache")
                val input = conn.inputStream
                val buf = ByteArray(32768)
                while (true) {
                    val elapsedSec = (System.nanoTime() - startTime) / 1_000_000_000.0
                    if (elapsedSec >= maxDurationSec) break
                    val read = input.read(buf)
                    if (read == -1) break
                    totalBytes.addAndGet(read.toLong())
                }
                input.close()
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    workers.awaitAll()
    running = false
    reporter.cancel()

    val totalSec = ((System.nanoTime() - startTime) / 1_000_000_000.0).coerceAtLeast(0.2)
    (totalBytes.get() * 8.0) / (totalSec * 1_000_000.0)
}

private suspend fun measureParallelUpload(onLive: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
    val streams = 3
    val perStreamBytes = 6_000_000
    val totalSent = AtomicLong(0L)
    val startTime = System.nanoTime()
    var running = true

    val reporter = async {
        while (running) {
            kotlinx.coroutines.delay(150)
            val elapsedSec = ((System.nanoTime() - startTime) / 1_000_000_000.0).coerceAtLeast(0.1)
            val mbps = (totalSent.get() * 8.0) / (elapsedSec * 1_000_000.0)
            withContext(Dispatchers.Main) { onLive(mbps) }
        }
    }

    val workers = (1..streams).map {
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

                val chunk = ByteArray(32768) { 0x41 }
                val out = conn.outputStream
                var written = 0
                while (written < perStreamBytes) {
                    val elapsedSec = (System.nanoTime() - startTime) / 1_000_000_000.0
                    if (elapsedSec >= 5.5) break
                    val toWrite = minOf(chunk.size, perStreamBytes - written)
                    out.write(chunk, 0, toWrite)
                    written += toWrite
                    totalSent.addAndGet(toWrite.toLong())
                }
                out.flush()
                out.close()
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    workers.awaitAll()
    running = false
    reporter.cancel()

    val totalSec = ((System.nanoTime() - startTime) / 1_000_000_000.0).coerceAtLeast(0.2)
    (totalSent.get() * 8.0) / (totalSec * 1_000_000.0)
}
