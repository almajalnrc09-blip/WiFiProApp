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
    var isPageLoading by remember { mutableStateOf(false) }
    var pageErrorText by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showVaultDialog by remember { mutableStateOf(false) }

    // Pending credentials for auto-login when page finishes loading
    var pendingUser by remember { mutableStateOf<String?>(null) }
    var pendingPass by remember { mutableStateOf<String?>(null) }

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
                    // 1. Huawei 5G CPE & TP-Link specific IDs/classes first
                    var directSelectors = [
                        '#login_btn', '#btn_login', '#loginBtn', '#submit_btn',
                        '.login-btn', '.btn-login', 'button[type="submit"]', 'input[type="submit"]'
                    ];
                    for (var s = 0; s < directSelectors.length; s++) {
                        var found = document.querySelector(directSelectors[s]);
                        if (found) {
                            fireFullClick(found);
                            return true;
                        }
                    }

                    // 2. Match visible element with exact text "Log In" / "Login"
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

                    // 3. Fallback Enter key on password input
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
                    setTimeout(function() { clickLoginButton(passEl); }, 350);
                    setTimeout(function() { clickLoginButton(passEl); }, 950);
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    fun navigateToUrl(rawHost: String, autoUser: String? = null, autoPass: String? = null) {
        val clean = rawHost.trim()
        val target = if (clean.startsWith("http://") || clean.startsWith("https://")) clean else "http://$clean"
        urlText = target
        pageErrorText = null
        pendingUser = autoUser
        pendingPass = autoPass
        webViewRef?.stopLoading()
        webViewRef?.loadUrl(target)
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
                    onClick = { navigateToUrl(urlText) },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Go / Reload", tint = AccentCyan)
                }
            }

            if (isPageLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = AccentCyan)
            }

            if (pageErrorText != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = CardDark)
                ) {
                    Text(
                        text = pageErrorText ?: "",
                        color = AccentAmber,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        // Desktop-compatible UA helps TP-Link & Huawei web panels render cleanly
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isPageLoading = true
                                if (!url.isNullOrBlank()) urlText = url
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isPageLoading = false
                                val u = pendingUser
                                val p = pendingPass
                                if (!p.isNullOrEmpty()) {
                                    // Trigger multiple times to handle SPA frameworks (TP-Link / Huawei)
                                    runAutoFillAndLogin(view, u ?: "admin", p)
                                    view?.postDelayed({ runAutoFillAndLogin(view, u ?: "admin", p) }, 1200)
                                    pendingUser = null
                                    pendingPass = null
                                }
                            }

                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                handler?.proceed()
                            }

                            @Suppress("DEPRECATION")
                            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                                isPageLoading = false
                                pageErrorText = "Cannot reach $failingUrl ($description). Check router IP or AP Isolation."
                            }
                        }
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
                        val targetHost = saved.gateway
                        val currentHost = urlText.removePrefix("http://").removePrefix("https://").substringBefore("/")
                        if (currentHost == targetHost) {
                            // Already on this router's page: fill & click Log In immediately
                            runAutoFillAndLogin(webViewRef, saved.username, saved.password)
                        } else {
                            // Navigate to the selected router and auto-login once loaded
                            navigateToUrl(targetHost, saved.username, saved.password)
                        }
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
