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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RouterTab() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("router_vault", Context.MODE_PRIVATE) }
    val detectedGateway = remember { detectGatewayIp(context) }

    var urlText by remember { mutableStateOf("http://$detectedGateway") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showVaultDialog by remember { mutableStateOf(false) }

    val savedJson = remember(detectedGateway, showVaultDialog) {
        prefs.getString(detectedGateway, null)?.let { JSONObject(it) }
    }
    var username by remember(savedJson) { mutableStateOf(savedJson?.optString("user", "admin") ?: "admin") }
    var password by remember(savedJson) { mutableStateOf(savedJson?.optString("pass", "") ?: "") }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(onClick = { webViewRef?.goBack() }) {
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
                IconButton(onClick = {
                    val target = if (urlText.startsWith("http")) urlText else "http://$urlText"
                    webViewRef?.loadUrl(target)
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Go/Reload", tint = AccentCyan)
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
            onClick = { showVaultDialog = true },
            containerColor = AccentCyan,
            contentColor = BgDark,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)
        ) {
            Icon(Icons.Default.Key, contentDescription = "Saved Password & Auto-Login")
        }
    }

    if (showVaultDialog) {
        AlertDialog(
            onDismissRequest = { showVaultDialog = false },
            containerColor = CardDark,
            title = { Text("Router Vault ($detectedGateway)", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Save your login once, then tap Auto-Login anytime.", fontSize = 12.sp, color = TextSecondary)
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username (if needed)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
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
                        val obj = JSONObject().put("user", username).put("pass", password)
                        prefs.edit().putString(detectedGateway, obj.toString()).apply()

                        val safeUser = JSONObject.quote(username)
                        val safePass = JSONObject.quote(password)
                        val js = """
                            (function() {
                                function setVal(el, val) {
                                    if (!el) return;
                                    var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                    setter.call(el, val);
                                    el.dispatchEvent(new Event('input', { bubbles: true }));
                                    el.dispatchEvent(new Event('change', { bubbles: true }));
                                }
                                var inputs = Array.from(document.querySelectorAll('input'));
                                var passEl = inputs.find(i => i.type === 'password' && i.offsetParent !== null);
                                var userEl = inputs.find(i => (i.type === 'text' || i.type === 'email') && i.offsetParent !== null);
                                if (userEl) setVal(userEl, $safeUser);
                                if (passEl) setVal(passEl, $safePass);
                                setTimeout(function() {
                                    var btn = document.querySelector('button[type="submit"], input[type="submit"], button[id*="login" i], a[id*="login" i], div[id*="login" i]');
                                    if (btn) btn.click();
                                }, 250);
                            })();
                        """.trimIndent()
                        webViewRef?.evaluateJavascript(js, null)
                        showVaultDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgDark)
                ) {
                    Text("Save & Auto-Login", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showVaultDialog = false }) {
                    Text("Close", color = TextSecondary)
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
        else "192.168.0.1"
    } catch (_: Exception) {
        "192.168.0.1"
    }
}