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
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"

private val DOWNLOAD_ENDPOINTS = listOf(
    "https://speed.cloudflare.com/__down?bytes=50000000",
    "https://proof.ovh.net/files/100Mb.dat",
    "https://speed.hetzner.de/100MB.bin"
)

@Composable
fun SpeedTestTab(
    state: SpeedTestState,
    onStartTest: () -> Unit
) {
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
                val progress = (state.currentMbps.coerceIn(0.0, 220.0) / 220.0).toFloat()
                drawArc(
                    color = if (state.phase.contains("Upload")) AccentGreen else AccentCyan,
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
                    text = String.format(Locale.US, "%.1f", state.currentMbps),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text("Mbps", fontSize = 16.sp, color = TextSecondary)
                Spacer(Modifier.height(6.dp))
                Text(state.phase, fontSize = 13.sp, color = AccentCyan)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SpeedStatCard(
                title = "Ping",
                value = if (state.pingMs > 0) "${state.pingMs} ms" else "--",
                modifier = Modifier.weight(1f)
            )
            SpeedStatCard(
                title = "Download",
                value = if (state.downloadMbps > 0.0) String.format(Locale.US, "%.1f", state.downloadMbps) + " Mbps" else "--",
                modifier = Modifier.weight(1f)
            )
            SpeedStatCard(
                title = "Upload",
                value = if (state.uploadMbps > 0.0) String.format(Locale.US, "%.1f", state.uploadMbps) + " Mbps" else "--",
                modifier = Modifier.weight(1f)
            )
        }

        Button(
            onClick = onStartTest,
            enabled = !state.isTesting,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgDark)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.isTesting) "Testing..." else "START SPEED TEST", fontWeight = FontWeight.Bold)
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

suspend fun runFullSpeedTest(state: SpeedTestState) {
    state.isTesting = true
    state.pingMs = 0L
    state.downloadMbps = 0.0
    state.uploadMbps = 0.0
    state.currentMbps = 0.0
    try {
        state.phase = "Testing Ping..."
        state.pingMs = measurePing()

        state.phase = "Testing Download..."
        val dlResult = measureDownload { live ->
            state.currentMbps = live
            state.downloadMbps = live
        }
        if (dlResult > 0.0) {
            state.downloadMbps = dlResult
            state.currentMbps = dlResult
        }

        state.phase = "Testing Upload..."
        val ulResult = try {
            measureUpload { live ->
                state.currentMbps = live
                state.uploadMbps = live
            }
        } catch (_: Exception) {
            0.0
        }
        if (ulResult > 0.0) {
            state.uploadMbps = ulResult
        }

        state.currentMbps = if (state.downloadMbps > 0.0) state.downloadMbps else state.uploadMbps
        state.phase = if (state.downloadMbps > 0.0 || state.uploadMbps > 0.0) "Test Completed" else "Check Internet"
    } catch (e: Exception) {
        state.phase = "Error: ${e.message?.take(28) ?: "Check Internet"}"
    } finally {
        state.isTesting = false
    }
}

private suspend fun measurePing(): Long = withContext(Dispatchers.IO) {
    val samples = mutableListOf<Long>()
    repeat(3) {
        try {
            val start = System.currentTimeMillis()
            val conn = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", UA)
            conn.connect()
            conn.responseCode
            conn.disconnect()
            val ms = System.currentTimeMillis() - start
            if (ms in 5..400) samples.add(ms)
        } catch (_: Exception) {
        }
    }
    if (samples.isEmpty()) 30L else samples.sorted()[samples.size / 2]
}

private suspend fun measureDownload(onLive: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
    val streamCount = 4
    val targetDurationSec = 10.0
    val totalBytes = AtomicLong(0L)
    val firstByteNano = AtomicLong(0L)
    val startNano = System.nanoTime()
    var active = true
    var smoothed = 0.0

    val reporter = async {
        while (active) {
            delay(250)
            val fb = firstByteNano.get()
            val bytes = totalBytes.get()
            if (fb > 0L && bytes > 0L) {
                val sec = ((System.nanoTime() - fb) / 1_000_000_000.0).coerceAtLeast(0.3)
                val rawMbps = (bytes * 8.0) / (sec * 1_000_000.0)
                smoothed = if (smoothed == 0.0) rawMbps else (smoothed * 0.6 + rawMbps * 0.4)
                withContext(Dispatchers.Main) { onLive(smoothed) }
            }
        }
    }

    val workers = (0 until streamCount).map { idx ->
        async {
            var attempt = 0
            while (attempt < 3) {
                val elapsedTotal = (System.nanoTime() - startNano) / 1_000_000_000.0
                if (elapsedTotal >= targetDurationSec) break

                val baseUrl = DOWNLOAD_ENDPOINTS[(idx + attempt) % DOWNLOAD_ENDPOINTS.size]
                val sep = if (baseUrl.contains("?")) "&" else "?"
                val fullUrl = "$baseUrl${sep}s=$idx&a=$attempt&t=${System.nanoTime()}"
                try {
                    val conn = URL(fullUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 10000
                    conn.instanceFollowRedirects = true
                    conn.useCaches = false
                    conn.setRequestProperty("User-Agent", UA)
                    conn.setRequestProperty("Accept", "*/*")
                    conn.connect()

                    if (conn.responseCode in 200..299) {
                        val buf = ByteArray(32768)
                        conn.inputStream.use { input ->
                            while (true) {
                                val elapsed = (System.nanoTime() - startNano) / 1_000_000_000.0
                                if (elapsed >= targetDurationSec) break
                                val n = input.read(buf)
                                if (n == -1) break
                                firstByteNano.compareAndSet(0L, System.nanoTime())
                                totalBytes.addAndGet(n.toLong())
                            }
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                }
                attempt++
            }
        }
    }

    workers.awaitAll()
    active = false
    reporter.cancel()

    val fb = firstByteNano.get()
    val bytes = totalBytes.get()
    if (fb == 0L || bytes <= 0L) {
        0.0
    } else {
        val sec = ((System.nanoTime() - fb) / 1_000_000_000.0).coerceAtLeast(0.5)
        (bytes * 8.0) / (sec * 1_000_000.0)
    }
}

private suspend fun measureUpload(onLive: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
    val streamCount = 3
    val perStreamBytes = 6_000_000
    val targetDurationSec = 8.0
    val totalSent = AtomicLong(0L)
    val startNano = System.nanoTime()
    var active = true
    var smoothed = 0.0

    val reporter = async {
        while (active) {
            delay(250)
            val bytes = totalSent.get()
            if (bytes > 0L) {
                val sec = ((System.nanoTime() - startNano) / 1_000_000_000.0).coerceAtLeast(0.3)
                val rawMbps = (bytes * 8.0) / (sec * 1_000_000.0)
                smoothed = if (smoothed == 0.0) rawMbps else (smoothed * 0.6 + rawMbps * 0.4)
                withContext(Dispatchers.Main) { onLive(smoothed) }
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

                val chunk = ByteArray(32768) { 0x5A }
                var written = 0
                conn.outputStream.use { out ->
                    while (written < perStreamBytes) {
                        val elapsed = (System.nanoTime() - startNano) / 1_000_000_000.0
                        if (elapsed >= targetDurationSec) break
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
    reporter.cancel()

    val sec = ((System.nanoTime() - startNano) / 1_000_000_000.0).coerceAtLeast(0.5)
    val bytes = totalSent.get()
    if (bytes <= 0L) 0.0 else (bytes * 8.0) / (sec * 1_000_000.0)
}
