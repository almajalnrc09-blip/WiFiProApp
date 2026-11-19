package com.raju.wifipro

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.http.SslError
import android.net.wifi.WifiManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
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
    val prefs = remember { context.getSharedPreferences("router_default_vault", Context.MODE_PRIVATE) }
    val gatewayIp = remember { detectGatewayIp(context) }

    val savedJson = remember(gatewayIp) {
        prefs.getString(gatewayIp, null)?.let { JSONObject(it) }
    }
    var username by remember(savedJson) { mutableStateOf(savedJson?.optString("user", "admin") ?: "admin") }
    var password by remember(savedJson) { mutableStateOf(savedJson?.optString("pass", "") ?: "") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showVaultDialog by remember { mutableStateOf(false) }

    fun runAutoFillAndLogin(view: WebView?, user: String, pass: String) {
        if (view == null || pass.isEmpty()) return
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
                    el.dispatchEvent(new KeyboardEvent('keyup', { key: 'a', bubbles: true }));
                }

                function fireFullClick(el) {
                    if (!el) return;
                    try {
                        el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window }));
                        el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window }));
                        el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                    } catch(e) {}
                    try { el.click(); } catch(e) {}
                }

                function submitByEnter(el) {
                    if (!el) return;
                    el.focus();
                    ['keydown', 'keypress', 'keyup'].forEach(function(evtName) {
                        el.dispatchEvent(new KeyboardEvent(evtName, {
                            key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true
                        }));
                    });
                }

                function clickLoginButton(passEl) {
                    var directSelectors = [
                        '#login_btn', '#btn_login', '#loginBtn', '#submit_btn', '#pc-login-btn',
                        '.login-btn', '.btn-login', 'button[type="submit"]', 'input[type="submit"]'
                    ];
                    for (var s = 0; s < directSelectors.length; s++) {
                        var found = document.querySelector(directSelectors[s]);
                        if (found) {
                            fireFullClick(found);
                            return true;
                        }
                    }

                    var all = Array.from(document.querySelectorAll('div, button, a, span, input'));
                    for (var i = 0; i < all.length; i++) {
                        var el = all[i];
                        var r = el.getBoundingClientRect();
                        if (r.width <= 10 || r.height <= 10) continue;
                        var txt = ((el.innerText || el.value || '') + '').trim().toLowerCase();
                        if (txt === 'log in' || txt === 'login' || txt === 'sign in') {
                            fireFullClick(el);
                            return true;
                        }
                    }

                    submitByEnter(passEl);
                    return false;
                }

                var inputs = Array.from(document.querySelectorAll('input'));
                var passEl = inputs.find(function(i) { return (i.type || '').toLowerCase() === 'password'; });
                var userEl = inputs.find(function(i) {
                    var t = (i.type || '').toLowerCase();
                    var n = (i.name || '').toLowerCase();
                    var id = (i.id || '').toLowerCase();
                    return t === 'text' || t === 'email' || n.includes('user') || id.includes('user');
                });

                if (userEl && $safeUser.length > 0) setNativeValue(userEl, $safeUser);
                if (passEl) {
                    setNativeValue(passEl, $safePass);
                    setTimeout(function() { clickLoginButton(passEl); }, 300);
                    setTimeout(function() { clickLoginButton(passEl); }, 900);
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false

                    webViewClient = object : WebViewClient() {
                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                            handler?.proceed()
                        }
                    }
                    webChromeClient = WebChromeClient()
                    loadUrl("http://$gatewayIp")
                    webViewRef = this
                }
            }
        )

        FloatingActionButton(
            onClick = { showVaultDialog = true },
            containerColor = AccentCyan,
            contentColor = BgDark,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)
        ) {
            Icon(Icons.Default.Key, contentDescription = "Router Login Vault")
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
                    OutlinedTextField(
                        value = gatewayIp,
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        label = { Text("Default Gateway IP") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username (if needed)") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val obj = JSONObject()
                            .put("user", username)
                            .put("pass", password)
                        prefs.edit().putString(gatewayIp, obj.toString()).apply()
                        showVaultDialog = false
                        runAutoFillAndLogin(webViewRef, username, password)
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
        else "192.168.8.1"
    } catch (_: Exception) {
        "192.168.8.1"
    }
}
