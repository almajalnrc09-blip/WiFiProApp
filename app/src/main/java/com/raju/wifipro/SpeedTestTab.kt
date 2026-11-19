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
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"

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
        state.pingMs = measureTcpPing()

        state.phase = "Testing Download..."
        val dlResult = measureSteadyDownload { live ->
            state.currentMbps = live
            state.downloadMbps = live
        }
        if (dlResult > 0.0) {
            state.downloadMbps = dlResult
            state.currentMbps = dlResult
        }

        state.phase = "Testing Upload..."
        val ulResult = try {
            measureSteadyUpload { live ->
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

// Pure TCP RTT to nearest Cloudflare Anycast edge (no TLS handshake overhead)
private suspend fun measureTcpPing(): Long = withContext(Dispatchers.IO) {
    val hosts = listOf("1.1.1.1", "8.8.8.8")
    for (host in hosts) {
        val samples = mutableListOf<Long>()
        // 1 warm-up connect to wake radio / NAT
        try {
            Socket().use { s -> s.connect(InetSocketAddress(host, 443), 2500) }
        } catch (_: Exception) {}

        repeat(5) {
            try {
                val startNano = System.nanoTime()
                Socket().use { s -> s.connect(InetSocketAddress(host, 443), 2500) }
                val ms = (System.nanoTime() - startNano) / 1_000_000L
                if (ms in 2..400) samples.add(ms)
            } catch (_: Exception) {}
            delay(80)
        }
        if (samples.isNotEmpty()) {
            val sorted = samples.sorted()
            // Take lower-median (2nd lowest when 4-5 samples) to match standard speed tests
            return@withContext sorted[minOf(1, sorted.size - 1)]
        }
    }
    30L
}

private fun computeTrimmedMean(samples: List<Double>, lowTrimFrac: Double, highTrimFrac: Double): Double {
    if (samples.isEmpty()) return 0.0
    val sorted = samples.sorted()
    val lowIdx = (sorted.size * lowTrimFrac).toInt().coerceIn(0, sorted.lastIndex)
    val highIdx = (sorted.size * (1.0 - highTrimFrac)).toInt().coerceIn(lowIdx + 1, sorted.size)
    val slice = sorted.subList(lowIdx, highIdx)
    return if (slice.isEmpty()) sorted.average() else slice.average()
}

private suspend fun measureSteadyDownload(onLive: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
    val streamCount = 6
    val durationSec = 10.0
    val warmupSec = 1.8
    val totalBytes = AtomicLong(0L)
    val isRunning = AtomicBoolean(true)
    val activeConns = Collections.synchronizedList(mutableListOf<HttpURLConnection>())
    val steadySamples = mutableListOf<Double>()

    fun launchStream(urlStr: String) = launch(Dispatchers.IO) {
        var loop = 0
        while (isRunning.get() && loop < 4) {
            var conn: HttpURLConnection? = null
            try {
                val sep = if (urlStr.contains("?")) "&" else "?"
                conn = URL("$urlStr${sep}l=$loop&r=${System.nanoTime()}").openConnection() as HttpURLConnection
                activeConns.add(conn)
                conn.connectTimeout = 7000
                conn.readTimeout = 9000
                conn.instanceFollowRedirects = true
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", "*/*")
                conn.connect()
                if (conn.responseCode in 200..299) {
                    val buf = ByteArray(65536)
                    conn.inputStream.use { input ->
                        while (isRunning.get()) {
                            val n = input.read(buf)
                            if (n == -1) break
                            totalBytes.addAndGet(n.toLong())
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                conn?.let {
                    activeConns.remove(it)
                    try { it.disconnect() } catch (_: Exception) {}
                }
            }
            loop++
        }
    }

    val startNano = System.nanoTime()
    val workers = (0 until streamCount).map { idx ->
        launchStream("https://speed.cloudflare.com/__down?bytes=80000000&s=$idx")
    }.toMutableList()

    var fallbackStarted = false
    var prevBytes = 0L
    var prevNano = startNano
    var smoothLive = 0.0

    while (true) {
        delay(400)
        val now = System.nanoTime()
        val elapsedSec = (now - startNano) / 1_000_000_000.0
        val curBytes = totalBytes.get()

        // Only add backup server if Cloudflare delivered zero bytes after 2.4s
        if (!fallbackStarted && elapsedSec >= 2.4 && curBytes == 0L) {
            fallbackStarted = true
            repeat(3) { idx ->
                workers.add(launchStream("https://proof.ovh.net/files/100Mb.dat?s=$idx"))
            }
        }

        val dt = ((now - prevNano) / 1_000_000_000.0).coerceAtLeast(0.1)
        val dBytes = (curBytes - prevBytes).coerceAtLeast(0L)
        prevBytes = curBytes
        prevNano = now

        if (dBytes > 0L) {
            val intervalMbps = (dBytes * 8.0) / (dt * 1_000_000.0)
            if (elapsedSec >= warmupSec) {
                steadySamples.add(intervalMbps)
            }
            smoothLive = if (smoothLive == 0.0) intervalMbps else (smoothLive * 0.5 + intervalMbps * 0.5)
            withContext(Dispatchers.Main) { onLive(smoothLive) }
        }

        if (elapsedSec >= durationSec) break
    }

    // Stop all streams immediately so teardown time never dilutes the final speed
    isRunning.set(false)
    val toClose = synchronized(activeConns) { activeConns.toList() }
    launch(Dispatchers.IO) {
        toClose.forEach { try { it.disconnect() } catch (_: Exception) {} }
    }
    withTimeoutOrNull(800) { workers.joinAll() }

    if (steadySamples.isNotEmpty()) {
        computeTrimmedMean(steadySamples, lowTrimFrac = 0.20, highTrimFrac = 0.10)
    } else {
        val totalSec = ((System.nanoTime() - startNano) / 1_000_000_000.0).coerceAtLeast(0.5)
        (totalBytes.get() * 8.0) / (totalSec * 1_000_000.0)
    }
}

private suspend fun measureSteadyUpload(onLive: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
    val streamCount = 4
    val perStreamBytes = 15_000_000
    val durationSec = 9.0
    val bufferFillWarmupSec = 2.2 // Discard initial OS TCP send-buffer burst (prevents fake 75 Mbps spikes)
    val totalSent = AtomicLong(0L)
    val isRunning = AtomicBoolean(true)
    val activeConns = Collections.synchronizedList(mutableListOf<HttpURLConnection>())
    val steadySamples = mutableListOf<Double>()

    val startNano = System.nanoTime()
    val workers = (0 until streamCount).map {
        launch(Dispatchers.IO) {
            while (isRunning.get()) {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
                    activeConns.add(conn)
                    conn.connectTimeout = 7000
                    conn.readTimeout = 10000
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("User-Agent", UA)
                    conn.setRequestProperty("Content-Type", "application/octet-stream")
                    conn.setFixedLengthStreamingMode(perStreamBytes)

                    val chunk = ByteArray(16384) { 0x5A }
                    var written = 0
                    conn.outputStream.use { out ->
                        while (isRunning.get() && written < perStreamBytes) {
                            val step = minOf(chunk.size, perStreamBytes - written)
                            out.write(chunk, 0, step)
                            written += step
                            totalSent.addAndGet(step.toLong())
                        }
                        if (isRunning.get()) out.flush()
                    }
                    if (isRunning.get()) conn.responseCode
                } catch (_: Exception) {
                } finally {
                    conn?.let {
                        activeConns.remove(it)
                        try { it.disconnect() } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    var prevSent = 0L
    var prevNano = startNano
    var smoothLive = 0.0

    while (true) {
        delay(400)
        val now = System.nanoTime()
        val elapsedSec = (now - startNano) / 1_000_000_000.0
        val curSent = totalSent.get()

        val dt = ((now - prevNano) / 1_000_000_000.0).coerceAtLeast(0.1)
        val dBytes = (curSent - prevSent).coerceAtLeast(0L)
        prevSent = curSent
        prevNano = now

        if (elapsedSec >= bufferFillWarmupSec && dBytes > 0L) {
            val intervalMbps = (dBytes * 8.0) / (dt * 1_000_000.0)
            steadySamples.add(intervalMbps)
            smoothLive = if (smoothLive == 0.0) intervalMbps else (smoothLive * 0.5 + intervalMbps * 0.5)
            withContext(Dispatchers.Main) { onLive(smoothLive) }
        }

        if (elapsedSec >= durationSec) break
    }

    isRunning.set(false)
    val toClose = synchronized(activeConns) { activeConns.toList() }
    launch(Dispatchers.IO) {
        toClose.forEach { try { it.disconnect() } catch (_: Exception) {} }
    }
    withTimeoutOrNull(800) { workers.joinAll() }

    if (steadySamples.isNotEmpty()) {
        computeTrimmedMean(steadySamples, lowTrimFrac = 0.15, highTrimFrac = 0.15)
    } else {
        val totalSec = ((System.nanoTime() - startNano) / 1_000_000_000.0).coerceAtLeast(0.5)
        (totalSent.get() * 8.0) / (totalSec * 1_000_000.0)
    }
}
