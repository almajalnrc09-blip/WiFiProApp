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

private val DOWNLOAD_SOURCES = listOf(
    "https://speed.cloudflare.com/__down?bytes=50000000",
    "https://ash-speed.hetzner.com/100MB.bin",
    "https://fsn1-speed.hetzner.com/100MB.bin",
    "https://nbg1-speed.hetzner.com/100MB.bin"
)

private val UPLOAD_SOURCES = listOf(
    "https://speed.cloudflare.com/__up",
    "https://httpbin.org/post"
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
                val progress = (state.currentMbps.coerceIn(0.0, 250.0) / 250.0).toFloat()
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
        val dlResult = measureDownload { live, partial ->
            state.currentMbps = live
            if (partial > 0.0) state.downloadMbps = partial
        }
        if (dlResult > 0.0) {
            state.downloadMbps = dlResult
            state.currentMbps = dlResult
        }

        state.phase = "Testing Upload..."
        val ulResult = try {
            measureUpload { live, partial ->
                state.currentMbps = live
                if (partial > 0.0) state.uploadMbps = partial
            }
        } catch (_: Exception) {
            0.0
        }
        if (ulResult > 0.0) {
            state.uploadMbps = ulResult
        }

        state.currentMbps = if (state.downloadMbps > 0.0) state.downloadMbps else state.uploadMbps
        state.phase = if (state.downloadMbps > 0.0 || state.uploadMbps > 0.0) "Test Completed" else "No Speed Data"
    } catch (e: Exception) {
        state.phase = "Error: ${e.message?.take(28) ?: "Check Internet"}"
    } finally {
        state.isTesting = false
    }
}

private suspend fun measurePing(): Long = withContext(Dispatchers.IO) {
    val samples = mutableListOf<Long>()
    repeat(4) {
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
            if (ms in 5..350) samples.add(ms)
        } catch (_: Exception) {
        }
    }
    if (samples.isEmpty()) 25L else samples.sorted()[samples.size / 2]
}

private suspend fun downloadFromUrl(
    baseUrl: String,
    streamIdx: Int,
    startNano: Long,
    durationSec: Double,
    maxTotalBytes: Long,
    totalBytes: AtomicLong
) {
    try {
        val sep = if (baseUrl.contains("?")) "&" else "?"
        val url = "$baseUrl${sep}s=$streamIdx&r=${System.nanoTime()}"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.instanceFollowRedirects = true
        conn.useCaches = false
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

private suspend fun measureDownload(onLive: (liveMbps: Double, partialMbps: Double) -> Unit): Double =
    withContext(Dispatchers.IO) {
        val streamCount = 6
        val durationSec = 9.0
        val warmupSec = 1.0
        val maxTotalBytes = 150_000_000L
        val totalBytes = AtomicLong(0L)
        val warmupBytes = AtomicLong(0L)
        val startNano = System.nanoTime()
        var warmupRecorded = false
        var active = true

        val liveJob = async {
            while (active) {
                delay(160)
                val sec = (System.nanoTime() - startNano) / 1_000_000_000.0
                if (sec >= warmupSec) {
                    if (!warmupRecorded) {
                        warmupBytes.set(totalBytes.get())
                        warmupRecorded = true
                    }
                    val postWarmupSec = (sec - warmupSec).coerceAtLeast(0.25)
                    val postWarmupBytes = (totalBytes.get() - warmupBytes.get()).coerceAtLeast(0L)
                    if (postWarmupBytes > 0L) {
                        val mbps = (postWarmupBytes * 8.0) / (postWarmupSec * 1_000_000.0)
                        withContext(Dispatchers.Main) { onLive(mbps, mbps) }
                    }
                }
            }
        }

        val workers = (0 until streamCount).map { idx ->
            async {
                val primaryUrl = DOWNLOAD_SOURCES[idx % DOWNLOAD_SOURCES.size]
                downloadFromUrl(primaryUrl, idx, startNano, durationSec, maxTotalBytes, totalBytes)
                // If primary stream yielded very little data early on, try fallback source
                val elapsed = (System.nanoTime() - startNano) / 1_000_000_000.0
                if (elapsed < durationSec - 1.5 && totalBytes.get() < 5_000_000L) {
                    val fallbackUrl = DOWNLOAD_SOURCES[(idx + 1) % DOWNLOAD_SOURCES.size]
                    downloadFromUrl(fallbackUrl, idx + 10, startNano, durationSec, maxTotalBytes, totalBytes)
                }
            }
        }

        workers.awaitAll()
        active = false
        liveJob.cancel()

        val totalSec = (System.nanoTime() - startNano) / 1_000_000_000.0
        val postWarmupSec = (totalSec - warmupSec).coerceAtLeast(0.4)
        val postWarmupBytes = (totalBytes.get() - warmupBytes.get()).coerceAtLeast(totalBytes.get() / 2)
        if (postWarmupBytes <= 0L) 0.0 else (postWarmupBytes * 8.0) / (postWarmupSec * 1_000_000.0)
    }

private suspend fun measureUpload(onLive: (liveMbps: Double, partialMbps: Double) -> Unit): Double =
    withContext(Dispatchers.IO) {
        val streamCount = 4
        val perStreamBytes = 6_000_000
        val totalSent = AtomicLong(0L)
        val startNano = System.nanoTime()
        var active = true

        val liveJob = async {
            while (active) {
                delay(160)
                val sec = ((System.nanoTime() - startNano) / 1_000_000_000.0).coerceAtLeast(0.25)
                val bytes = totalSent.get()
                if (bytes > 0L) {
                    val mbps = (bytes * 8.0) / (sec * 1_000_000.0)
                    withContext(Dispatchers.Main) { onLive(mbps, mbps) }
                }
            }
        }

        val workers = (0 until streamCount).map { idx ->
            async {
                val targetUrl = UPLOAD_SOURCES[idx % UPLOAD_SOURCES.size]
                try {
                    val conn = URL(targetUrl).openConnection() as HttpURLConnection
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
                            if (elapsed >= 7.0) break
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

        val finalSec = ((System.nanoTime() - startNano) / 1_000_000_000.0).coerceAtLeast(0.35)
        val finalBytes = totalSent.get()
        if (finalBytes <= 0L) 0.0 else (finalBytes * 8.0) / (finalSec * 1_000_000.0)
    }
