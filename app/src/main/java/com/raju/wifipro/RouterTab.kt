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

private data class RouterProfile(
    val slotId: String,
    val label: String,
    val username: String,
    val password: String
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RouterTab() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("router_profiles_v2", Context.MODE_PRIVATE) }
    val detectedGateway = remember { detectGatewayIp(context) }

    var urlText by remember { mutableStateOf("http://$detectedGateway") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showVaultDialog by remember { mutableStateOf(false) }

    fun loadProfiles(): List<RouterProfile> {
        val defaults = listOf(
            "slot_1" to "TP-Link",
            "slot_2" to "Huawei",
            "slot_3" to "Tenda"
        )
        return defaults.map { (id, defLabel) ->
            val raw = prefs.getString(id, null)
            if (raw != null) {
                val obj = JSONObject(raw)
                RouterProfile(
                    slotId = id,
                    label = obj.optString("label", defLabel).ifBlank { defLabel },
                    username = obj.optString("user", "admin"),
                    password = obj.optString("pass", "")
                )
            } else {
                RouterProfile(id, defLabel, "admin", "")
            }
        }
    }

    var profiles by remember { mutableStateOf(loadProfiles()) }
    var selectedIdx by remember { mutableIntStateOf(0) }

    val activeProfile = profiles[selectedIdx]
    var editLabel by remember(selectedIdx, showVaultDialog) { mutableStateOf(activeProfile.label) }
    var editUser by remember(selectedIdx, showVaultDialog) { mutableStateOf(activeProfile.username) }
    var editPass by remember(selectedIdx, showVaultDialog) { mutableStateOf(activeProfile.password) }

    fun saveCurrentProfile(): RouterProfile {
        val finalLabel = editLabel.trim().ifBlank { activeProfile.label }
        val obj = JSONObject()
            .put("label", finalLabel)
            .put("user", editUser)
            .put("pass", editPass)
        prefs.edit().putString(activeProfile.slotId, obj.toString()).apply()
        val updated = RouterProfile(activeProfile.slotId, finalLabel, editUser, editPass)
        profiles = profiles.mapIndexed { idx, item -> if (idx == selectedIdx) updated else item }
        return updated
    }

    fun triggerAutoFillAndClickLogin(user: String, pass: String) {
        val safeUser = JSONObject.quote(user)
        val safePass = JSONObject.quote(pass)
        val js = """
            (function() {
                function setNativeValue(el, val) {
                    if (!el) return;
                    el.focus();
                    var protos = [window.HTMLInputElement && window.HTMLInputElement.prototype];
                    for (var i = 0; i < protos.length; i++) {
                        var desc = protos[i] && Object.getOwnPropertyDescriptor(protos[i], 'value');
                        if (desc && desc.set) {
                            desc.set.call(el, val);
                            break;
                        }
                    }
                    el.value = val;
                    el.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                    el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
                }

                function tryLoginClick() {
                    var candidates = Array.from(document.querySelectorAll('button, input[type="submit"], input[type="button"], a, div, span'));
                    for (var i = 0; i < candidates.length; i++) {
                        var el = candidates[i];
                        var txt = ((el.innerText || el.value || el.textContent || '') + '').trim().toLowerCase();
                        var idAndClass = ((el.id || '') + ' ' + (el.className || '')).toLowerCase();
                        var visible = el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().height > 0;
                        if (!visible) continue;
                        if (txt === 'log in' || txt === 'login' || txt === 'sign in' || idAndClass.includes('login-btn') || idAndClass.includes('btn_login')) {
                            el.click();
                            return true;
                        }
                    }
                    var fallback = document.querySelector('button[type="submit"], input[type="submit"], button[id*="login" i], a[id*="login" i], div[id*="login" i]');
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

                setTimeout(tryLoginClick, 250);
                setTimeout(tryLoginClick, 700);
            })();
        """.trimIndent()
        webViewRef?.evaluateJavascript(js, null)
    }

    fun openGatewayAndAutoLogin(rawUrl: String, user: String, pass: String) {
        val cleaned = rawUrl.trim()
        val target = if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) cleaned else "http://$cleaned"
        urlText = target
        webViewRef?.apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    triggerAutoFillAndClickLogin(user, pass)
                }
            }
            loadUrl(target)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Editable Gateway URL + Direct Login Button
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
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = TextPrimary),
                    shape = RoundedCornerShape(10.dp)
                )
                Button(
                    onClick = {
                        val currentList = loadProfiles()
                        profiles = currentList
                        val p = currentList[selectedIdx]
                        openGatewayAndAutoLogin(urlText, p.username, p.password)
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = BgDark)
                ) {
                    Text("Login", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                IconButton(
                    onClick = {
                        val latestGw = detectGatewayIp(context)
                        val target = if (urlText.isBlank()) "http://$latestGw" else if (urlText.startsWith("http")) urlText else "http://$urlText"
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
                profiles = loadProfiles()
                showVaultDialog = true
            },
            containerColor = AccentCyan,
            contentColor = BgDark,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)
        ) {
            Icon(Icons.Default.Key, contentDescription = "Saved Routers & Auto-Login")
        }
    }

    if (showVaultDialog) {
        AlertDialog(
            onDismissRequest = { showVaultDialog = false },
            containerColor = CardDark,
            title = {
                Column {
                    Text("Saved Router Logins (3)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Gateway: $detectedGateway", color = TextSecondary, fontSize = 12.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        profiles.forEachIndexed { idx, prof ->
                            FilterChip(
                                selected = selectedIdx == idx,
                                onClick = {
                                    saveCurrentProfile()
                                    selectedIdx = idx
                                },
                                label = {
                                    Text(
                                        text = prof.label,
                                        maxLines = 1,
                                        fontSize = 12.sp
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = editLabel,
                        onValueChange = { editLabel = it },
                        label = { Text("Router Name (e.g. TP-Link / Huawei / Tenda)") },
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
                        val saved = saveCurrentProfile()
                        triggerAutoFillAndClickLogin(saved.username, saved.password)
                        showVaultDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgDark)
                ) {
                    Text("Save & Auto-Login", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        saveCurrentProfile()
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
