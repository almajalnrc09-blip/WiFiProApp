package com.raju.wifipro

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.net.Inet4Address

private data class RouterSlot(
    val key: String,
    val title: String,
    val gateway: String,
    val username: String,
    val password: String
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RouterTab() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("router_main_sub_v1", Context.MODE_PRIVATE) }
    val detectedGateway = remember { detectGatewayIp(context) }

    fun loadSlots(): List<RouterSlot> {
        val mainRaw = prefs.getString("main_router", null)?.let { JSONObject(it) }
        val subRaw = prefs.getString("sub_router", null)?.let { JSONObject(it) }

        val mainGw = mainRaw?.optString("gateway", "")?.ifBlank { detectedGateway } ?: detectedGateway
        val subGw = subRaw?.optString("gateway", "")?.ifBlank { "192.168.8.2" } ?: "192.168.8.2"

        return listOf(
            RouterSlot(
                key = "main_router",
                title = "Main Router",
                gateway = mainGw,
                username = mainRaw?.optString("user", "admin") ?: "admin",
                password = mainRaw?.optString("pass", "") ?: ""
            ),
            RouterSlot(
                key = "sub_router",
                title = "Sub Router",
                gateway = subGw,
                username = subRaw?.optString("user", "admin") ?: "admin",
                password = subRaw?.optString("pass", "") ?: ""
            )
        )
    }

    var slots by remember { mutableStateOf(loadSlots()) }
    var selectedIdx by remember { mutableIntStateOf(0) }
    var urlText by remember { mutableStateOf("http://$detectedGateway") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showVaultDialog by remember { mutableStateOf(false) }

    val activeSlot = slots[selectedIdx]
    var editGateway by remember(selectedIdx, showVaultDialog) { mutableStateOf(activeSlot.gateway) }
    var editUser by remember(selectedIdx, showVaultDialog) { mutableStateOf(activeSlot.username) }
    var editPass by remember(selectedIdx, showVaultDialog) { mutableStateOf(activeSlot.password) }

    fun saveCurrentSlot(): RouterSlot {
        val cleanGw = editGateway.trim().removePrefix("http://").removePrefix("https://").ifBlank { detectedGateway }
        val obj = JSONObject()
            .put("gateway", cleanGw)
            .put("user", editUser)
            .put("pass", editPass)
        prefs.edit().putString(activeSlot.key, obj.toString()).apply()
        val updated = RouterSlot(activeSlot.key, activeSlot.title, cleanGw, editUser, editPass)
        slots = slots.mapIndexed { idx, item -> if (idx == selectedIdx) updated else item }
        return updated
    }

    fun runAutoFillAndLogin(user: String, pass: String) {
        val safeUser = JSONObject.quote(user)
        val safePass = JSONObject.quote(pass)
        val js = """
            (function() {
                function setNativeValue(el, val) {
                    if (!el) return;
                    el.focus();
                    var desc = window.HTMLInputElement && Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                    if (desc && desc.set) {
                        desc.set.call(el, val);
                    } else {
                        el.value = val;
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                    el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
                }

                function clickLoginNow() {
                    var btns = Array.from(document.querySelectorAll('button, input[type="submit"], input[type="button"], a, div, span'));
                    for (var i = 0; i < btns.length; i++) {
                        var el = btns[i];
                        var r = el.getBoundingClientRect();
                        if (r.width <= 0 || r.height <= 0) continue;
                        var txt = ((el.innerText || el.value || el.textContent || '') + '').trim().toLowerCase();
                        var clsId = ((el.id || '') + ' ' + (el.className || '')).toLowerCase();
                        if (txt === 'log in' || txt === 'login' || txt === 'sign in' || clsId.includes('login-btn') || clsId.includes('btn_login')) {
                            el.click();
                            return true;
                        }
                    }
                    var fallback = document.querySelector('button[type="submit"], input[type="submit"], button[id*="login" i], a[id*="login" i]');
                    if (fallback) {
                        fallback.click();
                        return true;
                    }
                    return false;
                }

                var inputs = Array.from(document.querySelectorAll('input'));
                var passEl = inputs.find(function(i) { return (i.type || '').toLowerCase() === 'password'; });
                var userEl = inputs.find(function(i) {
                    var t = (i.type || '').toLowerCase();
                    var n = (i.name || '').toLowerCase();
                    return t === 'text' || t === 'email' || n.includes('user');
                });

                if (userEl && $safeUser.length > 0) setNativeValue(userEl, $safeUser);
                if (passEl) setNativeValue(passEl, $safePass);

                setTimeout(clickLoginNow, 250);
                setTimeout(clickLoginNow, 750);
            })();
        """.trimIndent()
        webViewRef?.evaluateJavascript(js, null)
    }

    fun openGatewayAndAutoLogin(gatewayHost: String, user: String, pass: String) {
        val clean = gatewayHost.trim()
        val target = if (clean.startsWith("http://") || clean.startsWith("https://")) clean else "http://$clean"
        urlText = target
        webViewRef?.apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    runAutoFillAndLogin(user, pass)
                }
            }
            loadUrl(target)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Editable Gateway URL bar + Reload
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = { webViewRef?.goBack() }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = TextPrimary),
                    shape = RoundedCornerShape(10.dp)
                )
                IconButton(
                    onClick = {
                        val clean = urlText.trim()
                        val target = if (clean.startsWith("http://") || clean.startsWith("https://")) clean else "http://$clean"
                        urlText = target
                        webViewRef?.loadUrl(target)
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = AccentCyan)
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        loadUrl(urlText)
                        webViewRef = this
                    }
                }
            )
        }

        FloatingActionButton(
            onClick = {
                slots = loadSlots()
                showVaultDialog = true
            },
            containerColor = AccentCyan,
            contentColor = BgDark,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)
        ) {
            Icon(Icons.Default.Key, contentDescription = "Main & Sub Router Vault")
        }
    }

    if (showVaultDialog) {
        AlertDialog(
            onDismissRequest = { showVaultDialog = false },
            containerColor = CardDark,
            title = {
                Text("Router Login Vault", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        slots.forEachIndexed { idx, slot ->
                            FilterChip(
                                selected = selectedIdx == idx,
                                onClick = {
                                    saveCurrentSlot()
                                    selectedIdx = idx
                                },
                                label = { Text(slot.title, fontWeight = FontWeight.SemiBold) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = editGateway,
                        onValueChange = { editGateway = it },
                        label = { Text("Gateway IP / Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editUser,
                        onValueChange = { editUser = it },
                        label = { Text("Username (if needed)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editPass,
                        onValueChange = { editPass = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val saved = saveCurrentSlot()
                        showVaultDialog = false
                        openGatewayAndAutoLogin(saved.gateway, saved.username, saved.password)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgDark)
                ) {
                    Text("Save & Auto-Login", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        saveCurrentSlot()
                        showVaultDialog = false
                    }) {
                        Text("Save Only", color = AccentGreen)
                    }
                    TextButton(onClick = { showVaultDialog = false }) {
                        Text("Close", color = TextSecondary)
                    }
                }
            }
        )
    }
}

@Suppress("DEPRECATION")
fun detectGatewayIp(context: Context): String {
    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork)
        val route = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
        val gw = route?.gateway?.hostAddress
        if (!gw.isNullOrBlank()) return gw
    } catch (_: Exception) {}

    return try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val g = wm.dhcpInfo.gateway
        if (g != 0) "${g and 0xFF}.${(g shr 8) and 0xFF}.${(g shr 16) and 0xFF}.${(g shr 24) and 0xFF}"
        else "192.168.8.1"
    } catch (_: Exception) {
        "192.168.8.1"
    }
}
